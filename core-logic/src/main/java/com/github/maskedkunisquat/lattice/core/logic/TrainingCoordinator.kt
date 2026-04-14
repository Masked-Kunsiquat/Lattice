package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Note: [androidx.work.BackoffPolicy] is not compatible with [Constraints.requiresDeviceIdle]
     * on JobScheduler — failures are silently retried on the next 24-hour period instead.
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

    /**
     * Cancels any in-flight [EmbeddingTrainingWorker] run, waits until it is no longer
     * RUNNING (up to 5 s), then deletes all `affective_head_*.bin` weight files and
     * clears the manifest and warm-start guard via [AffectiveManifestStore.resetAll].
     *
     * This is the single authoritative reset path shared by
     * [com.github.maskedkunisquat.lattice.ui.SettingsViewModel] and the
     * `resetPersonalization` instrumented test, so both exercise the same
     * cancellation/waiting and WorkManager-related behaviour.
     */
    suspend fun resetPersonalization(context: Context) = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)

        // Poll until no RUNNING entry remains, or give up after 5 s
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val infos = wm.getWorkInfosForUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME).get()
            if (infos.none { it.state == WorkInfo.State.RUNNING }) break
            Thread.sleep(100)
        }

        context.filesDir.listFiles { f ->
            f.name.startsWith("affective_head_") && f.name.endsWith(".bin")
        }?.forEach { it.delete() }

        val prefs = context.getSharedPreferences(AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        AffectiveManifestStore.resetAll(prefs)
    }
}
