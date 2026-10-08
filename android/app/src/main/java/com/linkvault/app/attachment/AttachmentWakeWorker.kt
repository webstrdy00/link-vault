package com.linkvault.app.attachment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await

class AttachmentWakeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ownerId = inputData.getString(AttachmentWorker.OWNER_ID_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        AttachmentWorker.enqueue(
            context = applicationContext,
            ownerId = ownerId,
            retryAttempt = inputData.getInt(AttachmentWorker.RETRY_ATTEMPT_KEY, 0),
        ).await()
        return Result.success()
    }
}
