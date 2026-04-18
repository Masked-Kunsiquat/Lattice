package com.github.maskedkunisquat.lattice.core.logic

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two-layer MLP that maps a 384-dim sentence embedding to a multi-label
 * [BooleanArray] of 12 [CognitiveDistortion] predictions.
 *
 * Architecture: `Linear(384 → 128) → ReLU → Linear(128 → 12) → Sigmoid`
 *
 * Each output class is thresholded independently using [thresholds].  Default
 * thresholds are 0.5; per-class tuned values are stored in [DistortionManifest]
 * after the evaluation sweep and provided here at load time.
 *
 * Weight layout (total ~50 K floats, ~203 KB):
 * ```
 *   w1 : [OUT1 × IN]   = [128 × 384] = 49 152 floats
 *   b1 : [OUT1]        = [128]        =    128 floats
 *   w2 : [OUT2 × OUT1] = [ 12 × 128] =  1 536 floats
 *   b2 : [OUT2]        = [ 12]        =     12 floats
 * ```
 *
 * Binary serialisation (IEEE 754 float32 little-endian):
 * ```
 *   [w1 floats] [b1 floats] [w2 floats] [b2 floats]
 * ```
 * Thresholds are **not** stored in the binary — they live in [DistortionManifest].
 */
class DistortionMlp(
    internal val w1: FloatArray = FloatArray(OUT1 * IN)   { xavierUniform(IN, OUT1) },
    internal val b1: FloatArray = FloatArray(OUT1),
    internal val w2: FloatArray = FloatArray(OUT2 * OUT1) { xavierUniform(OUT1, OUT2) },
    internal val b2: FloatArray = FloatArray(OUT2),
    val thresholds: FloatArray  = FloatArray(OUT2) { DEFAULT_THRESHOLD },
) {

    init {
        require(w1.size == OUT1 * IN)   { "w1 must be ${OUT1 * IN} floats, got ${w1.size}" }
        require(b1.size == OUT1)         { "b1 must be $OUT1 floats, got ${b1.size}" }
        require(w2.size == OUT2 * OUT1)  { "w2 must be ${OUT2 * OUT1} floats, got ${w2.size}" }
        require(b2.size == OUT2)         { "b2 must be $OUT2 floats, got ${b2.size}" }
        require(thresholds.size == OUT2) { "thresholds must be $OUT2 floats, got ${thresholds.size}" }
        require(w1.all { it.isFinite() }) { "w1 contains non-finite values" }
        require(b1.all { it.isFinite() }) { "b1 contains non-finite values" }
        require(w2.all { it.isFinite() }) { "w2 contains non-finite values" }
        require(b2.all { it.isFinite() }) { "b2 contains non-finite values" }
        require(thresholds.all { it.isFinite() && it in 0f..1f }) {
            "thresholds must all be finite and in [0, 1]"
        }
    }

    /**
     * Maps [embedding] (384-dim) to a 12-element [BooleanArray] of distortion predictions.
     *
     * Each class is thresholded independently: `output[i] = sigmoid(z2[i]) >= thresholds[i]`.
     *
     * IMPORTANT: [embedding] must be produced from PII-masked text only.
     */
    fun forward(embedding: FloatArray): BooleanArray {
        require(embedding.size == IN) { "Expected $IN-dim embedding, got ${embedding.size}" }
        require(embedding.all { it.isFinite() }) { "embedding contains non-finite values" }
        val logits = rawLogits(embedding)
        return BooleanArray(OUT2) { i -> logits[i] >= thresholds[i] }
    }

    /**
     * Returns the raw sigmoid outputs before thresholding.
     * Used by [DistortionMlpTrainer] for gradient computation.
     */
    internal fun rawLogits(embedding: FloatArray): FloatArray {
        val hidden = AffectiveMlp.linear(w1, b1, embedding, OUT1, IN).relu()
        val z2     = AffectiveMlp.linear(w2, b2, hidden,    OUT2, OUT1)
        return FloatArray(OUT2) { sigmoid(z2[it]) }
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Writes weights to [file] as a flat IEEE 754 float32 little-endian binary.
     * Thresholds are not written here — they are stored in [DistortionManifest].
     * Uses a temp-file + atomic-rename strategy to prevent partial writes.
     */
    fun saveWeights(file: File) {
        val parentDir = file.parentFile
        if (parentDir != null) {
            require(parentDir.mkdirs() || parentDir.exists()) {
                "Failed to create weight directory: $parentDir"
            }
        }
        val buf = ByteBuffer.allocate(WEIGHT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (arr in listOf(w1, b1, w2, b2)) arr.forEach { buf.putFloat(it) }

        val tmpFile = File.createTempFile(file.name, ".tmp", parentDir)
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(buf.array())
                fos.fd.sync()
            }
            try {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    companion object {
        const val IN   = 384
        const val OUT1 = 128
        const val OUT2 = 12   // one output per CognitiveDistortion

        const val DEFAULT_THRESHOLD = 0.5f

        val WEIGHT_COUNT = OUT1 * IN + OUT1 + OUT2 * OUT1 + OUT2  // 50 828
        val WEIGHT_BYTES = WEIGHT_COUNT * Float.SIZE_BYTES          // 203 312

        /**
         * Loads weights from [file] and returns a ready-to-use [DistortionMlp].
         *
         * @param thresholds Per-class sigmoid thresholds (12 floats). Defaults to 0.5 if absent.
         * @throws IllegalArgumentException if the file size does not match [WEIGHT_BYTES].
         */
        fun loadWeights(
            file: File,
            thresholds: FloatArray = FloatArray(OUT2) { DEFAULT_THRESHOLD },
        ): DistortionMlp {
            require(file.length() == WEIGHT_BYTES.toLong()) {
                "Weight file ${file.name} is ${file.length()} bytes; expected $WEIGHT_BYTES"
            }
            val buf = ByteBuffer.wrap(file.readBytes())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()

            fun next(n: Int) = FloatArray(n) { buf.get() }

            return DistortionMlp(
                w1         = next(OUT1 * IN),
                b1         = next(OUT1),
                w2         = next(OUT2 * OUT1),
                b2         = next(OUT2),
                thresholds = thresholds.copyOf(),
            )
        }

        /** Xavier uniform: U(−limit, +limit), limit = sqrt(6 / (fan_in + fan_out)) */
        internal fun xavierUniform(fanIn: Int, fanOut: Int): Float {
            val limit = sqrt(6f / (fanIn + fanOut))
            return Random.nextFloat() * 2f * limit - limit
        }

        /** Reproducible seeded initialisation for tests. */
        fun seeded(seed: Long): DistortionMlp {
            val rng = Random(seed)
            fun xavier(fanIn: Int, fanOut: Int): Float {
                val limit = sqrt(6f / (fanIn + fanOut))
                return rng.nextFloat() * 2f * limit - limit
            }
            return DistortionMlp(
                w1 = FloatArray(OUT1 * IN)   { xavier(IN, OUT1) },
                b1 = FloatArray(OUT1),
                w2 = FloatArray(OUT2 * OUT1) { xavier(OUT1, OUT2) },
                b2 = FloatArray(OUT2),
            )
        }

        const val CURRENT_SCHEMA_VERSION  = 1
        const val WEIGHT_FILE             = "distortion_mlp.bin"
        const val EMBEDDING_ASSET         = "snowflake-arctic-embed-xs_float32.tflite"
    }
}

// ── Activation helpers ────────────────────────────────────────────────────────

private fun FloatArray.relu(): FloatArray = FloatArray(size) { if (this[it] > 0f) this[it] else 0f }

internal fun sigmoid(x: Float): Float = 1f / (1f + exp(-x.toDouble()).toFloat())
