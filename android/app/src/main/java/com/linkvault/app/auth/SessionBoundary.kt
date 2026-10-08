package com.linkvault.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountSessionState(
    val generation: Long = 0,
    val ownerId: String? = null,
    val recoverable: Boolean = false,
    val initialized: Boolean = false,
)

internal data class SessionOrigin(
    val generation: Long,
    val ownerId: String,
)

internal class SessionBoundaryChangedException : Exception("The authenticated session changed")

internal class SessionBoundary {
    private val mutex = Mutex()
    private val mutableSessionState = MutableStateFlow(AccountSessionState())

    val sessionState = mutableSessionState.asStateFlow()

    fun generation(): Long = mutableSessionState.value.generation

    fun ownerId(): String? = mutableSessionState.value.ownerId

    fun hasSession(): Boolean = mutableSessionState.value.ownerId != null

    fun isRecoverable(): Boolean = mutableSessionState.value.recoverable

    suspend fun <T> locked(block: suspend SessionBoundary.() -> T): T =
        mutex.withLock { block() }

    fun currentOrigin(): SessionOrigin? = mutableSessionState.value.let { state ->
        state.ownerId?.let { ownerId -> SessionOrigin(state.generation, ownerId) }
    }

    fun requireGeneration(expectedGeneration: Long) {
        if (mutableSessionState.value.generation != expectedGeneration) {
            throw SessionBoundaryChangedException()
        }
    }

    fun requireCurrent(origin: SessionOrigin) {
        val state = mutableSessionState.value
        if (state.generation != origin.generation || state.ownerId != origin.ownerId) {
            throw SessionBoundaryChangedException()
        }
    }

    fun beginRestore(ownerId: String): SessionOrigin {
        require(ownerId.isNotBlank())
        val current = currentOrigin()
        if (current != null && current.ownerId == ownerId) {
            mutableSessionState.value = mutableSessionState.value.copy(recoverable = true)
            return current
        }
        val state = mutableSessionState.value
        mutableSessionState.value = AccountSessionState(
            generation = state.generation + 1,
            ownerId = ownerId,
            recoverable = true,
            initialized = state.initialized,
        )
        return requireNotNull(currentOrigin())
    }

    fun commitLogin(ownerId: String): SessionOrigin {
        require(ownerId.isNotBlank())
        val state = mutableSessionState.value
        mutableSessionState.value = AccountSessionState(
            generation = state.generation + 1,
            ownerId = ownerId,
            recoverable = false,
            initialized = state.initialized,
        )
        return requireNotNull(currentOrigin())
    }

    fun confirmAuthenticated(origin: SessionOrigin, ownerId: String) {
        requireCurrent(origin)
        if (ownerId != origin.ownerId) throw SessionBoundaryChangedException()
        mutableSessionState.value = mutableSessionState.value.copy(recoverable = false)
    }

    fun markRecoverable(origin: SessionOrigin) {
        requireCurrent(origin)
        mutableSessionState.value = mutableSessionState.value.copy(recoverable = true)
    }

    fun markInitialized() {
        mutableSessionState.value = mutableSessionState.value.copy(initialized = true)
    }

    fun clear(origin: SessionOrigin) {
        requireCurrent(origin)
        clearCurrent()
    }

    fun clearCurrent() {
        val state = mutableSessionState.value
        mutableSessionState.value = AccountSessionState(
            generation = state.generation + 1,
            initialized = state.initialized,
        )
    }
}
