package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies Task 6.4 acceptance criterion:
 * "Toggle cloud → orchestrator routes accordingly"
 *
 * SettingsRepository requires Android DataStore (not available in JVM unit tests).
 * LatticeApplication wires it as:
 *   cloudEnabled = { settingsRepository.settings.first().cloudEnabled }
 *
 * These tests exercise the same contract via a mutable lambda — the integration
 * point is the `cloudEnabled: suspend () -> Boolean` parameter, not the DataStore itself.
 */
class SettingsOrchestratorIntegrationTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeProvider(
        override val id: String,
        private val available: Boolean,
    ) : LlmProvider {
        var callCount = 0
        override suspend fun isAvailable() = available
        override fun process(prompt: String): Flow<LlmResult> {
            callCount++
            return listOf(LlmResult.Complete).asFlow()
        }
    }

    private class FakeTransitEventDao : TransitEventDao {
        private val _events = MutableStateFlow<List<TransitEvent>>(emptyList())
        val events: List<TransitEvent> get() = _events.value
        override suspend fun insertEvent(event: TransitEvent) { _events.update { it + event } }
        override suspend fun getAllEvents(): List<TransitEvent> = _events.value
        override fun getEventsFlow(): Flow<List<TransitEvent>> = _events
        override suspend fun deleteEventsForEntry(entryId: String) = Unit
    }

    /**
     * Builds an orchestrator where nano and local are unavailable, so cloud is the
     * only viable route. [cloudEnabledFlag] is the mutable setting state.
     */
    private fun buildOrchestrator(
        dao: FakeTransitEventDao,
        cloudProvider: FakeProvider,
        cloudEnabledFlag: () -> Boolean,
    ): LlmOrchestrator {
        val nano = FakeProvider("gemini_nano", available = false)
        val local = FakeProvider("llama3_onnx_local", available = false)
        return LlmOrchestrator(
            nanoProvider = nano,
            localFallbackProvider = local,
            cloudProvider = cloudProvider,
            transitEventDao = dao,
            cloudEnabled = { cloudEnabledFlag() },
            piiDetector = { false },
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `cloud routing is disabled by default — no transit event logged`() = runTest {
        val dao = FakeTransitEventDao()
        val cloud = FakeProvider("cloud_claude", available = true)
        val orch = buildOrchestrator(dao, cloud, cloudEnabledFlag = { false })

        orch.process("masked prompt").toList()

        assertEquals("Cloud must not be called when disabled", 0, cloud.callCount)
        assertEquals("No transit event when cloud disabled", 0, dao.events.size)
        assertEquals(PrivacyLevel.LocalOnly, orch.privacyState.value)
    }

    @Test
    fun `enabling cloud routes requests to cloud provider`() = runTest {
        val dao = FakeTransitEventDao()
        val cloud = FakeProvider("cloud_claude", available = true)
        val orch = buildOrchestrator(dao, cloud, cloudEnabledFlag = { true })

        orch.process("masked prompt").toList()

        assertEquals("Cloud must be called when enabled", 1, cloud.callCount)
        assertEquals("Transit event must be logged", 1, dao.events.size)
        val state = orch.privacyState.value
        assertTrue("State must be CloudTransit", state is PrivacyLevel.CloudTransit)
        assertEquals("cloud_claude", (state as PrivacyLevel.CloudTransit).providerName)
    }

    @Test
    fun `toggling cloud off stops routing to cloud`() = runTest {
        val dao = FakeTransitEventDao()
        val cloud = FakeProvider("cloud_claude", available = true)
        var cloudEnabled = true
        val orch = buildOrchestrator(dao, cloud, cloudEnabledFlag = { cloudEnabled })

        // First request — cloud enabled
        orch.process("masked prompt").toList()
        assertEquals(1, cloud.callCount)

        // Simulate user disabling cloud in Settings
        cloudEnabled = false

        // Second request — cloud now disabled, falls back to local (unavailable → still calls local)
        orch.process("masked prompt").toList()

        assertEquals("Cloud must not receive second request", 1, cloud.callCount)
        assertEquals(PrivacyLevel.LocalOnly, orch.privacyState.value)
    }

    @Test
    fun `re-enabling cloud after disable resumes cloud routing`() = runTest {
        val dao = FakeTransitEventDao()
        val cloud = FakeProvider("cloud_claude", available = true)
        var cloudEnabled = false
        val orch = buildOrchestrator(dao, cloud, cloudEnabledFlag = { cloudEnabled })

        orch.process("masked prompt").toList()
        assertEquals(0, cloud.callCount)

        cloudEnabled = true
        orch.process("masked prompt").toList()

        assertEquals("Cloud must be used after re-enable", 1, cloud.callCount)
        assertEquals(1, dao.events.size)
    }
}
