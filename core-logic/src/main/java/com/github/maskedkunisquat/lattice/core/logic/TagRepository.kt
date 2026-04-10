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
     * Case-sensitive to preserve the casing the user typed.
     */
    suspend fun insertTag(name: String): Tag {
        tagDao.getByName(name)?.let { return it }
        val tag = Tag(id = UUID.randomUUID(), name = name)
        tagDao.insertTag(tag)
        return tag
    }
}
