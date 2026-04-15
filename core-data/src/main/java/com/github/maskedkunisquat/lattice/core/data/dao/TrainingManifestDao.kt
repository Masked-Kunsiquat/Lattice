package com.github.maskedkunisquat.lattice.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.maskedkunisquat.lattice.core.data.model.TrainingManifestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingManifestDao {

    /**
     * Reactive stream of the checkpoint manifest; emits `null` when the table is empty
     * (i.e., before the first training run or after [clearManifest]).
     */
    @Query("SELECT * FROM training_manifest WHERE id = 1")
    fun getManifest(): Flow<TrainingManifestEntity?>

    /**
     * Inserts or replaces the singleton manifest row (primary key is always 1).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManifest(entity: TrainingManifestEntity)

    /**
     * Removes the manifest row. Called by `TrainingCoordinator.resetPersonalization` so the
     * Settings screen reactively reflects the cleared state.
     */
    @Query("DELETE FROM training_manifest")
    suspend fun clearManifest()
}
