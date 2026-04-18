package com.github.maskedkunisquat.lattice.ui

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PhoneNumberDao
import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.dao.TagDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LlmProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.PeopleRepository
import com.github.maskedkunisquat.lattice.core.logic.PlaceRepository
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import com.github.maskedkunisquat.lattice.core.logic.TagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        override suspend fun deleteEntryById(id: UUID) = Unit
        override suspend fun getLabeledEntriesBetween(fromTimestamp: Long, toTimestamp: Long): List<JournalEntry> = emptyList()
        override suspend fun countLabeledEntriesBetween(fromTimestamp: Long, toTimestamp: Long): Int = 0
        override fun getEntriesForPerson(personId: UUID): Flow<List<JournalEntry>> = flowOf(emptyList())
        override fun getEntryRefs(): Flow<List<com.github.maskedkunisquat.lattice.core.data.model.JournalEntryRef>> = flowOf(emptyList())
    }

    private class FakeTagDao : TagDao {
        override suspend fun insertTag(tag: Tag) = Unit
        override suspend fun deleteById(id: UUID) = Unit
        override fun getAll(): Flow<List<Tag>> = flowOf(emptyList())
        override fun searchByName(query: String): Flow<List<Tag>> = flowOf(emptyList())
        override suspend fun getById(id: UUID): Tag? = null
        override suspend fun getByName(name: String): Tag? = null
    }

    private class FakePhoneNumberDao : PhoneNumberDao {
        override suspend fun insertPhoneNumber(phoneNumber: PhoneNumber) = Unit
        override suspend fun insertPhoneNumbers(phoneNumbers: List<PhoneNumber>) = Unit
        override suspend fun updatePhoneNumber(phoneNumber: PhoneNumber) = Unit
        override suspend fun deletePhoneNumber(phoneNumber: PhoneNumber) = Unit
        override fun getPhoneNumbersForPerson(personId: UUID): Flow<List<PhoneNumber>> = flowOf(emptyList())
        override fun getAllPhoneNumbers(): Flow<List<PhoneNumber>> = flowOf(emptyList())
        override suspend fun deleteByPersonId(personId: UUID) = Unit
    }

    private class FakeTransitEventDao : TransitEventDao {
        override suspend fun insertEvent(event: TransitEvent) = Unit
        override suspend fun getAllEvents(): List<TransitEvent> = emptyList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(emptyList())
        override suspend fun deleteEventsForEntry(entryId: String) = Unit
    }

    private class FakeMentionDao : MentionDao {
        override suspend fun insertMention(mention: Mention) = Unit
        override suspend fun updateMention(mention: Mention) = Unit
        override suspend fun deleteMention(mention: Mention) = Unit
        override fun getMentionsForEntry(entryId: UUID): Flow<List<Mention>> = flowOf(emptyList())
        override suspend fun getMentionsByEntry(entryId: UUID): List<Mention> = emptyList()
        override fun getMentionsForPerson(personId: UUID): Flow<List<Mention>> = flowOf(emptyList())
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
            mentionDao = FakeMentionDao(),
            transitEventDao = FakeTransitEventDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            },
            placeDao = FakePlaceDao(),
        )
        val unavailableProvider = object : LlmProvider {
            override val id = "none"
            override suspend fun isAvailable() = false
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
        }
        val fakeLocal = object : LlmProvider {
            override val id = "fake_local"
            override suspend fun isAvailable() = providerTokens.isNotEmpty()
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> =
                (providerTokens.map { LlmResult.Token(it) } + LlmResult.Complete).asFlow()
        }
        val orchestrator = LlmOrchestrator(
            nanoProvider = unavailableProvider,
            localFallbackProvider = fakeLocal,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        return JournalEditorViewModel(
            journalRepository = repo,
            orchestrator = orchestrator,
            // Use testDispatcher so ReframingLoop's withContext() stays on the unconfined
            // scheduler and completes synchronously within runTest.
            reframingLoop = ReframingLoop(orchestrator, dispatcher = testDispatcher),
            transitEventDao = FakeTransitEventDao(),
            modelLoadState = MutableStateFlow(ModelLoadState.IDLE),
            peopleRepository = PeopleRepository(
                personDao = FakePersonDao(),
                phoneNumberDao = FakePhoneNumberDao(),
                transact = { it() },
            ),
            tagRepository = TagRepository(FakeTagDao()),
            placeRepository = PlaceRepository(FakePlaceDao()),
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
        vm.onTextChanged("I feel stuck today.")
        vm.requestReframe()
        // UnconfinedTestDispatcher runs coroutines eagerly, so the pipeline is already done.
        val reframeState = vm.uiState.value.reframeState
        assertTrue(
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

        vm.onTextChanged("I feel stuck today.")
        vm.requestReframe()

        val finalState = vm.uiState.value.reframeState
        assertTrue(
            "Expected Done state after pipeline, got: $finalState",
            finalState is ReframeState.Done
        )
        // Done.text is the trimmed accumulation of all Stage 3 tokens, proving streaming ran.
        val doneText = (finalState as ReframeState.Done).text
        assertTrue(
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
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> {
                cloudCallCount++
                return flowOf(LlmResult.Complete)
            }
        }
        val fakeLocal = object : LlmProvider {
            override val id = "fake_local"
            override suspend fun isAvailable() = true
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> =
                (listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "All good.")
                    .map { LlmResult.Token(it) } + LlmResult.Complete).asFlow()
        }
        val dao = FakeJournalDao()
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
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "none"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = fakeLocal,
            cloudProvider = fakeCloud,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { true },
            piiDetector = { false },
        )
        val vm = JournalEditorViewModel(
            journalRepository = repo,
            orchestrator = orchestrator,
            reframingLoop = ReframingLoop(orchestrator, dispatcher = testDispatcher),
            transitEventDao = FakeTransitEventDao(),
            modelLoadState = MutableStateFlow(ModelLoadState.IDLE),
            peopleRepository = PeopleRepository(
                personDao = FakePersonDao(),
                phoneNumberDao = FakePhoneNumberDao(),
                transact = { it() },
            ),
            tagRepository = TagRepository(FakeTagDao()),
            placeRepository = PlaceRepository(FakePlaceDao()),
        )

        vm.onTextChanged("I feel stuck today.")
        vm.requestReframe()

        assertEquals("Cloud provider must not be called during reframe", 0, cloudCallCount)
        assertTrue(
            "reframeState must be Done after successful local reframe",
            vm.uiState.value.reframeState is ReframeState.Done
        )
    }

    @Test
    fun `typing after !reframe cancels the in-flight pipeline and resets state to Idle`() = runTest {
        val dao = FakeJournalDao()
        // Use a StandardTestDispatcher that shares the runTest scheduler so
        // withContext(reframingDispatcher) suspends instead of running eagerly.
        // This keeps the pipeline genuinely in-flight between the two onTextChanged calls.
        val reframingDispatcher = StandardTestDispatcher(testScheduler)

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
        val fakeLocal = object : LlmProvider {
            override val id = "fake_local"
            override suspend fun isAvailable() = true
            override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> =
                (listOf("v=0.5 a=0.5\nDISTORTIONS: NONE\n", "All good.")
                    .map { LlmResult.Token(it) } + LlmResult.Complete).asFlow()
        }
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "none"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = fakeLocal,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        val vm = JournalEditorViewModel(
            journalRepository = repo,
            orchestrator = orchestrator,
            // StandardTestDispatcher: the first withContext(reframingDispatcher) suspends,
            // leaving the job in-flight so the second onTextChanged can cancel it.
            reframingLoop = ReframingLoop(orchestrator, dispatcher = reframingDispatcher),
            transitEventDao = FakeTransitEventDao(),
            modelLoadState = MutableStateFlow(ModelLoadState.IDLE),
            peopleRepository = PeopleRepository(
                personDao = FakePersonDao(),
                phoneNumberDao = FakePhoneNumberDao(),
                transact = { it() },
            ),
            tagRepository = TagRepository(FakeTagDao()),
            placeRepository = PlaceRepository(FakePlaceDao()),
        )

        // Set text and start the pipeline — suspends at the first withContext(reframingDispatcher).
        vm.onTextChanged("I feel stuck today.")
        vm.requestReframe()
        // Pipeline is suspended. Cancel it by typing new text.
        vm.onTextChanged("Something else entirely.")
        // Drain the scheduler — cancelled coroutine cleans up, no further state changes.
        advanceUntilIdle()

        assertEquals("reframeState must be Idle after cancellation", ReframeState.Idle, vm.uiState.value.reframeState)
        assertEquals("text must reflect the last typed value", "Something else entirely.", vm.uiState.value.text)
    }
}
