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
     * Training runs on a **copy** of [mlp]'s weights so that the original instance
     * (which may be serving [AffectiveMlp.forward] calls concurrently) is never
     * partially mutated.  Once training finishes and the checkpoint is persisted,
     * [onTrained] is invoked with the fully-trained copy so callers can atomically
     * swap their active reference.
     *
     * Safe to call multiple times — subsequent calls are no-ops once the
     * SharedPreferences guard is set.
     *
     * @param context   Android context — used for asset access and SharedPreferences.
     * @param mlp       The [AffectiveMlp] whose initial weights seed the warm-start copy.
     *                  It is **never** mutated by this method.
     * @param scope     CoroutineScope to run the background work in (e.g. applicationScope).
     * @param onTrained Invoked on the background dispatcher with the fully-trained
     *                  [AffectiveMlp] after the checkpoint has been persisted successfully.
     *                  Defaults to a no-op; callers that want to hot-swap the active model
     *                  should update their reference here.
     */
    fun maybeInitialize(
        context: Context,
        mlp: AffectiveMlp,
        scope: CoroutineScope,
        onTrained: (AffectiveMlp) -> Unit = {},
    ) {
        val prefs = context.getSharedPreferences(AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_KEY, false)) return

        scope.launch(dispatcher) {
            try {
                val samples = context.assets.open(ASSET_PATH).use { loadSamples(it) }

                // 2.7-b: empty asset must still set the guard — otherwise every launch retries
                if (samples.isEmpty()) {
                    Log.w(TAG, "No samples in $ASSET_PATH — skipping warm-start, marking initialized to prevent retry loop")
                    prefs.edit().putBoolean(PREF_KEY, true).apply()
                    return@launch
                }

                // Train on a fresh copy so forward() on the live mlp is never called
                // against partially-updated weights during the training loop.
                val trainableCopy = AffectiveMlp(
                    w1 = mlp.w1.copyOf(),
                    b1 = mlp.b1.copyOf(),
                    w2 = mlp.w2.copyOf(),
                    b2 = mlp.b2.copyOf(),
                )
                val trainer = AffectiveMlpTrainer(trainableCopy, epochs = EPOCHS)
                val finalLoss = trainer.trainBatch(samples)
                Log.i(TAG, "Warm-start complete: ${samples.size} samples, final loss=${"%.6f".format(finalLoss)}")

                // 2.7-a: set guard BEFORE writing weights/manifest so a process kill here
                // does not cause an infinite retry loop on next launch
                prefs.edit().putBoolean(PREF_KEY, true).apply()

                // Persist weights + manifest; clear guard on I/O failure so next launch retries
                try {
                    val weightFile = context.filesDir.resolve(WEIGHT_FILE)
                    trainableCopy.saveWeights(weightFile)
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
                    val wrote = AffectiveManifestStore.write(prefs, manifest)
                    if (!wrote) {
                        // Manifest could not be durably committed — clear the guard so the
                        // next launch retries rather than silently skipping initialisation.
                        Log.w(TAG, "Manifest commit failed — clearing guard so next launch retries")
                        prefs.edit().putBoolean(PREF_KEY, false).apply()
                    } else {
                        Log.i(TAG, "Manifest written: trainedOnCount=${samples.size}, hash=$modelHash")
                        onTrained(trainableCopy)
                    }
                } catch (e: Exception) {
                    prefs.edit().putBoolean(PREF_KEY, false).apply()
                    throw e
                }
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
     *   [AffectiveMlp.IN], or if the file is too short to contain the declared row count.
     */
    internal fun loadSamples(stream: InputStream): List<TrainingSample> {
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val count = buf.int
        val dim   = buf.int
        require(dim == AffectiveMlp.IN) {
            "Asset embedding dim=$dim; expected ${AffectiveMlp.IN}"
        }

        // 2.7-c: guard against truncated/corrupted assets before the read loop;
        // use Long arithmetic to avoid Int overflow on a malformed count header
        val expectedBytes = 8L + count.toLong() * (dim + 2) * Float.SIZE_BYTES
        require(bytes.size.toLong() >= expectedBytes) {
            "Asset truncated: ${bytes.size} bytes present, need $expectedBytes for $count samples of dim=$dim"
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
