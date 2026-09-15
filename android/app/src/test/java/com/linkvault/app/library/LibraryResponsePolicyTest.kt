package com.linkvault.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryResponsePolicyTest {
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
}
