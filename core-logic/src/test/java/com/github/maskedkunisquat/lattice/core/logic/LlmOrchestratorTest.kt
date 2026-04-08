package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmOrchestratorTest {

    // --- Fakes ---

    private class FakeProvider(
        override val id: String,
        private val available: Boolean,
        private val results: List<LlmResult> = listOf(LlmResult.Complete)
    ) : LlmProvider {
        var processCallCount = 0
        override suspend fun isAvailable() = available
        override fun process(prompt: String): Flow<LlmResult> {
            processCallCount++
            return results.asFlow()
        }
    }

    private class FakeTransitEventDao : TransitEventDao {
        val insertedEvents = mutableListOf<TransitEvent>()
        override suspend fun insertEvent(event: TransitEvent) { insertedEvents.add(event) }
        override suspend fun getAllEvents() = insertedEvents.toList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(insertedEvents.toList())
    }

    private fun orchestrator(
        nanoAvailable: Boolean = false,
        localAvailable: Boolean = false,
        cloudEnabled: Boolean = false,
        dao: TransitEventDao = FakeTransitEventDao()
    ): Triple<LlmOrchestrator, FakeProvider, FakeProvider> {
        val nano = FakeProvider("gemini_nano", nanoAvailable)
        val local = FakeProvider("qwen_onnx_local", localAvailable, listOf(LlmResult.Complete))
        val cloud = FakeProvider("cloud_claude", true, listOf(LlmResult.Complete))
        val orch = LlmOrchestrator(nano, local, cloud, dao, cloudEnabled)
        return Triple(orch, nano, local)
    }

    // --- Tests ---

    @Test
    fun `routes to nano when it is available`() = runTest {
        val (orch, nano, _) = orchestrator(nanoAvailable = true)
        orch.process("test prompt").toList()
        assertEquals(1, nano.processCallCount)
    }

    @Test
    fun `falls back to local when nano is unavailable`() = runTest {
        val (orch, nano, local) = orchestrator(nanoAvailable = false, localAvailable = true)
        orch.process("test prompt").toList()
        assertEquals(0, nano.processCallCount)
        assertEquals(1, local.processCallCount)
    }

    @Test
    fun `falls back to local when nothing is available and cloud is disabled`() = runTest {
        val (orch, _, local) = orchestrator(nanoAvailable = false, localAvailable = false, cloudEnabled = false)
        orch.process("test prompt").toList()
        // localFallback is used even when unavailable — it emits LlmResult.Error explaining the situation
        assertEquals(1, local.processCallCount)
    }

    @Test
    fun `sets state to LocalOnly when local provider is used`() = runTest {
        val (orch, _, _) = orchestrator(localAvailable = true)
        orch.process("test prompt").toList()
        assertEquals(PrivacyLevel.LocalOnly, orch.privacyState.value)
    }

    @Test
    fun `flips state to CloudTransit when cloud provider is invoked`() = runTest {
        val dao = FakeTransitEventDao()
        val nano = FakeProvider("gemini_nano", false)
        val local = FakeProvider("qwen_onnx_local", false)
        val cloud = FakeProvider("cloud_claude", true, listOf(LlmResult.Complete))
        val orch = LlmOrchestrator(nano, local, cloud, dao, cloudEnabled = true)

        orch.process("masked prompt").toList()

        val state = orch.privacyState.value
        assertTrue("Expected CloudTransit, got $state", state is PrivacyLevel.CloudTransit)
        assertEquals("cloud_claude", (state as PrivacyLevel.CloudTransit).providerName)
        assertTrue("sinceTimestamp must be positive", state.sinceTimestamp > 0L)
    }

    @Test
    fun `logs transit event to DAO when cloud is used`() = runTest {
        val dao = FakeTransitEventDao()
        val nano = FakeProvider("gemini_nano", false)
        val local = FakeProvider("qwen_onnx_local", false)
        val cloud = FakeProvider("cloud_claude", true, listOf(LlmResult.Complete))
        val orch = LlmOrchestrator(nano, local, cloud, dao, cloudEnabled = true)

        orch.process("masked prompt", operationType = "reframe").toList()

        assertEquals(1, dao.insertedEvents.size)
        val event = dao.insertedEvents[0]
        assertNotNull(event.id)
        assertEquals("cloud_claude", event.providerName)
        assertEquals("reframe", event.operationType)
        assertTrue(event.timestamp > 0L)
    }

    @Test
    fun `does not log transit event when local provider is used`() = runTest {
        val dao = FakeTransitEventDao()
        val (orch, _, _) = orchestrator(localAvailable = true, dao = dao)
        orch.process("test prompt").toList()
        assertEquals(0, dao.insertedEvents.size)
    }

    @Test
    fun `cloud dispatch is blocked and error emitted when raw PII is detected`() = runTest {
        val dao = FakeTransitEventDao()
        val nano = FakeProvider("gemini_nano", false)
        val local = FakeProvider("qwen_onnx_local", false)
        val cloud = FakeProvider("cloud_claude", true, listOf(LlmResult.Complete))
        // Detector: simulates a prompt that still contains a raw name (no [PERSON_uuid] placeholder)
        val orch = LlmOrchestrator(nano, local, cloud, dao, cloudEnabled = true,
            piiDetector = { prompt -> "John" in prompt })

        val results = orch.process("John did something today").toList()

        assertTrue("Expected an error result", results.any { it is LlmResult.Error })
        assertEquals("Cloud must not be called when PII detected", 0, cloud.processCallCount)
        assertEquals("No transit event should be logged", 0, dao.insertedEvents.size)
        // State should NOT have flipped to CloudTransit
        assertEquals(PrivacyLevel.LocalOnly, orch.privacyState.value)
    }

    @Test
    fun `cloud dispatch proceeds when prompt contains only PII placeholders`() = runTest {
        val dao = FakeTransitEventDao()
        val nano = FakeProvider("gemini_nano", false)
        val local = FakeProvider("qwen_onnx_local", false)
        val cloud = FakeProvider("cloud_claude", true, listOf(LlmResult.Complete))
        // Detector: raw name absent — prompt is already masked
        val orch = LlmOrchestrator(nano, local, cloud, dao, cloudEnabled = true,
            piiDetector = { prompt -> "John" in prompt })

        val results = orch.process("[PERSON_abc123] did something today").toList()

        assertTrue("Expected complete result", results.any { it is LlmResult.Complete })
        assertEquals("Cloud should be called for masked prompt", 1, cloud.processCallCount)
    }

    @Test
    fun `cloud is not invoked when cloudEnabled is false even if local unavailable`() = runTest {
        val dao = FakeTransitEventDao()
        val (orch, _, _) = orchestrator(
            nanoAvailable = false,
            localAvailable = false,
            cloudEnabled = false,
            dao = dao
        )
        orch.process("test").toList()
        assertEquals(0, dao.insertedEvents.size)
        assertEquals(PrivacyLevel.LocalOnly, orch.privacyState.value)
    }
}
