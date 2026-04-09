package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SearchRepositoryTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakePersonDao : PersonDao {
        override suspend fun insertPerson(person: Person) = Unit
        override suspend fun updatePerson(person: Person) = Unit
        override suspend fun deletePerson(person: Person) = Unit
        override fun getPersons(): Flow<List<Person>> = flowOf(emptyList())
        override fun getPersonById(id: UUID): Flow<Person?> = flowOf(null)
        override suspend fun incrementVibeScore(personId: UUID, delta: Float) = Unit
    }

    private fun entry(
        valence: Float,
        content: String,
        hasEmbedding: Boolean = true,
    ) = JournalEntry(
        id = UUID.randomUUID(),
        timestamp = System.currentTimeMillis(),
        content = content,
        valence = valence,
        arousal = 0f,
        moodLabel = "SERENE",
        embedding = if (hasEmbedding) FloatArray(384) { 0.1f } else FloatArray(384),
    )

    private fun makeRepo(entries: List<JournalEntry>): SearchRepository {
        val dao = object : JournalDao {
            override suspend fun insertEntry(e: JournalEntry) = Unit
            override suspend fun updateEntry(e: JournalEntry) = Unit
            override suspend fun deleteEntry(e: JournalEntry) = Unit
            override fun getEntries(): Flow<List<JournalEntry>> = flowOf(emptyList())
            override fun getEntryById(id: UUID): Flow<JournalEntry?> = flowOf(null)
            override suspend fun getAllEntries(): List<JournalEntry> = emptyList()
            override suspend fun updateReframedContent(id: String, content: String) = Unit
            override suspend fun getEntriesWithMinValence(minValence: Float): List<JournalEntry> =
                entries.filter { it.valence > minValence }.sortedByDescending { it.valence }
        }
        return SearchRepository(
            journalDao = dao,
            personDao = FakePersonDao(),
            embeddingProvider = object : EmbeddingProvider() {
                override suspend fun generateEmbedding(text: String) = FloatArray(384) { 0.1f }
            }
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `findEvidenceEntries - valence gate enforced`() = runTest {
        val personId = UUID.randomUUID()
        val placeholder = "[PERSON_$personId]"

        val aboveThreshold = entry(valence = 0.8f, content = "Had a great day with $placeholder.")
        val belowThreshold = entry(valence = 0.3f, content = "Was okay with $placeholder.")
        val atThreshold    = entry(valence = 0.5f, content = "Exactly at threshold with $placeholder.")

        val repo = makeRepo(listOf(aboveThreshold, belowThreshold, atThreshold))
        val results = repo.findEvidenceEntries(
            placeholders = setOf(placeholder),
            minValence = 0.5f,
        ).first()

        assertTrue("Only entries above minValence=0.5 should be returned", results.all { it.valence > 0.5f })
        assertEquals(1, results.size)
        assertEquals(aboveThreshold.id, results[0].id)
    }

    @Test
    fun `findEvidenceEntries - placeholder match required`() = runTest {
        val personA = UUID.randomUUID()
        val personB = UUID.randomUUID()
        val placeholderA = "[PERSON_$personA]"
        val placeholderB = "[PERSON_$personB]"

        val matchingEntry    = entry(valence = 0.9f, content = "Loved spending time with $placeholderA.")
        val noPlaceholder    = entry(valence = 0.9f, content = "Had a great solo hike.")
        val wrongPerson      = entry(valence = 0.9f, content = "Worked well with $placeholderB.")

        val repo = makeRepo(listOf(matchingEntry, noPlaceholder, wrongPerson))
        val results = repo.findEvidenceEntries(
            placeholders = setOf(placeholderA),
            minValence = 0.5f,
        ).first()

        assertEquals("Only entries containing a matching placeholder should be returned", 1, results.size)
        assertEquals(matchingEntry.id, results[0].id)
    }
}
