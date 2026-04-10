package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.TagDao
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import kotlinx.coroutines.flow.first
import java.util.UUID

class TagRepository(private val tagDao: TagDao) {

    suspend fun searchTags(query: String): List<Tag> =
        tagDao.searchByName(query).first()

    /**
     * Returns an existing tag with [name] (exact match) or inserts and returns a new one.
     * Re-queries after insert so a concurrent insert that was silently ignored by the
     * UNIQUE+IGNORE constraint still returns the canonical row.
     */
    suspend fun insertTag(name: String): Tag {
        tagDao.getByName(name)?.let { return it }
        tagDao.insertTag(Tag(id = UUID.randomUUID(), name = name))
        return checkNotNull(tagDao.getByName(name)) { "insertTag: no row for name=$name after insert" }
    }
}
