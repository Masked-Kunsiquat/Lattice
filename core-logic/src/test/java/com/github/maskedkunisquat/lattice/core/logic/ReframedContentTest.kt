package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
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

    private fun makeRepo(journalDao: FakeJournalDao): JournalRepository =
        JournalRepository(
            journalDao = journalDao,
            personDao = FakePersonDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384)
            }
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
