/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads the community prompt library (issue #105) from the snapshot bundled inside the APK
 * (`assets/prompt-library.json`).
 *
 * The library is never fetched from the network: what ships with the app is what the browser shows.
 * Earlier versions downloaded the file from the upstream repository and cached it in `filesDir`; that
 * cache is no longer read, and is deleted (best effort) the next time the library is opened.
 */
object PromptLibraryManager {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Name of the download cache older versions kept in `filesDir`. Only ever deleted now. */
    private const val LEGACY_CACHE_FILE_NAME = "prompt-library.json"

    /** Name of the bundled library in the APK's assets. */
    private const val BUNDLED_ASSET_NAME = "prompt-library.json"

    /**
     * The bundled library, or an empty list if the asset cannot be read or parsed. Also removes the
     * download cache left behind by older versions.
     */
    suspend fun load(context: Context): List<PromptLibraryEntry> = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, LEGACY_CACHE_FILE_NAME).delete() }
        runCatching { parse(readBundledBody(context)) }.getOrDefault(emptyList())
    }

    private fun readBundledBody(context: Context): String =
        context.applicationContext.assets.open(BUNDLED_ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun parse(body: String): List<PromptLibraryEntry> {
        val doc = json.decodeFromString<PromptLibraryDocument>(body)
        // Guard against blank/duplicate entries so the browser stays clean regardless of the source.
        return doc.prompts
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.prompt.isNotBlank() }
            .distinctBy { it.id }
    }
}
