package com.linkvault.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

internal class PrivateSessionStoreViewModel : ViewModel() {
    private var activeStore: SessionStore? = null

    fun storeOwnerFor(identity: PrivateSessionIdentity): ViewModelStoreOwner {
        val current = activeStore
        if (current?.identity == identity) return current.owner

        current?.owner?.viewModelStore?.clear()
        return PrivateViewModelStoreOwner().also { owner ->
            activeStore = SessionStore(identity, owner)
        }
    }

    override fun onCleared() {
        activeStore?.owner?.viewModelStore?.clear()
        activeStore = null
    }

    private data class SessionStore(
        val identity: PrivateSessionIdentity,
        val owner: ViewModelStoreOwner,
    )

    private class PrivateViewModelStoreOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}
