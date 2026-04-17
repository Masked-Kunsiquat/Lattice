package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Log
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * On-device AdamW trainer for [DistortionMlp].
 *
 * Mutates [mlp]'s weight arrays in-place so that the same [DistortionMlp] instance
 * can be used for inference immediately after training without an explicit reload.
 *
 * ### Loss
 * Mean binary cross-entropy over all 12 output classes:
 * ```
 * L = (1 / OUT2) * Σ_i  −[ y_i · log(p_i) + (1 − y_i) · log(1 − p_i) ]
 * ```
 * where `p_i = sigmoid(z2[i])`.  Probabilities are clamped to [1e-7, 1 − 1e-7]
 * before taking the log to prevent NaN.
 *
 * ### Gradient (sigmoid + BCE collapses cleanly)
 * ```
 * dL/dz2[i]   = (p_i − y_i) / OUT2
 * dL/dW2[i,j] = dz2[i] · h1[j]
 * dL/db2[i]   = dz2[i]
 * dL/dh1[j]   = Σ_i  dz2[i] · W2[i · OUT1 + j]
 * dL/dz1[j]   = dh1[j] · (z1[j] > 0 ? 1 : 0)    [ReLU derivative]
 * dL/dW1[j,k] = dz1[j] · x[k]
 * dL/db1[j]   = dz1[j]
 * ```
 *
 * ### AdamW update rule (per parameter θ, gradient g, global step t)
 * ```
 * m  = β1·m + (1−β1)·g
 * v  = β2·v + (1−β2)·g²
 * m̂  = m / (1 − β1^t)
 * v̂  = v / (1 − β2^t)
 * θ  = θ·(1 − lr·λ) − lr·m̂ / (√v̂ + ε)    [weight matrices — weight decay applied]
 * θ  = θ − lr·m̂ / (√v̂ + ε)               [biases — no weight decay]
 * ```
 *
 * @param mlp          The [DistortionMlp] whose weights will be updated in-place.
 * @param lr           Learning rate (default 1e-3).
 * @param weightDecay  L2 regularisation coefficient λ (default 1e-4). Applied to
 *                     weight matrices only, not biases, per AdamW convention.
 * @param epochs       Number of full passes over the sample list in [trainBatch] (default 10).
 */
