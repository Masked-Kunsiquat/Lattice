package com.github.maskedkunisquat.lattice.core.logic

/**
 * Platform-agnostic interface for persisting a trained [DistortionMlp] checkpoint.
 *
 * Implementations are responsible for:
 * - Writing the weight binary to stable storage.
 * - Hashing the embedding asset to detect model drift.
 * - Persisting a [DistortionManifest] durably.
 *
 * The Android-specific implementation lives in `:app` and uses `Context.filesDir`,
 * `Context.assets`, and `SharedPreferences`. Core-logic code never imports Android APIs.
 *
 * @throws Exception on any I/O or persistence failure — callers must not swallow this.
 */
fun interface CheckpointWriter {
    /**
     * Persists [mlp]'s weights and writes a [DistortionManifest] recording the training run.
     *
     * @param mlp              The trained model whose weights should be saved.
     * @param trainedOnCount   Number of samples this head was trained on.
     * @param corpusVersion    Corpus version tag (e.g. "v1") written into the manifest.
     * @throws Exception if saving weights, hashing the embedding asset, or writing
     *                   the manifest fails.
     */
    fun write(mlp: DistortionMlp, trainedOnCount: Int, corpusVersion: String)
}
