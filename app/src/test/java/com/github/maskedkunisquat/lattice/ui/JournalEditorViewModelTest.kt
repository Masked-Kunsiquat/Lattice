package com.github.maskedkunisquat.lattice.ui

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LlmProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class JournalEditorViewModelTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakePersonDao : PersonDao {
        override suspend fun insertPerson(person: Person) = Unit
        override suspend fun updatePerson(person: Person) = Unit
        override suspend fun deletePerson(person: Person) = Unit
        override fun getPersons(): Flow<List<Person>> = flowOf(emptyList())
        override fun getPersonById(id: UUID): Flow<Person?> = flowOf(null)
        override suspend fun incrementVibeScore(personId: UUID, delta: Float) = Unit
    }

    private class FakeJournalDao : JournalDao {
        val updatedReframes = mutableListOf<Pair<String, String>>()
        override suspend fun insertEntry(entry: JournalEntry) = Unit
        override suspend fun updateEntry(entry: JournalEntry) = Unit
        override suspend fun deleteEntry(entry: JournalEntry) = Unit
        override fun getEntries(): Flow<List<JournalEntry>> = flowOf(emptyList())
        override fun getEntryById(id: UUID): Flow<JournalEntry?> = flowOf(null)
        override suspend fun getAllEntries(): List<JournalEntry> = emptyList()
        override suspend fun updateReframedContent(entryId: String, content: String) {
            updatedReframes.add(entryId to content)
        }
        override suspend fun getEntriesWithMinValence(minValence: Float): List<JournalEntry> = emptyList()
    }

    private class FakeTransitEventDao : TransitEventDao {
        override suspend fun insertEvent(event: TransitEvent) = Unit
        override suspend fun getAllEvents(): List<TransitEvent> = emptyList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(emptyList())
    }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /**
     * Builds a ViewModel whose local provider returns [tokens] for every process() call.
     * A response of "v=0.5 a=0.5\nDISTORTIONS: NONE\n<reframe>" satisfies all three
     * ReframingLoop stages from a single token stream.
     */
    private fun makeViewModel(
        journalDao: FakeJournalDao,
        providerTokens: List<String> = emptyList(),
    ): JournalEditorViewModel {
        val repo = JournalRepository(
            journalDao = journalDao,
            personDao = FakePersonDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            }
        )
        val unavailableProvider = object : LlmProvider {
            override val id = "none"
            override suspend fun isAvailable() = false
            override fun process(prompt: String): Flow<LlmResult> = flowOf()
        }
        val fakeLocal = object : LlmProvider {
            override val id = "fake_local"
            override suspend fun isAvailable() = providerTokens.isNotEmpty()
            override fun process(prompt: String): Flow<LlmResult> =
                (providerTokens.map { LlmResult.Token(it) } + LlmResult.Complete).asFlow()
        }
        val orchestrator = LlmOrchestrator(
            nanoProvider = unavailableProvider,
            localFallbackProvider = fakeLocal,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        return JournalEditorViewModel(
            journalRepository = repo,
            orchestrator = orchestrator,
            // Use testDispatcher so ReframingLoop's withContext() stays on the unconfined
            // scheduler and completes synchronously within runTest.
            reframingLoop = ReframingLoop(orchestrator, dispatcher = testDispatcher),
            transitEventDao = FakeTransitEventDao(),
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `dismissReframe clears non-null reframeResult without writing to the DAO`() = runTest {
        val dao = FakeJournalDao()
        // Single token stream that satisfies all three ReframingLoop stages:
        //   Stage 1 — affective coords parsed from "v=0.5 a=0.5"
        //   Stage 2 — DISTORTIONS: sentinel parsed
        //   Stage 3 — non-blank reframe text collected
        val vm = makeViewModel(
            journalDao = dao,
            providerTokens = listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "You are doing well."),
        )

        // Trigger the full pipeline so reframeState becomes Done.
        vm.onTextChanged("!reframe I feel stuck today.")
        // UnconfinedTestDispatcher runs coroutines eagerly, so the pipeline is already done.
        val reframeState = vm.uiState.value.reframeState
        org.junit.Assert.assertTrue(
            "Pipeline must have produced a Done state before dismissReframe() is tested, got: $reframeState",
            reframeState is ReframeState.Done
        )

        // No DAO writes should have happened yet (triggerReframe only writes a TransitEvent,
        // not a reframedContent — applyReframe() is the only writer).
        assertEquals(0, dao.updatedReframes.size)

        // Dismiss — must reset reframeState to Idle without writing to the DAO.
        vm.dismissReframe()

        assertEquals("reframeState must be Idle after dismiss", ReframeState.Idle, vm.uiState.value.reframeState)
        assertEquals("dismissReframe must not call updateReframedContent", 0, dao.updatedReframes.size)
    }

    @Test
    fun `applyReframe is a no-op when reframeState is Idle`() = runTest {
        val dao = FakeJournalDao()
        val vm = makeViewModel(dao)

        // reframeState is Idle (no Done) — applyReframe() must short-circuit without writing.
        vm.applyReframe()

        assertEquals(
            "applyReframe with Idle state must not write to the DAO",
            0,
            dao.updatedReframes.size
        )
    }

    @Test
    fun `triggerReframe accumulates tokens and seals to Done`() = runTest {
        val dao = FakeJournalDao()
        // Each process() call gets the same token list. Stage 1 + 2 drain all tokens via
        // collectTokens(). Stage 3 streams them token-by-token via streamStage3Intervention,
        // accumulating into the Done text.
        val vm = makeViewModel(
            journalDao = dao,
            providerTokens = listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "All good."),
        )

        vm.onTextChanged("!reframe I feel stuck today.")

        val finalState = vm.uiState.value.reframeState
        org.junit.Assert.assertTrue(
            "Expected Done state after pipeline, got: $finalState",
            finalState is ReframeState.Done
        )
        // Done.text is the trimmed accumulation of all Stage 3 tokens, proving streaming ran.
        val doneText = (finalState as ReframeState.Done).text
        org.junit.Assert.assertTrue(
            "Done.text must contain Stage 3 token content; got: \"$doneText\"",
            "All good." in doneText
        )
    }

    @Test
    fun `cloud provider is never invoked during reframe even when cloud is enabled`() = runTest {
        var cloudCallCount = 0
        val fakeCloud = object : LlmProvider {
            override val id = "fake_cloud"
            override suspend fun isAvailable() = true
            override fun process(prompt: String): Flow<LlmResult> {
                cloudCallCount++
                return flowOf(LlmResult.Complete)
            }
        }
        val fakeLocal = object : LlmProvider {
            override val id = "fake_local"
            override suspend fun isAvailable() = true
            override fun process(prompt: String): Flow<LlmResult> =
                (listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "All good.")
                    .map { LlmResult.Token(it) } + LlmResult.Complete).asFlow()
        }
        val dao = FakeJournalDao()
        val repo = JournalRepository(
            journalDao = dao,
            personDao = FakePersonDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            }
        )
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "none"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = fakeLocal,
            cloudProvider = fakeCloud,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = true,
            piiDetector = { false },
        )
        val vm = JournalEditorViewModel(
            journalRepository = repo,
            orchestrator = orchestrator,
            reframingLoop = ReframingLoop(orchestrator, dispatcher = testDispatcher),
            transitEventDao = FakeTransitEventDao(),
        )

        vm.onTextChanged("!reframe I feel stuck today.")

        assertEquals("Cloud provider must not be called during reframe", 0, cloudCallCount)
        org.junit.Assert.assertTrue(
            "reframeState must be Done after successful local reframe",
            vm.uiState.value.reframeState is ReframeState.Done
        )
    }
}
