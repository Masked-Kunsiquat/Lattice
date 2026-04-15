package com.github.maskedkunisquat.lattice.core.logic

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A single labeled training sample for the affective head.
 *
 * [embedding] must be produced from PII-masked text only (already guaranteed
 * upstream by [EmbeddingProvider]).
 */
data class TrainingSample(
    val embedding: FloatArray,
    val targetValence: Float,
    val targetArousal: Float,
) {
    // 2.7-d: FloatArray equality is reference-based in Kotlin data classes; override
    // so that two samples with identical float values compare and hash correctly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrainingSample) return false
        return embedding.contentEquals(other.embedding) &&
            targetValence == other.targetValence &&
            targetArousal == other.targetArousal
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + targetValence.hashCode()
        result = 31 * result + targetArousal.hashCode()
        return result
    }
}

/**
 * On-device AdamW trainer for [AffectiveMlp].
 *
 * Mutates [mlp]'s weight arrays in-place so that the same [AffectiveMlp] instance
 * can be used for inference immediately after training without an explicit reload.
 *
 * ### Gradient derivation
 * ```
 * Forward:
 *   z1  = W1 @ x + b1          (OUT1-dim)
 *   h1  = relu(z1)              (OUT1-dim)
 *   z2  = W2 @ h1 + b2         (OUT2-dim)
 *   out = tanh(z2)              (OUT2-dim)
 *
 * Loss (MSE):
 *   L = 0.5 * ||out - target||^2
 *
 * Backward:
 *   dL/dz2[i]      = (out[i] - target[i]) * (1 - out[i]^2)     [tanh derivative]
 *   dL/dW2[i,j]    = dL/dz2[i] * h1[j]
 *   dL/db2[i]      = dL/dz2[i]
 *   dL/dh1[j]      = sum_i  dL/dz2[i] * W2[i * OUT1 + j]
 *   dL/dz1[j]      = dL/dh1[j] * (z1[j] > 0 ? 1 : 0)           [relu derivative]
 *   dL/dW1[j,k]    = dL/dz1[j] * x[k]
 *   dL/db1[j]      = dL/dz1[j]
 * ```
 *
 * ### AdamW update rule (per parameter θ with gradient g at global step t)
 * ```
 *   m       = β1·m + (1−β1)·g
 *   v       = β2·v + (1−β2)·g²
 *   m̂       = m / (1 − β1^t)
 *   v̂       = v / (1 − β2^t)
 *   θ       = θ·(1 − lr·λ) − lr·m̂ / (√v̂ + ε)      [weight matrices only]
 *   θ       = θ − lr·m̂ / (√v̂ + ε)                  [biases — no decay]
 * ```
 *
 * @param mlp          The [AffectiveMlp] whose weights will be updated in-place.
 * @param lr           Learning rate (default 1e-3).
 * @param weightDecay  L2 regularisation coefficient λ (default 1e-4). Applied to
 *                     weight matrices only, not biases, per AdamW convention.
 * @param epochs       Number of full passes over the sample list in [trainBatch]
 *                     (default 10).
 */
