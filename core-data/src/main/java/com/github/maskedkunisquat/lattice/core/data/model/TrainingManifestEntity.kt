package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the single-row affective MLP checkpoint manifest.
 *
 * Written by `EmbeddingTrainingWorker` after each fine-tuning cycle and cleared by
 * `TrainingCoordinator.resetPersonalization`. The primary key is always 1 so
 * [androidx.room.OnConflictStrategy.REPLACE] on insert gives upsert semantics.
 *
 * `SettingsViewModel` collects `TrainingManifestDao.getManifest()` as a reactive
 * [kotlinx.coroutines.flow.Flow] to drive the Personalization section of the Settings screen.
 */
@Entity(tableName = "training_manifest")
data class TrainingManifestEntity(
    @PrimaryKey val id: Int = 1,
    val schemaVersion: Int,
    val baseModelHash: String,
    val headPath: String,
    val trainedOnCount: Int,
    val lastTrainingTimestamp: Long,
    val baseLayerVersion: String,
)
