package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntryRef
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: JournalEntry)

    @Update
    suspend fun updateEntry(entry: JournalEntry)

    @Delete
    suspend fun deleteEntry(entry: JournalEntry)

    @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC")
    fun getEntries(): Flow<List<JournalEntry>>

    @Query("SELECT id, tagIds, placeIds FROM journal_entries")
    fun getEntryRefs(): Flow<List<JournalEntryRef>>

    @Query("""
        SELECT * FROM journal_entries
        WHERE id IN (SELECT entryId FROM mentions WHERE personId = :personId)
        ORDER BY timestamp DESC
    """)
    fun getEntriesForPerson(personId: UUID): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    fun getEntryById(id: UUID): Flow<JournalEntry?>

    @Query("SELECT * FROM journal_entries ORDER BY timestamp ASC")
    suspend fun getAllEntries(): List<JournalEntry>

    @Query("UPDATE journal_entries SET reframedContent = :content WHERE id = :entryId")
    suspend fun updateReframedContent(entryId: String, content: String)

    @Query("SELECT * FROM journal_entries WHERE valence > :minValence ORDER BY valence DESC")
    suspend fun getEntriesWithMinValence(minValence: Float): List<JournalEntry>

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun deleteEntryById(id: UUID)

    @Query("SELECT * FROM journal_entries WHERE user_valence IS NOT NULL AND user_arousal IS NOT NULL AND timestamp > :fromTimestamp AND timestamp <= :toTimestamp ORDER BY timestamp ASC")
    suspend fun getLabeledEntriesBetween(fromTimestamp: Long, toTimestamp: Long): List<JournalEntry>

    @Query("SELECT COUNT(*) FROM journal_entries WHERE user_valence IS NOT NULL AND user_arousal IS NOT NULL AND timestamp > :fromTimestamp AND timestamp <= :toTimestamp")
    suspend fun countLabeledEntriesBetween(fromTimestamp: Long, toTimestamp: Long): Int
}
