package com.github.maskedkunisquat.lattice

import android.content.Context
import android.util.Log
import com.github.maskedkunisquat.lattice.core.logic.DistortionManifestStore
import com.github.maskedkunisquat.lattice.core.logic.DistortionMlp
import com.github.maskedkunisquat.lattice.core.logic.sha256Hex

private const val TAG = "DistortionMlpLoader"

/**
 * Android-specific entry point for loading a trained [DistortionMlp] from disk.
 *
 * Reads the manifest from SharedPreferences, verifies the embedding asset hash has not
 * changed since training, then delegates to the pure-JVM [DistortionMlp.loadWeights].
 * Returns `null` if no trained head exists, the manifest is stale, or the weight file
 * is corrupt — all without throwing.
 */
object DistortionMlpLoader {

    fun load(context: Context): DistortionMlp? {
        val prefs = context.getSharedPreferences(
            DistortionManifestStore.PREFS_NAME, Context.MODE_PRIVATE
        )
        val manifest = DistortionManifestStore.read(prefs) ?: return null

        if (manifest.schemaVersion != DistortionMlp.CURRENT_SCHEMA_VERSION) {
            Log.w(TAG, "Manifest schema v${manifest.schemaVersion} != ${DistortionMlp.CURRENT_SCHEMA_VERSION} — discarding")
            context.filesDir.resolve(manifest.headPath).delete()
            DistortionManifestStore.reset(prefs)
            return null
        }

        val currentHash = try {
            "sha256:${context.assets.open(DistortionMlp.EMBEDDING_ASSET).use { sha256Hex(it) }}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hash ${DistortionMlp.EMBEDDING_ASSET} — cannot verify manifest", e)
            return null
        }
        if (manifest.baseModelHash != currentHash) {
            Log.w(TAG, "Embedding model hash mismatch — discarding stale distortion head")
            context.filesDir.resolve(manifest.headPath).delete()
            DistortionManifestStore.reset(prefs)
            return null
        }

        // Clean up any staging file left behind by a crash between manifest commit and rename.
        context.filesDir.resolve("${manifest.headPath}.staged").delete()

        val weightFile = context.filesDir.resolve(manifest.headPath)
        if (!weightFile.exists() || weightFile.length() != DistortionMlp.WEIGHT_BYTES.toLong()) {
            weightFile.delete()
            DistortionManifestStore.reset(prefs)
            return null
        }
        return runCatching { DistortionMlp.loadWeights(weightFile, manifest.thresholds) }.getOrElse { e ->
            Log.e(TAG, "Failed to load weights — deleting stale head", e)
            weightFile.delete()
            DistortionManifestStore.reset(prefs)
            null
        }
    }
}
