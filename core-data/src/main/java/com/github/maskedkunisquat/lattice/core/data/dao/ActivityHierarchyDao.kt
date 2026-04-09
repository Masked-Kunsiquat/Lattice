package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityHierarchyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(activity: ActivityHierarchy)

    @Update
    suspend fun updateActivity(activity: ActivityHierarchy)

    @Delete
    suspend fun deleteActivity(activity: ActivityHierarchy)

    /**
     * Reactive stream of all activities ordered by difficulty ascending.
     * Used by the Settings screen to display and reactively update the activity list.
     */
    @Query("SELECT * FROM activity_hierarchy ORDER BY difficulty ASC")
    fun getAllActivities(): Flow<List<ActivityHierarchy>>

    /**
     * Returns all activities whose [ActivityHierarchy.difficulty] is at most [max],
     * ordered ascending by difficulty so the easiest step appears first.
     */
    @Query("SELECT * FROM activity_hierarchy WHERE difficulty <= :max ORDER BY difficulty ASC")
    suspend fun getActivitiesByMaxDifficulty(max: Int): List<ActivityHierarchy>
}
