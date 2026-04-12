package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Masked text — never raw PII in the embedding pipeline.
    private val testText =
        "I feel overwhelmed by everything today and cannot seem to focus on anything."

    /**
     * Cold session latency: a new [EmbeddingProvider] (and therefore a new [OrtSession]) is
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
                "check that the ONNX model asset is present",
            embedding.all { it == 0f }
        )
    }
}
