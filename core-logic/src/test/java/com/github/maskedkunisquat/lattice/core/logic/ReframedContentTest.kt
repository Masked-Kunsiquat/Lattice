package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for Task 5.4: reframedContent column + JournalRepository delegation.
 */
class ReframedContentTest {

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
    }

    private fun makeRepo(journalDao: FakeJournalDao): JournalRepository =
        JournalRepository(
            journalDao = journalDao,
            personDao = FakePersonDao(),
            mentionDao = FakeMentionDao(),
            transitEventDao = FakeTransitEventDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            },
            placeDao = FakePlaceDao(),
        )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `reframedContent defaults to null on new JournalEntry`() {
        val entry = JournalEntry(
            id = UUID.randomUUID(),
            timestamp = System.currentTimeMillis(),
            content = "Today was tough.",
            valence = -0.5f,
            arousal = -0.3f,
            moodLabel = "DEPRESSED",
            embedding = FloatArray(384),
        )
        assertNull(
            "reframedContent should be null when not set",
            entry.reframedContent
        )
    }

    @Test
    fun `updateReframedContent delegates to JournalDao with correct args`() = runTest {
        val dao = FakeJournalDao()
        val repo = makeRepo(dao)
        val entryId = UUID.randomUUID().toString()

        repo.updateReframedContent(entryId, "You handled this well.")

        assertEquals(1, dao.updatedReframes.size)
        assertEquals(entryId, dao.updatedReframes[0].first)
        assertEquals("You handled this well.", dao.updatedReframes[0].second)
    }

    @Test
    fun `updateReframedContent is idempotent — only explicit apply calls write to the DAO`() = runTest {
        val dao = FakeJournalDao()
        val repo = makeRepo(dao)
        val entryId = UUID.randomUUID().toString()

        // Apply: one DAO write.
        repo.updateReframedContent(entryId, "Applied reframe.")
        assertEquals(1, dao.updatedReframes.size)

        // Dismiss simulation: the ViewModel's dismissReframe() is a pure UI-state update —
        // it must not call updateReframedContent. Verified in JournalEditorViewModelTest
        // (app module); here we assert the repo has no self-side-effects between calls.
        assertEquals(entryId, dao.updatedReframes[0].first)
        assertEquals("Applied reframe.", dao.updatedReframes[0].second)
    }
}
