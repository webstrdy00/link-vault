package com.linkvault.app.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LibraryDetailVersion(
    val itemId: String,
    val version: Long?,
)

internal class LibraryEditRequestOwnershipRegistry {
    private data class RequestKey(
        val ownerId: String,
        val requestId: String,
    )

    private val lock = Any()
    private val ownersByRequest = mutableMapOf<RequestKey, MutableSet<String>>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun acquire(ownerId: String, requestId: String, viewModelToken: String) {
        require(ownerId.isNotBlank() && requestId.isNotBlank() && viewModelToken.isNotBlank())
        synchronized(lock) {
            val changed = ownersByRequest
                .getOrPut(RequestKey(ownerId, requestId)) { mutableSetOf() }
                .add(viewModelToken)
            if (changed) mutableRevision.value += 1
        }
    }

    fun release(ownerId: String, requestId: String, viewModelToken: String) {
        synchronized(lock) {
            val key = RequestKey(ownerId, requestId)
            val owners = ownersByRequest[key] ?: return
            if (!owners.remove(viewModelToken)) return
            if (owners.isEmpty()) ownersByRequest.remove(key)
            mutableRevision.value += 1
        }
    }

    fun releaseAll(viewModelToken: String) {
        synchronized(lock) {
            var changed = false
            val requests = ownersByRequest.iterator()
            while (requests.hasNext()) {
                val owners = requests.next().value
                changed = owners.remove(viewModelToken) || changed
                if (owners.isEmpty()) requests.remove()
            }
            if (changed) mutableRevision.value += 1
        }
    }

    fun hasLiveOwner(ownerId: String, requestId: String): Boolean =
        synchronized(lock) {
            ownersByRequest[RequestKey(ownerId, requestId)].orEmpty().isNotEmpty()
        }
}

internal val libraryEditRequestOwnershipRegistry = LibraryEditRequestOwnershipRegistry()

internal fun shouldApplyLibraryEditReceipt(
    receiptRequestId: String,
    receiptItemId: String?,
    ownedRequestId: String?,
    ownedItemId: String?,
): Boolean =
    receiptRequestId.isNotBlank() &&
        receiptRequestId == ownedRequestId &&
        receiptItemId != null &&
        receiptItemId == ownedItemId

internal fun shouldAcknowledgeLibraryEditReceipt(
    receiptRequestId: String,
    receiptItemId: String?,
    reconciledDetail: LibraryDetailVersion?,
    hasLiveRequestOwner: Boolean,
): Boolean {
    val detail = reconciledDetail ?: return false
    val reconciledVersion = detail.version ?: return false
    return !hasLiveRequestOwner &&
        receiptRequestId.isNotBlank() &&
        !receiptItemId.isNullOrBlank() &&
        detail.itemId == receiptItemId &&
        reconciledVersion > 0
}

internal fun shouldPublishLibraryDetail(
    requestedItemId: String,
    candidate: LibraryDetailVersion,
    knownVersions: Iterable<LibraryDetailVersion>,
): Boolean {
    val candidateVersion = candidate.version ?: return false
    if (
        requestedItemId.isBlank() ||
        candidate.itemId != requestedItemId ||
        candidateVersion <= 0
    ) {
        return false
    }
    return knownVersions
        .asSequence()
        .filter { it.itemId == requestedItemId }
        .mapNotNull(LibraryDetailVersion::version)
        .filter { it > 0 }
        .all { candidateVersion >= it }
}