class AffectiveMlpTrainer(
    val mlp: AffectiveMlp,
    val lr: Float = 1e-3f,
    val weightDecay: Float = 1e-4f,
    val epochs: Int = 10,
) {
    private val beta1 = 0.9f
    private val beta2 = 0.999f
    private val epsilon = 1e-8f

    // First-moment buffers (same shape as corresponding weight arrays)
    private val mW1 = FloatArray(mlp.w1.size)
    private val mB1 = FloatArray(mlp.b1.size)
    private val mW2 = FloatArray(mlp.w2.size)
    private val mB2 = FloatArray(mlp.b2.size)

    // Second-moment buffers
    private val vW1 = FloatArray(mlp.w1.size)
    private val vB1 = FloatArray(mlp.b1.size)
    private val vW2 = FloatArray(mlp.w2.size)
    private val vB2 = FloatArray(mlp.b2.size)

    // Running products beta1^t and beta2^t — updated each step to avoid pow()
    private var beta1t = 1f
    private var beta2t = 1f

    /**
     * Runs one forward pass, computes MSE loss, runs backprop, and applies an
     * AdamW update to all weights in [mlp].
     *
     * @param embedding     384-dim float vector (must originate from PII-masked text).
     * @param targetValence Ground-truth valence in [-1, 1].
     * @param targetArousal Ground-truth arousal in [-1, 1].
     * @return MSE loss for this sample: `0.5 * ((v − tv)² + (a − ta)²)`.
     */
    fun trainStep(
        embedding: FloatArray,
        targetValence: Float,
        targetArousal: Float,
    ): Float {
        require(embedding.size == AffectiveMlp.IN) {
            "Expected ${AffectiveMlp.IN}-dim embedding, got ${embedding.size}"
        }

        // Advance bias correction accumulators
        beta1t *= beta1
        beta2t *= beta2
        val bias1 = 1f - beta1t
        val bias2 = 1f - beta2t

        // ── Forward pass ─────────────────────────────────────────────────────
        val z1 = AffectiveMlp.linear(mlp.w1, mlp.b1, embedding, AffectiveMlp.OUT1, AffectiveMlp.IN)
        val h1 = FloatArray(AffectiveMlp.OUT1) { if (z1[it] > 0f) z1[it] else 0f }
        val z2 = AffectiveMlp.linear(mlp.w2, mlp.b2, h1, AffectiveMlp.OUT2, AffectiveMlp.OUT1)
        val out = FloatArray(AffectiveMlp.OUT2) { tanh(z2[it].toDouble()).toFloat() }

        // ── MSE loss ─────────────────────────────────────────────────────────
        val errV = out[0] - targetValence
        val errA = out[1] - targetArousal
        val loss = 0.5f * (errV * errV + errA * errA)

        // ── Backward pass ─────────────────────────────────────────────────────
        // dL/dz2[i] = (out[i] - target[i]) * (1 - out[i]^2)
        val dz2 = FloatArray(AffectiveMlp.OUT2) { i ->
            val err = if (i == 0) errV else errA
            err * (1f - out[i] * out[i])
        }

        // dL/dW2[i*OUT1 + j] = dz2[i] * h1[j]
        val gW2 = FloatArray(mlp.w2.size) { idx ->
            dz2[idx / AffectiveMlp.OUT1] * h1[idx % AffectiveMlp.OUT1]
        }

        // dL/dh1[j] = sum_i dz2[i] * W2[i * OUT1 + j]
        val dh1 = FloatArray(AffectiveMlp.OUT1) { j ->
            var s = 0f
            for (i in 0 until AffectiveMlp.OUT2) s += dz2[i] * mlp.w2[i * AffectiveMlp.OUT1 + j]
            s
        }

        // dL/dz1[j] = dh1[j] * relu'(z1[j])
        val dz1 = FloatArray(AffectiveMlp.OUT1) { j -> if (z1[j] > 0f) dh1[j] else 0f }

        // dL/dW1[j*IN + k] = dz1[j] * x[k]
        val gW1 = FloatArray(mlp.w1.size) { idx ->
            dz1[idx / AffectiveMlp.IN] * embedding[idx % AffectiveMlp.IN]
        }

        // ── AdamW updates ─────────────────────────────────────────────────────
        // Biases: dL/db2 = dz2,  dL/db1 = dz1  (element-wise identity)
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
     * @param samples         Non-empty list of labeled training samples.
     * @param shouldContinue  Called before each epoch; return `false` to stop early
     *                        (e.g., pass `{ !isStopped }` from a WorkManager worker so
     *                        cancellation is cooperative between epochs).
     * @return Average MSE loss over the last completed epoch, or 0f if no epoch ran.
     */
    fun trainBatch(
        samples: List<TrainingSample>,
        shouldContinue: () -> Boolean = { true },
    ): Float {
        require(samples.isNotEmpty()) { "trainBatch requires at least one sample" }
        var lastEpochLoss = 0f
        repeat(epochs) { _ ->
            if (!shouldContinue()) return lastEpochLoss
            var epochLoss = 0f
            val order = samples.indices.shuffled()
            for (i in order) {
                val s = samples[i]
                epochLoss += trainStep(s.embedding, s.targetValence, s.targetArousal)
            }
            lastEpochLoss = epochLoss / samples.size
        }
        return lastEpochLoss
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
            val mHat = m[i] / bias1
            val vHat = v[i] / bias2
            val delta = lr * mHat / (sqrt(vHat) + epsilon)
            param[i] = if (applyDecay) param[i] * (1f - lr * weightDecay) - delta
                       else param[i] - delta
        }
    }
}
