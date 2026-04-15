package com.github.maskedkunisquat.lattice.core.logic

/**
 * Abstracts the WorkManager-specific scheduling operations needed by [TrainingCoordinator].
 *
 * The production implementation (`WorkManagerTrainingScheduler` in `:app`) wraps
 * [androidx.work.WorkManager]; test implementations can substitute lightweight fakes
 * without pulling the Android/WorkManager framework into JVM unit tests.
 */
interface TrainingScheduler {

    /** Enqueues the periodic [EmbeddingTrainingWorker] job. No-op if already enqueued. */
    fun schedulePeriodicTraining()

    /** Cancels all enqueued/running instances of [EmbeddingTrainingWorker]. */
    fun cancelTraining()

    /**
     * Cancels any in-flight [EmbeddingTrainingWorker] run and suspends until neither
     * a RUNNING nor an ENQUEUED entry remains (up to 5 s).
     *
     * @throws IllegalStateException if the work has not quiesced within the timeout.
     */
    suspend fun cancelAndAwaitQuiescence()
}
