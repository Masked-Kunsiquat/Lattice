package com.github.maskedkunisquat.lattice.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.core.data.seed.RawJournalEntry
import com.github.maskedkunisquat.lattice.core.data.seed.RawMention
import com.github.maskedkunisquat.lattice.core.data.seed.RawMoodLog
import com.github.maskedkunisquat.lattice.core.data.seed.RawPerson
import com.github.maskedkunisquat.lattice.core.data.seed.RawSeed
import com.github.maskedkunisquat.lattice.core.data.seed.SeedManager
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SeedManagerTest {

    private lateinit var db: LatticeDatabase
    private lateinit var context: Context
    private lateinit var seedManager: SeedManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear seed manifest from any prior test run so state doesn't bleed across tests.
        context.getSharedPreferences("lattice_seed_manager", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, LatticeDatabase::class.java).build()
        seedManager = SeedManager(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── parseSeed ─────────────────────────────────────────────────────────────

    @Test
    fun parseSeed_parsesPersonAnd30Entries() {
        val seed = seedManager.parseSeed(minimalJson(entryCount = 30))
        assertEquals(1, seed.people.size)
        assertEquals(30, seed.journalEntries.size)
        assertEquals(0, seed.moodLogs.size)
    }

    @Test
    fun parseSeed_parsesMoodLogsWhenPresent() {
        val seed = seedManager.parseSeed(minimalJson(entryCount = 30, moodLogCount = 3))
        assertEquals(3, seed.moodLogs.size)
    }

    @Test
    fun parseSeed_optionalFieldsDefaultCorrectly() {
        val seed = seedManager.parseSeed(minimalJson(entryCount = 30))
        val entry = seed.journalEntries[0]
        assertEquals(PERSON_ID, seed.people[0].id)
        assertEquals(ZERO_EMBED, entry.embeddingBase64)
        assertTrue(entry.cognitiveDistortions.isNotEmpty())
        assertEquals(1, entry.mentions.size)
    }

    // ── validateSeed ──────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun validateSeed_fewerThan30EntriesThrows() {
        seedManager.validateSeed(minimalSeed(entryCount = 29))
    }

    @Test
    fun validateSeed_exactly30EntriesPasses() {
        seedManager.validateSeed(minimalSeed(entryCount = 30))
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateSeed_unresolvedPlaceholderThrows() {
        val unknownId = UUID.randomUUID().toString()
        val seed = minimalSeed(entryCount = 30).copy(
            journalEntries = List(30) { i ->
                if (i == 0) testEntry(1).copy(content = "Something [PERSON_$unknownId] happened.")
                else testEntry(i + 1)
            }
        )
        seedManager.validateSeed(seed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateSeed_rawFirstNameInContentThrows() {
        val seed = minimalSeed(entryCount = 30).copy(
            journalEntries = List(30) { i ->
                if (i == 0) testEntry(1).copy(content = "Saw Alice at the pub.")
                else testEntry(i + 1)
            }
        )
        seedManager.validateSeed(seed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateSeed_rawFullNameInContentThrows() {
        val seed = minimalSeed(entryCount = 30).copy(
            journalEntries = List(30) { i ->
                if (i == 0) testEntry(1).copy(content = "Alice Doe was there.")
                else testEntry(i + 1)
            }
        )
        seedManager.validateSeed(seed)
    }

    @Test
    fun validateSeed_caseInsensitiveNameCheckCatchesUppercase() {
        var threw = false
        try {
            val seed = minimalSeed(entryCount = 30).copy(
                journalEntries = List(30) { i ->
                    if (i == 0) testEntry(1).copy(content = "ALICE was mentioned.")
                    else testEntry(i + 1)
                }
            )
            seedManager.validateSeed(seed)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Raw name in uppercase must be rejected", threw)
    }

    // ── Seed lifecycle ────────────────────────────────────────────────────────

    @Test
    fun seedPersona_inserts30JournalEntries() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        assertEquals(30, db.journalDao().getAllEntries().size)
    }

    @Test
    fun seedPersona_insertsPersonRecord() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        val persons = db.personDao().getPersons().first()
        assertEquals(1, persons.size)
    }

    @Test
    fun seedPersona_writesTransitEventsForEveryEntry() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        val events = db.transitEventDao().getAllEvents()
        assertEquals(30, events.size)
        assertTrue(events.all { it.providerName == "seed_injection" })
        assertTrue(events.all { it.operationType == "seed" })
    }

    @Test
    fun seedPersona_transitEventsIncludeMoodLogs() = runBlocking {
        val seed = minimalSeed(30).copy(moodLogs = List(3) { testMoodLog(it + 1) })
        seedManager.seedPersona(SeedPersona.HOLMES, seed)
        assertEquals(33, db.transitEventDao().getAllEvents().size)
    }

    @Test
    fun getSeededEntryCount_zeroBeforeSeed30After() = runBlocking {
        assertEquals(0, seedManager.getSeededEntryCount(SeedPersona.HOLMES))
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        assertEquals(30, seedManager.getSeededEntryCount(SeedPersona.HOLMES))
    }

    @Test
    fun clearPersona_removesAllJournalEntries() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        seedManager.clearPersona(SeedPersona.HOLMES)
        assertEquals(0, db.journalDao().getAllEntries().size)
    }

    @Test
    fun clearPersona_removesPersonRecord() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        seedManager.clearPersona(SeedPersona.HOLMES)
        val persons = db.personDao().getPersons().first()
        assertEquals(0, persons.size)
    }

    @Test
    fun clearPersona_removesTransitEvents() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        seedManager.clearPersona(SeedPersona.HOLMES)
        assertEquals(0, db.transitEventDao().getAllEvents().size)
    }

    @Test
    fun clearPersona_resetsManifestCountToZero() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        seedManager.clearPersona(SeedPersona.HOLMES)
        assertEquals(0, seedManager.getSeededEntryCount(SeedPersona.HOLMES))
    }

    @Test
    fun clearPersona_manifestAbsentCountIsZero() = runBlocking {
        // Seed, then wipe the manifest directly (simulates app reinstall / prefs clear).
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        context.getSharedPreferences("lattice_seed_manager", Context.MODE_PRIVATE)
            .edit().remove(SeedPersona.HOLMES.name).commit()

        // clearPersona falls back to re-parsing; since no asset is available in core-data
        // tests we verify the manifest-absent path by confirming getSeededEntryCount is 0.
        assertEquals(0, seedManager.getSeededEntryCount(SeedPersona.HOLMES))
    }

    @Test
    fun seedPersona_reseedAfterClearSucceeds() = runBlocking {
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        seedManager.clearPersona(SeedPersona.HOLMES)
        seedManager.seedPersona(SeedPersona.HOLMES, minimalSeed(30))
        assertEquals(30, db.journalDao().getAllEntries().size)
        assertEquals(30, seedManager.getSeededEntryCount(SeedPersona.HOLMES))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    companion object {
        private const val PERSON_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        private val ZERO_EMBED = "A".repeat(2048)

        private fun testPerson() = RawPerson(
            id = PERSON_ID,
            firstName = "Alice",
            lastName = "Doe",
            relationshipType = "FRIEND"
        )

        private fun testEntry(index: Int) = RawJournalEntry(
            id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
            timestamp = 1700000000000L + index * 1000L,
            content = "Entry $index alongside [PERSON_$PERSON_ID].",
            valence = -0.5f,
            arousal = 0.7f,
            moodLabel = "TENSE",
            embeddingBase64 = ZERO_EMBED,
            cognitiveDistortions = listOf("CATASTROPHIZING"),
            mentions = listOf(RawMention(personId = PERSON_ID))
        )

        private fun testMoodLog(index: Int) = RawMoodLog(
            id = "00000000-0000-0000-0000-1${index.toString().padStart(11, '0')}",
            timestamp = 1700100000000L + index * 1000L,
            valence = -0.3f,
            arousal = -0.4f,
            moodLabel = "FATIGUED"
        )

        fun minimalSeed(entryCount: Int = 30) = RawSeed(
            people = listOf(testPerson()),
            journalEntries = List(entryCount) { testEntry(it + 1) },
            moodLogs = emptyList()
        )

        /** Builds a minimal JSON string for parseSeed tests. */
        private fun minimalJson(entryCount: Int, moodLogCount: Int = 0): String {
            val entries = (1..entryCount).joinToString(",\n") { i ->
                """{"id":"00000000-0000-0000-0000-${i.toString().padStart(12, '0')}","timestamp":${1700000000000L + i * 1000},"content":"Entry $i alongside [PERSON_$PERSON_ID].","valence":-0.5,"arousal":0.7,"moodLabel":"TENSE","embeddingBase64":"$ZERO_EMBED","cognitiveDistortions":["CATASTROPHIZING"],"mentions":[{"personId":"$PERSON_ID","source":"MANUAL","status":"CONFIRMED"}]}"""
            }
            val moodLogs = (1..moodLogCount).joinToString(",\n") { i ->
                """{"id":"00000000-0000-0000-0000-1${i.toString().padStart(11, '0')}","timestamp":${1700100000000L + i * 1000},"valence":-0.3,"arousal":-0.4,"moodLabel":"FATIGUED"}"""
            }
            return """
                {
                  "people": [{"id":"$PERSON_ID","firstName":"Alice","lastName":"Doe","relationshipType":"FRIEND","vibeScore":0.5}],
                  "journalEntries": [$entries],
                  "moodLogs": [$moodLogs]
                }
            """.trimIndent()
        }
    }
}
