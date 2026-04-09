package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy

@Dao
interface ActivityHierarchyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(activity: ActivityHierarchy)

    /**
     * Returns all activities whose [ActivityHierarchy.difficulty] is at most [max],
     * ordered ascending by difficulty so the easiest step appears first.
     */
    @Query("SELECT * FROM activity_hierarchy WHERE difficulty <= :max ORDER BY difficulty ASC")
    suspend fun getActivitiesByMaxDifficulty(max: Int): List<ActivityHierarchy>
}
