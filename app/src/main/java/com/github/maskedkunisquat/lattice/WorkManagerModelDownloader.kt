package com.github.maskedkunisquat.lattice

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.maskedkunisquat.lattice.core.logic.ModelDownloader

/** Implements [ModelDownloader] by enqueueing a [ModelDownloadWorker] via WorkManager. */
class WorkManagerModelDownloader(private val context: Context) : ModelDownloader {

    override fun enqueue(modelFile: String, url: String, sha256: String?) {
        val inputData = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ASSET, modelFile)
            .putString(ModelDownloadWorker.KEY_URL, url)
            .also { if (sha256 != null) it.putString(ModelDownloadWorker.KEY_SHA256, sha256) }
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag(ModelDownloadWorker.UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