class DistortionMlpTrainer(
    val mlp: DistortionMlp,
    val lr: Float = 1e-3f,
    val weightDecay: Float = 1e-4f,
    val epochs: Int = 10,
) {
    private val beta1   = 0.9f
    private val beta2   = 0.999f
    private val epsilon = 1e-8f

    // First-moment buffers
    private val mW1 = FloatArray(mlp.w1.size)
    private val mB1 = FloatArray(mlp.b1.size)
    private val mW2 = FloatArray(mlp.w2.size)
    private val mB2 = FloatArray(mlp.b2.size)

    // Second-moment buffers
    private val vW1 = FloatArray(mlp.w1.size)
    private val vB1 = FloatArray(mlp.b1.size)
    private val vW2 = FloatArray(mlp.w2.size)
    private val vB2 = FloatArray(mlp.b2.size)

    // Running products β1^t and β2^t — updated each step to avoid pow()
    private var beta1t = 1f
    private var beta2t = 1f

    /**
     * Runs one forward pass, computes mean-BCE loss, runs backprop, and applies an
     * AdamW update to all weights in [mlp].
     *
     * @param embedding 384-dim float vector (must originate from PII-masked text).
     * @param labels    12-element boolean array aligned to [CognitiveDistortion.ordinal].
     * @return Mean BCE loss over all 12 classes for this sample.
     */
    fun trainStep(embedding: FloatArray, labels: BooleanArray): Float {
        require(embedding.size == DistortionMlp.IN) {
            "Expected ${DistortionMlp.IN}-dim embedding, got ${embedding.size}"
        }
        require(labels.size == DistortionMlp.OUT2) {
            "Expected ${DistortionMlp.OUT2} labels, got ${labels.size}"
        }

        // Advance bias-correction accumulators
        beta1t *= beta1
        beta2t *= beta2
        val bias1 = 1f - beta1t
        val bias2 = 1f - beta2t

        // ── Forward pass ─────────────────────────────────────────────────────
        val z1 = AffectiveMlp.linear(mlp.w1, mlp.b1, embedding, DistortionMlp.OUT1, DistortionMlp.IN)
        val h1 = FloatArray(DistortionMlp.OUT1) { if (z1[it] > 0f) z1[it] else 0f }
        val z2 = AffectiveMlp.linear(mlp.w2, mlp.b2, h1, DistortionMlp.OUT2, DistortionMlp.OUT1)
        // sigmoid probabilities, clamped for numerical stability
        val p  = FloatArray(DistortionMlp.OUT2) { sigmoid(z2[it]).coerceIn(1e-7f, 1f - 1e-7f) }

        // ── Mean BCE loss ─────────────────────────────────────────────────────
        var loss = 0f
        for (i in 0 until DistortionMlp.OUT2) {
            val y = if (labels[i]) 1f else 0f
            loss += -(y * ln(p[i]) + (1f - y) * ln(1f - p[i]))
        }
        loss /= DistortionMlp.OUT2

        // ── Backward pass ─────────────────────────────────────────────────────
        // dL/dz2[i] = (p_i - y_i) / OUT2   [sigmoid-BCE combined gradient]
        val dz2 = FloatArray(DistortionMlp.OUT2) { i ->
            val y = if (labels[i]) 1f else 0f
            (p[i] - y) / DistortionMlp.OUT2
        }

        // dL/dW2[i*OUT1 + j] = dz2[i] * h1[j]
        val gW2 = FloatArray(mlp.w2.size) { idx ->
            dz2[idx / DistortionMlp.OUT1] * h1[idx % DistortionMlp.OUT1]
        }

        // dL/dh1[j] = Σ_i  dz2[i] * W2[i * OUT1 + j]
        val dh1 = FloatArray(DistortionMlp.OUT1) { j ->
            var s = 0f
            for (i in 0 until DistortionMlp.OUT2) s += dz2[i] * mlp.w2[i * DistortionMlp.OUT1 + j]
            s
        }

        // dL/dz1[j] = dh1[j] * relu'(z1[j])
        val dz1 = FloatArray(DistortionMlp.OUT1) { j -> if (z1[j] > 0f) dh1[j] else 0f }

        // dL/dW1[j*IN + k] = dz1[j] * embedding[k]
        val gW1 = FloatArray(mlp.w1.size) { idx ->
            dz1[idx / DistortionMlp.IN] * embedding[idx % DistortionMlp.IN]
        }

        // ── AdamW updates ─────────────────────────────────────────────────────
        adamw(mlp.w1, mW1, vW1, gW1, bias1, bias2, applyDecay = true)
        adamw(mlp.b1, mB1, vB1, dz1, bias1, bias2, applyDecay = false)
        adamw(mlp.w2, mW2, vW2, gW2, bias1, bias2, applyDecay = true)
        adamw(mlp.b2, mB2, vB2, dz2, bias1, bias2, applyDecay = false)

        return loss
    }

    /**
     * Trains [mlp] on [samples] for [epochs] full passes, shuffling the sample
     * order before each epoch.
     *
     * @param samples         Non-empty list of [DistortionSample] instances.
     * @param shouldContinue  Called before each epoch; return `false` to stop early.
     * @return Average BCE loss over the last completed epoch, or 0f if no epoch ran.
     */
    fun trainBatch(
        samples: List<DistortionSample>,
        shouldContinue: () -> Boolean = { true },
    ): Float {
        require(samples.isNotEmpty()) { "trainBatch requires at least one sample" }
        var lastEpochLoss = 0f
        repeat(epochs) {
            if (!shouldContinue()) return lastEpochLoss
            var epochLoss = 0f
            val order = samples.indices.shuffled()
            for (i in order) {
                val s = samples[i]
                epochLoss += trainStep(s.embedding, s.labels)
            }
            lastEpochLoss = epochLoss / samples.size
        }
        return lastEpochLoss
    }

    /**
     * Saves the trained weights and writes a [DistortionManifest] to SharedPreferences.
     *
     * Thresholds default to 0.5 — call [DistortionManifestStore.write] with an updated
     * manifest after running a threshold sweep on the validation set.
     *
     * @param context          Android [Context] used to resolve `filesDir` and hash the
     *                         embedding asset.
     * @param trainedOnCount   Number of samples this head was trained on (for the manifest).
     * @param corpusVersion    Corpus version tag written into the manifest (default "v1").
     */
    fun save(
        context: Context,
        trainedOnCount: Int,
        corpusVersion: String = "v1",
    ) {
        val weightFile = context.filesDir.resolve(DistortionMlp.WEIGHT_FILE)
        mlp.saveWeights(weightFile)

        val modelHash = try {
            "sha256:${context.assets.open(EMBEDDING_ASSET).use { sha256Hex(it) }}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hash $EMBEDDING_ASSET while saving manifest", e)
            ""
        }

        val manifest = DistortionManifest(
            schemaVersion         = DistortionMlp.CURRENT_SCHEMA_VERSION,
            baseModelHash         = modelHash,
            headPath              = DistortionMlp.WEIGHT_FILE,
            trainedOnCount        = trainedOnCount,
            lastTrainingTimestamp = System.currentTimeMillis(),
            corpusVersion         = corpusVersion,
            thresholds            = mlp.thresholds.copyOf(),
        )

        val prefs = context.getSharedPreferences(
            DistortionManifestStore.PREFS_NAME, Context.MODE_PRIVATE
        )
        DistortionManifestStore.write(prefs, manifest)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun adamw(
        param: FloatArray,
        m: FloatArray,
        v: FloatArray,
        grad: FloatArray,
        bias1: Float,
        bias2: Float,
        applyDecay: Boolean,
    ) {
        for (i in param.indices) {
            m[i] = beta1 * m[i] + (1f - beta1) * grad[i]
            v[i] = beta2 * v[i] + (1f - beta2) * grad[i] * grad[i]
            val mHat  = m[i] / bias1
            val vHat  = v[i] / bias2
            val delta = lr * mHat / (sqrt(vHat) + epsilon)
            param[i]  = if (applyDecay) param[i] * (1f - lr * weightDecay) - delta
                        else param[i] - delta
        }
    }

    companion object {
        private const val TAG              = "DistortionMlpTrainer"
        private const val EMBEDDING_ASSET  = "snowflake-arctic-embed-xs_float32.tflite"
    }
}
