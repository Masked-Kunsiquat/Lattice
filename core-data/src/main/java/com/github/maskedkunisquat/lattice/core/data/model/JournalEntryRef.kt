package com.github.maskedkunisquat.lattice.core.data.model

import java.util.UUID

/**
 * Lightweight projection of [JournalEntry] containing only the fields needed for
 * count-based UI operations (e.g. "how many entries are tagged with X?").
 * Does not include content, embedding, or any other sensitive text.
 */
data class JournalEntryRef(
    val id: UUID,
    val tagIds: List<UUID>,
    val placeIds: List<UUID>,
)
