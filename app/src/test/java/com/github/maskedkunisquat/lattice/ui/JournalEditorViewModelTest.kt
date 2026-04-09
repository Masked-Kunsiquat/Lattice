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

        // Trigger the full pipeline so reframeResult becomes non-null.
        vm.onTextChanged("!reframe I feel stuck today.")
        // UnconfinedTestDispatcher runs coroutines eagerly, so the pipeline is already done.
        val reframeResult = vm.uiState.value.reframeResult
        org.junit.Assert.assertNotNull(
            "Pipeline must have produced a reframeResult before dismissReframe() is tested",
            reframeResult
        )

        // No DAO writes should have happened yet (triggerReframe only writes a TransitEvent,
        // not a reframedContent — applyReframe() is the only writer).
        assertEquals(0, dao.updatedReframes.size)

        // Dismiss — must clear reframeResult without writing to the DAO.
        vm.dismissReframe()

        assertNull("reframeResult must be null after dismiss", vm.uiState.value.reframeResult)
        assertEquals("dismissReframe must not call updateReframedContent", 0, dao.updatedReframes.size)
    }

    @Test
    fun `applyReframe is a no-op when reframeResult is null`() = runTest {
        val dao = FakeJournalDao()
        val vm = makeViewModel(dao)

        // No reframeResult is set — applyReframe() must short-circuit without writing.
        vm.applyReframe()

        assertEquals(
            "applyReframe with no reframeResult must not write to the DAO",
            0,
            dao.updatedReframes.size
        )
    }
}
