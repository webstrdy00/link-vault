package com.linkvault.app.storage

import android.content.Context
import androidx.room.withTransaction
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OutboxRepository(
    appContext: Context,
    private val client: AccountClient,
    private val database: VaultDatabase = VaultDatabase.create(appContext),
) {
    private val applicationContext = appContext.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val outbox = database.outboxDao()
    private val cache = database.cachedItemDao()
    private val drafts = database.pendingInputDao()

    suspend fun enqueue(
        ownerId: String,
        requestId: String,
        method: String,
        path: String,
        payloadJson: String,
    ) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        val normalizedRequestId = requestId.requireCanonicalUuid()
        val normalizedMethod = method.trim().uppercase()
        require(normalizedMethod in MUTATING_METHODS) { "Only mutating requests belong in the outbox." }
        require(path == "/items" || path.startsWith("/items/")) { "Unsupported outbox path." }
        json.decodeFromString<JsonObject>(payloadJson)

        val now = System.currentTimeMillis()
        val entry = OutboxEntry(
            requestId = normalizedRequestId,
            ownerId = normalizedOwnerId,
            method = normalizedMethod,
            path = path,
            payloadJson = payloadJson,
            state = OutboxState.PENDING,
            attemptCount = 0,
            createdAt = now,
            expiresAt = OutboxPolicy.expiresAt(now),
            nextAttemptAt = now,
        )
        client.withSessionOwner(normalizedOwnerId) {
            outbox.insertImmutable(entry)
        }
        UploadWorker.enqueue(applicationContext, normalizedOwnerId)
    }

    fun observeOutbox(ownerId: String): Flow<List<OutboxEntry>> =
        outbox.observe(ownerId.requireOwnerId())

    suspend fun acknowledge(ownerId: String, requestId: String) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        client.withSessionOwner(normalizedOwnerId) {
            database.withTransaction {
                val entry = outbox.findByRequestId(requestId)
                if (entry?.ownerId != normalizedOwnerId || entry.state != OutboxState.SAVED) return@withTransaction
                if (outbox.acknowledge(normalizedOwnerId, requestId) == 1) {
                    entry.pendingCaptureText()?.let { sharedText ->
                        drafts.deleteMatchingCapture(sharedText, entry.createdAt)
                    }
                }
            }
        }
    }

    suspend fun discard(ownerId: String, requestId: String) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        client.withSessionOwner(normalizedOwnerId) {
            outbox.discard(normalizedOwnerId, requestId)
        }
    }

    suspend fun retry(ownerId: String, requestId: String) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        client.withSessionOwner(normalizedOwnerId) {
            outbox.retry(normalizedOwnerId, requestId, System.currentTimeMillis())
        }
        UploadWorker.enqueue(applicationContext, normalizedOwnerId)
    }

    suspend fun pendingCount(ownerId: String): Int {
        val normalizedOwnerId = ownerId.requireOwnerId()
        return client.withSessionOwner(normalizedOwnerId) {
            outbox.pendingCount(normalizedOwnerId)
        }
    }

    suspend fun clearOwner(ownerId: String) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        UploadWorker.cancel(applicationContext, normalizedOwnerId)
        database.clearOwner(normalizedOwnerId)
    }

    suspend fun clearAllOwners() {
        outbox.ownerIds().forEach { ownerId ->
            UploadWorker.cancel(applicationContext, ownerId)
        }
        database.clearAllOwners()
    }

    suspend fun retainedCount(): Int = outbox.retainedCount()

    suspend fun resumeOwner(ownerId: String) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        client.withSessionOwner(normalizedOwnerId) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                outbox.expireAutomaticRequests(normalizedOwnerId, now)
                outbox.resumeWaitingLogin(normalizedOwnerId, now)
            }
        }
        UploadWorker.enqueue(applicationContext, normalizedOwnerId)
    }

    suspend fun cacheList(ownerId: String, items: List<JsonObject>, replace: Boolean) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        val fetchedAt = System.currentTimeMillis()
        val rows = items.map { item ->
            item.toCachedItem(normalizedOwnerId, isDetail = false, fetchedAt = fetchedAt)
        }
        client.withSessionOwner(normalizedOwnerId) {
            cache.cacheList(normalizedOwnerId, rows, replace)
        }
    }

    fun cachedItems(ownerId: String): Flow<List<CachedItem>> =
        cache.observeList(ownerId.requireOwnerId())

    suspend fun cacheDetail(ownerId: String, item: JsonObject) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        val row = item.toCachedItem(
            ownerId = normalizedOwnerId,
            isDetail = true,
            fetchedAt = System.currentTimeMillis(),
        )
        client.withSessionOwner(normalizedOwnerId) {
            cache.upsert(row)
        }
    }

    suspend fun readCachedDetail(ownerId: String, itemId: String): CachedItem? =
        cache.readDetail(ownerId.requireOwnerId(), itemId)

    suspend fun saveDraft(localId: String, text: String, selectedUrl: String?) {
        require(localId.isNotBlank()) { "Draft ID must not be blank." }
        val now = System.currentTimeMillis()
        drafts.saveDraft(
            PendingInput(
                localId = localId,
                text = text,
                selectedUrl = selectedUrl,
                createdAt = now,
                expiresAt = OutboxPolicy.expiresAt(now),
            ),
        )
    }

    suspend fun readDraft(localId: String): PendingInput? {
        require(localId.isNotBlank()) { "Draft ID must not be blank." }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            drafts.readActive(localId, now) ?: run {
                drafts.delete(localId)
                null
            }
        }
    }

    suspend fun deleteDraft(localId: String) {
        require(localId.isNotBlank()) { "Draft ID must not be blank." }
        drafts.delete(localId)
    }

    suspend fun cleanupDrafts() {
        drafts.deleteExpired(System.currentTimeMillis())
    }

    internal suspend fun processBatch(ownerId: String): BatchResult {
        val normalizedOwnerId = ownerId.requireOwnerId()
        try {
            client.withSessionOwner(normalizedOwnerId) {
                val now = System.currentTimeMillis()
                database.withTransaction {
                    outbox.expireAutomaticRequests(normalizedOwnerId, now)
                    outbox.resumeWaitingLogin(normalizedOwnerId, now)
                }
            }
        } catch (error: AccountClientException) {
            return if (error.retryable) BatchResult.Retry else BatchResult.Complete
        }

        repeat(OutboxPolicy.MAX_BATCH_SIZE) {
            val now = System.currentTimeMillis()
            val claimed = try {
                client.withSessionOwner(normalizedOwnerId) {
                    outbox.claimNext(
                        ownerId = normalizedOwnerId,
                        now = now,
                        leaseUntil = OutboxPolicy.leaseUntil(now),
                    )
                }
            } catch (error: AccountClientException) {
                return if (error.retryable) BatchResult.Retry else BatchResult.Complete
            }
            if (claimed == null) return nextBatchResult(normalizedOwnerId)

            when (val outcome = uploadClaimed(claimed)) {
                UploadOutcome.Continue -> Unit
                UploadOutcome.Retry -> return BatchResult.Retry
                UploadOutcome.Pause -> return BatchResult.Complete
            }
        }
        return nextBatchResult(normalizedOwnerId)
    }

    private suspend fun uploadClaimed(entry: OutboxEntry): UploadOutcome {
        val leaseUntil = checkNotNull(entry.leaseUntil)
        if (System.currentTimeMillis() >= entry.expiresAt) {
            return completeFailure(
                entry = entry,
                leaseUntil = leaseUntil,
                retryable = false,
                errorCode = "REQUEST_EXPIRED",
                errorMessage = "Automatic retry expired. User confirmation is required.",
                retryAfterSeconds = null,
            )
        }
        return try {
            val response = client.libraryRequest(
                expectedOwnerId = entry.ownerId,
                path = entry.path,
                method = entry.method,
                body = entry.payloadJson,
                requestId = entry.requestId,
            )
            val cacheRows = response.cacheRowsFor(entry, System.currentTimeMillis())
            val persisted = client.withSessionOwner(entry.ownerId) {
                database.withTransaction {
                    val completed = outbox.completeSaved(
                        ownerId = entry.ownerId,
                        requestId = entry.requestId,
                        leaseUntil = leaseUntil,
                        resultJson = response.toString(),
                    )
                    if (completed == 1) cacheRows.forEach { cache.upsert(it) }
                    completed == 1
                }
            }
            if (persisted) UploadOutcome.Continue else UploadOutcome.Pause
        } catch (error: CancellationException) {
            throw error
        } catch (error: AccountClientException) {
            completeFailure(entry, leaseUntil, error)
        } catch (error: InvalidServerResponseException) {
            completeFailure(
                entry = entry,
                leaseUntil = leaseUntil,
                retryable = true,
                errorCode = "INVALID_RESPONSE",
                errorMessage = error.message,
                retryAfterSeconds = null,
            )
        }
    }

    private suspend fun completeFailure(
        entry: OutboxEntry,
        leaseUntil: Long,
        error: AccountClientException,
    ): UploadOutcome = completeFailure(
        entry = entry,
        leaseUntil = leaseUntil,
        retryable = error.retryable,
        errorCode = error.code,
        errorMessage = error.message,
        retryAfterSeconds = error.retryAfterSeconds,
    )

    private suspend fun completeFailure(
        entry: OutboxEntry,
        leaseUntil: Long,
        retryable: Boolean,
        errorCode: String?,
        errorMessage: String?,
        retryAfterSeconds: Int?,
    ): UploadOutcome {
        val now = System.currentTimeMillis()
        return when (
            val action = OutboxPolicy.classifyFailure(
                now = now,
                expiresAt = entry.expiresAt,
                attemptCount = entry.attemptCount,
                retryable = retryable,
                errorCode = errorCode,
                retryAfterSeconds = retryAfterSeconds,
            )
        ) {
            FailureAction.WaitForLogin -> {
                outbox.completeFailure(
                    entry.ownerId,
                    entry.requestId,
                    leaseUntil,
                    OutboxState.WAITING_LOGIN,
                    now,
                    errorCode,
                    errorMessage,
                )
                UploadOutcome.Pause
            }
            FailureAction.Conflict -> {
                persistBoundFailure(
                    entry,
                    leaseUntil,
                    OutboxState.CONFLICT,
                    now,
                    errorCode,
                    errorMessage,
                    UploadOutcome.Continue,
                )
            }
            is FailureAction.Retry -> {
                persistBoundFailure(
                    entry,
                    leaseUntil,
                    OutboxState.RETRY,
                    action.nextAttemptAt,
                    errorCode,
                    errorMessage,
                    UploadOutcome.Retry,
                )
            }
            FailureAction.Fail -> {
                persistBoundFailure(
                    entry,
                    leaseUntil,
                    OutboxState.FAILED,
                    now,
                    errorCode,
                    errorMessage,
                    UploadOutcome.Continue,
                )
            }
            FailureAction.Expire -> {
                persistBoundFailure(
                    entry,
                    leaseUntil,
                    OutboxState.EXPIRED,
                    entry.expiresAt,
                    "REQUEST_EXPIRED",
                    "Automatic retry expired. User confirmation is required.",
                    UploadOutcome.Continue,
                )
            }
        }
    }

    private suspend fun persistBoundFailure(
        entry: OutboxEntry,
        leaseUntil: Long,
        state: OutboxState,
        nextAttemptAt: Long,
        errorCode: String?,
        errorMessage: String?,
        successOutcome: UploadOutcome,
    ): UploadOutcome = try {
        client.withSessionOwner(entry.ownerId) {
            outbox.completeFailure(
                entry.ownerId,
                entry.requestId,
                leaseUntil,
                state,
                nextAttemptAt,
                errorCode,
                errorMessage,
            )
        }
        successOutcome
    } catch (_: AccountClientException) {
        outbox.completeFailure(
            entry.ownerId,
            entry.requestId,
            leaseUntil,
            OutboxState.WAITING_LOGIN,
            System.currentTimeMillis(),
            "SESSION_CHANGED",
            "The signed-in account changed before the result could be stored.",
        )
        UploadOutcome.Pause
    }

    private suspend fun nextBatchResult(ownerId: String): BatchResult {
        val nextWakeAt = outbox.nextWakeAt(ownerId) ?: return BatchResult.Complete
        return BatchResult.ScheduleAt(nextWakeAt)
    }

    private fun JsonObject.toCachedItem(
        ownerId: String,
        isDetail: Boolean,
        fetchedAt: Long,
    ): CachedItem {
        val itemId = (this["id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw InvalidServerResponseException("The server item is missing its ID.")
        val serverVersion = (this["version"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it > 0L }
            ?: throw InvalidServerResponseException("The server item has an invalid version.")
        val rawCreatedAt = (this["created_at"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?: throw InvalidServerResponseException("The server item is missing its creation time.")
        val serverCreatedAt = try {
            FIXED_INSTANT_FORMAT.format(Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(rawCreatedAt)))
        } catch (error: Exception) {
            throw InvalidServerResponseException("The server item has an invalid creation time.")
        }
        return CachedItem(
            ownerId = ownerId,
            itemId = itemId,
            responseJson = toString(),
            serverVersion = serverVersion,
            serverCreatedAt = serverCreatedAt,
            fetchedAt = fetchedAt,
            isDetail = isDetail,
        )
    }

    private fun JsonObject.cacheRowsFor(entry: OutboxEntry, fetchedAt: Long): List<CachedItem> = when {
        entry.method == "POST" && entry.path == "/items" -> {
            val item = this["item"] as? JsonObject
                ?: throw InvalidServerResponseException("The save response is missing its item.")
            listOf(
                item.toCachedItem(entry.ownerId, isDetail = false, fetchedAt = fetchedAt),
                item.toCachedItem(entry.ownerId, isDetail = true, fetchedAt = fetchedAt),
            )
        }
        entry.method == "PATCH" && entry.path.startsWith("/items/") -> {
            val summary = toCachedItem(entry.ownerId, isDetail = false, fetchedAt = fetchedAt)
            val detail = toCachedItem(entry.ownerId, isDetail = true, fetchedAt = fetchedAt)
            val expectedItemId = entry.path.removePrefix("/items/")
            if (detail.itemId != expectedItemId) {
                throw InvalidServerResponseException("The edited item response has a different ID.")
            }
            listOf(summary, detail)
        }
        else -> emptyList()
    }

    private fun OutboxEntry.pendingCaptureText(): String? {
        if (method != "POST" || path != "/items") return null
        return try {
            json.decodeFromString<JsonObject>(payloadJson)["shared_text"]
                ?.jsonPrimitive
                ?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val MUTATING_METHODS = setOf("POST", "PATCH", "DELETE")
        val FIXED_INSTANT_FORMAT: DateTimeFormatter =
            DateTimeFormatterBuilder().appendInstant(9).toFormatter()
    }
}

internal sealed interface BatchResult {
    data object Complete : BatchResult
    data object Retry : BatchResult
    data class ScheduleAt(val timestamp: Long) : BatchResult
}

private enum class UploadOutcome {
    Continue,
    Retry,
    Pause,
}

private class InvalidServerResponseException(message: String) : Exception(message)

private fun String.requireOwnerId(): String = trim().also {
    require(it.isNotEmpty()) { "Owner ID must not be blank." }
}

private fun String.requireCanonicalUuid(): String {
    val value = trim()
    val parsed = try {
        UUID.fromString(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Request ID must be a UUID.", error)
    }
    require(parsed.toString().equals(value, ignoreCase = true)) {
        "Request ID must be a UUID."
    }
    return value
}
