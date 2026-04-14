package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AffectiveMlpInitializerTest {

    private val initializer = AffectiveMlpInitializer()

    // ── loadSamples — format parsing ──────────────────────────────────────────

    @Test
    fun `loadSamples returns correct count`() {
        val samples = initializer.loadSamples(fakeAsset(count = 3))
        assertEquals(3, samples.size)
    }

    @Test
    fun `loadSamples preserves embedding values`() {
        val expected = FloatArray(AffectiveMlp.IN) { it * 0.001f }
        val samples = initializer.loadSamples(fakeAsset(count = 1, embeddingFor = { expected }))
        for (i in expected.indices) {
            assertEquals("embedding[$i]", expected[i], samples[0].embedding[i], 1e-6f)
        }
    }

    @Test
    fun `loadSamples preserves valence and arousal`() {
        val samples = initializer.loadSamples(
            fakeAsset(count = 1, valence = 0.72f, arousal = -0.28f)
        )
        assertEquals(0.72f,  samples[0].targetValence, 1e-6f)
        assertEquals(-0.28f, samples[0].targetArousal,  1e-6f)
    }

    @Test
    fun `loadSamples handles multiple rows independently`() {
        val valences = floatArrayOf(-0.80f, 0.90f, -0.48f)
        val arousals = floatArrayOf(-0.40f, 0.60f,  0.70f)
        val samples = initializer.loadSamples(
            fakeAsset(count = 3, valences = valences, arousals = arousals)
        )
        for (i in 0..2) {
            assertEquals("valence[$i]", valences[i], samples[i].targetValence, 1e-6f)
            assertEquals("arousal[$i]", arousals[i], samples[i].targetArousal, 1e-6f)
        }
    }

    @Test
    fun `loadSamples returns list with correct embedding size per sample`() {
        val samples = initializer.loadSamples(fakeAsset(count = 5))
        for (s in samples) {
            assertEquals(AffectiveMlp.IN, s.embedding.size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `loadSamples rejects asset with wrong embedding dimension`() {
        initializer.loadSamples(fakeAsset(count = 1, dim = 128))
    }

    // 2.7-b: zero-count asset returns empty list (confirms the early-return path is reachable;
    // the guard-flag side-effect requires an Android Context and is covered by instrumented tests)
    @Test
    fun `loadSamples returns empty list when count is zero`() {
        val samples = initializer.loadSamples(fakeAsset(count = 0))
        assertEquals(0, samples.size)
    }

    // 2.7-c: header claims more rows than the payload contains → clear IllegalArgumentException
    @Test(expected = IllegalArgumentException::class)
    fun `loadSamples rejects truncated asset whose header count exceeds available bytes`() {
        // Build correct bytes for 3 rows, then replace the count header with 5
        val realBytes  = fakeAsset(count = 3).readBytes()
        val spoofHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(5).putInt(AffectiveMlp.IN).array()          // lie: claim count=5
        val truncated = spoofHeader + realBytes.drop(8).toByteArray()
        initializer.loadSamples(ByteArrayInputStream(truncated))
    }

    // ── loadSamples → trainBatch integration ──────────────────────────────────

    @Test
    fun `samples from loadSamples are accepted by trainBatch without error`() {
        val samples = initializer.loadSamples(fakeAsset(count = 10))
        val trainer = AffectiveMlpTrainer(AffectiveMlp(), epochs = 1)
        val loss = trainer.trainBatch(samples)
        assertTrue("trainBatch loss must be finite", loss.isFinite())
        assertTrue("trainBatch loss must be >= 0",   loss >= 0f)
    }

    @Test
    fun `training on loaded samples reduces loss`() {
        val samples = initializer.loadSamples(fakeAsset(count = 20, valence = 0.8f, arousal = 0.4f))
        val mlp = AffectiveMlp()

        val (vBefore, aBefore) = mlp.forward(samples[0].embedding)
        val lossBefore = 0.5f * ((vBefore - 0.8f).let { it * it } + (aBefore - 0.4f).let { it * it })

        AffectiveMlpTrainer(mlp, lr = 1e-3f, epochs = AffectiveMlpInitializer.EPOCHS)
            .trainBatch(samples)

        val (vAfter, aAfter) = mlp.forward(samples[0].embedding)
        val lossAfter = 0.5f * ((vAfter - 0.8f).let { it * it } + (aAfter - 0.4f).let { it * it })

        assertTrue(
            "Loss must decrease after warm-start training: before=$lossBefore, after=$lossAfter",
            lossAfter < lossBefore,
        )
    }

    // ── constants ─────────────────────────────────────────────────────────────

    @Test
    fun `EPOCHS constant is 5`() {
        assertEquals(5, AffectiveMlpInitializer.EPOCHS)
    }

    @Test
    fun `WEIGHT_FILE constant is correct`() {
        assertEquals("affective_head_v1.bin", AffectiveMlpInitializer.WEIGHT_FILE)
    }

    @Test
    fun `PREF_KEY constant is correct`() {
        assertEquals("affective_head_initialized", AffectiveMlpInitializer.PREF_KEY)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal binary asset matching `goEmotions_base_v1.bin` format.
     *
     * @param count        Number of rows to write.
     * @param dim          Embedding dimension written in the header (default: [AffectiveMlp.IN]).
     * @param valence      Valence value used for all rows (unless [valences] overrides).
     * @param arousal      Arousal value used for all rows (unless [arousals] overrides).
     * @param valences     Per-row valence values; overrides [valence] when provided.
     * @param arousals     Per-row arousal values; overrides [arousal] when provided.
     * @param embeddingFor Lambda returning the embedding for a given row index.
     */
    private fun fakeAsset(
        count: Int,
        dim: Int = AffectiveMlp.IN,
        valence: Float = 0.0f,
        arousal: Float = 0.0f,
        valences: FloatArray? = null,
        arousals: FloatArray? = null,
        embeddingFor: (Int) -> FloatArray = { FloatArray(dim) { 0.1f } },
    ): ByteArrayInputStream {
        // Header: count (int32) + dim (int32) = 8 bytes
        // Each row: dim floats + 2 floats = (dim + 2) × 4 bytes
        val rowBytes = (dim + 2) * Float.SIZE_BYTES
        val buf = ByteBuffer.allocate(8 + count * rowBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(count)
        buf.putInt(dim)
        for (i in 0 until count) {
            embeddingFor(i).forEach { buf.putFloat(it) }
            buf.putFloat(valences?.get(i) ?: valence)
            buf.putFloat(arousals?.get(i) ?: arousal)
        }
        return ByteArrayInputStream(buf.array())
    }
}
