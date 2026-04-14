package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.MentionSource
import com.github.maskedkunisquat.lattice.core.data.model.MentionStatus
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class JournalRepositoryDeleteEntryTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeJournalDao : JournalDao {
        val deleted = mutableListOf<JournalEntry>()
        override suspend fun deleteEntry(entry: JournalEntry) { deleted.add(entry) }
        override suspend fun insertEntry(entry: JournalEntry) = Unit
        override suspend fun updateEntry(entry: JournalEntry) = Unit
        override fun getEntries(): Flow<List<JournalEntry>> = flowOf(emptyList())
        override fun getEntryById(id: UUID): Flow<JournalEntry?> = flowOf(null)
        override suspend fun getAllEntries(): List<JournalEntry> = emptyList()
        override suspend fun updateReframedContent(entryId: String, content: String) = Unit
        override suspend fun getEntriesWithMinValence(minValence: Float): List<JournalEntry> = emptyList()
        override suspend fun deleteEntryById(id: UUID) = Unit
        override suspend fun getLabeledEntriesSince(timestamp: Long): List<JournalEntry> = emptyList()
        override suspend fun countLabeledEntriesSince(timestamp: Long): Int = 0
    }

    private class FakePersonDao : PersonDao {
        // personId → list of deltas applied
        val vibeIncrements = mutableMapOf<UUID, MutableList<Float>>()
        override suspend fun incrementVibeScore(personId: UUID, delta: Float) {
            vibeIncrements.getOrPut(personId) { mutableListOf() }.add(delta)
        }
        override suspend fun insertPerson(person: com.github.maskedkunisquat.lattice.core.data.model.Person) = Unit
        override suspend fun updatePerson(person: com.github.maskedkunisquat.lattice.core.data.model.Person) = Unit
        override suspend fun deletePerson(person: com.github.maskedkunisquat.lattice.core.data.model.Person) = Unit
        override fun getPersons(): Flow<List<com.github.maskedkunisquat.lattice.core.data.model.Person>> = flowOf(emptyList())
        override fun getPersonById(id: UUID): Flow<com.github.maskedkunisquat.lattice.core.data.model.Person?> = flowOf(null)
        override suspend fun deletePersonById(id: UUID) = Unit
        override fun searchByName(query: String): Flow<List<com.github.maskedkunisquat.lattice.core.data.model.Person>> = flowOf(emptyList())
    }

    private class FakePlaceDao : PlaceDao {
        override suspend fun insertPlace(place: Place) = Unit
        override suspend fun deleteById(id: UUID) = Unit
        override fun getAll(): Flow<List<Place>> = flowOf(emptyList())
        override fun searchByName(query: String): Flow<List<Place>> = flowOf(emptyList())
        override suspend fun getById(id: UUID): Place? = null
        override suspend fun getByName(name: String): Place? = null
    }

    private class FakeMentionDao(private val mentions: List<Mention>) : MentionDao {
        override suspend fun getMentionsByEntry(entryId: UUID): List<Mention> =
            mentions.filter { it.entryId == entryId }
        override suspend fun insertMention(mention: Mention) = Unit
        override suspend fun updateMention(mention: Mention) = Unit
        override suspend fun deleteMention(mention: Mention) = Unit
        override fun getMentionsForEntry(entryId: UUID): Flow<List<Mention>> = flowOf(emptyList())
        override fun getMentionsForPerson(personId: UUID): Flow<List<Mention>> = flowOf(emptyList())
    }

    private class FakeTransitEventDao : TransitEventDao {
        val deletedEntryIds = mutableListOf<String>()
        override suspend fun deleteEventsForEntry(entryId: String) { deletedEntryIds.add(entryId) }
        override suspend fun insertEvent(event: TransitEvent) = Unit
        override suspend fun getAllEvents(): List<TransitEvent> = emptyList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(emptyList())
    }

    private val fakeEmbeddingProvider = object : EmbeddingProvider() {
        override suspend fun generateEmbedding(text: String): FloatArray = FloatArray(384)
    }

    private fun makeEntry(id: UUID = UUID.randomUUID(), valence: Float = 0.5f) = JournalEntry(
        id = id,
        timestamp = System.currentTimeMillis(),
        content = "test content",
        valence = valence,
        arousal = 0.5f,
        moodLabel = "EXCITED",
        embedding = FloatArray(384),
    )

    private fun makeMention(entryId: UUID, personId: UUID) = Mention(
        entryId = entryId,
        personId = personId,
        source = MentionSource.MANUAL,
        status = MentionStatus.CONFIRMED,
    )

    private fun makeRepo(
        journalDao: FakeJournalDao = FakeJournalDao(),
        personDao: FakePersonDao = FakePersonDao(),
        mentionDao: MentionDao,
        transitEventDao: FakeTransitEventDao = FakeTransitEventDao(),
    ) = JournalRepository(journalDao, personDao, mentionDao, transitEventDao, fakeEmbeddingProvider, FakePlaceDao())

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `deleteEntry decrements vibe score for each mentioned person`() = runTest {
        val entryId = UUID.randomUUID()
        val person1 = UUID.randomUUID()
        val person2 = UUID.randomUUID()
        val entry = makeEntry(id = entryId, valence = 0.6f)

        val personDao = FakePersonDao()
        val repo = makeRepo(
            personDao = personDao,
            mentionDao = FakeMentionDao(listOf(
                makeMention(entryId, person1),
                makeMention(entryId, person2),
            )),
        )

        repo.deleteEntry(entry)

        val expectedDelta = -(0.6f * 0.1f)
        assertEquals(1, personDao.vibeIncrements[person1]?.size)
        assertEquals(expectedDelta, personDao.vibeIncrements[person1]!![0], 0.0001f)
        assertEquals(1, personDao.vibeIncrements[person2]?.size)
        assertEquals(expectedDelta, personDao.vibeIncrements[person2]!![0], 0.0001f)
    }

    @Test
    fun `deleteEntry prunes transit events for the deleted entry`() = runTest {
        val entryId = UUID.randomUUID()
        val entry = makeEntry(id = entryId)

        val transitDao = FakeTransitEventDao()
        val repo = makeRepo(
            mentionDao = FakeMentionDao(emptyList()),
            transitEventDao = transitDao,
        )

        repo.deleteEntry(entry)

        assertEquals(1, transitDao.deletedEntryIds.size)
        assertEquals(entryId.toString(), transitDao.deletedEntryIds[0])
    }

    @Test
    fun `deleteEntry calls journalDao deleteEntry`() = runTest {
        val entry = makeEntry()
        val journalDao = FakeJournalDao()
        val repo = makeRepo(
            journalDao = journalDao,
            mentionDao = FakeMentionDao(emptyList()),
        )

        repo.deleteEntry(entry)

        assertTrue(journalDao.deleted.contains(entry))
    }

    @Test
    fun `deleteEntry with no mentions does not call incrementVibeScore`() = runTest {
        val entry = makeEntry()
        val personDao = FakePersonDao()
        val repo = makeRepo(
            personDao = personDao,
            mentionDao = FakeMentionDao(emptyList()),
        )

        repo.deleteEntry(entry)

        assertTrue(personDao.vibeIncrements.isEmpty())
    }

    @Test
    fun `deleteEntry vibe delta uses negative valence correctly`() = runTest {
        val entryId = UUID.randomUUID()
        val personId = UUID.randomUUID()
        // Negative valence entry: vibe should increase on delete (undo the penalty)
        val entry = makeEntry(id = entryId, valence = -0.4f)

        val personDao = FakePersonDao()
        val repo = makeRepo(
            personDao = personDao,
            mentionDao = FakeMentionDao(listOf(makeMention(entryId, personId))),
        )

        repo.deleteEntry(entry)

        val expectedDelta = -(-0.4f * 0.1f)  // = +0.04
        assertEquals(expectedDelta, personDao.vibeIncrements[personId]!![0], 0.0001f)
    }
}
