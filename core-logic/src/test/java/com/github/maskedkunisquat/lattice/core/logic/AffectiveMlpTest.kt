package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.sqrt

class AffectiveMlpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── forward() ─────────────────────────────────────────────────────────────

    @Test
    fun `forward output is within tanh range`() {
        val mlp = AffectiveMlp()
        val embedding = FloatArray(AffectiveMlp.IN) { 0.1f }

        val (v, a) = mlp.forward(embedding)

        assertTrue("valence must be in [-1, 1], got $v", v in -1f..1f)
        assertTrue("arousal must be in [-1, 1], got $a", a in -1f..1f)
    }

    @Test
    fun `forward is deterministic for the same weights and input`() {
        val mlp = AffectiveMlp()
        val embedding = FloatArray(AffectiveMlp.IN) { it * 0.001f }

        val (v1, a1) = mlp.forward(embedding)
        val (v2, a2) = mlp.forward(embedding)

        assertEquals("valence must be deterministic", v1, v2)
        assertEquals("arousal must be deterministic", a1, a2)
    }

    @Test
    fun `forward with all-zero weights and biases returns (0, 0)`() {
        val mlp = AffectiveMlp(
            w1 = FloatArray(AffectiveMlp.OUT1 * AffectiveMlp.IN),
            b1 = FloatArray(AffectiveMlp.OUT1),
            w2 = FloatArray(AffectiveMlp.OUT2 * AffectiveMlp.OUT1),
            b2 = FloatArray(AffectiveMlp.OUT2),
        )
        val (v, a) = mlp.forward(FloatArray(AffectiveMlp.IN) { 1f })

        assertEquals("valence with zero weights must be tanh(0)=0", 0f, v, 1e-6f)
        assertEquals("arousal with zero weights must be tanh(0)=0", 0f, a, 1e-6f)
    }

    @Test
    fun `forward with known weights produces expected output`() {
        // Minimal 1-neuron-per-layer sanity check using hand-computable values.
        // w1 row 0 = [1, 0, 0, ...], b1[0] = 0 → hidden[0] = relu(1*1 + 0) = 1
        // w2 row 0 = [1, 0, ...], b2[0] = 0 → out[0] = tanh(1*1) ≈ 0.7616
        val w1 = FloatArray(AffectiveMlp.OUT1 * AffectiveMlp.IN)
        w1[0] = 1f  // w1[0][0] = 1
        val w2 = FloatArray(AffectiveMlp.OUT2 * AffectiveMlp.OUT1)
        w2[0] = 1f  // w2[0][0] = 1

        val mlp = AffectiveMlp(w1 = w1, b1 = FloatArray(AffectiveMlp.OUT1),
                               w2 = w2, b2 = FloatArray(AffectiveMlp.OUT2))
        val embedding = FloatArray(AffectiveMlp.IN).also { it[0] = 1f }

        val (v, _) = mlp.forward(embedding)

        val expected = Math.tanh(1.0).toFloat()
        assertEquals("valence for unit-weight path must be tanh(1)", expected, v, 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forward rejects wrong embedding size`() {
        AffectiveMlp().forward(FloatArray(128))
    }

    // ── linear helper ─────────────────────────────────────────────────────────

    @Test
    fun `linear computes correct dot product`() {
        // W = [[1, 2], [3, 4]], b = [0, 0], x = [1, 1] → [3, 7]
        val w = floatArrayOf(1f, 2f, 3f, 4f)
        val b = floatArrayOf(0f, 0f)
        val x = floatArrayOf(1f, 1f)
        val out = AffectiveMlp.linear(w, b, x, rows = 2, cols = 2)

        assertEquals(3f, out[0], 1e-6f)
        assertEquals(7f, out[1], 1e-6f)
    }

    // ── save / load round-trip ────────────────────────────────────────────────

    @Test
    fun `saveWeights and loadWeights round-trip preserves all weights exactly`() {
        val original = AffectiveMlp()
        val file = tmp.newFile("head.bin")

        original.saveWeights(file)

        assertEquals("weight file must be exactly WEIGHT_BYTES",
            AffectiveMlp.WEIGHT_BYTES.toLong(), file.length())

        val loaded = AffectiveMlp.loadWeights(file)

        assertFloatArrayEquals("w1 must survive round-trip", original.w1, loaded.w1)
        assertFloatArrayEquals("b1 must survive round-trip", original.b1, loaded.b1)
        assertFloatArrayEquals("w2 must survive round-trip", original.w2, loaded.w2)
        assertFloatArrayEquals("b2 must survive round-trip", original.b2, loaded.b2)
    }

    @Test
    fun `loadWeights produces identical forward output to original`() {
        val original = AffectiveMlp()
        val file = tmp.newFile("head.bin")
        original.saveWeights(file)

        val loaded = AffectiveMlp.loadWeights(file)
        val embedding = FloatArray(AffectiveMlp.IN) { it * 0.001f }

        val (v1, a1) = original.forward(embedding)
        val (v2, a2) = loaded.forward(embedding)

        assertEquals("valence must match after load", v1, v2, 1e-6f)
        assertEquals("arousal must match after load", a1, a2, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `loadWeights rejects file with wrong size`() {
        val file = tmp.newFile("bad.bin")
        file.writeBytes(ByteArray(42))
        AffectiveMlp.loadWeights(file)
    }

    // ── weight shape validation ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects wrong w1 size`() {
        AffectiveMlp(w1 = FloatArray(10))
    }

    // ── convergence (2.7-j) ──────────────────────────────────────────────────

    @Test
    fun `forward output converges toward target after trainBatch`() {
        val mlp = AffectiveMlp()
        val targetV = 0.7f
        val targetA = -0.3f
        val samples = List(5) {
            TrainingSample(
                embedding = FloatArray(AffectiveMlp.IN) { it * 0.001f },
                targetValence = targetV,
                targetArousal = targetA,
            )
        }

        val (v0, a0) = mlp.forward(samples[0].embedding)
        val distBefore = sqrt((v0 - targetV) * (v0 - targetV) + (a0 - targetA) * (a0 - targetA))

        AffectiveMlpTrainer(mlp, lr = 1e-3f, epochs = 20).trainBatch(samples)

        val (v1, a1) = mlp.forward(samples[0].embedding)
        val distAfter = sqrt((v1 - targetV) * (v1 - targetV) + (a1 - targetA) * (a1 - targetA))

        assertTrue(
            "forward() output must be closer to target after training: before=$distBefore, after=$distAfter",
            distAfter < distBefore,
        )
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private fun assertFloatArrayEquals(msg: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$msg — size", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$msg — index $i", expected[i], actual[i], 0f)
        }
    }
}
