package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "people",
    indices = [
        Index(value = ["firstName"]),
        Index(value = ["nickname"])
    ]
)
data class Person(
    @PrimaryKey val id: UUID,
    val firstName: String,
    val lastName: String?,
    val nickname: String?,
    val relationshipType: RelationshipType,
    val vibeScore: Float = 0f,
    val isFavorite: Boolean = false
)
