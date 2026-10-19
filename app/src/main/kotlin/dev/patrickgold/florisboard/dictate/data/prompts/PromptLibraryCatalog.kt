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

/**
 * Hosting locations for the community prompt library (issue #105).
 *
 * Same zero-ops, self-hosted pattern as the glide dictionaries and Whisper models: a single static
 * JSON file lives on the project's own repository, and the app fetches it on demand (only when the
 * user opens the library browser — never on launch). No third-party service is involved.
 *
 * The library lives on its own orphan branch [BRANCH] rather than in a release asset, so it can be
 * updated without cutting a release for every added prompt.
 */
object PromptLibraryCatalog {
    private const val OWNER = "DevEmperor"
    private const val REPO = "DictateKeyboard"

    /** Branch that holds the library file. Single re-point for hosting. */
    const val BRANCH = "prompt-library"

    /** Path of the library file within the branch. */
    const val LIBRARY_PATH = "library.json"

    /**
     * Raw URL the app fetches. `raw.githubusercontent.com` serves it from GitHub's CDN with a short
     * (~5 min) cache — perfectly fine for a prompt list that only needs to be eventually consistent.
     */
    const val LIBRARY_URL = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$LIBRARY_PATH"
}
