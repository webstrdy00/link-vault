package com.linkvault.app.attachment

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.linkvault.app.LinkVaultApplication
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.auth.AccountAccess
import com.linkvault.app.storage.OutboxPolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AttachmentWorker internal constructor(
    appContext: Context,
    params: WorkerParameters,
    private val dependencies: AttachmentWorkerDependencies?,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, null)

    override suspend fun doWork(): Result {
        val ownerId = inputData.getString(OWNER_ID_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val dependencies = dependencies ?: run {
            val application = applicationContext as? LinkVaultApplication
                ?: return Result.failure()
            val client = application.accountClient
            object : AttachmentWorkerDependencies {
                override suspend fun restoreAccount() = client.restoreAccount()

                override fun sessionUserId() = client.sessionUserId()

                override suspend fun clearAcceptedDeletionLocalData(ownerId: String) =
                    client.clearAcceptedDeletionLocalData(ownerId).localDataCleared

                override suspend fun processBatch(ownerId: String) =
                    application.attachmentRepository.processBatch(ownerId)
            }
        }

        try {
            when (val access = dependencies.restoreAccount()) {
                is AccountAccess.Deleting -> {
                    if (access.receipt.ownerId != ownerId) return Result.success()
                    val cleared = dependencies.clearAcceptedDeletionLocalData(ownerId)
                    return if (cleared) Result.success() else scheduleRetry(ownerId)
                }
                is AccountAccess.Active -> Unit
                else -> return Result.success()
            }
        } catch (error: AccountClientException) {
            return if (error.retryable) scheduleRetry(ownerId, error.retryAfterSeconds) else Result.success()
        }
        if (dependencies.sessionUserId() != ownerId) return Result.success()

        return when (val batch = dependencies.processBatch(ownerId)) {
            AttachmentBatchResult.Complete -> Result.success()
            AttachmentBatchResult.Retry -> scheduleRetry(ownerId)
            is AttachmentBatchResult.ScheduleAt -> {
                enqueue(
                    context = applicationContext,
                    ownerId = ownerId,
                    delayMillis = (batch.timestamp - System.currentTimeMillis()).coerceAtLeast(0L),
                ).await()
                Result.success()
            }
        }
    }

    private suspend fun scheduleRetry(ownerId: String, retryAfterSeconds: Int? = null): Result {
        val retryAttempt = inputData.getInt(RETRY_ATTEMPT_KEY, 0).coerceIn(0, 16)
        enqueue(
            context = applicationContext,
            ownerId = ownerId,
            delayMillis = OutboxPolicy.retryDelayMillis(retryAttempt + 1, retryAfterSeconds),
            retryAttempt = retryAttempt + 1,
        ).await()
        // Backoff must not remain a prerequisite of the next explicit enqueue.
        // The repository still gates each row by its retry/lease/expiry timestamp.
        return Result.success()
    }

    companion object {
        internal const val OWNER_ID_KEY = "owner_id"
        internal const val RETRY_ATTEMPT_KEY = "retry_attempt"
        private const val UNIQUE_WORK_PREFIX = "link-vault-attachment-"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun enqueue(
            context: Context,
            ownerId: String,
            delayMillis: Long = 0L,
            retryAttempt: Int = 0,
        ): Operation {
            val manager = WorkManager.getInstance(context.applicationContext)
            val input = Data.Builder()
                .putString(OWNER_ID_KEY, ownerId)
                .putInt(RETRY_ATTEMPT_KEY, retryAttempt)
                .build()
            if (delayMillis > 0L) {
                val wake = OneTimeWorkRequestBuilder<AttachmentWakeWorker>()
                    .setInputData(input)
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .build()
                return manager.enqueueUniqueWork(wakeWorkName(ownerId), ExistingWorkPolicy.REPLACE, wake)
            }

            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setInputData(input)
                .setConstraints(constraints)
                .build()
            // Only ready work belongs to this chain. Appending preserves a running upload.
            return manager.enqueueUniqueWork(
                uniqueWorkName(ownerId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        internal fun cancel(context: Context, ownerId: String) {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(uniqueWorkName(ownerId))
            manager.cancelUniqueWork(wakeWorkName(ownerId))
        }

        internal fun wakeWorkName(ownerId: String): String = uniqueWorkName(ownerId) + "-wake"

        internal fun uniqueWorkName(ownerId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ownerId.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return UNIQUE_WORK_PREFIX + digest
        }
    }
}

internal interface AttachmentWorkerDependencies {
    suspend fun restoreAccount(): AccountAccess?
    fun sessionUserId(): String?
    suspend fun clearAcceptedDeletionLocalData(ownerId: String): Boolean
    suspend fun processBatch(ownerId: String): AttachmentBatchResult
}
