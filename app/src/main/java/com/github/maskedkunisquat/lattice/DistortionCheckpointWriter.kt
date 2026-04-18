package com.github.maskedkunisquat.lattice

import android.content.Context
import android.util.Log
import com.github.maskedkunisquat.lattice.core.logic.CheckpointWriter
import com.github.maskedkunisquat.lattice.core.logic.DistortionManifest
import com.github.maskedkunisquat.lattice.core.logic.DistortionManifestStore
import com.github.maskedkunisquat.lattice.core.logic.DistortionMlp
import com.github.maskedkunisquat.lattice.core.logic.sha256Hex
import java.io.IOException

/**
 * Android implementation of [CheckpointWriter] that saves [DistortionMlp] checkpoints
 * to [Context.filesDir] and persists the [DistortionManifest] via [SharedPreferencesKeyValueStore].
 *
 * ## Error contract
 * All I/O failures propagate — nothing is swallowed. Specifically:
 * - Hashing [DistortionMlp.EMBEDDING_ASSET] throws on failure rather than returning an empty hash.
 * - The manifest commit result is checked; an [IllegalStateException] is thrown on failure.
 */
class DistortionCheckpointWriter(private val context: Context) : CheckpointWriter {

    override fun write(mlp: DistortionMlp, trainedOnCount: Int, corpusVersion: String) {
        val weightFile  = context.filesDir.resolve(DistortionMlp.WEIGHT_FILE)
        val stagingFile = context.filesDir.resolve("${DistortionMlp.WEIGHT_FILE}.staged")
        val backupFile  = context.filesDir.resolve("${DistortionMlp.WEIGHT_FILE}.bak")

        // Write weights to a staging path to protect the final path from partial writes.
        mlp.saveWeights(stagingFile)
        Log.i(TAG, "Weights staged to ${stagingFile.absolutePath}")

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

        // Weights are promoted BEFORE the manifest is committed so the manifest never
        // points to a missing or partial weight file. Any existing weights are snapshotted
        // first so we can restore them on failure.
        if (weightFile.exists()) weightFile.renameTo(backupFile)

        val renamed = stagingFile.renameTo(weightFile)
        if (!renamed) {
            try {
                stagingFile.copyTo(weightFile, overwrite = true)
                stagingFile.delete()
            } catch (e: IOException) {
                // Promotion failed — clean up the partial target and restore the backup.
                weightFile.delete()
                backupFile.takeIf { it.exists() }?.renameTo(weightFile)
                stagingFile.delete()
                throw e
            }
        }

        // Weights are in place — now durably commit the manifest.
        val store = SharedPreferencesKeyValueStore(
            context.getSharedPreferences(DistortionManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val committed = DistortionManifestStore.write(store, manifest)
        if (!committed) {
            // Manifest write failed — roll back to the previous weights.
            weightFile.delete()
            backupFile.takeIf { it.exists() }?.renameTo(weightFile)
            throw IllegalStateException("DistortionManifest write failed — SharedPreferences.commit() returned false")
        }

        backupFile.delete()
        Log.i(TAG, "Weights promoted and manifest committed (trainedOn=$trainedOnCount, corpus=$corpusVersion)")
    }

    companion object {
        private const val TAG = "DistortionCheckpointWriter"
    }
}
