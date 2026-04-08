package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface PhoneNumberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoneNumber(phoneNumber: PhoneNumber)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoneNumbers(phoneNumbers: List<PhoneNumber>)

    @Update
    suspend fun updatePhoneNumber(phoneNumber: PhoneNumber)

    @Delete
    suspend fun deletePhoneNumber(phoneNumber: PhoneNumber)

    @Query("SELECT * FROM phone_numbers WHERE personId = :personId")
    fun getPhoneNumbersForPerson(personId: UUID): Flow<List<PhoneNumber>>

    @Query("SELECT * FROM phone_numbers")
    fun getAllPhoneNumbers(): Flow<List<PhoneNumber>>

    @Query("DELETE FROM phone_numbers WHERE personId = :personId")
    suspend fun deleteByPersonId(personId: UUID)
}
