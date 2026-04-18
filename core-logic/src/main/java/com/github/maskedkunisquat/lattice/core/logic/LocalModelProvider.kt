package com.github.maskedkunisquat.lattice.core.logic

import kotlinx.coroutines.flow.StateFlow

/**
 * Extended [LlmProvider] contract for on-device model providers that require
 * explicit lifecycle management and expose download/load state to the UI.
 *
 * The Android-specific implementation lives in `:app` (`LocalFallbackProvider`)
 * and uses `Context`, `Engine`/`EngineConfig`/`Backend` from the LiteRT-LM SDK.
 * Core-logic code refers only to this interface.
 */
interface LocalModelProvider : LlmProvider {
    /** Current load state of the underlying model engine. */
    val modelLoadState: StateFlow<ModelLoadState>

    /**
     * Creates the model engine and marks the provider ready. Safe to call multiple
     * times — no-op once the engine is loaded. Automatically resets after a failure
     * so callers can retry (e.g. after [ModelDownloader] replaces a corrupt file).
     */
    fun initialize()

    /**
     * Enqueues a background download for the appropriate model file for this device.
     * Callers should observe [getDownloadWorkInfo] (or equivalent) for progress.
     */
    fun downloadModel()
}
