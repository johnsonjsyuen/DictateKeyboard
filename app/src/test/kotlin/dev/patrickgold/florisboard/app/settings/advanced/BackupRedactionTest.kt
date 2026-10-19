/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.advanced

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.hasNoSecrets
import dev.patrickgold.florisboard.dictate.provider.withSecretsFrom
import dev.patrickgold.florisboard.dictate.provider.withoutSecrets
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a backup made "without provider credentials" actually contains (issue #367).
 *
 * A settings backup used to carry every API key in plain text while the screen offered a share menu, so
 * the flow that leaked them was the one the UI proposed. Everything here is about the two halves of the
 * fix being exact: nothing secret may survive the redaction — asserted against the whole document string,
 * because a key that is still *in the file* is the bug, whatever the parsed record says — and nothing else
 * may change, a preference file being no place where a stray byte is forgiven.
 *
 * The third concern only shows up later: a credential-free archive merged onto a phone that has keys must
 * not blank them, because the whole keyring is a single preference and would otherwise be replaced
 * wholesale. And the last one is a year from now, which is what `the drop list still covers every secret
 * preference` is for.
 */
class BackupRedactionTest {

    private fun keyringLine(accounts: ProviderAccounts): String =
        "s;${BackupRedaction.ACCOUNTS_KEY};" +
            BackupRedaction.encodeValue(ProviderAccounts.Serializer.serialize(accounts))

    private fun accountsOf(vararg accounts: ProviderAccount): ProviderAccounts =
        ProviderAccounts(accounts.associateBy { it.providerId })

    private fun keyringOf(document: String): ProviderAccounts =
        BackupRedaction.accountsFrom(document)!!

    private val openai = ProviderAccount(
        providerId = "openai",
        displayName = "Work",
        apiKey = "sk-the-secret-one",
        customBaseUrl = "https://api.example.test/v1",
        transcriptionModel = "whisper-1",
        chatModel = "gpt-5",
        transcriptionViaChat = true,
        cachedModels = listOf("gpt-5", "whisper-1"),
    )

    private val groq = ProviderAccount(
        providerId = "groq",
        apiKey = "gsk-the-other-secret",
        transcriptionModel = "whisper-large-v3",
    )

    @Test
    fun `no secret survives anywhere in the document`() {
        val redacted = BackupRedaction.redact(keyringLine(accountsOf(openai, groq)))

        // The point of the issue: not "the record parses as keyless" but "the string does not contain it".
        for (secret in listOf("sk-the-secret-one", "gsk-the-other-secret")) {
            assertFalse(redacted.contains(secret), "redacted document still contains $secret")
        }
    }

    @Test
    fun `redaction keeps the configuration and clears the credential`() {
        val redacted = keyringOf(BackupRedaction.redact(keyringLine(accountsOf(openai, groq))))

        val openaiBack = redacted["openai"]!!
        assertEquals("openai", openaiBack.providerId)
        assertEquals("Work", openaiBack.displayName)
        assertEquals("https://api.example.test/v1", openaiBack.customBaseUrl)
        assertEquals("whisper-1", openaiBack.transcriptionModel)
        assertEquals("gpt-5", openaiBack.chatModel)
        assertTrue(openaiBack.transcriptionViaChat)
        assertEquals(listOf("gpt-5", "whisper-1"), openaiBack.cachedModels)
        assertEquals("", openaiBack.apiKey)

        val groqBack = redacted["groq"]!!
        assertEquals("whisper-large-v3", groqBack.transcriptionModel)
        assertEquals("", groqBack.apiKey)
    }

    @Test
    fun `the value encoding is JetPref's, to the byte`() {
        // The library's own StringEncoder is internal, so this is a copy — and a copy is only any use if it
        // writes what the library writes. The first case is a real line read off a device's store; the
        // second pins the escaping and its order. If this test fails, a redacted archive has stopped being
        // readable by the app that wrote it.
        assertEquals("\"gemini\"", BackupRedaction.encodeValue("gemini"))
        assertEquals("\"\"", BackupRedaction.encodeValue(""))

        val awkward = "a\"b\\c\nd\re"
        assertEquals("\"a\\\"b\\\\c\\nd\\re\"", BackupRedaction.encodeValue(awkward))
        assertEquals(awkward, BackupRedaction.decodeValue(BackupRedaction.encodeValue(awkward)))

        // Anything not in quotes is the empty string, exactly as the library reads it.
        assertEquals("", BackupRedaction.decodeValue("notquoted"))
        assertEquals("", BackupRedaction.decodeValue("\""))
    }

    @Test
    fun `redaction is idempotent`() {
        val once = BackupRedaction.redact(keyringLine(accountsOf(openai, groq)))
        assertEquals(once, BackupRedaction.redact(once))
    }

    @Test
    fun `a value full of delimiters and quotes survives the line rewrite`() {
        // `;` is the line delimiter and `"` wraps the value, so a value containing either is exactly where a
        // naive split or a hand-rolled escape falls over. A backslash for good measure.
        //
        // What is deliberately *not* here is a line break. JetPref unescapes by running four `replace` calls
        // in sequence rather than in one pass, so a value that already contains the two characters
        // backslash-n decodes to a real newline and is corrupted — and since kotlinx writes a real newline
        // into the JSON as exactly those two characters, no provider account field can hold one. That is
        // the library's limitation and it is true with or without this redaction; the copy in
        // BackupRedaction reproduces it on purpose, because a pass that "fixed" it would hand the app back
        // something other than what it stored.
        val awkward = ProviderAccount(
            providerId = "custom:abc",
            displayName = "a;b\"c",
            apiKey = "k;e\"y\\z",
            customBaseUrl = "https://h;st/pa\\th?q=\"x\"",
        )
        val line = keyringLine(accountsOf(awkward))
        assertEquals(1, line.lines().size, "the keyring must stay on one line")

        val redacted = BackupRedaction.redact(line)
        assertFalse(redacted.contains("k;e\"y\\z"))
        val back = keyringOf(redacted)["custom:abc"]!!
        assertEquals("a;b\"c", back.displayName)
        assertEquals("https://h;st/pa\\th?q=\"x\"", back.customBaseUrl)
    }

    @Test
    fun `every other line comes through untouched`() {
        val document = listOf(
            "b;clipboard__history_enabled;true",
            "s;dictate__transcription_provider_id;\"gemini\"",
            "i;dictate__audio_speed_up_percent;25",
            // Not a JetPref line at all; the importer skips it, so inventing a reading of it would only
            // lose information.
            "garbage",
            "",
        ).joinToString("\n") + "\n"

        assertEquals(document, BackupRedaction.redact(document))
        assertEquals(document, BackupRedaction.restoreSecrets(document, accountsOf(openai)))
    }

    @Test
    fun `the credential-only keys are dropped and lookalikes are not`() {
        val document = listOf(
            "s;dictate__proxy_username;\"admin\"",
            "s;dictate__proxy_password;\"hunter2\"",
            "s;gif__klipy_api_key;\"klipy-secret\"",
            "s;dictate__api_key;\"legacy\"",
            "s;dictate__rewording_api_key;\"legacy-reword\"",
            "b;dictate__provider_accounts_migrated;true",
            "s;dictate__proxy_host;\"proxy.example.test\"",
        ).joinToString("\n")

        val redacted = BackupRedaction.redact(document)

        for (gone in listOf("admin", "hunter2", "klipy-secret", "legacy", "legacy-reword")) {
            assertFalse(redacted.contains(gone), "redacted document still contains $gone")
        }
        // A key that merely starts with or contains a dropped name is a different preference.
        assertTrue(redacted.contains("b;dictate__provider_accounts_migrated;true"))
        assertTrue(redacted.contains("s;dictate__proxy_host;\"proxy.example.test\""))
    }

    @Test
    fun `an unreadable keyring line is dropped rather than emptied`() {
        // The serializer answers a broken blob with an empty keyring. Writing that back would turn a line
        // we merely failed to parse into an instruction to forget every key in it; leaving it out asks the
        // importer to keep whatever the device already has, which is the only safe reading.
        val document = "s;${BackupRedaction.ACCOUNTS_KEY};\"{not json\""

        assertEquals("", BackupRedaction.redact(document))
        assertEquals("", BackupRedaction.restoreSecrets(document, accountsOf(openai)))
        assertNull(BackupRedaction.accountsFrom(document))
    }

    @Test
    fun `merging fills a missing key and keeps the archive's own`() {
        val local = accountsOf(
            openai,
            groq,
            ProviderAccount(providerId = "mistral", apiKey = "local-mistral"),
        )
        val incoming = accountsOf(
            openai.withoutSecrets(),
            // The archive brings a key of its own here, so the local one does not overwrite it.
            groq.copy(apiKey = "gsk-newer"),
            ProviderAccount(providerId = "ollama"),
        )

        val merged = incoming.withSecretsFrom(local)

        assertEquals("sk-the-secret-one", merged["openai"]!!.apiKey)
        assertEquals("gsk-newer", merged["groq"]!!.apiKey)
        // The archive does not mention mistral, so neither does the result: this fills gaps, it does not
        // add accounts — the same whole-preference reading of Merge that JetPref itself applies.
        assertNull(merged["mistral"])
        // Nothing here to fill an account the device has never seen with.
        assertEquals("", merged["ollama"]!!.apiKey)
    }

    @Test
    fun `redacting and merging back is an exact round trip`() {
        val original = accountsOf(openai, groq)
        assertEquals(original, original.withoutSecrets().withSecretsFrom(original))
    }

    @Test
    fun `a keyless account is unchanged by the redaction`() {
        val ollama = ProviderAccount(
            providerId = "ollama",
            customBaseUrl = "http://10.0.0.2:11434/v1",
            chatModel = "llama3",
        )
        assertEquals(ollama, ollama.withoutSecrets())
    }

    @Test
    fun `hasNoSecrets answers for the keyring as a whole`() {
        assertFalse(ProviderAccounts.Empty.hasNoSecrets, "an empty keyring says nothing about credentials")
        assertFalse(accountsOf(openai, groq).hasNoSecrets)
        assertFalse(accountsOf(openai.withoutSecrets(), groq).hasNoSecrets)
        assertTrue(accountsOf(openai, groq).withoutSecrets().hasNoSecrets)
    }

    @Test
    fun `accountsFrom finds the keyring or says it is not there`() {
        val document = "b;clipboard__history_enabled;true\n" + keyringLine(accountsOf(openai)) + "\n"

        assertEquals("sk-the-secret-one", BackupRedaction.accountsFrom(document)!!["openai"]!!.apiKey)
        assertNull(BackupRedaction.accountsFrom("b;clipboard__history_enabled;true\n"))
    }

    @Test
    fun `the drop list still covers every secret preference`() {
        // The test that keeps this feature true a year from now. Without it, someone adds a preference that
        // holds a credential, forgets BackupRedaction, and every credential-free backup quietly carries it
        // — with nothing failing to say so.
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
        val suspicious = Regex("api_key|apikey|password|token|secret|credential|wallet|customer")

        // Preferences whose name trips the pattern but which hold nothing secret. Each one needs a reason.
        val allowed = setOf(
            // A one-time "the legacy flat keys have been imported" flag. A boolean, not a key.
            "dictate__provider_accounts_migrated",
        )

        val unhandled = prefs.declaredPreferenceEntries.keys
            .map { it.key }
            .filter { suspicious.containsMatchIn(it) }
            .filterNot { it in BackupRedaction.DROPPED_KEYS || it == BackupRedaction.ACCOUNTS_KEY }
            .filterNot { it in allowed }

        assertEquals(
            emptyList(),
            unhandled,
            "these preferences look like credentials but the backup redaction does not handle them; " +
                "either add them to BackupRedaction.DROPPED_KEYS or to this test's allow-list with a reason",
        )
    }
}
