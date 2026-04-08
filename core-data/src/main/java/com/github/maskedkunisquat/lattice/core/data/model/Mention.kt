package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID

@Entity(
    tableName = "mentions",
    primaryKeys = ["entryId", "personId"],
    foreignKeys = [
        ForeignKey(
            entity = JournalEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("entryId"),
        Index("personId")
    ]
)
data class Mention(
    val entryId: UUID,
    val personId: UUID,
    val source: MentionSource,
    val status: MentionStatus
)
