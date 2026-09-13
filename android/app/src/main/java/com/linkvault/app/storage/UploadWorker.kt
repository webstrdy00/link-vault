package com.linkvault.app.storage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.linkvault.app.LinkVaultApplication
import com.linkvault.app.auth.AccountClientException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ownerId = inputData.getString(OWNER_ID_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val application = applicationContext as? LinkVaultApplication
            ?: return Result.failure()
        val client = application.accountClient

        try {
            client.restoreAccount()
        } catch (error: AccountClientException) {
            return if (error.retryable) Result.retry() else Result.success()
        }
        if (client.sessionUserId() != ownerId) return Result.success()

        return when (val batchResult = application.outboxRepository.processBatch(ownerId)) {
            BatchResult.Complete -> Result.success()
            BatchResult.Retry -> Result.retry()
            is BatchResult.ScheduleAt -> {
                enqueue(
                    context = applicationContext,
                    ownerId = ownerId,
                    delayMillis = (batchResult.timestamp - System.currentTimeMillis()).coerceAtLeast(0L),
                )
                Result.success()
            }
        }
    }

    companion object {
        private const val OWNER_ID_KEY = "owner_id"
        private const val UNIQUE_WORK_PREFIX = "link-vault-upload-"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun enqueue(context: Context, ownerId: String, delayMillis: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(OWNER_ID_KEY, ownerId).build())
                .setConstraints(constraints)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    OutboxPolicy.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).beginUniqueWork(
                uniqueWorkName(ownerId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            ).enqueue()
        }

        internal fun cancel(context: Context, ownerId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(ownerId))
        }

        private fun uniqueWorkName(ownerId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ownerId.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return UNIQUE_WORK_PREFIX + digest
        }
    }
}
