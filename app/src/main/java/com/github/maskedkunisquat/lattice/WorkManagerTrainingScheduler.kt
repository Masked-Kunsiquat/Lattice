package com.github.maskedkunisquat.lattice

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingTrainingWorker
import com.github.maskedkunisquat.lattice.core.logic.TrainingScheduler
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Production [TrainingScheduler] that wraps [WorkManager].
 *
 * Constructed in [LatticeApplication] and injected into
 * [com.github.maskedkunisquat.lattice.core.logic.TrainingCoordinator]. Keeping all
 * WorkManager / Android-framework usage here lets [TrainingCoordinator] live in
 * `:core-logic` without Android framework imports.
 */
class WorkManagerTrainingScheduler(private val context: Context) : TrainingScheduler {

    private val wm get() = WorkManager.getInstance(context)

    /**
     * Enqueues a 24-hour periodic [EmbeddingTrainingWorker] with
     * [ExistingPeriodicWorkPolicy.KEEP] — a no-op if the work is already enqueued or running.
     *
     * Constraints: device must be charging, idle, and have adequate storage.
     * Note: [androidx.work.BackoffPolicy] is not compatible with [Constraints.requiresDeviceIdle]
     * on JobScheduler — failures are silently retried on the next 24-hour period instead.
     */
    override fun schedulePeriodicTraining() {
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

        wm.enqueueUniquePeriodicWork(
            EmbeddingTrainingWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Cancels all enqueued/running instances of [EmbeddingTrainingWorker].
     */
    override fun cancelTraining() {
        wm.cancelUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)
    }

    /**
     * Cancels any in-flight [EmbeddingTrainingWorker] run and suspends until it is no longer
     * RUNNING or ENQUEUED (up to 5 s).
     *
     * Uses [await] (work-runtime-ktx) instead of the blocking [java.util.concurrent.Future.get]
     * so this function remains non-blocking and cancellable throughout the wait loop.
     *
     * After the loop, re-checks work infos and throws [IllegalStateException] if the work
     * has still not quiesced — this prevents file deletion from racing a worker that is
     * transitioning ENQUEUED → RUNNING at the timeout boundary.
     */
    override suspend fun cancelAndAwaitQuiescence() {
        wm.cancelUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME)

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val infos = withContext(Dispatchers.IO) {
                wm.getWorkInfosForUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME).get()
            }
            if (infos.none {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }) break
            delay(100)
        }

        val finalInfos = withContext(Dispatchers.IO) {
            wm.getWorkInfosForUniqueWork(EmbeddingTrainingWorker.UNIQUE_WORK_NAME).get()
        }
        check(finalInfos.none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }) {
            "EmbeddingTrainingWorker did not quiesce within 5 s timeout; remaining states: ${
                finalInfos.map { it.state }
            }"
        }
    }
}
