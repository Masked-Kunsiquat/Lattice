package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LatticeDatabaseTest {
    private lateinit var repository: JournalRepository
    private lateinit var db: LatticeDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, LatticeDatabase::class.java
        ).build()
        repository = JournalRepository(db.journalDao(), db.personDao(), db.mentionDao(), db.transitEventDao(), EmbeddingProvider(), db.placeDao())
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun saveEntry_persistsMentionRows() = runBlocking {
        val personId = UUID.randomUUID()
        db.personDao().insertPerson(
            Person(
                id               = personId,
                firstName        = "Alice",
                lastName         = null,
                nickname         = null,
                relationshipType = RelationshipType.FRIEND,
                vibeScore        = 0f,
                isFavorite       = false,
            )
        )

        val entry = JournalEntry(
            id                   = UUID.randomUUID(),
            timestamp            = System.currentTimeMillis(),
            content              = "[PERSON_$personId] and I had coffee",
            valence              = 0.5f,
            arousal              = 0.3f,
            moodLabel            = "CONTENT",
            embedding            = FloatArray(384),
            cognitiveDistortions = emptyList(),
        )

        // Pass already-masked content; PiiShield.mask() is a no-op when no raw names are present
        repository.saveEntry(entry)

        val mentions = db.mentionDao().getMentionsByEntry(entry.id)
        assertEquals("Expected one Mention row for Alice", 1, mentions.size)
        assertEquals(personId, mentions[0].personId)
        assertEquals(entry.id, mentions[0].entryId)
    }

    @Test
    @Throws(Exception::class)
    fun testVectorPrecision() = runBlocking {
        // Create a specific 384-length FloatArray
        val expectedEmbedding = FloatArray(384) { i -> i.toFloat() * 0.123456f }
        
        val entry = JournalEntry(
            id = UUID.randomUUID(),
            timestamp = System.currentTimeMillis(),
            content = "Vector precision test content",
            valence = 0.5f,
            arousal = 0.5f,
            moodLabel = "ALIVE",
            embedding = expectedEmbedding,
            cognitiveDistortions = emptyList()
        )

        // Insert directly via DAO to test the type converter in isolation —
        // repository.saveEntry() generates its own embedding, which would overwrite this value.
        db.journalDao().insertEntry(entry)

        // Retrieve via DAO and verify bit-perfect round-trip through LatticeTypeConverters
        val retrievedEntries = db.journalDao().getEntries().first()
        assertEquals(1, retrievedEntries.size)
        val retrievedEntry = retrievedEntries[0]

        assertArrayEquals(
            "FloatArray embedding must be preserved within tolerance after string serialization",
            expectedEmbedding,
            retrievedEntry.embedding,
            1e-6f
        )
    }
}
