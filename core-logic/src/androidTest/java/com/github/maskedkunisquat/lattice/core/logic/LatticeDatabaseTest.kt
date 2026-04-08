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
        repository = JournalRepository(db.journalDao(), db.personDao())
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
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

        // Save via Repository (to fulfill requirement of retrieving via Repository)
        repository.saveEntry(entry)

        // Retrieve via Repository
        val retrievedEntries = repository.getEntries().first()
        assertEquals(1, retrievedEntries.size)
        val retrievedEntry = retrievedEntries[0]

        // Verify the retrieved array matches the input exactly
        // We use a delta of 0.0f to ensure bit-perfect matching for precision check
        assertArrayEquals(
            "Retrieved embedding should match input exactly",
            expectedEmbedding,
            retrievedEntry.embedding,
            0.0f
        )
    }
}
