package com.github.maskedkunisquat.lattice.core.logic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class EmbeddingProviderTest {

    /**
     * Verifies that [EmbeddingProvider.generateEmbedding] dispatches work through its
     * [CoroutineDispatcher] rather than running inline on the caller's thread.
     * In production the default dispatcher is [kotlinx.coroutines.Dispatchers.Default],
     * which uses background worker threads — this test confirms the indirection is wired up.
     */
    @Test
    fun `generateEmbedding dispatches to its CoroutineDispatcher`() = runTest {
        var dispatchInvoked = false

        val trackingDispatcher = object : CoroutineDispatcher() {
            val delegate = StandardTestDispatcher(testScheduler)
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatchInvoked = true
                delegate.dispatch(context, block)
            }
        }

        val provider = EmbeddingProvider(dispatcher = trackingDispatcher)
        provider.generateEmbedding("test input — no model loaded, expects zero-vector")
        testScheduler.advanceUntilIdle()

        assertTrue(
            "generateEmbedding must use withContext(dispatcher); dispatch was never called",
            dispatchInvoked
        )
    }

    /**
     * Verifies the zero-vector fallback dimensions when no model is initialized.
     */
    @Test
    fun `generateEmbedding returns 384-dim vector when model is absent`() = runTest {
        val provider = EmbeddingProvider(dispatcher = UnconfinedTestDispatcher(testScheduler))
        val result = provider.generateEmbedding("some masked text [PERSON_abc]")

        assertEquals(EmbeddingProvider.EMBEDDING_DIM, result.size)
    }
}
