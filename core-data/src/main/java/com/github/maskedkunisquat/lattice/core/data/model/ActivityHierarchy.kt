package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A single entry in the user's Behavioral Activation (BA) activity hierarchy.
 *
 * Activities are ranked by [difficulty] so that the reframing engine can suggest
 * the most accessible first step when the client is in a low-arousal negative state
 * (Quadrant III — depressed / fatigued).
 */
@Entity(tableName = "activity_hierarchy")
data class ActivityHierarchy(
    @PrimaryKey val id: UUID,
    val taskName: String,
    /** Subjective difficulty rating 0 (trivially easy) – 10 (very hard). */
    val difficulty: Int,
    /** Broad value category the activity serves, e.g. "connection", "health", "creativity". */
    val valueCategory: String,
)
