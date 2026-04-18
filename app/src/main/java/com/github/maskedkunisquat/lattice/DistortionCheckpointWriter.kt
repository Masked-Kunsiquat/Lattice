package com.github.maskedkunisquat.lattice

import android.content.Context
import android.util.Log
import com.github.maskedkunisquat.lattice.core.logic.CheckpointWriter
import com.github.maskedkunisquat.lattice.core.logic.DistortionManifest
import com.github.maskedkunisquat.lattice.core.logic.DistortionManifestStore
import com.github.maskedkunisquat.lattice.core.logic.DistortionMlp
import com.github.maskedkunisquat.lattice.core.logic.sha256Hex

/**
 * Android implementation of [CheckpointWriter] that saves [DistortionMlp] checkpoints
 * to [Context.filesDir] and persists the [DistortionManifest] in [SharedPreferences].
 *
 * ## Error contract
 * All I/O failures propagate — nothing is swallowed. Specifically:
 * - Hashing [DistortionMlp.EMBEDDING_ASSET] throws on failure rather than returning an empty hash.
 * - The manifest commit result is checked; an [IllegalStateException] is thrown on failure.
 */
class DistortionCheckpointWriter(private val context: Context) : CheckpointWriter {

    override fun write(mlp: DistortionMlp, trainedOnCount: Int, corpusVersion: String) {
        val weightFile = context.filesDir.resolve(DistortionMlp.WEIGHT_FILE)
        mlp.saveWeights(weightFile)
        Log.i(TAG, "Weights saved to ${weightFile.absolutePath}")

        val modelHash = "sha256:${context.assets.open(DistortionMlp.EMBEDDING_ASSET).use { sha256Hex(it) }}"
        Log.i(TAG, "Embedding asset hashed: $modelHash")

        val manifest = DistortionManifest(
            schemaVersion         = DistortionMlp.CURRENT_SCHEMA_VERSION,
            baseModelHash         = modelHash,
            headPath              = DistortionMlp.WEIGHT_FILE,
            trainedOnCount        = trainedOnCount,
            lastTrainingTimestamp = System.currentTimeMillis(),
            corpusVersion         = corpusVersion,
            thresholds            = mlp.thresholds.copyOf(),
        )

        val prefs = context.getSharedPreferences(DistortionManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        val committed = DistortionManifestStore.write(prefs, manifest)
        if (!committed) {
            throw IllegalStateException("DistortionManifest write failed — SharedPreferences.commit() returned false")
        }
        Log.i(TAG, "Manifest committed (trainedOn=$trainedOnCount, corpus=$corpusVersion)")
    }

    companion object {
        private const val TAG = "DistortionCheckpointWriter"
    }
}
