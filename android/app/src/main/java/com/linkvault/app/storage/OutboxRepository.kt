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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    private val categoryCache = database.cachedCategoriesDao()
    private val drafts = database.pendingInputDao()
    private val deletedItems = database.deletedItems()

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
        requireValidOutboxRoute(normalizedMethod, path)
        val route = checkNotNull(parseOutboxRoute(normalizedMethod, path))
        val payload = json.decodeFromString<JsonObject>(payloadJson)
        val deleteExpectedVersion = if (route is OutboxRoute.DeleteItem) {
            requireItemDeleteExpectedVersion(payload)
        } else {
            null
        }

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
            database.withTransaction {
                if (route is OutboxRoute.DeleteItem) {
                    val knownItem = cache.readDetail(normalizedOwnerId, route.itemId)
                    require(
                        knownItem != null &&
                            knownItem.serverVersion == deleteExpectedVersion,
                    ) {
                        "Item deletion requires a matching owner-bound cached detail."
                    }
                }
                outbox.insertImmutable(entry)
            }
        }
        UploadWorker.enqueue(applicationContext, normalizedOwnerId)
    }

    fun observeOutbox(ownerId: String): Flow<List<OutboxEntry>> =
        outbox.observe(ownerId.requireOwnerId())

    fun observeDeletedItemIds(ownerId: String): Flow<List<String>> =
        deletedItems.observeItemIds(ownerId.requireOwnerId())

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
        (outbox.ownerIds() + categoryCache.ownerIds()).distinct().forEach { ownerId ->
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

    suspend fun isItemDeleted(ownerId: String, itemId: String): Boolean {
        val normalizedOwnerId = ownerId.requireOwnerId()
        val normalizedItemId = itemId.requireCanonicalItemId()
        return client.withSessionOwner(normalizedOwnerId) {
            deletedItems.isDeleted(normalizedOwnerId, normalizedItemId)
        }
    }

    suspend fun retainActiveItemIds(ownerId: String, itemIds: Collection<String>): Set<String> {
        val normalizedOwnerId = ownerId.requireOwnerId()
        val normalizedItemIds = itemIds.map(String::requireCanonicalItemId).distinct()
        return client.withSessionOwner(normalizedOwnerId) {
            normalizedItemIds.filterTo(linkedSetOf()) { itemId ->
                !deletedItems.isDeleted(normalizedOwnerId, itemId)
            }
        }
    }

    suspend fun cacheCategories(ownerId: String, response: JsonObject) {
        val normalizedOwnerId = ownerId.requireOwnerId()
        validateCategoriesResponse(response)
        val cached = CachedCategories(
            ownerId = normalizedOwnerId,
            responseJson = response.toString(),
            fetchedAt = System.currentTimeMillis(),
        )
        client.withSessionOwner(normalizedOwnerId) {
            categoryCache.upsert(cached)
        }
    }

    suspend fun readCachedCategories(ownerId: String): CachedCategories? =
        categoryCache.read(ownerId.requireOwnerId())

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
            val responseEffect = mapOutboxResponse(entry, response, System.currentTimeMillis())
            val persisted = client.withSessionOwner(entry.ownerId) {
                database.withTransaction {
                    val completed = outbox.completeSaved(
                        ownerId = entry.ownerId,
                        requestId = entry.requestId,
                        leaseUntil = leaseUntil,
                        resultJson = response.toString(),
                    )
                    if (completed == 1) {
                        responseEffect.deletedItemId?.let { itemId ->
                            deletedItems.markDeleted(entry.ownerId, itemId, System.currentTimeMillis())
                            cache.deleteItem(entry.ownerId, itemId)
                            outbox.deleteItemRequests(
                                ownerId = entry.ownerId,
                                exactItemPath = "/items/$itemId",
                                nestedItemPathPattern = "/items/$itemId/%",
                                preservedRequestId = entry.requestId,
                            )
                        }
                        responseEffect.invalidatesItemIds
                            .filterNot { it == responseEffect.deletedItemId }
                            .forEach { cache.deleteItem(entry.ownerId, it) }
                        responseEffect.cachedItems.forEach { cache.upsert(it) }
                        if (responseEffect.invalidatesCategories) {
                            categoryCache.deleteOwner(entry.ownerId)
                        }
                    }
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
    ): UploadOutcome {
        if (error.code == "ITEM_DELETED") {
            when (val route = parseOutboxRoute(entry.method, entry.path)) {
                OutboxRoute.CreateItem -> return discardDeletedCreate(entry, leaseUntil)
                is OutboxRoute.PatchItem -> return reconcileDeletedItem(
                    entry = entry,
                    leaseUntil = leaseUntil,
                    itemId = route.itemId,
                )
                else -> Unit
            }
        }
        if (error.code == "ITEM_NOT_FOUND") {
            val route = parseOutboxRoute(entry.method, entry.path)
            if (route is OutboxRoute.DeleteItem) {
                return completeKnownMissingItem(entry, leaseUntil, route.itemId)
            }
        }
        val isPermanentCategoryFailure = error.code in PERMANENT_CATEGORY_ERROR_CODES
        return completeFailure(
            entry = entry,
            leaseUntil = leaseUntil,
            retryable = error.retryable && !isPermanentCategoryFailure,
            errorCode = error.code,
            errorMessage = error.message,
            retryAfterSeconds = if (isPermanentCategoryFailure) {
                null
            } else {
                error.retryAfterSeconds
            },
        )
    }

    private suspend fun discardDeletedCreate(
        entry: OutboxEntry,
        leaseUntil: Long,
    ): UploadOutcome = try {
        val discarded = client.withSessionOwner(entry.ownerId) {
            outbox.discardClaimed(entry.ownerId, entry.requestId, leaseUntil)
        }
        if (discarded == 1) UploadOutcome.Continue else UploadOutcome.Pause
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

    private suspend fun completeKnownMissingItem(
        entry: OutboxEntry,
        leaseUntil: Long,
        itemId: String,
    ): UploadOutcome = try {
        val result = knownMissingItemDeleteReceipt(itemId)
        val completed = client.withSessionOwner(entry.ownerId) {
            database.withTransaction {
                val expectedVersion = runCatching {
                    requireItemDeleteExpectedVersion(
                        json.decodeFromString<JsonObject>(entry.payloadJson),
                    )
                }.getOrNull() ?: return@withTransaction false
                val knownItem = cache.readDetail(entry.ownerId, itemId)
                if (knownItem?.serverVersion != expectedVersion) {
                    return@withTransaction false
                }
                val saved = outbox.completeSaved(
                    ownerId = entry.ownerId,
                    requestId = entry.requestId,
                    leaseUntil = leaseUntil,
                    resultJson = result.toString(),
                )
                if (saved != 1) return@withTransaction false

                deletedItems.markDeleted(entry.ownerId, itemId, System.currentTimeMillis())
                cache.deleteItem(entry.ownerId, itemId)
                categoryCache.deleteOwner(entry.ownerId)
                outbox.deleteItemRequests(
                    ownerId = entry.ownerId,
                    exactItemPath = "/items/$itemId",
                    nestedItemPathPattern = "/items/$itemId/%",
                    preservedRequestId = entry.requestId,
                )
                true
            }
        }
        if (completed) UploadOutcome.Continue else {
            completeFailure(
                entry = entry,
                leaseUntil = leaseUntil,
                retryable = false,
                errorCode = "ITEM_NOT_FOUND",
                errorMessage = "The requested item was not found.",
                retryAfterSeconds = null,
            )
        }
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

    private suspend fun reconcileDeletedItem(
        entry: OutboxEntry,
        leaseUntil: Long,
        itemId: String,
    ): UploadOutcome = try {
        val reconciled = client.withSessionOwner(entry.ownerId) {
            database.withTransaction {
                if (!outbox.isClaimed(entry.ownerId, entry.requestId, leaseUntil)) {
                    return@withTransaction false
                }
                deletedItems.markDeleted(entry.ownerId, itemId, System.currentTimeMillis())
                cache.deleteItem(entry.ownerId, itemId)
                categoryCache.deleteOwner(entry.ownerId)
                outbox.deleteItemRequests(
                    ownerId = entry.ownerId,
                    exactItemPath = "/items/$itemId",
                    nestedItemPathPattern = "/items/$itemId/%",
                    preservedRequestId = null,
                )
                true
            }
        }
        if (reconciled) UploadOutcome.Continue else UploadOutcome.Pause
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
        val PERMANENT_CATEGORY_ERROR_CODES = setOf(
            "CATEGORY_LIMIT_REACHED",
            "CATEGORY_NAME_EXISTS",
            "CATEGORY_NOT_FOUND",
            "INVALID_CATEGORY_IDS",
            "SYSTEM_CATEGORY_READONLY",
        )
    }
}

internal data class OutboxResponseEffect(
    val cachedItems: List<CachedItem>,
    val invalidatesCategories: Boolean,
    val invalidatesItemIds: List<String> = emptyList(),
    val deletedItemId: String? = null,
)

internal fun requireValidOutboxRoute(method: String, path: String) {
    require(parseOutboxRoute(method, path) != null) { "Unsupported outbox route." }
}

internal fun mapOutboxResponse(
    entry: OutboxEntry,
    response: JsonObject,
    fetchedAt: Long,
): OutboxResponseEffect {
    return when (val route = parseOutboxRoute(entry.method, entry.path)) {
        OutboxRoute.CreateItem -> {
            val duplicate = response["duplicate"] as? JsonPrimitive
            if (duplicate == null || duplicate.isString || duplicate.booleanOrNull == null) {
                throw InvalidServerResponseException(
                    "The save response has an invalid duplicate marker.",
                )
            }
            val item = response["item"] as? JsonObject
                ?: throw InvalidServerResponseException("The save response is missing its item.")
            OutboxResponseEffect(
                cachedItems = item.cacheAsListAndDetail(entry.ownerId, fetchedAt),
                invalidatesCategories = false,
            )
        }
        is OutboxRoute.PatchItem -> {
            response.requireMatchingId(route.itemId, "edited item")
            OutboxResponseEffect(
                cachedItems = response.cacheAsListAndDetail(entry.ownerId, fetchedAt),
                invalidatesCategories = entry.payloadContains("category_ids"),
            )
        }
        is OutboxRoute.DeleteItem -> {
            if (response.keys != setOf("item_id", "state")) {
                throw InvalidServerResponseException(
                    "The item deletion response has an invalid envelope.",
                )
            }
            val itemId = response.requireUuid(
                "item_id",
                "The item deletion response has an invalid item ID.",
            )
            if (!itemId.equals(route.itemId, ignoreCase = true)) {
                throw InvalidServerResponseException(
                    "The item deletion response has a different item ID.",
                )
            }
            if (response.requireString("state", "The item deletion response has an invalid state.") !=
                "deleting"
            ) {
                throw InvalidServerResponseException(
                    "The item deletion response has an invalid state.",
                )
            }
            OutboxResponseEffect(
                cachedItems = emptyList(),
                invalidatesCategories = true,
                invalidatesItemIds = listOf(route.itemId),
                deletedItemId = route.itemId,
            )
        }
        OutboxRoute.CreateCategory -> {
            response.requireUuid("id", "The created category response has an invalid ID.")
            OutboxResponseEffect(emptyList(), invalidatesCategories = true)
        }
        is OutboxRoute.PatchCategory -> {
            response.requireMatchingId(route.categoryId, "edited category")
            OutboxResponseEffect(emptyList(), invalidatesCategories = true)
        }
        OutboxRoute.DeleteCategory -> {
            if (response.isNotEmpty()) {
                throw InvalidServerResponseException(
                    "The deleted category response must be empty.",
                )
            }
            OutboxResponseEffect(emptyList(), invalidatesCategories = true)
        }
        is OutboxRoute.DismissCue -> {
            response.requireMatchingId(route.itemId, "cue-dismissed item")
            OutboxResponseEffect(
                cachedItems = response.cacheAsListAndDetail(entry.ownerId, fetchedAt),
                invalidatesCategories = false,
            )
        }
        is OutboxRoute.Reclassify -> {
            response.requireUuid("job_id", "The reclassification response has an invalid job ID.")
            val itemId = response.requireUuid(
                "item_id",
                "The reclassification response has an invalid item ID.",
            )
            if (!itemId.equals(route.itemId, ignoreCase = true)) {
                throw InvalidServerResponseException(
                    "The reclassification response has a different item ID.",
                )
            }
            OutboxResponseEffect(emptyList(), invalidatesCategories = true)
        }
        is OutboxRoute.RetryMetadata -> {
            response.requireUuid("job_id", "The metadata response has an invalid job ID.")
            OutboxResponseEffect(emptyList(), invalidatesCategories = false)
        }
        is OutboxRoute.DeleteAsset -> {
            val assetId = response.requireUuid("asset_id", "The deleted asset response has an invalid ID.")
            if (!assetId.equals(route.assetId, ignoreCase = true)) {
                throw InvalidServerResponseException("The deleted asset response has a different ID.")
            }
            OutboxResponseEffect(
                emptyList(),
                invalidatesCategories = true,
                invalidatesItemIds = listOf(route.itemId),
            )
        }
        is OutboxRoute.UpdateOcr -> {
            response.requireMatchingId(route.itemId, "OCR-updated item")
            OutboxResponseEffect(
                response.cacheAsListAndDetail(entry.ownerId, fetchedAt),
                invalidatesCategories = true,
            )
        }
        null -> throw InvalidServerResponseException(
            "The stored request uses an unsupported outbox route.",
        )
    }
}

internal fun validateCategoriesResponse(response: JsonObject) {
    val categories = response["categories"] as? JsonArray
        ?: throw InvalidServerResponseException(
            "The category response is missing its category list.",
        )
    val count = response.requireNonNegativeLong(
        "count",
        "The category response has an invalid count.",
    )
    if (count != categories.size.toLong()) {
        throw InvalidServerResponseException(
            "The category response count does not match its category list.",
        )
    }
    response.requireNonNegativeLong(
        "unclassified_count",
        "The category response has an invalid unclassified count.",
    )

    val categoryIds = mutableSetOf<String>()
    categories.forEach { element ->
        val category = element as? JsonObject
            ?: throw InvalidServerResponseException(
                "The category response contains an invalid category.",
            )
        val id = category.requireUuid("id", "A category has an invalid ID.")
        if (!categoryIds.add(id.lowercase())) {
            throw InvalidServerResponseException(
                "The category response contains a duplicate category ID.",
            )
        }
        category.requireString("name", "A category has an invalid name.")
            .takeIf(String::isNotBlank)
            ?: throw InvalidServerResponseException("A category has an invalid name.")
        val kind = category.requireString("kind", "A category has an invalid kind.")
        if (kind != "system" && kind != "custom") {
            throw InvalidServerResponseException("A category has an invalid kind.")
        }
        val systemCodeElement = category["system_code"]
        val systemCode = if (systemCodeElement == null || systemCodeElement is JsonNull) {
            null
        } else {
            val value = systemCodeElement as? JsonPrimitive
            if (value == null || !value.isString || value.contentOrNull.isNullOrBlank()) {
                throw InvalidServerResponseException("A category has an invalid system code.")
            }
            value.content
        }
        if (
            (kind == "system" && systemCode == null) ||
            (kind == "custom" && systemCode != null)
        ) {
            throw InvalidServerResponseException("A category has an invalid system code.")
        }
        category.requireNonNegativeLong(
            "item_count",
            "A category has an invalid item count.",
        )
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

private sealed interface OutboxRoute {
    data object CreateItem : OutboxRoute
    data class PatchItem(val itemId: String) : OutboxRoute
    data class DeleteItem(val itemId: String) : OutboxRoute
    data object CreateCategory : OutboxRoute
    data class PatchCategory(val categoryId: String) : OutboxRoute
    data object DeleteCategory : OutboxRoute
    data class DismissCue(val itemId: String) : OutboxRoute
    data class Reclassify(val itemId: String) : OutboxRoute
    data class RetryMetadata(val itemId: String) : OutboxRoute
    data class DeleteAsset(val itemId: String, val assetId: String) : OutboxRoute
    data class UpdateOcr(val itemId: String, val assetId: String) : OutboxRoute
}

private class InvalidServerResponseException(message: String) : Exception(message)

private fun parseOutboxRoute(method: String, path: String): OutboxRoute? {
    if (method == "POST" && path == "/items") return OutboxRoute.CreateItem
    if (method == "POST" && path == "/categories") return OutboxRoute.CreateCategory

    path.resourceId("/items/")?.let { itemId ->
        if (method == "PATCH") return OutboxRoute.PatchItem(itemId)
        if (method == "DELETE") return OutboxRoute.DeleteItem(itemId)
    }
    path.resourceId("/categories/")?.let { categoryId ->
        if (method == "PATCH") return OutboxRoute.PatchCategory(categoryId)
        if (method == "DELETE") return OutboxRoute.DeleteCategory
    }
    path.resourceId("/items/", "/cue-dismiss")?.let { itemId ->
        if (method == "POST") return OutboxRoute.DismissCue(itemId)
    }
    path.resourceId("/items/", "/reclassify")?.let { itemId ->
        if (method == "POST") return OutboxRoute.Reclassify(itemId)
    }
    path.resourceId("/items/", "/retry-metadata")?.let { itemId ->
        if (method == "POST") return OutboxRoute.RetryMetadata(itemId)
    }
    val segments = path.split("/")
    if (segments.size in 5..6 && segments[0].isEmpty() &&
        segments[1] == "items" && segments[3] == "assets" &&
        segments[2].isCanonicalUuid() && segments[4].isCanonicalUuid()
    ) {
        if (segments.size == 5 && method == "DELETE") {
            return OutboxRoute.DeleteAsset(segments[2], segments[4])
        }
        if (segments.size == 6 && segments[5] == "ocr" && method == "PATCH") {
            return OutboxRoute.UpdateOcr(segments[2], segments[4])
        }
    }
    return null
}

private fun String.resourceId(prefix: String, suffix: String = ""): String? {
    if (
        !startsWith(prefix) ||
        !endsWith(suffix) ||
        length <= prefix.length + suffix.length
    ) {
        return null
    }
    val id = substring(prefix.length, length - suffix.length)
    return id.takeIf(String::isCanonicalUuid)
}

private fun JsonObject.cacheAsListAndDetail(
    ownerId: String,
    fetchedAt: Long,
): List<CachedItem> = listOf(
    toCachedItem(ownerId, isDetail = false, fetchedAt = fetchedAt),
    toCachedItem(ownerId, isDetail = true, fetchedAt = fetchedAt),
)

private fun JsonObject.toCachedItem(
    ownerId: String,
    isDetail: Boolean,
    fetchedAt: Long,
): CachedItem {
    val itemId = requireUuid("id", "The server item has an invalid ID.")
    val serverVersion = requirePositiveLong(
        "version",
        "The server item has an invalid version.",
    )
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

private fun JsonObject.requireMatchingId(expectedId: String, responseName: String) {
    val actualId = requireUuid("id", "The $responseName response has an invalid ID.")
    if (!actualId.equals(expectedId, ignoreCase = true)) {
        throw InvalidServerResponseException(
            "The $responseName response has a different ID.",
        )
    }
}

private fun JsonObject.requireString(field: String, errorMessage: String): String {
    val value = this[field] as? JsonPrimitive
    if (value == null || !value.isString) throw InvalidServerResponseException(errorMessage)
    return value.contentOrNull ?: throw InvalidServerResponseException(errorMessage)
}

private fun JsonObject.requireUuid(field: String, errorMessage: String): String =
    requireString(field, errorMessage).takeIf(String::isCanonicalUuid)
        ?: throw InvalidServerResponseException(errorMessage)

private fun JsonObject.requirePositiveLong(field: String, errorMessage: String): Long {
    val value = this[field] as? JsonPrimitive
    if (value == null || value.isString) throw InvalidServerResponseException(errorMessage)
    return value.longOrNull?.takeIf { it > 0L }
        ?: throw InvalidServerResponseException(errorMessage)
}

private fun JsonObject.requireNonNegativeLong(field: String, errorMessage: String): Long {
    val value = this[field] as? JsonPrimitive
    if (value == null || value.isString) throw InvalidServerResponseException(errorMessage)
    return value.longOrNull?.takeIf { it >= 0L }
        ?: throw InvalidServerResponseException(errorMessage)
}

private fun OutboxEntry.payloadContains(field: String): Boolean = try {
    Json.decodeFromString<JsonObject>(payloadJson).containsKey(field)
} catch (error: Exception) {
    throw InvalidServerResponseException("The stored request body is invalid.")
}

internal fun requireItemDeleteExpectedVersion(payload: JsonObject): Long {
    require(payload.keys == setOf("expected_version")) {
        "Item deletion body must contain only expected_version."
    }
    val value = payload["expected_version"] as? JsonPrimitive
    require(value != null && !value.isString) {
        "Item deletion expected_version must be a positive integer."
    }
    return requireNotNull(value.longOrNull?.takeIf { it > 0L }) {
        "Item deletion expected_version must be a positive integer."
    }
}

internal fun knownMissingItemDeleteReceipt(itemId: String): JsonObject {
    require(itemId.isCanonicalUuid()) { "Item ID must be a UUID." }
    return JsonObject(
        mapOf(
            "item_id" to JsonPrimitive(itemId),
            "state" to JsonPrimitive("already_deleted"),
        ),
    )
}

private fun String.requireOwnerId(): String = trim().also {
    require(it.isNotEmpty()) { "Owner ID must not be blank." }
}

private fun String.requireCanonicalItemId(): String {
    val value = trim()
    val parsed = try {
        UUID.fromString(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Item ID must be a UUID.", error)
    }
    require(parsed.toString().equals(value, ignoreCase = true)) {
        "Item ID must be a UUID."
    }
    return parsed.toString()
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

private fun String.isCanonicalUuid(): Boolean = runCatching {
    requireCanonicalUuid() == this
}.getOrDefault(false)

private val FIXED_INSTANT_FORMAT: DateTimeFormatter =
    DateTimeFormatterBuilder().appendInstant(9).toFormatter()
