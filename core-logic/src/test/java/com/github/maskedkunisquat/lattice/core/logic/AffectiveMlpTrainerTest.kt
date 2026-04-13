package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectiveMlpTrainerTest {

    // ── trainStep — loss behaviour ────────────────────────────────────────────

    @Test
    fun `loss decreases over 100 trainStep calls on a single synthetic sample`() {
        val mlp = AffectiveMlp()
        val trainer = AffectiveMlpTrainer(mlp, lr = 1e-3f, weightDecay = 1e-4f, epochs = 1)

        val embedding = FloatArray(AffectiveMlp.IN) { 0.1f }
        val targetV = 0.7f
        val targetA = -0.3f

        val firstLoss = trainer.trainStep(embedding, targetV, targetA)
        var lastLoss = firstLoss
        repeat(99) { lastLoss = trainer.trainStep(embedding, targetV, targetA) }

        assertTrue(
            "Loss must decrease over 100 steps: first=$firstLoss, last=$lastLoss",
            lastLoss < firstLoss,
        )
    }

    @Test
    fun `trainStep returns non-negative loss`() {
        val trainer = AffectiveMlpTrainer(AffectiveMlp())
        val loss = trainer.trainStep(
            FloatArray(AffectiveMlp.IN) { 0.05f },
            targetValence = 0.5f,
            targetArousal = -0.5f,
        )
        assertTrue("MSE loss must be >= 0, got $loss", loss >= 0f)
    }

    @Test
    fun `trainStep loss is zero when output already matches target`() {
        // Construct a network whose forward pass returns exactly (tv, ta).
        // We force this by using zero weights so out = tanh(0) = (0, 0) and targeting (0, 0).
        val mlp = AffectiveMlp(
            w1 = FloatArray(AffectiveMlp.OUT1 * AffectiveMlp.IN),
            b1 = FloatArray(AffectiveMlp.OUT1),
            w2 = FloatArray(AffectiveMlp.OUT2 * AffectiveMlp.OUT1),
            b2 = FloatArray(AffectiveMlp.OUT2),
        )
        val trainer = AffectiveMlpTrainer(mlp)
        val loss = trainer.trainStep(FloatArray(AffectiveMlp.IN) { 1f }, 0f, 0f)

        assertEquals("Loss must be 0 when prediction equals target", 0f, loss, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trainStep rejects wrong embedding size`() {
        AffectiveMlpTrainer(AffectiveMlp()).trainStep(FloatArray(128), 0f, 0f)
    }

    // ── trainStep — weight update ─────────────────────────────────────────────

    @Test
    fun `trainStep mutates mlp weights`() {
        val mlp = AffectiveMlp()
        val w1Before = mlp.w1.copyOf()

        AffectiveMlpTrainer(mlp).trainStep(
            FloatArray(AffectiveMlp.IN) { 0.2f },
            targetValence = 0.5f,
            targetArousal = 0.5f,
        )

        var changed = false
        for (i in w1Before.indices) {
            if (w1Before[i] != mlp.w1[i]) { changed = true; break }
        }
        assertTrue("w1 must be mutated after trainStep", changed)
    }

    @Test
    fun `trainStep with zero-loss target does not change weights significantly`() {
        // target = (0, 0) with zero-weight network → gradients are zero → weights unchanged
        val mlp = AffectiveMlp(
            w1 = FloatArray(AffectiveMlp.OUT1 * AffectiveMlp.IN),
            b1 = FloatArray(AffectiveMlp.OUT1),
            w2 = FloatArray(AffectiveMlp.OUT2 * AffectiveMlp.OUT1),
            b2 = FloatArray(AffectiveMlp.OUT2),
        )
        val w1Before = mlp.w1.copyOf()
        AffectiveMlpTrainer(mlp).trainStep(FloatArray(AffectiveMlp.IN) { 1f }, 0f, 0f)

        // All gradients are zero so AdamW delta is 0; weight decay on zeros is also 0
        for (i in w1Before.indices) {
            assertEquals("w1[$i] must be unchanged at zero loss", w1Before[i], mlp.w1[i], 1e-9f)
        }
    }

    // ── trainBatch ────────────────────────────────────────────────────────────

    @Test
    fun `trainBatch reduces loss across multiple samples`() {
        val mlp = AffectiveMlp()
        val trainer = AffectiveMlpTrainer(mlp, lr = 1e-3f, weightDecay = 1e-4f, epochs = 10)

        // 5 samples, all with the same target — model should overfit quickly
        val samples = List(5) {
            TrainingSample(
                embedding = FloatArray(AffectiveMlp.IN) { (it * 0.001f) },
                targetValence = 0.6f,
                targetArousal = -0.4f,
            )
        }

        // Measure loss before training
        val lossBeforeV = mlp.forward(samples[0].embedding).first - 0.6f
        val lossBeforeA = mlp.forward(samples[0].embedding).second - (-0.4f)
        val lossBefore = 0.5f * (lossBeforeV * lossBeforeV + lossBeforeA * lossBeforeA)

        trainer.trainBatch(samples)

        val lossAfterV = mlp.forward(samples[0].embedding).first - 0.6f
        val lossAfterA = mlp.forward(samples[0].embedding).second - (-0.4f)
        val lossAfter = 0.5f * (lossAfterV * lossAfterV + lossAfterA * lossAfterA)

        assertTrue(
            "Loss after trainBatch must be lower than before: before=$lossBefore, after=$lossAfter",
            lossAfter < lossBefore,
        )
    }

    @Test
    fun `trainBatch returns finite average loss`() {
        val trainer = AffectiveMlpTrainer(AffectiveMlp(), epochs = 3)
        val samples = List(4) {
            TrainingSample(FloatArray(AffectiveMlp.IN) { 0.1f }, 0.3f, -0.2f)
        }
        val loss = trainer.trainBatch(samples)
        assertTrue("trainBatch loss must be finite", loss.isFinite())
        assertTrue("trainBatch loss must be >= 0", loss >= 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trainBatch rejects empty sample list`() {
        AffectiveMlpTrainer(AffectiveMlp()).trainBatch(emptyList())
    }

    // ── TrainingSample equality (2.7-d) ──────────────────────────────────────────

    @Test
    fun `TrainingSample equals is value-based not reference-based`() {
        val emb = FloatArray(AffectiveMlp.IN) { it * 0.001f }
        val a = TrainingSample(emb.copyOf(), 0.5f, -0.3f)
        val b = TrainingSample(emb.copyOf(), 0.5f, -0.3f)
        assertEquals("Two samples with identical values must be equal", a, b)
    }

    @Test
    fun `TrainingSample hashCode is consistent with equals`() {
        val emb = FloatArray(AffectiveMlp.IN) { it * 0.001f }
        val a = TrainingSample(emb.copyOf(), 0.5f, -0.3f)
        val b = TrainingSample(emb.copyOf(), 0.5f, -0.3f)
        assertEquals("Equal samples must have the same hashCode", a.hashCode(), b.hashCode())
    }

    @Test
    fun `TrainingSample with different embedding values are not equal`() {
        val a = TrainingSample(FloatArray(AffectiveMlp.IN) { 0.1f }, 0.5f, -0.3f)
        val b = TrainingSample(FloatArray(AffectiveMlp.IN) { 0.2f }, 0.5f, -0.3f)
        assertTrue("Samples with different embeddings must not be equal", a != b)
    }

    // ── hyperparameter wiring ─────────────────────────────────────────────────

    @Test
    fun `higher learning rate converges faster than lower on same task`() {
        val embedding = FloatArray(AffectiveMlp.IN) { 0.1f }
        val targetV = 0.8f
        val targetA = 0.2f

        fun trainAndMeasureFinalLoss(lr: Float): Float {
            val trainer = AffectiveMlpTrainer(AffectiveMlp(), lr = lr)
            var loss = 0f
            repeat(50) { loss = trainer.trainStep(embedding, targetV, targetA) }
            return loss
        }

        val lossHighLr = trainAndMeasureFinalLoss(1e-2f)
        val lossLowLr  = trainAndMeasureFinalLoss(1e-5f)

        assertTrue(
            "High lr should converge faster: highLr=$lossHighLr, lowLr=$lossLowLr",
            lossHighLr < lossLowLr,
        )
    }

    @Test
    fun `trainer exposes configurable epochs`() {
        val trainer = AffectiveMlpTrainer(AffectiveMlp(), epochs = 7)
        assertEquals(7, trainer.epochs)
    }
}
