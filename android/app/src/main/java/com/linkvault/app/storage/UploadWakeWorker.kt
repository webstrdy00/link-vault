package com.linkvault.app.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await

class UploadWakeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ownerId = inputData.getString(UploadWorker.OWNER_ID_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        UploadWorker.enqueue(
            context = applicationContext,
            ownerId = ownerId,
            retryAttempt = inputData.getInt(UploadWorker.RETRY_ATTEMPT_KEY, 0),
        ).await()
        return Result.success()
    }
}
