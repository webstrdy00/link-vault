package com.linkvault.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSessionIdentityTest {
    @Test
    fun firstLoginPreservesTheUnownedCaptureRoute() {
        val signedOut = PrivateSessionIdentity(0, null)
        assertFalse(signedOut.invalidatesPrivateContent(PrivateSessionIdentity(1, "owner-a")))
        assertFalse(signedOut.invalidatesPrivateContent(PrivateSessionIdentity(1, null)))
    }

    @Test
    fun ownerLossChangeAndNewSessionInvalidatePrivateRoutes() {
        val origin = PrivateSessionIdentity(1, "owner-a")
        assertTrue(origin.invalidatesPrivateContent(PrivateSessionIdentity(2, null)))
        assertTrue(origin.invalidatesPrivateContent(PrivateSessionIdentity(2, "owner-b")))
        assertTrue(origin.invalidatesPrivateContent(PrivateSessionIdentity(2, "owner-a")))
    }

    @Test
    fun sameIdentityKeepsNavigationDrafts() {
        val origin = PrivateSessionIdentity(1, "owner-a")
        assertFalse(origin.invalidatesPrivateContent(PrivateSessionIdentity(1, "owner-a")))
    }

    @Test
    fun activityStoreRetainsPrivateDraftAcrossConfigurationRecreation() {
        val retainedActivityStore = ViewModelStore()
        val activityBeforeRecreation = TestStoreOwner(retainedActivityStore)
        val holderBeforeRecreation = privateStoreHolder(activityBeforeRecreation)
        val identity = PrivateSessionIdentity(1, "owner-a")
        val ownerBeforeRecreation = holderBeforeRecreation.storeOwnerFor(identity)
        val draft = trackingViewModel(ownerBeforeRecreation)

        val activityAfterRecreation = TestStoreOwner(retainedActivityStore)
        val holderAfterRecreation = privateStoreHolder(activityAfterRecreation)
        val ownerAfterRecreation = holderAfterRecreation.storeOwnerFor(identity)

        assertSame(holderBeforeRecreation, holderAfterRecreation)
        assertSame(ownerBeforeRecreation, ownerAfterRecreation)
        assertSame(draft, trackingViewModel(ownerAfterRecreation))
        assertEquals(0, draft.clearCount)

        retainedActivityStore.clear()
        assertEquals(1, draft.clearCount)
    }

    @Test
    fun firstLoginPreservesRouteButClearsUnownedPrivateStore() {
        val holder = PrivateSessionStoreViewModel()
        val signedOut = PrivateSessionIdentity(0, null)
        val signedIn = PrivateSessionIdentity(1, "owner-a")
        val unownedStore = holder.storeOwnerFor(signedOut)
        val unownedDraft = trackingViewModel(unownedStore)

        val signedInStore = holder.storeOwnerFor(signedIn)

        assertFalse(signedOut.invalidatesPrivateContent(signedIn))
        assertNotSame(unownedStore, signedInStore)
        assertEquals(1, unownedDraft.clearCount)
    }

    @Test
    fun generationAndOwnerTransitionsClearOldStores() {
        val holder = PrivateSessionStoreViewModel()
        val firstStore = holder.storeOwnerFor(PrivateSessionIdentity(7, "owner-a"))
        val firstDraft = trackingViewModel(firstStore)

        val secondStore = holder.storeOwnerFor(PrivateSessionIdentity(8, "owner-a"))
        val secondDraft = trackingViewModel(secondStore)

        assertNotSame(firstStore, secondStore)
        assertEquals(1, firstDraft.clearCount)

        val thirdStore = holder.storeOwnerFor(PrivateSessionIdentity(8, "owner-b"))

        assertNotSame(secondStore, thirdStore)
        assertEquals(1, secondDraft.clearCount)
        assertNotSame(secondDraft, trackingViewModel(thirdStore))
    }

    private fun privateStoreHolder(owner: ViewModelStoreOwner): PrivateSessionStoreViewModel =
        ViewModelProvider(owner, PrivateSessionStoreFactory)[PrivateSessionStoreViewModel::class.java]

    private fun trackingViewModel(owner: ViewModelStoreOwner): TrackingViewModel =
        ViewModelProvider(owner, TrackingViewModelFactory)[TrackingViewModel::class.java]

    private class TestStoreOwner(
        override val viewModelStore: ViewModelStore,
    ) : ViewModelStoreOwner

    private class TrackingViewModel : ViewModel() {
        var clearCount = 0
            private set

        override fun onCleared() {
            clearCount += 1
        }
    }

    private object PrivateSessionStoreFactory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PrivateSessionStoreViewModel::class.java)
            return PrivateSessionStoreViewModel() as T
        }
    }

    private object TrackingViewModelFactory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TrackingViewModel::class.java)
            return TrackingViewModel() as T
        }
    }
}
