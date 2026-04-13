package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Warm-starts [AffectiveMlp] from the GoEmotions base layer asset on first launch.
 *
 * Reads `training/goEmotions_base_v1.bin` from assets, deserialises the
 * (embedding, valence, arousal) pairs, runs [AffectiveMlpTrainer.trainBatch] for
 * [EPOCHS] passes, and persists the resulting weights to
 * `filesDir/affective_head_v1.bin`.
 *
 * Guarded by [PREF_KEY] in SharedPreferences so training runs exactly once.
 * [maybeInitialize] returns immediately; the MLP uses its current (randomly
 * initialised or previously saved) weights until the background work finishes.
 *
 * Binary format of `goEmotions_base_v1.bin` (all values IEEE 754 little-endian):
 * ```
 *   [int32]  count  — number of rows
 *   [int32]  dim    — embedding dimension (must equal AffectiveMlp.IN = 384)
 *   count × (dim + 2) × float32
 *       [0 : dim]   embedding vector (384 floats)
 *       [dim]       valence  (float32, range [-1, 1])
 *       [dim + 1]   arousal  (float32, range [-1, 1])
 * ```
 */
class AffectiveMlpInitializer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Launches warm-start training in the background if not already completed.
     *
     * Safe to call multiple times — subsequent calls are no-ops once the
     * SharedPreferences guard is set.
     *
     * @param context Android context — used for asset access and SharedPreferences.
     * @param mlp     The [AffectiveMlp] to warm-start; weights are updated in-place.
     * @param scope   CoroutineScope to run the background work in (e.g. applicationScope).
     */
    fun maybeInitialize(
        context: Context,
        mlp: AffectiveMlp,
        scope: CoroutineScope,
    ) {
        val prefs = context.getSharedPreferences(AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_KEY, false)) return

        scope.launch(dispatcher) {
            try {
                val samples = context.assets.open(ASSET_PATH).use { loadSamples(it) }
                if (samples.isEmpty()) {
                    Log.w(TAG, "No samples in $ASSET_PATH — skipping warm-start")
                    return@launch
                }

                val trainer = AffectiveMlpTrainer(mlp, epochs = EPOCHS)
                val finalLoss = trainer.trainBatch(samples)
                Log.i(TAG, "Warm-start complete: ${samples.size} samples, final loss=${"%.6f".format(finalLoss)}")

                val weightFile = context.filesDir.resolve(WEIGHT_FILE)
                mlp.saveWeights(weightFile)
                Log.i(TAG, "Weights saved to ${weightFile.absolutePath}")

                val modelHash = "sha256:${context.assets.open(AffectiveMlp.EMBEDDING_ASSET).use { sha256Hex(it) }}"
                val manifest = AffectiveManifest(
                    schemaVersion         = 1,
                    baseModelHash         = modelHash,
                    headPath              = WEIGHT_FILE,
                    trainedOnCount        = samples.size,
                    lastTrainingTimestamp = System.currentTimeMillis(),
                    baseLayerVersion      = BASE_LAYER_VERSION,
                )
                AffectiveManifestStore.write(prefs, manifest)
                Log.i(TAG, "Manifest written: trainedOnCount=${samples.size}, hash=$modelHash")

                prefs.edit().putBoolean(PREF_KEY, true).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Warm-start failed: ${e.message}", e)
            }
        }
    }

    // ── Deserialization ───────────────────────────────────────────────────────

    /**
     * Reads (embedding, valence, arousal) triples from [stream] and returns
     * them as [TrainingSample]s.
     *
     * Internal and takes [InputStream] directly so it can be exercised in
     * desktop-JVM unit tests without an Android [Context].
     *
     * @throws IllegalArgumentException if the header dimension does not match
     *   [AffectiveMlp.IN].
     */
    internal fun loadSamples(stream: InputStream): List<TrainingSample> {
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val count = buf.int
        val dim   = buf.int
        require(dim == AffectiveMlp.IN) {
            "Asset embedding dim=$dim; expected ${AffectiveMlp.IN}"
        }

        return List(count) {
            val embedding = FloatArray(dim) { buf.float }
            val valence   = buf.float
            val arousal   = buf.float
            TrainingSample(embedding, valence, arousal)
        }
    }

    companion object {
        private const val TAG         = "AffectiveMlpInit"
        const val PREF_KEY            = "affective_head_initialized"
        const val WEIGHT_FILE         = "affective_head_v1.bin"
        const val ASSET_PATH          = "training/goEmotions_base_v1.bin"
        const val EPOCHS              = 5
        const val BASE_LAYER_VERSION  = "goEmotions-1.0"
    }
}
