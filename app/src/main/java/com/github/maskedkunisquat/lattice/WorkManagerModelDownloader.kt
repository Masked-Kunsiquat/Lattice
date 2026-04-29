package com.github.maskedkunisquat.lattice

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.maskedkunisquat.lattice.core.logic.ModelDownloader

/** Implements [ModelDownloader] by enqueueing a [ModelDownloadWorker] via WorkManager. */
class WorkManagerModelDownloader(context: Context) : ModelDownloader {
    private val context = context.applicationContext

    override fun enqueue(modelFile: String, url: String, sha256: String?) {
        val uniqueName = if (modelFile == LocalFallbackProvider.MODEL_FILE_CBT) {
            ModelDownloadWorker.UNIQUE_WORK_NAME_CBT
        } else {
            ModelDownloadWorker.UNIQUE_WORK_NAME
        }

        val inputData = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ASSET, modelFile)
            .putString(ModelDownloadWorker.KEY_URL, url)
            .also { if (sha256 != null) it.putString(ModelDownloadWorker.KEY_SHA256, sha256) }
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag(uniqueName)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
