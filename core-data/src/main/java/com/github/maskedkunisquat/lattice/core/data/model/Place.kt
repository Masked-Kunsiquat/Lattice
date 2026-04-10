package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "places",
    indices = [Index(value = ["name"])]
)
data class Place(
    @PrimaryKey val id: UUID,
    val name: String,
)
