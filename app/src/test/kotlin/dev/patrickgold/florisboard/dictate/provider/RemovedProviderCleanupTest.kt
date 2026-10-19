/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.settings.advanced.BackupRedaction
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import dev.patrickgold.jetpref.datastore.runtime.LoadStrategy
import dev.patrickgold.jetpref.datastore.runtime.PersistStrategy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Saved Dictate Cloud data is wiped on upgrade and kept out on restore.
 *
 * The in-app credit provider ("cloud") was removed, but an install that used it still has its account in
 * the keyring: the wallet's bearer token in `apiKey`, next to a wallet id, a recovery code and a balance
 * cache that this version no longer declares. Plus the low-credit nudge latch, a preference that no longer
 * exists. Everything here is asserted against the *written document*, because a token that is still in the
 * file is the bug, whatever the loaded record says.
 */
class RemovedProviderCleanupTest {

    /** A keyring exactly as the last version with Dictate Cloud wrote it, wallet fields included. */
    private val legacyKeyringJson = """
        {"accounts":{
          "cloud":{"providerId":"cloud","apiKey":"wallet-bearer-token-secret","walletId":"w-123",
                   "walletRecoveryCode":"RECOVER-ME-4567","balanceSeconds":5400,"balanceRewords":120,
                   "balanceCheckedAt":1760000000000},
          "openai":{"providerId":"openai","apiKey":"sk-openai-keep","transcriptionModel":"whisper-1"},
          "custom:ab12cd34":{"providerId":"custom:ab12cd34","displayName":"Home","apiKey":"home-key-keep",
                   "customBaseUrl":"http://10.0.0.2:8000/v1/"}
        }}
    """.trimIndent().replace("\n", "")

    private val cloudSecrets = listOf("wallet-bearer-token-secret", "w-123", "RECOVER-ME-4567")

    private fun keyringLine(json: String) =
        "s;${BackupRedaction.ACCOUNTS_KEY};${BackupRedaction.encodeValue(json)}"

    /** A settings file from a Cloud user: the keyring, the nudge latch, both pointers at "cloud". */
    private val legacyDocument = listOf(
        keyringLine(legacyKeyringJson),
        "b;dictate__cloud_low_credit_nudged;true",
        "s;dictate__transcription_provider_id;\"cloud\"",
        "s;dictate__rewording_provider_id;\"cloud\"",
        "b;dictate__provider_accounts_migrated;true",
    ).joinToString("\n") + "\n"

    private class MemoryStorage(var content: String = "") : DataStoreReader, DataStoreWriter {
        override suspend fun read(): String = content
        override suspend fun write(content: String) {
            this.content = content
        }
    }

    private fun assertOtherAccountsSurvive(accounts: ProviderAccounts) {
        assertEquals(setOf("openai", "custom:ab12cd34"), accounts.accounts.keys)
        assertEquals("sk-openai-keep", accounts["openai"]!!.apiKey)
        assertEquals("whisper-1", accounts["openai"]!!.transcriptionModel)
        assertEquals("home-key-keep", accounts["custom:ab12cd34"]!!.apiKey)
        assertEquals("http://10.0.0.2:8000/v1/", accounts["custom:ab12cd34"]!!.customBaseUrl)
        assertEquals("Home", accounts["custom:ab12cd34"]!!.displayName)
    }

    @Test
    fun `a keyring with a cloud account loads without it and keeps every other key`() {
        val loaded = ProviderAccounts.Serializer.deserialize(legacyKeyringJson)

        assertNull(loaded["cloud"])
        assertOtherAccountsSurvive(loaded)
    }

    @Test
    fun `the re-encoded keyring no longer contains the wallet or its token`() {
        val cleaned = assertNotNull(ProviderAccounts.Serializer.withoutRemovedProvidersOrNull(legacyKeyringJson))

        for (secret in cloudSecrets) {
            assertFalse(cleaned.contains(secret), "cleaned keyring still contains $secret")
        }
        assertFalse(cleaned.contains("wallet"), "the old wallet fields must not be written back")
        assertOtherAccountsSurvive(ProviderAccounts.Serializer.deserialize(cleaned))
    }

