package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Local sovereignty audit trail entry.
 *
 * Written whenever user data is routed to a cloud LLM provider. The content
 * of the prompt is intentionally NOT stored here — only metadata that tells
 * the user *when* and *where* their data travelled, not *what* was sent.
 */
@Entity(
    tableName = "transit_events",
    indices = [Index(value = ["timestamp"])]
)
data class TransitEvent(
    @PrimaryKey val id: UUID,
    val timestamp: Long,
    val providerName: String,   // e.g. "cloud_claude", "cloud_gemini_pro"
    val operationType: String   // e.g. "reframe", "summarize"
)
