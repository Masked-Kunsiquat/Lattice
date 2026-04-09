package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface TransitEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: TransitEvent)

    /** One-shot snapshot for export. */
    @Query("SELECT * FROM transit_events ORDER BY timestamp DESC")
    suspend fun getAllEvents(): List<TransitEvent>

    /** Reactive stream for the audit log UI. */
    @Query("SELECT * FROM transit_events ORDER BY timestamp DESC")
    fun getEventsFlow(): Flow<List<TransitEvent>>

    @Query("DELETE FROM transit_events WHERE entryId = :entryId")
    suspend fun deleteEventsForEntry(entryId: String)
}
