package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao

/**
 * Implemented by [android.app.Application] so that [EmbeddingTrainingWorker] can
 * resolve its DAO via a [android.content.Context] cast — no custom WorkerFactory needed.
 *
 * Usage in the worker:
 * ```kotlin
 * private val journalDao: JournalDao
 *     get() = (applicationContext as TrainingDependencies).journalDao
 * ```
 */
interface TrainingDependencies {
    val journalDao: JournalDao
}
