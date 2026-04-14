package com.github.maskedkunisquat.lattice.core.logic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * WorkManager worker that fine-tunes [AffectiveMlp] on accumulated user corrections.
 *
 * Resolved via `applicationContext as` [TrainingDependencies] — the Application must
 * implement that interface so the default WorkerFactory can instantiate this class.
 *
 * ## doWork() steps
 * 1. Read [AffectiveManifest.lastTrainingTimestamp] from the persisted manifest.
 * 2. Count labeled entries since that timestamp; return immediately if < [MIN_LABELED_ENTRIES].
 * 3. Post a silent foreground notification (API 31+ requires the foreground service type
 *    to be declared in AndroidManifest.xml; see app manifest for `SystemForegroundService`).
 * 4. Fetch the full sample batch and build [TrainingSample] objects.
 * 5. Load existing MLP weights (falls back to random Xavier init if absent or stale).
 * 6. Run [AffectiveMlpTrainer.trainBatch] for [TRAIN_EPOCHS] passes.
 * 7. Save updated weights to `filesDir/affective_head_v1_c<count>.bin`.
 * 8. Write updated manifest (bump `trainedOnCount`, `lastTrainingTimestamp`, `headPath`).
 * 9. Delete orphaned `affective_head_*.bin` files that don't match the new `headPath`.
 * 10. Return [Result.success].
 *
 * On [CancellationException], partial weights (if training has completed) are persisted
 * under the current manifest's `headPath` before re-throwing so the next run can resume
 * from a better starting point than random init.
 */
class EmbeddingTrainingWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    private val journalDao get() = (applicationContext as TrainingDependencies).journalDao

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE
        )
        val manifest = AffectiveManifestStore.read(prefs)
        val lastTimestamp = manifest?.lastTrainingTimestamp ?: 0L

        // Step 2 — gate: skip if insufficient labeled entries
        val count = journalDao.countLabeledEntriesSince(lastTimestamp)
        if (count < MIN_LABELED_ENTRIES) {
            Log.d(TAG, "Only $count labeled entries since $lastTimestamp — below $MIN_LABELED_ENTRIES threshold, skipping")
            return Result.success()
        }

        // Step 3 — foreground notification
        setForeground(buildForegroundInfo())

        // Step 4 — fetch and construct samples
        val entries = journalDao.getLabeledEntriesSince(lastTimestamp)
        val samples = entries.mapNotNull { entry ->
            val v = entry.userValence ?: return@mapNotNull null
            val a = entry.userArousal ?: return@mapNotNull null
            if (entry.embedding.size != AffectiveMlp.IN) {
                Log.w(TAG, "Skipping entry ${entry.id}: embedding dim ${entry.embedding.size} != ${AffectiveMlp.IN}")
                return@mapNotNull null
            }
            TrainingSample(entry.embedding.copyOf(), v, a)
        }
        if (samples.isEmpty()) {
            Log.w(TAG, "No valid samples constructed from $count labeled entries — skipping")
            return Result.success()
        }

        // Step 5 — load weights (or Xavier init)
        val mlp = AffectiveMlp.load(applicationContext) ?: AffectiveMlp()

        // Steps 6–9: train, save, manifest, cleanup — with cancellation guard
        val trainer = AffectiveMlpTrainer(mlp, epochs = TRAIN_EPOCHS)
        try {
            val finalLoss = trainer.trainBatch(samples)
            Log.i(TAG, "Training complete: ${samples.size} samples, final loss=${"%.6f".format(finalLoss)}")

            // Check for cancellation before writing to disk
            if (isStopped) {
                savePartialWeights(mlp, manifest, prefs)
                return Result.success()
            }

            return persistCheckpoint(mlp, manifest, prefs, samples.size)
        } catch (e: CancellationException) {
            savePartialWeights(mlp, manifest, prefs)
            throw e
        }
    }

    // ── Checkpoint persistence ────────────────────────────────────────────────

    private fun persistCheckpoint(
        mlp: AffectiveMlp,
        oldManifest: AffectiveManifest?,
        prefs: SharedPreferences,
        newSampleCount: Int,
    ): Result {
        val newTotalCount = (oldManifest?.trainedOnCount ?: 0) + newSampleCount
        val newPath = "affective_head_v1_c${newTotalCount}.bin"
        val weightFile = applicationContext.filesDir.resolve(newPath)

        // Step 7 — save weights
        mlp.saveWeights(weightFile)

        // Step 8 — write manifest
        val modelHash = oldManifest?.baseModelHash?.takeIf { it.isNotEmpty() } ?: run {
            "sha256:${applicationContext.assets.open(AffectiveMlp.EMBEDDING_ASSET).use { sha256Hex(it) }}"
        }
        val newManifest = AffectiveManifest(
            schemaVersion         = AffectiveMlp.CURRENT_SCHEMA_VERSION,
            baseModelHash         = modelHash,
            headPath              = newPath,
            trainedOnCount        = newTotalCount,
            lastTrainingTimestamp = System.currentTimeMillis(),
            baseLayerVersion      = oldManifest?.baseLayerVersion ?: AffectiveMlpInitializer.BASE_LAYER_VERSION,
        )
        val wrote = AffectiveManifestStore.write(prefs, newManifest)
        if (!wrote) {
            Log.w(TAG, "Manifest write failed — deleting newly saved weights at $newPath and retrying")
            weightFile.delete()
            return Result.retry()
        }
        Log.i(TAG, "Manifest updated: trainedOnCount=$newTotalCount, headPath=$newPath")

        // Step 9 — purge orphaned weight files
        val orphans = applicationContext.filesDir.listFiles { f ->
            f.name.startsWith("affective_head_") && f.name.endsWith(".bin") && f.name != newPath
        }
        orphans?.forEach { it.delete() }
        if ((orphans?.size ?: 0) > 0) {
            Log.d(TAG, "Deleted ${orphans!!.size} orphan weight file(s)")
        }

        return Result.success()
    }

    /**
     * Saves [mlp]'s current weights over the path recorded in [manifest], if available.
     * Called on cancellation so the next run starts from a partially-trained head rather
     * than random Xavier init.
     */
    private fun savePartialWeights(
        mlp: AffectiveMlp,
        manifest: AffectiveManifest?,
        prefs: SharedPreferences,
    ) {
        val path = manifest?.headPath?.takeIf { it.isNotEmpty() } ?: return
        try {
            val weightFile = applicationContext.filesDir.resolve(path)
            mlp.saveWeights(weightFile)
            Log.i(TAG, "Partial weights saved to $path after cancellation")
        } catch (e: Exception) {
            Log.w(TAG, "Could not save partial weights on cancellation", e)
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun buildForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Personalizing Lattice\u2026")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setSilent(true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Personalization",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "On-device model personalization"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "TrainingWorker"

        /** WorkManager unique work name — used by [TrainingCoordinator] to avoid duplicate enqueues. */
        const val UNIQUE_WORK_NAME = "lattice_affective_training"

        /** Notification channel ID for the foreground service notification. */
        const val CHANNEL_ID = "lattice_personalization"

        private const val NOTIFICATION_ID = 7001

        /** Minimum number of new labeled entries required before a training run is triggered. */
        const val MIN_LABELED_ENTRIES = 30

        /** Number of epochs passed to [AffectiveMlpTrainer.trainBatch] per cycle. */
        const val TRAIN_EPOCHS = 10
    }
}
