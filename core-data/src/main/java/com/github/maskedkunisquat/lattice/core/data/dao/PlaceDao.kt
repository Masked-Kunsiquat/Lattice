package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.maskedkunisquat.lattice.core.data.model.Place
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlace(place: Place)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("SELECT * FROM places ORDER BY name ASC")
    fun getAll(): Flow<List<Place>>

    @Query("SELECT * FROM places WHERE name LIKE '%' || REPLACE(REPLACE(REPLACE(:query, '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\' ORDER BY name ASC LIMIT 20")
    fun searchByName(query: String): Flow<List<Place>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getById(id: UUID): Place?

    @Query("SELECT * FROM places WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Place?
}
