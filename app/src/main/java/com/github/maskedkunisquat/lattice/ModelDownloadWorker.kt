package com.github.maskedkunisquat.lattice

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
import com.github.maskedkunisquat.lattice.core.logic.DownloadDependencies
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that downloads the LiteRT-LM model file from HuggingFace.
 *
 * ## doWork() steps
 * 1. If the file already exists: validate it by attempting [LocalFallbackProvider.initialize].
 *    Skip the download only if the engine reaches [ModelLoadState.READY]; otherwise fall through.
 * 2. Show foreground progress notification.
 * 3. Stream download to filesDir/<name>.tmp.
 * 4. Verify SHA-256 if [KEY_SHA256] was supplied; throw [PermanentDownloadException] on mismatch.
 * 5. Rename .tmp → final destination.
 * 6. Call [LocalFallbackProvider.initialize]; throw [PermanentDownloadException] if engine fails to load.
 *
 * Error classification:
 * - [CancellationException] — rethrown immediately (never retried).
 * - [PermanentDownloadException] — corrupt/wrong file, logged and returned as [Result.failure()].
 * - All other exceptions — transient network/IO errors, returned as [Result.retry()].
 */
class ModelDownloadWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    private val localFallbackProvider get() =
        (applicationContext as DownloadDependencies).localFallbackProvider

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(0)

    override suspend fun doWork(): Result {
        val modelAsset = inputData.getString(KEY_MODEL_ASSET) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val expectedSha256 = inputData.getString(KEY_SHA256) // optional
        val dest = File(applicationContext.filesDir, modelAsset)
        val tmp = File(applicationContext.filesDir, "$modelAsset.tmp")

        // If a file already exists, validate it by attempting initialization.
        // Only skip the download when the engine actually reaches READY.
        // initialize() deletes the file on failure, so we fall through to re-download.
        if (dest.exists() && dest.length() > 100_000_000L) {
            Log.i(TAG, "Model file exists (${dest.length()} bytes), validating…")
            withContext(Dispatchers.IO) { localFallbackProvider.initialize() }
            if (localFallbackProvider.modelLoadState.value == ModelLoadState.READY) {
                Log.i(TAG, "Existing model file is valid, skipping download.")
                return Result.success()
            }
            Log.w(TAG, "Existing model file failed validation, re-downloading.")
            // initialize() already deleted the file; fall through.
        }

        setForeground(buildForegroundInfo(0))

        return try {
            withContext(Dispatchers.IO) {
                downloadFile(url, tmp) { progress ->
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                    setForegroundAsync(buildForegroundInfo((progress * 100).toInt()))
                }

                if (tmp.length() < 100_000_000L) {
                    throw PermanentDownloadException(
                        "Downloaded file too small (${tmp.length()} bytes) — likely a 404 page."
                    )
                }

                if (expectedSha256 != null) {
                    val actual = sha256Hex(tmp)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        throw PermanentDownloadException(
                            "SHA-256 mismatch — expected $expectedSha256 but got $actual"
                        )
                    }
                    Log.i(TAG, "SHA-256 verified: $actual")
                }

                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }

                localFallbackProvider.initialize()

                if (localFallbackProvider.modelLoadState.value != ModelLoadState.READY) {
                    throw PermanentDownloadException(
                        "Engine init failed after download — ${localFallbackProvider.modelLoadState.value}"
                    )
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: PermanentDownloadException) {
            Log.e(TAG, "Permanent download failure — will not retry: ${e.message}")
            tmp.delete()
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Transient download error — scheduling retry", e)
            tmp.delete()
            Result.retry()
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        var location = url
        repeat(5) {
            val conn = URI(location).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Lattice-Android-Downloader")

            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val raw = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect $code with no Location header from $location")
                    val resolved = URI(location).resolve(raw)
                    val originalHost = URI(url).host
                    val resolvedHost = resolved.host ?: ""
                    // Trust redirects within huggingface.co and hf.co (covers the XetHub CDN
                    // at cas-bridge.xethub.hf.co that HuggingFace now uses for large files).
                    val trusted = resolved.scheme == "https" && (
                        resolvedHost == originalHost ||
                        resolvedHost.endsWith(".huggingface.co") ||
                        resolvedHost.endsWith(".hf.co")
                    )
                    if (!trusted) {
                        throw PermanentDownloadException(
                            "Redirect to untrusted location '${resolved}' rejected"
                        )
                    }
                    location = resolved.toString()
                    return@repeat
                }

                if (code in 400..499) throw PermanentDownloadException("HTTP $code from $location")
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
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects for $url")
    }

    private fun buildForegroundInfo(progress: Int): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading Local Model")
            .setContentText("$progress% complete")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        const val KEY_SHA256 = "sha256"
        const val KEY_PROGRESS = "progress"
        const val UNIQUE_WORK_NAME = "model_download"
    }
}

/** Thrown for permanent download failures that should not be retried (corrupt file, hash mismatch, engine load failure). */
class PermanentDownloadException(message: String) : Exception(message)
