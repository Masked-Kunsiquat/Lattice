package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Two-layer MLP that maps a 384-dim sentence embedding to (valence, arousal) in [-1, 1].
 *
 * Architecture: `Linear(384 → 128) → ReLU → Linear(128 → 2) → Tanh`
 *
 * Weights are stored as row-major [FloatArray]s so matrix–vector products are
 * straight inner-product loops — no BLAS dependency.
 *
 * Weight layout (total ~50 K floats, ~198 KB):
 * ```
 *   w1 : [OUT1 × IN]  = [128 × 384] = 49 152 floats
 *   b1 : [OUT1]       = [128]        =    128 floats
 *   w2 : [OUT2 × OUT1]= [  2 × 128] =    256 floats
 *   b2 : [OUT2]       = [  2]        =      2 floats
 * ```
 *
 * Binary serialisation (IEEE 754 float32 little-endian, same convention as
 * [com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters]):
 * ```
 *   [w1 floats] [b1 floats] [w2 floats] [b2 floats]
 * ```
 */
class AffectiveMlp(
    internal val w1: FloatArray = FloatArray(OUT1 * IN)  { xavierUniform(IN, OUT1) },
    internal val b1: FloatArray = FloatArray(OUT1),
    internal val w2: FloatArray = FloatArray(OUT2 * OUT1) { xavierUniform(OUT1, OUT2) },
    internal val b2: FloatArray = FloatArray(OUT2),
) {

    init {
        require(w1.size == OUT1 * IN)   { "w1 must be ${OUT1 * IN} floats, got ${w1.size}" }
        require(b1.size == OUT1)         { "b1 must be $OUT1 floats, got ${b1.size}" }
        require(w2.size == OUT2 * OUT1)  { "w2 must be ${OUT2 * OUT1} floats, got ${w2.size}" }
        require(b2.size == OUT2)         { "b2 must be $OUT2 floats, got ${b2.size}" }
    }

    /**
     * Maps [embedding] (384-dim) to (valence, arousal) in [-1, 1].
     *
     * IMPORTANT: [embedding] must be produced from PII-masked text only.
     *
     * @param embedding 384-dim float vector from [EmbeddingProvider.generateEmbedding].
     * @return Pair(valence, arousal) — both values in [-1, 1].
     */
    fun forward(embedding: FloatArray): Pair<Float, Float> {
        require(embedding.size == IN) {
            "Expected $IN-dim embedding, got ${embedding.size}"
        }
        val hidden = linear(w1, b1, embedding, OUT1, IN).relu()
        val output = linear(w2, b2, hidden, OUT2, OUT1).tanhActivation()
        return Pair(output[0], output[1])
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Writes all four weight arrays to [file] as a flat IEEE 754 float32
     * little-endian binary in the order w1, b1, w2, b2.
     *
     * The file size will always be exactly [WEIGHT_BYTES] bytes.
     */
    fun saveWeights(file: File) {
        file.parentFile?.mkdirs()
        val buf = ByteBuffer.allocate(WEIGHT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (arr in listOf(w1, b1, w2, b2)) {
            arr.forEach { buf.putFloat(it) }
        }
        FileOutputStream(file).use { it.write(buf.array()) }
    }

    companion object {
        const val IN   = 384
        const val OUT1 = 128
        const val OUT2 = 2

        /** Total floats across all four arrays. */
        val WEIGHT_COUNT = OUT1 * IN + OUT1 + OUT2 * OUT1 + OUT2  // 49 538

        /** Expected file size in bytes. */
        val WEIGHT_BYTES = WEIGHT_COUNT * Float.SIZE_BYTES         // 198 152

        private const val TAG_LOAD      = "AffectiveMlp"
        internal const val EMBEDDING_ASSET = "snowflake-arctic-embed-xs.onnx"

        /**
         * Loads [AffectiveMlp] from the checkpoint recorded in [AffectiveManifestStore].
         *
         * Steps:
         * 1. Read the manifest — returns `null` if none is present (first launch or reset).
         * 2. Compute SHA-256 of the bundled embedding asset and compare to
         *    [AffectiveManifest.baseModelHash].
         * 3. Hash mismatch (embedding model replaced): delete the stale weight file,
         *    clear the [AffectiveMlpInitializer] guard so warm-start re-runs on next
         *    launch, and return `null`.
         * 4. Weight file absent or wrong size: return `null`.
         * 5. On success: call [loadWeights] and return the trained [AffectiveMlp].
         *
         * @param context Android context — used for asset access, SharedPreferences, and `filesDir`.
         * @return A trained [AffectiveMlp], or `null` when the head is unavailable or stale.
         */
        fun load(context: Context): AffectiveMlp? {
            val prefs = context.getSharedPreferences(
                AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE
            )
            val manifest = AffectiveManifestStore.read(prefs) ?: return null

            val currentHash = "sha256:${context.assets.open(EMBEDDING_ASSET).use { sha256Hex(it) }}"
            if (manifest.baseModelHash != currentHash) {
                Log.w(TAG_LOAD, "Embedding model hash mismatch — deleting stale head and resetting warm-start")
                context.filesDir.resolve(manifest.headPath).delete()
                prefs.edit().remove(AffectiveMlpInitializer.PREF_KEY).apply()
                return null
            }

            val weightFile = context.filesDir.resolve(manifest.headPath)
            if (!weightFile.exists() || weightFile.length() != WEIGHT_BYTES.toLong()) return null
            return runCatching { loadWeights(weightFile) }.getOrNull()
        }

        /**
         * Loads weights from [file] and returns a ready-to-use [AffectiveMlp].
         *
         * @throws IllegalArgumentException if the file size does not match [WEIGHT_BYTES].
         */
        fun loadWeights(file: File): AffectiveMlp {
            require(file.length() == WEIGHT_BYTES.toLong()) {
                "Weight file ${file.name} is ${file.length()} bytes; expected $WEIGHT_BYTES"
            }
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            fun next(n: Int) = FloatArray(n) { buf.get() }

            return AffectiveMlp(
                w1 = next(OUT1 * IN),
                b1 = next(OUT1),
                w2 = next(OUT2 * OUT1),
                b2 = next(OUT2),
            )
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        /** Row-major matrix–vector product: out[i] = sum_j(W[i*cols + j] * x[j]) + bias[i] */
        internal fun linear(
            w: FloatArray, b: FloatArray,
            x: FloatArray, rows: Int, cols: Int,
        ): FloatArray {
            val out = FloatArray(rows)
            for (i in 0 until rows) {
                var acc = b[i]
                val rowOffset = i * cols
                for (j in 0 until cols) acc += w[rowOffset + j] * x[j]
                out[i] = acc
            }
            return out
        }

        /** Xavier uniform initialisation: U(-limit, +limit), limit = sqrt(6 / (fan_in + fan_out)) */
        internal fun xavierUniform(fanIn: Int, fanOut: Int): Float {
            val limit = sqrt(6f / (fanIn + fanOut))
            return Random.nextFloat() * 2f * limit - limit
        }
    }
}

// ── Activation helpers (private extensions) ───────────────────────────────────

private fun FloatArray.relu(): FloatArray = FloatArray(size) { if (this[it] > 0f) this[it] else 0f }

private fun FloatArray.tanhActivation(): FloatArray = FloatArray(size) { tanh(this[it].toDouble()).toFloat() }
