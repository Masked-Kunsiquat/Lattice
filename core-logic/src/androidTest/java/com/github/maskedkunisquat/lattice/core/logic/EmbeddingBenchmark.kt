package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Base64
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class EmbeddingBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Masked text — never raw PII in the embedding pipeline.
    private val testText =
        "I feel overwhelmed by everything today and cannot seem to focus on anything."

    /**
     * Cold session latency: a new [EmbeddingProvider] (and therefore a new TFLite [Interpreter]) is
     * created on every iteration. Measures the combined cost of model load + first inference.
     */
    @Test
    fun cold_generateEmbedding() {
        benchmarkRule.measureRepeated {
            val provider = EmbeddingProvider()
            provider.initialize(context)
            runBlocking { provider.generateEmbedding(testText) }
        }
    }

    /**
     * Warm session latency: the session stays open across all 50 calls per iteration.
     * Measures steady-state inference throughput — model and tokenizer are already loaded.
     */
    @Test
    fun warm_generateEmbedding() {
        val provider = EmbeddingProvider()
        provider.initialize(context)
        benchmarkRule.measureRepeated {
            repeat(50) {
                runBlocking { provider.generateEmbedding(testText) }
            }
        }
    }

    /**
     * Functional guard: output must be 384-dimensional and must not be a zero-vector for
     * any non-empty input. A zero-vector indicates the model failed to initialize.
     */
    @Test
    fun embedding_outputIsValid() {
        val provider = EmbeddingProvider()
        provider.initialize(context)
        val embedding = runBlocking { provider.generateEmbedding(testText) }
        assertEquals("Expected 384-dim output", EmbeddingProvider.EMBEDDING_DIM, embedding.size)
        assertFalse(
            "Embedding must not be a zero-vector for non-empty input — " +
                "check that the TFLite model asset is present",
            embedding.all { it == 0f }
        )
    }

    /**
     * Cosine similarity check: ONNX output must agree with the pre-computed seed
     * embeddings to ≥ 0.99.
     *
     * Also serves as the correctness gate for any future TFLite migration (Track B):
     * re-enable with the TFLite provider once a valid conversion is available.
     *
     * Reads the first 5 entries with both content and embeddingBase64 from each persona.
     */
    @Test
    fun tflite_cosineSimilarity_vs_onnxSeedEmbeddings() {
        val provider = EmbeddingProvider()
        provider.initialize(context)

        val personas = listOf("seeds/holmes.json", "seeds/watson.json", "seeds/werther.json")
        var checked = 0

        for (assetPath in personas) {
            val root = JSONObject(context.assets.open(assetPath).bufferedReader().use { it.readText() })
            val entries = root.getJSONArray("journalEntries")
            var perPersona = 0
            for (i in 0 until entries.length()) {
                if (perPersona >= 5) break
                val entry = entries.getJSONObject(i)
                val content = entry.optString("content").takeIf { it.isNotBlank() } ?: continue
                val b64 = entry.optString("embeddingBase64").takeIf { it.isNotBlank() } ?: continue

                val reference = decodeEmbedding(b64)
                val tflite = runBlocking { provider.generateEmbedding(content) }

                val sim = cosineSimilarity(reference, tflite)
                assertTrue(
                    "Cosine similarity $sim < 0.99 for entry in $assetPath (index $i). " +
                    "This suggests a bad TFLite conversion — check tensor index order and input dtype.",
                    sim >= 0.99f
                )
                perPersona++
                checked++
            }
        }

        assertTrue("No seed entries were checked — seeds may be missing from test assets", checked > 0)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Decodes a Base64 IEEE 754 little-endian float32 blob into a FloatArray. */
    private fun decodeEmbedding(base64: String): FloatArray {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
