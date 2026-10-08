package com.linkvault.app.library

import com.linkvault.app.auth.AccountAuthenticationRequiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryResponsePolicyTest {
    private val owner = "10000000-0000-0000-0000-000000000001"
    private val item = "20000000-0000-0000-0000-000000000001"
    private val request = "30000000-0000-0000-0000-000000000001"

    @Test
    fun unownedRestoredSavedReceiptIsReconciledWithoutTakingEditorOwnership() {
        assertFalse(
            shouldApplyLibraryEditReceipt(
                receiptRequestId = "request-other",
                receiptItemId = "item-a",
                ownedRequestId = null,
                ownedItemId = "item-a",
            ),
        )
        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-other",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = false,
            ),
        )
    }

    @Test
    fun exactOwnedRequestAppliesToItsEditorBeforeAcknowledgement() {
        assertTrue(
            shouldApplyLibraryEditReceipt(
                receiptRequestId = "request-own",
                receiptItemId = "item-a",
                ownedRequestId = "request-own",
                ownedItemId = "item-a",
            ),
        )
        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-own",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = false,
            ),
        )
    }

    @Test
    fun unrelatedDirtyEditorIsPreservedWhileReconciledReceiptIsAcknowledged() {
        assertFalse(
            shouldApplyLibraryEditReceipt(
                receiptRequestId = "request-other",
                receiptItemId = "item-a",
                ownedRequestId = null,
                ownedItemId = "item-b",
            ),
        )
        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-other",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = false,
            ),
        )
    }

    @Test
    fun anotherRequestForTheSameItemDoesNotOverwriteTheEditor() {
        assertFalse(
            shouldApplyLibraryEditReceipt(
                receiptRequestId = "request-other",
                receiptItemId = "item-a",
                ownedRequestId = "request-own",
                ownedItemId = "item-a",
            ),
        )
        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-other",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = false,
            ),
        )
    }

    @Test
    fun receiptIsNotAcknowledgedWithoutAnAcceptedMatchingDetail() {
        assertFalse(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-own",
                receiptItemId = "item-a",
                reconciledDetail = null,
                hasLiveRequestOwner = false,
            ),
        )
        assertFalse(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-own",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-b", 11),
                hasLiveRequestOwner = false,
            ),
        )
    }

    @Test
    fun delayedExactOwnerPreventsObserverAcknowledgementUntilEditorCompletion() {
        val registry = LibraryEditRequestOwnershipRegistry()
        registry.acquire("owner-a", "request-r", "view-model-a")

        assertFalse(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-r",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = registry.hasLiveOwner("owner-a", "request-r"),
            ),
        )

        registry.release("owner-a", "request-r", "view-model-a")

        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-r",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = registry.hasLiveOwner("owner-a", "request-r"),
            ),
        )
    }

    @Test
    fun ownerTeardownMakesSavedReceiptRecoverableAsAnOrphan() {
        val registry = LibraryEditRequestOwnershipRegistry()
        registry.acquire("owner-a", "request-r", "view-model-a")

        assertTrue(registry.hasLiveOwner("owner-a", "request-r"))
        registry.releaseAll("view-model-a")

        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-r",
                receiptItemId = "item-a",
                reconciledDetail = LibraryDetailVersion("item-a", 11),
                hasLiveRequestOwner = registry.hasLiveOwner("owner-a", "request-r"),
            ),
        )
    }

    @Test
    fun ownershipIsIsolatedByAccountRequestAndViewModelToken() {
        val registry = LibraryEditRequestOwnershipRegistry()
        registry.acquire("owner-a", "request-r", "view-model-a")

        registry.release("owner-a", "request-r", "view-model-b")

        assertTrue(registry.hasLiveOwner("owner-a", "request-r"))
        assertFalse(registry.hasLiveOwner("owner-b", "request-r"))
        assertFalse(registry.hasLiveOwner("owner-a", "request-other"))
        assertTrue(
            shouldAcknowledgeLibraryEditReceipt(
                receiptRequestId = "request-r",
                receiptItemId = "item-b",
                reconciledDetail = LibraryDetailVersion("item-b", 7),
                hasLiveRequestOwner = registry.hasLiveOwner("owner-b", "request-r"),
            ),
        )
    }

    @Test
    fun lowerDetailVersionCannotReplaceTheNewestKnownSnapshot() {
        assertFalse(
            shouldPublishLibraryDetail(
                requestedItemId = "item-a",
                candidate = LibraryDetailVersion("item-a", 10),
                knownVersions = listOf(LibraryDetailVersion("item-a", 11)),
            ),
        )
    }

    @Test
    fun detailForAnotherItemCannotBePublished() {
        assertFalse(
            shouldPublishLibraryDetail(
                requestedItemId = "item-a",
                candidate = LibraryDetailVersion("item-b", 12),
                knownVersions = emptyList(),
            ),
        )
    }

    @Test
    fun currentOrHigherDetailVersionCanBePublished() {
        val current = LibraryDetailVersion("item-a", 11)
        assertTrue(
            shouldPublishLibraryDetail(
                requestedItemId = "item-a",
                candidate = LibraryDetailVersion("item-a", 11),
                knownVersions = listOf(current),
            ),
        )
        assertTrue(
            shouldPublishLibraryDetail(
                requestedItemId = "item-a",
                candidate = LibraryDetailVersion("item-a", 12),
                knownVersions = listOf(current),
            ),
        )
    }

    @Test
    fun missingCandidateVersionCannotEraseAKnownVersion() {
        assertFalse(
            shouldPublishLibraryDetail(
                requestedItemId = "item-a",
                candidate = LibraryDetailVersion("item-a", null),
                knownVersions = listOf(LibraryDetailVersion("item-a", 11)),
            ),
        )
    }

    @Test
    fun deleteIntentFreezesVersionAndCanonicalRequestIdentity() {
        val operation = prepareLibraryDelete(
            ownerId = owner,
            itemId = item,
            requestId = request,
            expectedVersion = 17,
        )

        assertEquals(owner, operation.ownerId)
        assertEquals(item, operation.itemId)
        assertEquals(request, operation.requestId)
        assertEquals(17L, operation.expectedVersion)
        assertEquals("""{"expected_version":17}""", operation.body)
    }

    @Test
    fun deleteReceiptRequiresExactAcceptedShapeAndMatchingItem() {
        fun json(value: String) = Json.decodeFromString<JsonObject>(value)

        assertEquals(
            item,
            parseLibraryDeleteReceipt(
                json("""{"item_id":"$item","state":"deleting"}"""),
                item,
            ),
        )
        for (response in listOf(
            """{"item_id":"$request","state":"deleting"}""",
            """{"item_id":"$item","state":"deleted"}""",
            """{"item_id":"$item","state":"deleting","extra":true}""",
            """{"item":{"id":"$item"},"state":"deleting"}""",
        )) {
            assertTrue(
                runCatching {
                    parseLibraryDeleteReceipt(json(response), item)
                }.isFailure,
            )
        }
    }

    @Test
    fun localAlreadyDeletedReceiptIsDistinctFromServerAcceptance() {
        val localReceipt = Json.decodeFromString<JsonObject>(
            """{"item_id":"$item","state":"already_deleted"}""",
        )

        assertTrue(
            runCatching {
                parseLibraryDeleteReceipt(localReceipt, item)
            }.isFailure,
        )
        assertEquals(
            StoredLibraryDeleteReceipt(itemId = item, alreadyDeleted = true),
            parseStoredLibraryDeleteReceipt(localReceipt, item),
        )
    }

    @Test
    fun anotherViewCannotAcknowledgeDeleteBeforeItsLiveOwnerAppliesIt() {
        val registry = LibraryEditRequestOwnershipRegistry()
        registry.acquire(owner, request, "deleting-view")

        assertFalse(
            shouldApplyLibraryDeleteReceipt(
                receiptRequestId = request,
                receiptItemId = item,
                ownedRequestId = "unrelated-edit",
                ownedItemId = "40000000-0000-0000-0000-000000000001",
            ),
        )
        assertFalse(
            shouldAcknowledgeLibraryDeleteReceipt(
                receiptRequestId = request,
                receiptItemId = item,
                appliedItemId = item,
                hasLiveRequestOwner = registry.hasLiveOwner(owner, request),
            ),
        )

        registry.release(owner, request, "deleting-view")
        assertTrue(
            shouldAcknowledgeLibraryDeleteReceipt(
                receiptRequestId = request,
                receiptItemId = item,
                appliedItemId = item,
                hasLiveRequestOwner = registry.hasLiveOwner(owner, request),
            ),
        )
    }

    @Test
    fun tombstoneCommittedDuringFinalCacheReadBlocksLatePrivateBodyPublication() = runBlocking {
        val cacheReadStarted = CompletableDeferred<Unit>()
        val resumeCacheRead = CompletableDeferred<Unit>()
        val reconciledDeletedIds = mutableSetOf<String>()
        var tombstoneCommitted = false
        var activeBody: String? = null

        val publication = async {
            publishLatestLibraryValue(
                itemId = item,
                awaitLastSuspension = {
                    cacheReadStarted.complete(Unit)
                    resumeCacheRead.await()
                },
                isDeletedAfterLastSuspension = { tombstoneCommitted },
                deletedItemIds = { reconciledDeletedIds },
                publish = { activeBody = "private body" },
            )
        }

        cacheReadStarted.await()
        tombstoneCommitted = true
        reconciledDeletedIds += item
        resumeCacheRead.complete(Unit)

        assertFalse(publication.await())
        assertNull(activeBody)
    }

    @Test
    fun tombstoneCommittedDuringNoChangeDiscardBlocksLatestDetailPublication() = runBlocking {
        val discardStarted = CompletableDeferred<Unit>()
        val resumeDiscard = CompletableDeferred<Unit>()
        val reconciledDeletedIds = mutableSetOf<String>()
        var tombstoneCommitted = false
        var activeBody: String? = null

        val publication = async {
            publishLatestLibraryValue(
                itemId = item,
                awaitLastSuspension = {
                    discardStarted.complete(Unit)
                    resumeDiscard.await()
                },
                isDeletedAfterLastSuspension = { tombstoneCommitted },
                deletedItemIds = { reconciledDeletedIds },
                publish = { activeBody = "latest private body" },
            )
        }

        discardStarted.await()
        tombstoneCommitted = true
        reconciledDeletedIds += item
        resumeDiscard.complete(Unit)

        assertFalse(publication.await())
        assertNull(activeBody)
    }

    @Test
    fun authorizationLossDuringFinalFenceEscapesToTheOwnerBoundCallerWithoutPublishing() =
        runBlocking {
            val lastSuspensionStarted = CompletableDeferred<Unit>()
            val loseAuthorization = CompletableDeferred<Unit>()
            var activeBody: String? = null

            val publication = async {
                runCatching {
                    publishLatestLibraryValue(
                        itemId = item,
                        awaitLastSuspension = {
                            lastSuspensionStarted.complete(Unit)
                            loseAuthorization.await()
                        },
                        isDeletedAfterLastSuspension = {
                            throw AccountAuthenticationRequiredException()
                        },
                        deletedItemIds = { emptySet() },
                        publish = { activeBody = "private body" },
                    )
                }.exceptionOrNull()
            }

            lastSuspensionStarted.await()
            loseAuthorization.complete(Unit)

            assertTrue(publication.await() is AccountAuthenticationRequiredException)
            assertNull(activeBody)
        }
}
