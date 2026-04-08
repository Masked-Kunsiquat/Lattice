package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface MentionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMention(mention: Mention)

    @Update
    suspend fun updateMention(mention: Mention)

    @Delete
    suspend fun deleteMention(mention: Mention)

    @Query("SELECT * FROM mentions WHERE entryId = :entryId")
    fun getMentionsForEntry(entryId: UUID): Flow<List<Mention>>

    @Query("SELECT * FROM mentions WHERE personId = :personId")
    fun getMentionsForPerson(personId: UUID): Flow<List<Mention>>
}
