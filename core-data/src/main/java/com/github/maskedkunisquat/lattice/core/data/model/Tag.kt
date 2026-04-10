package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"])]
)
data class Tag(
    @PrimaryKey val id: UUID,
    val name: String,
)