    @Test
    fun `a keyring without removed providers is left exactly as it is`() {
        val clean = ProviderAccounts.Serializer.serialize(
            ProviderAccounts.Serializer.deserialize(legacyKeyringJson),
        )
        assertNull(ProviderAccounts.Serializer.withoutRemovedProvidersOrNull(clean))
        // Not a keyring at all: nothing to clean, and nothing invented in its place.
        assertNull(ProviderAccounts.Serializer.withoutRemovedProvidersOrNull("{not json"))
    }

    @Test
    fun `a cloud account is recognised by its stored id as well as its map key`() {
        val stray = ProviderAccounts(
            mapOf(
                "renamed" to ProviderAccount(providerId = "cloud", apiKey = "t"),
                "groq" to ProviderAccount(providerId = "groq", apiKey = "g"),
            ),
        )
        assertTrue(stray.hasRemovedProviders)
        assertEquals(setOf("groq"), stray.withoutRemovedProviders().accounts.keys)
        assertFalse(stray.withoutRemovedProviders().hasRemovedProviders)
    }

    @Test
    fun `startup wipes the cloud account and the nudge latch from the settings file`() = runBlocking {
        val storage = MemoryStorage(legacyDocument)
        val datastore = jetprefDataStoreOf(FlorisPreferenceModel::class)
        datastore.init(
            loadStrategy = LoadStrategy.UseReader(storage),
            persistStrategy = PersistStrategy.UseWriter(storage),
        ).getOrThrow()
        val prefs by datastore

        assertNull(prefs.dictate.providerAccounts.get()["cloud"])
        assertOtherAccountsSurvive(prefs.dictate.providerAccounts.get())
        assertEquals("openai", prefs.dictate.transcriptionProviderId.get())
        assertEquals("openai", prefs.dictate.rewordingProviderId.get())

        // What DictateLegacyMigrator.purgeRemovedProviderDataIfNeeded does: JetPref writes only on a set.
        prefs.dictate.providerAccounts.set(prefs.dictate.providerAccounts.get()).getOrThrow()

        val written = storage.content
        for (secret in cloudSecrets) {
            assertFalse(written.contains(secret), "settings file still contains $secret")
        }
        assertFalse(written.contains("dictate__cloud_low_credit_nudged"))
        assertFalse(written.contains("cloud"), "nothing of Dictate Cloud may remain in the file")
        assertTrue(written.contains("sk-openai-keep"))
        assertTrue(written.contains("home-key-keep"))
        assertTrue(written.contains("b;dictate__provider_accounts_migrated;true"))
    }

    @Test
    fun `restoring an old backup cannot bring the cloud account back`() = runBlocking {
        for (strategy in listOf(ImportStrategy.Merge, ImportStrategy.Erase)) {
            val storage = MemoryStorage()
            val datastore = jetprefDataStoreOf(FlorisPreferenceModel::class)
            datastore.init(
                loadStrategy = LoadStrategy.UseReader(storage),
                persistStrategy = PersistStrategy.UseWriter(storage),
            ).getOrThrow()
            val prefs by datastore

            // An import persists by itself, so the file is clean without any further write.
            datastore.import(strategy, MemoryStorage(legacyDocument)).getOrThrow()

            assertNull(prefs.dictate.providerAccounts.get()["cloud"], "$strategy")
            assertOtherAccountsSurvive(prefs.dictate.providerAccounts.get())
            for (secret in cloudSecrets) {
                assertFalse(storage.content.contains(secret), "$strategy restore wrote $secret")
            }
            assertFalse(storage.content.contains("dictate__cloud_low_credit_nudged"), "$strategy")
        }
    }

    @Test
    fun `the backup helpers never see a cloud account either`() {
        val document = keyringLine(legacyKeyringJson) + "\n"

        assertNull(BackupRedaction.accountsFrom(document)!!["cloud"])
        val restored = BackupRedaction.restoreSecrets(document, ProviderAccounts.Empty)
        for (secret in cloudSecrets) {
            assertFalse(restored.contains(secret), "merged document still contains $secret")
        }
        assertOtherAccountsSurvive(BackupRedaction.accountsFrom(restored)!!)
    }
}
