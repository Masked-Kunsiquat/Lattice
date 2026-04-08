package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SearchRepositoryTest {

    // --- Fakes ---

    private class FakeJournalDao(private val entries: List<JournalEntry>) : JournalDao {
        override suspend fun getAllEntries() = entries
        override suspend fun insertEntry(entry: JournalEntry) = Unit
        override suspend fun updateEntry(entry: JournalEntry) = Unit
        override suspend fun deleteEntry(entry: JournalEntry) = Unit
        override fun getEntries(): Flow<List<JournalEntry>> = flowOf(entries)
        override fun getEntryById(id: UUID): Flow<JournalEntry?> = flowOf(null)
    }

    private class FakePersonDao(private val people: List<Person> = emptyList()) : PersonDao {
        override fun getPersons(): Flow<List<Person>> = flowOf(people)
        override fun getPersonById(id: UUID): Flow<Person?> = flowOf(null)
        override suspend fun insertPerson(person: Person) = Unit
        override suspend fun updatePerson(person: Person) = Unit
        override suspend fun deletePerson(person: Person) = Unit
        override suspend fun incrementVibeScore(personId: UUID, delta: Float) = Unit
    }

    /**
     * EmbeddingProvider that returns a caller-controlled vector and records what text it received.
     * The parent dispatcher is never used because [generateEmbedding] is fully overridden.
     */
    private class ControlledEmbeddingProvider(
        private val embedFn: (String) -> FloatArray
    ) : EmbeddingProvider(Dispatchers.Unconfined) {
        val capturedTexts = mutableListOf<String>()
        override suspend fun generateEmbedding(text: String): FloatArray {
            capturedTexts.add(text)
            return embedFn(text)
        }
    }

    // --- Helpers ---

    private fun makeEntry(embedding: FloatArray, content: String = "content"): JournalEntry =
        JournalEntry(
            id = UUID.randomUUID(),
            timestamp = System.currentTimeMillis(),
            content = content,
            valence = 0f,
            arousal = 0f,
            moodLabel = "NEUTRAL",
            embedding = embedding
        )

    /** Builds a unit vector pointing only along dimension [dim]. */
    private fun basisVector(dim: Int, size: Int = EmbeddingProvider.EMBEDDING_DIM): FloatArray =
        FloatArray(size).also { it[dim] = 1f }

    // --- Tests ---

    @Test
    fun `findSimilarEntries masks PII in query before embedding`() = runTest {
        val personId = UUID.randomUUID()
        val person = Person(
            id = personId,
            firstName = "Alice",
            lastName = null,
            nickname = null,
            relationshipType = RelationshipType.FRIEND
        )
        val provider = ControlledEmbeddingProvider { FloatArray(EmbeddingProvider.EMBEDDING_DIM) }
        val repo = SearchRepository(FakeJournalDao(emptyList()), FakePersonDao(listOf(person)), provider)

        repo.findSimilarEntries("I talked to Alice today", limit = 5).first()

        assertEquals(1, provider.capturedTexts.size)
        val embedded = provider.capturedTexts[0]
        assertTrue("Raw name 'Alice' must not reach the embedding pipeline", !embedded.contains("Alice"))
        assertTrue("Masked placeholder must be present", embedded.contains("[PERSON_$personId]"))
    }

    @Test
    fun `findSimilarEntries ranks results by cosine similarity descending`() = runTest {
        // Query points along dimension 0 (similarity = 1.0).
        // Entry A aligns with dim 0 exactly → similarity 1.0.
        // Entry B points partly at dim 0, partly at dim 1 → similarity ~0.5 (lower).
        val entryA = makeEntry(basisVector(0), content = "entry A — high similarity")
        val entryBVec = FloatArray(EmbeddingProvider.EMBEDDING_DIM).also { it[0] = 0.5f; it[1] = 0.866f }
        val entryB = makeEntry(entryBVec, content = "entry B — lower similarity")

        val provider = ControlledEmbeddingProvider { basisVector(0) }
        val repo = SearchRepository(FakeJournalDao(listOf(entryA, entryB)), FakePersonDao(), provider)

        val results = repo.findSimilarEntries("anything", limit = 10).first()

        assertEquals(2, results.size)
        assertEquals(entryA.id, results[0].id)
        assertEquals(entryB.id, results[1].id)
    }

    @Test
    fun `findSimilarEntries respects the limit parameter`() = runTest {
        // All entries point along dim 0 so they all pass the score > 0 filter;
        // limit=3 should cap the result at 3.
        val entries = List(10) { makeEntry(basisVector(0)) }
        val provider = ControlledEmbeddingProvider { basisVector(0) }
        val repo = SearchRepository(FakeJournalDao(entries), FakePersonDao(), provider)

        val results = repo.findSimilarEntries("anything", limit = 3).first()

        assertEquals(3, results.size)
    }

    @Test
    fun `findSimilarEntries excludes zero-vector entries`() = runTest {
        val zeroEntry = makeEntry(FloatArray(EmbeddingProvider.EMBEDDING_DIM), content = "zero entry")
        val realEntry = makeEntry(basisVector(0), content = "real entry")

        val provider = ControlledEmbeddingProvider { basisVector(0) }
        val repo = SearchRepository(FakeJournalDao(listOf(zeroEntry, realEntry)), FakePersonDao(), provider)

        val results = repo.findSimilarEntries("anything", limit = 10).first()

        assertEquals(1, results.size)
        assertEquals(realEntry.id, results[0].id)
    }
}
