package com.linkvault.app.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecoveryPolicyTest {
    @Test
    fun validatedReconnectRecoversFailedListWithoutAnyQueuedSave() {
        val policy = LibraryListRecoveryPolicy()
        policy.updateEnvironment(visible = true, hasValidatedNetwork = false)
        assertTrue(policy.startRequest())
        policy.finishRequest(succeeded = false)

        assertFalse(policy.takeRecovery())
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        assertTrue(policy.takeRecovery())
        assertFalse(policy.takeRecovery())
        assertTrue(policy.startRequest())
        assertTrue(policy.hasFailedRequest)
        policy.finishRequest(succeeded = true)
        assertFalse(policy.hasFailedRequest)
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun receiptsAndReconnectDuringConcurrentRequestCoalesceIntoOnePendingRecovery() =
        runBlocking {
            val policy = LibraryListRecoveryPolicy()
            val requestStarted = CompletableDeferred<Unit>()
            val releaseRequest = CompletableDeferred<Unit>()
            policy.updateEnvironment(visible = true, hasValidatedNetwork = false)
            val request = async {
                assertTrue(policy.startRequest())
                requestStarted.complete(Unit)
                releaseRequest.await()
                policy.finishRequest(succeeded = false)
            }

            requestStarted.await()
            policy.onSavedReceipt("save-a")
            policy.onSavedReceipt("save-a")
            policy.onSavedReceipt("save-b")
            policy.updateEnvironment(visible = true, hasValidatedNetwork = true)
            policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

            assertFalse(policy.startRequest())
            assertFalse(policy.takeRecovery())
            releaseRequest.complete(Unit)
            request.await()

            assertTrue(policy.takeRecovery())
            assertFalse(policy.takeRecovery())
            assertTrue(policy.startRequest())
            policy.finishRequest(succeeded = false)
            assertTrue(policy.hasFailedRequest)
            assertFalse(policy.takeRecovery())
        }

    @Test
    fun successfulInFlightFetchSatisfiesPendingReceiptAndReconnect() {
        val policy = LibraryListRecoveryPolicy()
        policy.updateEnvironment(visible = true, hasValidatedNetwork = false)
        assertTrue(policy.startRequest())
        policy.onSavedReceipt("save-a")
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        policy.finishRequest(succeeded = true)

        assertFalse(policy.hasFailedRequest)
        assertFalse(policy.takeRecovery())
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)
        policy.onSavedReceipt("save-a")
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun recoveryFailureDoesNotRetryItselfOrDuplicateTriggers() {
        val policy = failedVisiblePolicy()
        policy.onSavedReceipt("save-a")
        assertTrue(policy.takeRecovery())
        assertTrue(policy.startRequest())
        policy.onSavedReceipt("save-a")
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        policy.finishRequest(succeeded = false)

        assertTrue(policy.hasFailedRequest)
        assertFalse(policy.takeRecovery())
        policy.onSavedReceipt("save-a")
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun newSuccessfulReceiptCanRetryOnceAfterRecoveryFailure() {
        val policy = failedVisiblePolicy()
        policy.onSavedReceipt("save-a")
        assertTrue(policy.takeRecovery())
        assertTrue(policy.startRequest())
        policy.finishRequest(succeeded = false)
        assertFalse(policy.takeRecovery())

        policy.onSavedReceipt("save-b")

        assertTrue(policy.hasFailedRequest)
        assertTrue(policy.takeRecovery())
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun triggerDuringRecoveryRetainsOnlyOneFollowupAfterFailure() {
        val policy = failedVisiblePolicy()
        policy.onSavedReceipt("save-a")
        assertTrue(policy.takeRecovery())
        assertTrue(policy.startRequest())

        policy.onSavedReceipt("save-b")
        policy.onSavedReceipt("save-c")
        assertFalse(policy.takeRecovery())
        policy.finishRequest(succeeded = false)

        assertTrue(policy.takeRecovery())
        assertTrue(policy.startRequest())
        policy.finishRequest(succeeded = false)
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun pendingRecoveryWaitsForBothVisibilityAndValidatedNetwork() {
        val policy = failedVisiblePolicy()
        policy.updateEnvironment(visible = false, hasValidatedNetwork = false)
        policy.onSavedReceipt("save-a")

        policy.updateEnvironment(visible = false, hasValidatedNetwork = true)
        assertFalse(policy.takeRecovery())
        policy.updateEnvironment(visible = true, hasValidatedNetwork = false)
        assertFalse(policy.takeRecovery())
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        assertTrue(policy.takeRecovery())
        assertTrue(policy.startRequest())
        assertTrue(policy.hasFailedRequest)
        policy.finishRequest(succeeded = true)
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun returningVisibleAfterBackgroundReconnectRecoversWithoutReceipt() {
        val policy = failedVisiblePolicy()
        policy.updateEnvironment(visible = false, hasValidatedNetwork = false)
        assertFalse(policy.takeRecovery())

        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        assertTrue(policy.takeRecovery())
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun unvalidatedNetworkDoesNotRecoverFailedList() {
        val policy = failedVisiblePolicy()
        policy.updateEnvironment(visible = true, hasValidatedNetwork = false)
        policy.onSavedReceipt("save-a")
        policy.updateEnvironment(visible = true, hasValidatedNetwork = false)

        assertTrue(policy.hasFailedRequest)
        assertFalse(policy.takeRecovery())
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)
        assertTrue(policy.takeRecovery())
    }

    @Test
    fun savedReceiptWithoutFailedRetrievalDoesNotRefreshListOrArmLaterReplay() {
        val policy = LibraryListRecoveryPolicy()
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)
        assertTrue(policy.startRequest())
        policy.finishRequest(succeeded = true)

        policy.onSavedReceipt("save-a")
        policy.updateEnvironment(visible = true, hasValidatedNetwork = false)
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        assertFalse(policy.takeRecovery())
        // A replayed receipt is not a new recovery opportunity for a later list failure.
        assertTrue(policy.startRequest())
        policy.finishRequest(succeeded = false)
        policy.onSavedReceipt("save-a")
        assertFalse(policy.takeRecovery())
    }

    @Test
    fun receiptAndRequestStartKeepFailedRetrievalUntilSuccessfulFetch() {
        val policy = failedVisiblePolicy()
        policy.onSavedReceipt("save-a")

        assertTrue(policy.hasFailedRequest)
        assertTrue(policy.takeRecovery())
        assertTrue(policy.startRequest())
        assertTrue(policy.hasFailedRequest)
        policy.finishRequest(succeeded = false)
        assertTrue(policy.hasFailedRequest)

        assertTrue(policy.startRequest())
        policy.finishRequest(succeeded = true)
        assertFalse(policy.hasFailedRequest)
    }

    @Test
    fun cancelledRetryDoesNotCreateRecoveryOrEraseExistingFailure() {
        val policy = failedVisiblePolicy()
        assertTrue(policy.startRequest())

        policy.finishRequest(succeeded = null)

        assertTrue(policy.hasFailedRequest)
        assertFalse(policy.takeRecovery())
        policy.onSavedReceipt("save-a")
        assertTrue(policy.takeRecovery())
    }

    @Test
    fun identityInvalidationDropsPendingRecoveryAndRejectsLateCompletionAndTriggers() {
        val policy = failedVisiblePolicy()
        assertTrue(policy.startRequest())
        policy.onSavedReceipt("save-a")

        policy.invalidateIdentity()
        policy.finishRequest(succeeded = false)
        policy.onSavedReceipt("save-b")
        policy.updateEnvironment(visible = true, hasValidatedNetwork = true)

        assertFalse(policy.takeRecovery())
        assertFalse(policy.startRequest())
        assertFalse(policy.hasFailedRequest)
    }

    private fun failedVisiblePolicy(): LibraryListRecoveryPolicy =
        LibraryListRecoveryPolicy().apply {
            updateEnvironment(visible = true, hasValidatedNetwork = true)
            assertTrue(startRequest())
            finishRequest(succeeded = false)
        }
}
