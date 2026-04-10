package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.model.Place
import kotlinx.coroutines.flow.first
import java.util.UUID

class PlaceRepository(private val placeDao: PlaceDao) {

    suspend fun searchPlaces(query: String): List<Place> =
        placeDao.searchByName(query).first()

    /**
     * Returns an existing place with [name] (exact match) or inserts and returns a new one.
     * Re-queries after insert so a concurrent insert ignored by the UNIQUE+IGNORE constraint
     * still returns the canonical row.
     */
    suspend fun insertPlace(name: String): Place {
        placeDao.getByName(name)?.let { return it }
        placeDao.insertPlace(Place(id = UUID.randomUUID(), name = name))
        return checkNotNull(placeDao.getByName(name)) { "insertPlace: no row for name=$name after insert" }
    }
}
