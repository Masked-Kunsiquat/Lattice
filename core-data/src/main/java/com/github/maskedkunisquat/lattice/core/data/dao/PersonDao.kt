package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.maskedkunisquat.lattice.core.data.model.Person
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person)

    @Update
    suspend fun updatePerson(person: Person)

    @Delete
    suspend fun deletePerson(person: Person)

    @Query("SELECT * FROM people")
    fun getPersons(): Flow<List<Person>>

    @Query("SELECT * FROM people WHERE id = :id")
    fun getPersonById(id: UUID): Flow<Person?>
}
