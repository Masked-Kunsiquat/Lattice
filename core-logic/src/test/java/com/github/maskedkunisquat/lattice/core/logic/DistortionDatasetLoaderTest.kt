package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class DistortionDatasetLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── parseJsonlRow ─────────────────────────────────────────────────────────

    @Test
    fun `parseJsonlRow parses text and labels correctly`() {
        val labels = BooleanArray(12).also { it[3] = true }   // DISQUALIFYING_POSITIVE
        val json = buildJsonlRow("hello world", labels)

        val (text, parsed) = DistortionDatasetLoader.parseJsonlRow(json)

        assertEquals("hello world", text)
        assertTrue(parsed.contentEquals(labels))
    }

    @Test
    fun `parseJsonlRow handles all-false label vector`() {
        val json = buildJsonlRow("no distortion here", BooleanArray(12))
        val (_, labels) = DistortionDatasetLoader.parseJsonlRow(json)
        assertTrue(labels.none { it })
    }

    @Test
    fun `parseJsonlRow handles multi-label row`() {
        val expected = BooleanArray(12).also { it[7] = true; it[9] = true }
        val (_, labels) = DistortionDatasetLoader.parseJsonlRow(buildJsonlRow("text", expected))
        assertTrue(labels.contentEquals(expected))
    }

    @Test
    fun `parseJsonlRow throws on wrong label count`() {
        val json = """{"text": "x", "labels": [false, true]}"""
        assertThrows(IllegalArgumentException::class.java) {
            DistortionDatasetLoader.parseJsonlRow(json)
        }
    }

    // ── serialize / deserialize round-trip ────────────────────────────────────

    @Test
    fun `serialize and deserialize round-trip preserves all samples`() {
        val samples = listOf(
            makeSample(floatValue = 0.1f, labelBit = 3),
            makeSample(floatValue = 0.5f, labelBit = 11),
            makeSample(floatValue = 0.9f, labelBit = null),
        )
        val file = tmp.newFile("dataset.bin")

        DistortionDatasetLoader.serialize(samples, file)
        val loaded = file.inputStream().use { DistortionDatasetLoader.deserialize(it) }

        assertEquals(samples.size, loaded.size)
        for (i in samples.indices) {
            assertEquals("sample $i embedding", samples[i], loaded[i])
        }
    }

    @Test
    fun `serialize produces correct file size`() {
        val n   = 10
        val dim = EmbeddingProvider.EMBEDDING_DIM
        val nc  = CognitiveDistortion.entries.size
        val expected = 3 * Int.SIZE_BYTES + n.toLong() * (dim * Float.SIZE_BYTES + nc)

        val file = tmp.newFile("dataset.bin")
        DistortionDatasetLoader.serialize(List(n) { makeSample(0f, null) }, file)

        assertEquals(expected, file.length())
    }

    @Test
    fun `deserialize throws on wrong dim`() {
        val badBin = buildBinary(count = 1, dim = 128, numClasses = 12, rowFloats = 128, rowBytes = 12)
        assertThrows(IllegalArgumentException::class.java) {
            DistortionDatasetLoader.deserialize(ByteArrayInputStream(badBin))
        }
    }

    @Test
    fun `deserialize throws on wrong numClasses`() {
        val badBin = buildBinary(count = 1, dim = 384, numClasses = 10, rowFloats = 384, rowBytes = 10)
        assertThrows(IllegalArgumentException::class.java) {
            DistortionDatasetLoader.deserialize(ByteArrayInputStream(badBin))
        }
    }

    @Test
    fun `deserialize throws on truncated stream`() {
        val full = buildBinary(count = 5, dim = 384, numClasses = 12, rowFloats = 384, rowBytes = 12)
        val truncated = full.copyOf(full.size / 2)
        assertThrows(IllegalArgumentException::class.java) {
            DistortionDatasetLoader.deserialize(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `deserialize returns empty list for count=0`() {
        val empty = buildBinary(count = 0, dim = 384, numClasses = 12, rowFloats = 384, rowBytes = 12)
        val result = DistortionDatasetLoader.deserialize(ByteArrayInputStream(empty))
        assertTrue(result.isEmpty())
    }

    // ── DistortionSample equality ─────────────────────────────────────────────

    @Test
    fun `DistortionSample equals by value not reference`() {
        val a = makeSample(0.5f, 3)
        val b = makeSample(0.5f, 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `DistortionSample rejects wrong embedding size`() {
        assertThrows(IllegalArgumentException::class.java) {
            DistortionSample(FloatArray(128), BooleanArray(12))
        }
    }

    @Test
    fun `DistortionSample rejects wrong label count`() {
        assertThrows(IllegalArgumentException::class.java) {
            DistortionSample(FloatArray(384), BooleanArray(5))
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeSample(floatValue: Float, labelBit: Int?): DistortionSample {
        val emb = FloatArray(EmbeddingProvider.EMBEDDING_DIM) { floatValue }
        val lbl = BooleanArray(CognitiveDistortion.entries.size).also {
            if (labelBit != null) it[labelBit] = true
        }
        return DistortionSample(emb, lbl)
    }

    private fun buildJsonlRow(text: String, labels: BooleanArray): String {
        val labelsJson = labels.joinToString(",") { if (it) "true" else "false" }
        return """{"text": "$text", "labels": [$labelsJson]}"""
    }

    private fun buildBinary(
        count: Int,
        dim: Int,
        numClasses: Int,
        rowFloats: Int,
        rowBytes: Int,
    ): ByteArray {
        val headerSize = 3 * Int.SIZE_BYTES
        val rowSize    = rowFloats * Float.SIZE_BYTES + rowBytes
        val buf = java.nio.ByteBuffer
            .allocate(headerSize + count * rowSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(count)
        buf.putInt(dim)
        buf.putInt(numClasses)
        repeat(count * (rowFloats + rowBytes)) { buf.put(0) }
        return buf.array()
    }
}
