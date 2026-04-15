package com.github.maskedkunisquat.lattice.core.logic

import android.content.SharedPreferences
import com.github.maskedkunisquat.lattice.core.data.dao.TrainingManifestDao
import java.io.File

/**
 * Thin orchestrator that coordinates the periodic [EmbeddingTrainingWorker] lifecycle and
 * exposes the single authoritative reset path shared by
 * [com.github.maskedkunisquat.lattice.ui.SettingsViewModel] and instrumented tests.
 *
 * All WorkManager / Android-framework interaction is delegated to [scheduler] so this class
 * carries no Android framework imports and is testable on the desktop JVM.
 *
 * @param scheduler      Schedules, cancels, and awaits quiescence of the training worker.
 * @param weightFilesDir Directory where `affective_head_*.bin` files are stored
 *                       (production: [android.content.Context.filesDir]).
 * @param prefs          SharedPreferences used by [AffectiveManifestStore] for the JSON
 *                       manifest and the [AffectiveMlpInitializer] warm-start guard.
 * @param manifestDao    Room DAO that mirrors the manifest for reactive UI updates.
 */
class TrainingCoordinator(
    private val scheduler: TrainingScheduler,
    private val weightFilesDir: File,
    private val prefs: SharedPreferences,
    private val manifestDao: TrainingManifestDao,
) {

    /**
     * Enqueues a 24-hour periodic [EmbeddingTrainingWorker]; no-op if already enqueued.
     */
    fun scheduleIfNeeded() = scheduler.schedulePeriodicTraining()

    /**
     * Cancels all enqueued/running instances of [EmbeddingTrainingWorker].
     */
    fun cancelAll() = scheduler.cancelTraining()

    /**
     * Cancels any in-flight [EmbeddingTrainingWorker] run, waits until it has quiesced,
     * then deletes all `affective_head_*.bin` weight files and clears both the
     * SharedPreferences manifest/warm-start guard (via [AffectiveManifestStore.resetAll])
     * and the Room manifest row (via [manifestDao]).
     *
     * This is the single authoritative reset path shared by
     * [com.github.maskedkunisquat.lattice.ui.SettingsViewModel] and the
     * `resetPersonalization` instrumented test.
     */
    suspend fun resetPersonalization() {
        scheduler.cancelAndAwaitQuiescence()

        weightFilesDir.listFiles { f ->
            f.name.startsWith("affective_head_") && f.name.endsWith(".bin")
        }?.forEach { it.delete() }

        AffectiveManifestStore.resetAll(prefs)
        manifestDao.clearManifest()
    }
}
