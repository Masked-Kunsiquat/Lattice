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
import kotlinx.coroutines.flow.first

/**
 * WorkManager worker that fine-tunes [AffectiveMlp] on accumulated user corrections.
 *
 * Resolved via `applicationContext as` [TrainingDependencies] — the Application must
 * implement that interface so the default WorkerFactory can instantiate this class.
 *
 * ## doWork() steps
 * 1. Capture `batchUpperBound = System.currentTimeMillis()` as the closed-window upper bound,
 *    then read [AffectiveManifest.lastTrainingTimestamp] as the lower bound.
 * 2. Count labeled entries since that timestamp; return immediately if < [MIN_LABELED_ENTRIES].
 * 3. Post a silent foreground notification (API 29+ exposes the foreground service type
 *    constant; API 31+ requires it to be declared in AndroidManifest.xml as well — see
 *    app manifest for `SystemForegroundService`).
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
    private val manifestDao get() = (applicationContext as TrainingDependencies).manifestDao

    override suspend fun doWork(): Result {
        // Step 1 — watermark: capture the upper bound before any I/O so entries written
        // during this run are deferred to the next cycle rather than silently lost.
        val batchUpperBound = System.currentTimeMillis()

        val prefs = applicationContext.getSharedPreferences(
            AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE
        )
        // Room is the authoritative manifest source; SharedPreferences is only a mirror for
        // AffectiveMlp.load() base-model-hash verification.
        val manifest = manifestDao.getManifest().first()?.toAffectiveManifest()
        val lastTimestamp = manifest?.lastTrainingTimestamp ?: 0L

        // Step 2 — gate: skip if insufficient labeled entries in the closed window
        val count = journalDao.countLabeledEntriesBetween(lastTimestamp, batchUpperBound)
        if (count < MIN_LABELED_ENTRIES) {
            Log.d(TAG, "Only $count labeled entries in [$lastTimestamp, $batchUpperBound] — below $MIN_LABELED_ENTRIES threshold, skipping")
            return Result.success()
        }

        // Step 3 — foreground notification
        setForeground(buildForegroundInfo())

        // Step 4 — fetch and construct samples (same closed window)
        val entries = journalDao.getLabeledEntriesBetween(lastTimestamp, batchUpperBound)
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
            // 3.6-b: pass isStopped as a per-epoch cancellation gate so WorkManager
            // cancellation is cooperative between epochs rather than fire-and-forget.
            val finalLoss = trainer.trainBatch(samples, shouldContinue = { !isStopped })
            Log.i(TAG, "Training complete: ${samples.size} samples, final loss=${"%.6f".format(finalLoss)}")

            // Check for cancellation before writing to disk
            if (isStopped) {
                savePartialWeights(mlp, manifest, prefs)
                return Result.success()
            }

            return persistCheckpoint(mlp, manifest, prefs, samples.size, batchUpperBound)
        } catch (e: CancellationException) {
            savePartialWeights(mlp, manifest, prefs)
            throw e
        }
    }

    // ── Checkpoint persistence ────────────────────────────────────────────────

    private suspend fun persistCheckpoint(
        mlp: AffectiveMlp,
        oldManifest: AffectiveManifest?,
        prefs: SharedPreferences,
        newSampleCount: Int,
        batchUpperBound: Long,
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
            lastTrainingTimestamp = batchUpperBound,
            baseLayerVersion      = oldManifest?.baseLayerVersion ?: AffectiveMlpInitializer.BASE_LAYER_VERSION,
        )
        // Step 8 — write manifest to Room (authoritative source of truth).
        // If this fails, abort and retry — the weight file is deleted to keep disk and DB in sync.
        try {
            manifestDao.upsertManifest(newManifest.toEntity())
        } catch (e: Exception) {
            Log.w(TAG, "Room manifest write failed — deleting newly saved weights at $newPath and retrying", e)
            weightFile.delete()
            return Result.retry()
        }
        Log.i(TAG, "Manifest updated: trainedOnCount=$newTotalCount, headPath=$newPath")

        // Mirror the persisted manifest to SharedPreferences so AffectiveMlp.load() can verify
        // the base-model hash. Room is the source of truth; a mirror failure is non-fatal because
        // the DAO write already succeeded.
        val mirrored = AffectiveManifestStore.write(prefs, newManifest)
        if (!mirrored) {
            Log.w(TAG, "SharedPreferences manifest mirror failed — AffectiveMlp.load() may use stale hash until next cycle")
        }

        // Step 9 — purge orphaned weight files
        val orphans = applicationContext.filesDir.listFiles { f ->
            f.name.startsWith("affective_head_") && f.name.endsWith(".bin") && f.name != newPath
        }
        val deleted = orphans?.size ?: 0
        orphans?.forEach { it.delete() }
        if (deleted > 0) Log.d(TAG, "Deleted $deleted orphan weight file(s)")

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
        // 3.6-i: FOREGROUND_SERVICE_TYPE_DATA_SYNC was added in API 29 (Q); the
        // API 31 requirement is for the manifest foregroundServiceType *attribute*
        // declaration, not for the Java API constant itself.
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
