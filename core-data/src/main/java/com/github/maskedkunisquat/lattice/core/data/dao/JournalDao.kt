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

    @Query("SELECT * FROM journal_entries")
    suspend fun getAllEntries(): List<JournalEntry>
}
