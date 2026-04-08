package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "phone_numbers",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class PhoneNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: UUID,
    val rawNumber: String,
    val normalizedNumber: String
)
