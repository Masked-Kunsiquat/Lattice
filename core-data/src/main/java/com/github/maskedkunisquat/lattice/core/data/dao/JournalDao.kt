package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
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

    @Query("SELECT * FROM journal_entries WHERE user_valence IS NOT NULL AND timestamp > :timestamp ORDER BY timestamp ASC")
    suspend fun getLabeledEntriesSince(timestamp: Long): List<JournalEntry>

    @Query("SELECT COUNT(*) FROM journal_entries WHERE user_valence IS NOT NULL AND timestamp > :timestamp")
    suspend fun countLabeledEntriesSince(timestamp: Long): Int
}
