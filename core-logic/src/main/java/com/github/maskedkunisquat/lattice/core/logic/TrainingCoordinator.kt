package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around [WorkManager] that enqueues or cancels the periodic
 * [EmbeddingTrainingWorker] job.
 *
 * Call [scheduleIfNeeded] on app startup (after DAOs are initialized) and from
 * the settings screen when personalization is re-enabled. Call [cancelAll] when
 * the user disables personalization.
 */
class TrainingCoordinator {

    /**
     * Enqueues a 24-hour periodic [EmbeddingTrainingWorker] with
     * [ExistingPeriodicWorkPolicy.KEEP] — a no-op if the work is already enqueued
     * or running.
     *
     * Constraints: device must be charging, idle, and have adequate storage.
     * Backoff: exponential starting at 1 hour.
     */
    fun scheduleIfNeeded(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresStorageNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<EmbeddingTrainingWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EmbeddingTrainingWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Cancels all enqueued/running instances of [EmbeddingTrainingWorker].
     * Called when the user disables personalization in settings.
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)
    }
}
