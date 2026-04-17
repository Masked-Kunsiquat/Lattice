package com.github.maskedkunisquat.lattice.core.logic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that downloads the Gemma 3 1B model tier from Hugging Face.
 *
 * ## doWork() steps
 * 1. Resolve model name based on hardware.
 * 2. Start foreground notification with progress.
 * 3. Stream download to [Context.filesDir]/model.tmp.
 * 4. Rename to final destination.
 * 5. Update LocalFallbackProvider state.
 */
class ModelDownloadWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    private val localFallbackProvider get() = (applicationContext as DownloadDependencies).localFallbackProvider

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(0)

    override suspend fun doWork(): Result {
        val modelAsset = inputData.getString(KEY_MODEL_ASSET) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val dest = File(applicationContext.filesDir, modelAsset)
        val tmp = File(applicationContext.filesDir, "$modelAsset.tmp")

        // If a large-enough file exists, try initialising it before skipping the download.
        // If init fails (stale/incompatible file), initialize() deletes the file and we fall
        // through to re-download. Only skip if the engine actually becomes READY.
        if (dest.exists() && dest.length() > 100_000_000L) {
            Log.i(TAG, "Model file exists (${dest.length()} bytes), validating…")
            withContext(Dispatchers.IO) { localFallbackProvider.initialize() }
            if (localFallbackProvider.modelLoadState.value == ModelLoadState.READY) {
                Log.i(TAG, "Existing model file is valid, skipping download.")
                return Result.success()
            }
            Log.w(TAG, "Existing model file failed validation, re-downloading.")
            // initialize() already deleted the file; fall through to download.
        }

        setForeground(buildForegroundInfo(0))

        return try {
            withContext(Dispatchers.IO) {
                downloadFile(url, tmp) { progress ->
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                    setForegroundAsync(buildForegroundInfo((progress * 100).toInt()))
                }

                // Final validation: Ensure the downloaded file isn't suspiciously small
                if (tmp.length() < 100_000_000L) {
                    throw IOException("Downloaded file is too small (${tmp.length()} bytes). Likely a 404 page.")
                }

                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }

                // Trigger initialization now that file is present
                localFallbackProvider.initialize()
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            tmp.delete()
            Result.retry()
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        var location = url
        repeat(5) { // Follow up to 5 redirects
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Lattice-Android-Downloader")

            val code = conn.responseCode
            if (code in 300..399) {
                location = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                return@repeat
            }

            if (code != 200) throw IOException("HTTP $code from $location")

            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastUpdate = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastUpdate > 500) {
                            onProgress(totalRead.toFloat() / total)
                            lastUpdate = now
                        }
                    }
                }
            }
            return
        }
        throw IOException("Too many redirects")
    }

    private fun buildForegroundInfo(progress: Int): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading Local Model")
            .setContentText("$progress% complete")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
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
                "Model Downloads",
                // IMPORTANCE_DEFAULT so the status bar icon is visible on OEM skins (e.g. OneUI 7).
                // IMPORTANCE_LOW hides the icon on Samsung devices while keeping the notification in
                // the shade. Sound is disabled explicitly so this behaves like IMPORTANCE_LOW in
                // terms of audio while keeping the icon visible.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Progress of large model file downloads"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val CHANNEL_ID = "lattice_model_download_v2"
        private const val NOTIFICATION_ID = 8001
        
        const val KEY_MODEL_ASSET = "model_asset"
        const val KEY_URL = "url"
        const val KEY_PROGRESS = "progress"
        const val UNIQUE_WORK_NAME = "model_download"
    }
}
