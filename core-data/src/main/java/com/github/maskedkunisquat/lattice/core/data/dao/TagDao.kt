package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE name LIKE '%' || REPLACE(REPLACE(REPLACE(:query, '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\' ORDER BY name ASC LIMIT 20")
    fun searchByName(query: String): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: UUID): Tag?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Tag?
}
