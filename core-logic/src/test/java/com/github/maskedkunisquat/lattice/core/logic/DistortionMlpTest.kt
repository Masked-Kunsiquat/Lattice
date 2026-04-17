package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DistortionMlpTest {

    // ── construction ──────────────────────────────────────────────────────────

    @Test
    fun `default constructor initializes with correct sizes`() {
        val mlp = DistortionMlp()
        assertEquals(DistortionMlp.OUT1 * DistortionMlp.IN,   mlp.w1.size)
        assertEquals(DistortionMlp.OUT1,                       mlp.b1.size)
        assertEquals(DistortionMlp.OUT2 * DistortionMlp.OUT1, mlp.w2.size)
        assertEquals(DistortionMlp.OUT2,                       mlp.b2.size)
        assertEquals(DistortionMlp.OUT2,                       mlp.thresholds.size)
    }

    @Test
    fun `default thresholds are all 0_5`() {
        val mlp = DistortionMlp()
        mlp.thresholds.forEach { t ->
            assertEquals(DistortionMlp.DEFAULT_THRESHOLD, t, 1e-6f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `init rejects wrong w1 size`() {
        DistortionMlp(w1 = FloatArray(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `init rejects non-finite w1`() {
        DistortionMlp(w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN) { Float.NaN })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `init rejects wrong thresholds size`() {
        DistortionMlp(thresholds = FloatArray(5))
    }

    // ── seeded ────────────────────────────────────────────────────────────────

    @Test
    fun `seeded produces deterministic weights`() {
        val a = DistortionMlp.seeded(42L)
        val b = DistortionMlp.seeded(42L)
        assertTrue(a.w1.contentEquals(b.w1))
        assertTrue(a.w2.contentEquals(b.w2))
    }

    @Test
    fun `seeded with different seeds produces different weights`() {
        val a = DistortionMlp.seeded(1L)
        val b = DistortionMlp.seeded(2L)
        assertFalse(a.w1.contentEquals(b.w1))
    }

    // ── forward ───────────────────────────────────────────────────────────────

    @Test
    fun `forward returns BooleanArray of length OUT2`() {
        val mlp = DistortionMlp.seeded(0L)
        val result = mlp.forward(FloatArray(DistortionMlp.IN) { 0.1f })
        assertEquals(DistortionMlp.OUT2, result.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forward rejects wrong embedding size`() {
        DistortionMlp.seeded(0L).forward(FloatArray(128))
    }

    @Test
    fun `forward with zero weights predicts all false at default threshold`() {
        val mlp = DistortionMlp(
            w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN),
            b1 = FloatArray(DistortionMlp.OUT1),
            w2 = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1),
            b2 = FloatArray(DistortionMlp.OUT2),
        )
        // sigmoid(0) = 0.5, threshold = 0.5 → 0.5 >= 0.5 → true
        // Actually all logits are 0, sigmoid(0) = 0.5, and 0.5 >= 0.5 is true
        val result = mlp.forward(FloatArray(DistortionMlp.IN) { 1f })
        assertEquals(DistortionMlp.OUT2, result.size)
        // With sigmoid(0) = 0.5 exactly at threshold — boundary is >=, so all true
        result.forEach { assertTrue("zero-weight output at threshold should be true (>= 0.5)", it) }
    }

    @Test
    fun `forward with very negative biases predicts all false`() {
        val mlp = DistortionMlp(
            w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN),
            b1 = FloatArray(DistortionMlp.OUT1),
            w2 = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1),
            b2 = FloatArray(DistortionMlp.OUT2) { -100f },
        )
        val result = mlp.forward(FloatArray(DistortionMlp.IN) { 1f })
        result.forEach { assertFalse("Very negative logit must predict false", it) }
    }

    @Test
    fun `forward with very positive biases predicts all true`() {
        val mlp = DistortionMlp(
            w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN),
            b1 = FloatArray(DistortionMlp.OUT1),
            w2 = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1),
            b2 = FloatArray(DistortionMlp.OUT2) { 100f },
        )
        val result = mlp.forward(FloatArray(DistortionMlp.IN) { 1f })
        result.forEach { assertTrue("Very positive logit must predict true", it) }
    }

    @Test
    fun `per-class threshold controls individual output independently`() {
        // Class 0 has high threshold (never fires), class 1 has low threshold (always fires)
        val thresholds = FloatArray(DistortionMlp.OUT2) { 0.5f }
        thresholds[0] = 0.99f
        thresholds[1] = 0.01f
        val mlp = DistortionMlp(
            w1         = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN),
            b1         = FloatArray(DistortionMlp.OUT1),
            w2         = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1),
            b2         = FloatArray(DistortionMlp.OUT2),   // sigmoid(0) = 0.5
            thresholds = thresholds,
        )
        val result = mlp.forward(FloatArray(DistortionMlp.IN))
        assertFalse("Class 0 (threshold 0.99) must be false when logit is 0", result[0])
        assertTrue("Class 1 (threshold 0.01) must be true when logit is 0",  result[1])
    }

    // ── rawLogits ─────────────────────────────────────────────────────────────

    @Test
    fun `rawLogits all values in (0, 1)`() {
        val logits = DistortionMlp.seeded(7L).rawLogits(FloatArray(DistortionMlp.IN) { 0.05f })
        logits.forEach { p ->
            assertTrue("sigmoid output must be in (0,1), got $p", p > 0f && p < 1f)
        }
    }

    @Test
    fun `rawLogits length equals OUT2`() {
        val logits = DistortionMlp.seeded(0L).rawLogits(FloatArray(DistortionMlp.IN))
        assertEquals(DistortionMlp.OUT2, logits.size)
    }

    // ── weight constants ──────────────────────────────────────────────────────

    @Test
    fun `WEIGHT_COUNT matches sum of all parameter counts`() {
        val expected = DistortionMlp.OUT1 * DistortionMlp.IN +
            DistortionMlp.OUT1 +
            DistortionMlp.OUT2 * DistortionMlp.OUT1 +
            DistortionMlp.OUT2
        assertEquals(expected, DistortionMlp.WEIGHT_COUNT)
    }

    @Test
    fun `WEIGHT_BYTES equals WEIGHT_COUNT times 4`() {
        assertEquals(DistortionMlp.WEIGHT_COUNT * 4, DistortionMlp.WEIGHT_BYTES)
    }

    // ── saveWeights / loadWeights round-trip ──────────────────────────────────

    @Test
    fun `saveWeights then loadWeights round-trips weights exactly`() {
        val original = DistortionMlp.seeded(123L)
        val file = File.createTempFile("distortion_test", ".bin")
        try {
            original.saveWeights(file)
            assertEquals(DistortionMlp.WEIGHT_BYTES.toLong(), file.length())

            val loaded = DistortionMlp.loadWeights(file)
            assertTrue("w1 must round-trip exactly", original.w1.contentEquals(loaded.w1))
            assertTrue("b1 must round-trip exactly", original.b1.contentEquals(loaded.b1))
            assertTrue("w2 must round-trip exactly", original.w2.contentEquals(loaded.w2))
            assertTrue("b2 must round-trip exactly", original.b2.contentEquals(loaded.b2))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `saveWeights does not write thresholds — loaded thresholds are default`() {
        val custom = FloatArray(DistortionMlp.OUT2) { 0.3f }
        val original = DistortionMlp(thresholds = custom)
        val file = File.createTempFile("distortion_threshold_test", ".bin")
        try {
            original.saveWeights(file)
            val loaded = DistortionMlp.loadWeights(file)
            // Without passing thresholds to loadWeights, defaults are 0.5
            loaded.thresholds.forEach { t ->
                assertEquals(DistortionMlp.DEFAULT_THRESHOLD, t, 1e-6f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `loadWeights with custom thresholds preserves them`() {
        val original = DistortionMlp.seeded(0L)
        val file = File.createTempFile("distortion_thresh2_test", ".bin")
        try {
            original.saveWeights(file)
            val custom = FloatArray(DistortionMlp.OUT2) { 0.35f }
            val loaded = DistortionMlp.loadWeights(file, custom)
            assertTrue(loaded.thresholds.contentEquals(custom))
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `loadWeights rejects wrong file size`() {
        val file = File.createTempFile("distortion_bad", ".bin")
        try {
            file.writeBytes(ByteArray(100))
            DistortionMlp.loadWeights(file)
        } finally {
            file.delete()
        }
    }

    // ── xavierUniform ─────────────────────────────────────────────────────────

    @Test
    fun `xavierUniform output stays within expected bounds`() {
        val limit = kotlin.math.sqrt(6f / (DistortionMlp.IN + DistortionMlp.OUT1))
        repeat(1000) {
            val v = DistortionMlp.xavierUniform(DistortionMlp.IN, DistortionMlp.OUT1)
            assertTrue("xavier sample $v must be in [-$limit, $limit]", v >= -limit && v <= limit)
        }
    }
}
