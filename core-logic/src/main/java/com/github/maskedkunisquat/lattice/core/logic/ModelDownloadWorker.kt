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
import kotlinx.coroutines.runBlocking

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

    override suspend fun doWork(): Result {
        val modelAsset = inputData.getString(KEY_MODEL_ASSET) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val dest = File(applicationContext.filesDir, modelAsset)
        val tmp = File(applicationContext.filesDir, "$modelAsset.tmp")

        if (dest.exists()) return Result.success()

        setForeground(buildForegroundInfo(0))

        return try {
            downloadFile(url, tmp) { progress ->
                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                setForegroundAsync(buildForegroundInfo((progress * 100).toInt()))
            }

            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            
            // Trigger initialization now that file is present
            localFallbackProvider.initialize()
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            tmp.delete()
            Result.retry()
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: suspend (Float) -> Unit) {
        var location = url
        repeat(5) { // Follow up to 5 redirects
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
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
                            val progressValue = totalRead.toFloat() / total
                            runBlocking { onProgress(progressValue) }
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
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status of large model file downloads"
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val CHANNEL_ID = "lattice_downloads"
        private const val NOTIFICATION_ID = 8001
        
        const val KEY_MODEL_ASSET = "model_asset"
        const val KEY_URL = "url"
        const val KEY_PROGRESS = "progress"
        const val UNIQUE_WORK_NAME = "model_download"
    }
}
