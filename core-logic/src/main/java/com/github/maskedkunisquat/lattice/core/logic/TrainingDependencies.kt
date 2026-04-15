package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.TrainingManifestDao

/**
 * Implemented by [android.app.Application] so that [EmbeddingTrainingWorker] can
 * resolve its DAOs via a [android.content.Context] cast — no custom WorkerFactory needed.
 *
 * Usage in the worker:
 * ```kotlin
 * private val journalDao: JournalDao
 *     get() = (applicationContext as TrainingDependencies).journalDao
 * private val manifestDao: TrainingManifestDao
 *     get() = (applicationContext as TrainingDependencies).manifestDao
 * ```
 */
interface TrainingDependencies {
    val journalDao: JournalDao
    val manifestDao: TrainingManifestDao
}
