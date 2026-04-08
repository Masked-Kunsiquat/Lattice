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
import kotlinx.coroutines.Dispatchers
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

    private fun makeViewModel(journalDao: FakeJournalDao): JournalEditorViewModel {
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
        val orchestrator = LlmOrchestrator(
            nanoProvider = unavailableProvider,
            localFallbackProvider = unavailableProvider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        return JournalEditorViewModel(
            journalRepository = repo,
            orchestrator = orchestrator,
            reframingLoop = ReframingLoop(orchestrator),
            transitEventDao = FakeTransitEventDao(),
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `dismissReframe does not write to the DAO`() = runTest {
        val dao = FakeJournalDao()
        val vm = makeViewModel(dao)

        // Simulate the "Apply" path by writing directly through the repo (as the ViewModel
        // would do in applyReframe). This confirms the DAO records one write.
        val repo = JournalRepository(
            journalDao = dao,
            personDao = FakePersonDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            }
        )
        val entryId = UUID.randomUUID().toString()
        repo.updateReframedContent(entryId, "You handled this with care.")
        assertEquals(1, dao.updatedReframes.size)

        // Now call dismissReframe() on the ViewModel — this must NOT touch the DAO.
        vm.dismissReframe()

        assertEquals(
            "dismissReframe must not call updateReframedContent",
            1,
            dao.updatedReframes.size
        )
        assertNull(
            "reframeResult should be null after dismiss",
            vm.uiState.value.reframeResult
        )
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
