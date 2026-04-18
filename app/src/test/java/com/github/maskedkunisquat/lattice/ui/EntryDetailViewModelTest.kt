package com.github.maskedkunisquat.lattice.ui

import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LlmProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import com.github.maskedkunisquat.lattice.core.logic.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailViewModelTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakePersonDao : PersonDao {
        override suspend fun insertPerson(person: Person) = Unit
        override suspend fun updatePerson(person: Person) = Unit
        override suspend fun deletePerson(person: Person) = Unit
        override fun getPersons(): Flow<List<Person>> = flowOf(emptyList())
        override fun getPersonById(id: UUID): Flow<Person?> = flowOf(null)
        override suspend fun incrementVibeScore(personId: UUID, delta: Float) = Unit
        override suspend fun deletePersonById(id: UUID) = Unit
        override fun searchByName(query: String): Flow<List<Person>> = flowOf(emptyList())
    }

    private class FakePlaceDao : PlaceDao {
        override suspend fun insertPlace(place: Place) = Unit
        override suspend fun deleteById(id: UUID) = Unit
        override fun getAll(): Flow<List<Place>> = flowOf(emptyList())
        override fun searchByName(query: String): Flow<List<Place>> = flowOf(emptyList())
        override suspend fun getById(id: UUID): Place? = null
        override suspend fun getByName(name: String): Place? = null
    }

    private class FakeMentionDao : MentionDao {
        override suspend fun insertMention(mention: Mention) = Unit
        override suspend fun updateMention(mention: Mention) = Unit
        override suspend fun deleteMention(mention: Mention) = Unit
        override fun getMentionsForEntry(entryId: UUID): Flow<List<Mention>> = flowOf(emptyList())
        override suspend fun getMentionsByEntry(entryId: UUID): List<Mention> = emptyList()
        override fun getMentionsForPerson(personId: UUID): Flow<List<Mention>> = flowOf(emptyList())
    }

    private class FakeTransitEventDao : TransitEventDao {
        override suspend fun insertEvent(event: TransitEvent) = Unit
        override suspend fun getAllEvents(): List<TransitEvent> = emptyList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(emptyList())
        override suspend fun deleteEventsForEntry(entryId: String) = Unit
    }

    private class FakeActivityHierarchyDao : ActivityHierarchyDao {
        override suspend fun insertActivity(activity: ActivityHierarchy) = Unit
        override suspend fun updateActivity(activity: ActivityHierarchy) = Unit
        override suspend fun deleteActivity(activity: ActivityHierarchy) = Unit
        override fun getAllActivities(): Flow<List<ActivityHierarchy>> = flowOf(emptyList())
        override suspend fun getActivitiesByMaxDifficulty(max: Int): List<ActivityHierarchy> = emptyList()
        override suspend fun deleteActivityById(id: UUID) = Unit
    }

    /**
     * Captures the last entry passed to [updateEntry] — the field under assertion.
     * Also returns [seedEntry] from [getEntryById] so [entryState] becomes [Found].
     */
    private class FakeJournalDao(private val seedEntry: JournalEntry) : JournalDao {
        var lastUpdatedEntry: JournalEntry? = null

        override suspend fun insertEntry(entry: JournalEntry) = Unit
        override suspend fun updateEntry(entry: JournalEntry) { lastUpdatedEntry = entry }
        override suspend fun deleteEntry(entry: JournalEntry) = Unit
        override fun getEntries(): Flow<List<JournalEntry>> = flowOf(emptyList())
        override fun getEntryById(id: UUID): Flow<JournalEntry?> = flowOf(seedEntry)
        override suspend fun getAllEntries(): List<JournalEntry> = emptyList()
        override suspend fun updateReframedContent(entryId: String, content: String) = Unit
        override suspend fun getEntriesWithMinValence(minValence: Float): List<JournalEntry> = emptyList()
        override suspend fun deleteEntryById(id: UUID) = Unit
        override suspend fun getLabeledEntriesBetween(fromTimestamp: Long, toTimestamp: Long): List<JournalEntry> = emptyList()
        override suspend fun countLabeledEntriesBetween(fromTimestamp: Long, toTimestamp: Long): Int = 0
        override fun getEntriesForPerson(personId: UUID): Flow<List<JournalEntry>> = flowOf(emptyList())
        override fun getEntryRefs(): Flow<List<com.github.maskedkunisquat.lattice.core.data.model.JournalEntryRef>> = flowOf(emptyList())
    }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /**
     * Builds an [EntryDetailViewModel] whose local provider emits [providerTokens] for
     * every process() call. All three ReframingLoop stages share the same stream:
     *   Stage 1 — "v=0.5 a=0.5\n" satisfies affective mapping
     *   Stage 2 — "DISTORTIONS: NONE\n" satisfies diagnosis
     *   Stage 3 — remaining tokens are accumulated as the reframe text
     */
    private fun makeViewModel(
        dao: FakeJournalDao,
        entryId: UUID,
        providerTokens: List<String>,
    ): EntryDetailViewModel {
        val unavailable = object : LlmProvider {
            override val id = "none"
            override suspend fun isAvailable() = false
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
        }
        val fakeLocal = object : LlmProvider {
            override val id = "fake_local"
            override suspend fun isAvailable() = true
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> =
                (providerTokens.map { LlmResult.Token(it) } + LlmResult.Complete).asFlow()
        }
        val orchestrator = LlmOrchestrator(
            nanoProvider = unavailable,
            localFallbackProvider = fakeLocal,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        val repo = JournalRepository(
            journalDao = dao,
            personDao = FakePersonDao(),
            mentionDao = FakeMentionDao(),
            transitEventDao = FakeTransitEventDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            },
            placeDao = FakePlaceDao(),
        )
        val searchRepo = SearchRepository(
            journalDao = dao,
            personDao = FakePersonDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            },
        )
        return EntryDetailViewModel(
            journalRepository = repo,
            reframingLoop = ReframingLoop(
                orchestrator = orchestrator,
                activityHierarchyDao = FakeActivityHierarchyDao(),
                searchRepository = searchRepo,
                dispatcher = testDispatcher,
            ),
            transitEventDao = FakeTransitEventDao(),
            modelLoadState = MutableStateFlow(ModelLoadState.READY),
            entryId = entryId,
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `acceptReframe with identical text sets reframeEditedByUser to false`() = runTest {
        val entryId = UUID.randomUUID()
        val entry = JournalEntry(
            id = entryId,
            timestamp = System.currentTimeMillis(),
            content = "I feel overwhelmed.",
            valence = -0.4f,
            arousal = 0.7f,
            moodLabel = "DISTRESSED",
            embedding = FloatArray(384),
        )
        val dao = FakeJournalDao(entry)
        val vm = makeViewModel(
            dao = dao,
            entryId = entryId,
            providerTokens = listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "Original reframe."),
        )

        // Subscribe so WhileSubscribed triggers upstream — entryState becomes Found.
        val stateJob = launch { vm.entryState.collect {} }
        advanceUntilIdle()

        assertTrue(
            "entryState must be Found before requestReframe()",
            vm.entryState.value is EntryDetailState.Found,
        )

        vm.requestReframe()
        advanceUntilIdle()

        assertTrue(
            "reframeState must be Done after pipeline; got ${vm.reframeState.value}",
            vm.reframeState.value is ReframeState.Done,
        )

        // Accept with the exact text the model produced — no user edits.
        val modelOutput = (vm.reframeState.value as ReframeState.Done).text
        vm.acceptReframe(modelOutput)
        advanceUntilIdle()

        assertFalse(
            "reframeEditedByUser must be false when editedText matches the model output",
            dao.lastUpdatedEntry!!.reframeEditedByUser,
        )
        stateJob.cancel()
    }

    @Test
    fun `acceptReframe with modified text sets reframeEditedByUser to true`() = runTest {
        val entryId = UUID.randomUUID()
        val entry = JournalEntry(
            id = entryId,
            timestamp = System.currentTimeMillis(),
            content = "I feel overwhelmed.",
            valence = -0.4f,
            arousal = 0.7f,
            moodLabel = "DISTRESSED",
            embedding = FloatArray(384),
        )
        val dao = FakeJournalDao(entry)
        val vm = makeViewModel(
            dao = dao,
            entryId = entryId,
            providerTokens = listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "Original reframe."),
        )

        val stateJob = launch { vm.entryState.collect {} }
        advanceUntilIdle()

        vm.requestReframe()
        advanceUntilIdle()

        // Accept with user-modified text.
        vm.acceptReframe("I changed this reframe.")
        advanceUntilIdle()

        assertTrue(
            "reframeEditedByUser must be true when editedText differs from the model output",
            dao.lastUpdatedEntry!!.reframeEditedByUser,
        )
        stateJob.cancel()
    }
}
