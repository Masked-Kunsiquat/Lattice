package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistortionMlpTrainerTest {

    // ── trainStep — loss behaviour ────────────────────────────────────────────

    @Test
    fun `loss decreases over 100 trainStep calls on a single synthetic sample`() {
        val mlp = DistortionMlp.seeded(0L)
        val trainer = DistortionMlpTrainer(mlp, lr = 1e-3f)

        val embedding = FloatArray(DistortionMlp.IN) { 0.1f }
        val labels = BooleanArray(DistortionMlp.OUT2) { it % 3 == 0 }  // sparse multi-label

        val firstLoss = trainer.trainStep(embedding, labels)
        var lastLoss = firstLoss
        repeat(99) { lastLoss = trainer.trainStep(embedding, labels) }

        assertTrue(
            "Loss must decrease over 100 steps: first=$firstLoss, last=$lastLoss",
            lastLoss < firstLoss,
        )
    }

    @Test
    fun `trainStep returns non-negative loss`() {
        val trainer = DistortionMlpTrainer(DistortionMlp.seeded(1L))
        val loss = trainer.trainStep(
            FloatArray(DistortionMlp.IN) { 0.05f },
            BooleanArray(DistortionMlp.OUT2),
        )
        assertTrue("BCE loss must be >= 0, got $loss", loss >= 0f)
    }

    @Test
    fun `trainStep loss is near zero when output already matches target`() {
        // Force all logits to a large negative value → all probs ~0 → loss ~0 when all labels false
        val mlp = DistortionMlp(
            w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN),
            b1 = FloatArray(DistortionMlp.OUT1),
            w2 = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1),
            b2 = FloatArray(DistortionMlp.OUT2) { -10f },
        )
        val trainer = DistortionMlpTrainer(mlp)
        val allFalse = BooleanArray(DistortionMlp.OUT2)
        val loss = trainer.trainStep(FloatArray(DistortionMlp.IN) { 1f }, allFalse)
        assertTrue("Loss must be near 0 when all predictions match all-false target, got $loss", loss < 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trainStep rejects wrong embedding size`() {
        DistortionMlpTrainer(DistortionMlp()).trainStep(FloatArray(128), BooleanArray(12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trainStep rejects wrong labels size`() {
        DistortionMlpTrainer(DistortionMlp()).trainStep(FloatArray(DistortionMlp.IN), BooleanArray(5))
    }

    // ── trainStep — weight mutation ───────────────────────────────────────────

    @Test
    fun `trainStep mutates mlp weights`() {
        val mlp = DistortionMlp.seeded(5L)
        val w1Before = mlp.w1.copyOf()

        DistortionMlpTrainer(mlp).trainStep(
            FloatArray(DistortionMlp.IN) { 0.2f },
            BooleanArray(DistortionMlp.OUT2) { it < 3 },
        )

        var changed = false
        for (i in w1Before.indices) {
            if (w1Before[i] != mlp.w1[i]) { changed = true; break }
        }
        assertTrue("w1 must be mutated after trainStep", changed)
    }

    @Test
    fun `loss stays finite under aggressive lr`() {
        val trainer = DistortionMlpTrainer(DistortionMlp.seeded(99L), lr = 1.0f)
        val emb = FloatArray(DistortionMlp.IN) { 0.1f }
        val labels = BooleanArray(DistortionMlp.OUT2) { it % 2 == 0 }
        repeat(20) { step ->
            val loss = trainer.trainStep(emb, labels)
            assertTrue("Loss must be finite at step $step, got $loss", loss.isFinite())
        }
    }

    // ── trainBatch ────────────────────────────────────────────────────────────

    @Test
    fun `trainBatch reduces loss across multiple samples`() {
        val mlp = DistortionMlp.seeded(10L)
        val trainer = DistortionMlpTrainer(mlp, lr = 1e-3f, epochs = 10)

        val labels = BooleanArray(DistortionMlp.OUT2) { it % 4 == 0 }
        val samples = List(8) {
            DistortionSample(
                embedding = FloatArray(DistortionMlp.IN) { (it * 0.001f) },
                labels    = labels,
            )
        }

        // Measure BCE loss before training via rawLogits
        fun sampleLoss(): Float {
            val p = mlp.rawLogits(samples[0].embedding)
            var l = 0f
            for (i in 0 until DistortionMlp.OUT2) {
                val y = if (labels[i]) 1f else 0f
                val pi = p[i].coerceIn(1e-7f, 1f - 1e-7f)
                l += -(y * kotlin.math.ln(pi) + (1f - y) * kotlin.math.ln(1f - pi))
            }
            return l / DistortionMlp.OUT2
        }

        val lossBefore = sampleLoss()
        trainer.trainBatch(samples)
        val lossAfter = sampleLoss()

        assertTrue(
            "Loss after trainBatch must be lower: before=$lossBefore, after=$lossAfter",
            lossAfter < lossBefore,
        )
    }

    @Test
    fun `trainBatch returns finite average loss`() {
        val trainer = DistortionMlpTrainer(DistortionMlp.seeded(2L), epochs = 3)
        val samples = List(4) {
            DistortionSample(
                FloatArray(DistortionMlp.IN) { 0.1f },
                BooleanArray(DistortionMlp.OUT2) { it == 0 },
            )
        }
        val loss = trainer.trainBatch(samples)
        assertTrue("trainBatch loss must be finite", loss.isFinite())
        assertTrue("trainBatch loss must be >= 0", loss >= 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trainBatch rejects empty sample list`() {
        DistortionMlpTrainer(DistortionMlp()).trainBatch(emptyList())
    }

    @Test
    fun `shouldContinue false on first epoch returns 0`() {
        val trainer = DistortionMlpTrainer(DistortionMlp.seeded(3L), epochs = 5)
        val samples = List(3) {
            DistortionSample(FloatArray(DistortionMlp.IN), BooleanArray(DistortionMlp.OUT2))
        }
        val result = trainer.trainBatch(samples, shouldContinue = { false })
        assertEquals("Returning false immediately should yield 0f loss", 0f, result, 1e-9f)
    }

    // ── hyperparameter wiring ─────────────────────────────────────────────────

    @Test
    fun `higher learning rate converges faster than lower on same task`() {
        val embedding = FloatArray(DistortionMlp.IN) { 0.1f }
        val labels = BooleanArray(DistortionMlp.OUT2) { it < 4 }

        fun trainAndMeasureFinalLoss(lr: Float): Float {
            val trainer = DistortionMlpTrainer(DistortionMlp.seeded(0L), lr = lr)
            var loss = 0f
            repeat(50) { loss = trainer.trainStep(embedding, labels) }
            return loss
        }

        val lossHighLr = trainAndMeasureFinalLoss(1e-2f)
        val lossLowLr  = trainAndMeasureFinalLoss(1e-5f)
        assertTrue(
            "Higher lr should converge faster: highLr=$lossHighLr, lowLr=$lossLowLr",
            lossHighLr < lossLowLr,
        )
    }

    @Test
    fun `trainer exposes configurable epochs`() {
        assertEquals(7, DistortionMlpTrainer(DistortionMlp(), epochs = 7).epochs)
    }

    // ── weight decay ─────────────────────────────────────────────────────────

    @Test
    fun `weight magnitudes decrease under weight decay with zero-gradient step`() {
        // Zero embedding + zero b1 guarantees h1 = relu(W1 @ 0 + 0) = 0.
        // With h1 = 0: gW2 = dz2 * h1 = 0; dz1 = dh1 * relu'(z1=0) = 0; gW1 = dz1 * 0 = 0.
        // AdamW then applies pure weight decay to W1 and W2, shrinking them.
        val mlp = DistortionMlp(
            w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN) { 0.1f },
            b1 = FloatArray(DistortionMlp.OUT1),                              // zero → h1 = 0
            w2 = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1) { 0.1f },
            b2 = FloatArray(DistortionMlp.OUT2),
        )
        val sumBefore = mlp.w1.sumOf { kotlin.math.abs(it.toDouble()) }

        DistortionMlpTrainer(mlp, lr = 1e-2f, weightDecay = 1e-1f).trainStep(
            FloatArray(DistortionMlp.IN),           // zero embedding
            BooleanArray(DistortionMlp.OUT2),       // all-false labels
        )

        val sumAfter = mlp.w1.sumOf { kotlin.math.abs(it.toDouble()) }
        assertTrue(
            "w1 magnitudes must shrink due to weight decay: before=$sumBefore, after=$sumAfter",
            sumAfter < sumBefore,
        )
    }

    // ── DistortionSample equality ─────────────────────────────────────────────

    @Test
    fun `DistortionSample equals is value-based`() {
        val emb = FloatArray(DistortionMlp.IN) { it * 0.001f }
        val lbl = BooleanArray(DistortionMlp.OUT2) { it % 2 == 0 }
        val a = DistortionSample(emb.copyOf(), lbl.copyOf())
        val b = DistortionSample(emb.copyOf(), lbl.copyOf())
        assertEquals("Two samples with identical values must be equal", a, b)
    }

    @Test
    fun `DistortionSample hashCode consistent with equals`() {
        val emb = FloatArray(DistortionMlp.IN) { it * 0.001f }
        val lbl = BooleanArray(DistortionMlp.OUT2) { it % 3 == 0 }
        val a = DistortionSample(emb.copyOf(), lbl.copyOf())
        val b = DistortionSample(emb.copyOf(), lbl.copyOf())
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `DistortionSample with different labels are not equal`() {
        val emb = FloatArray(DistortionMlp.IN) { 0.1f }
        val a = DistortionSample(emb.copyOf(), BooleanArray(DistortionMlp.OUT2) { true })
        val b = DistortionSample(emb.copyOf(), BooleanArray(DistortionMlp.OUT2) { false })
        assertFalse("Samples with different labels must not be equal", a == b)
    }
}
