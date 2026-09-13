package com.linkvault.app.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SessionOrigin(
    val generation: Long,
    val ownerId: String,
)

internal class SessionBoundaryChangedException : Exception("The authenticated session changed")

internal class SessionBoundary {
    private val mutex = Mutex()

    @Volatile
    private var state = State()

    fun generation(): Long = state.generation

    fun ownerId(): String? = state.ownerId

    fun hasSession(): Boolean = state.ownerId != null

    fun isRecoverable(): Boolean = state.recoverable

    suspend fun <T> locked(block: suspend SessionBoundary.() -> T): T =
        mutex.withLock { block() }

    fun currentOrigin(): SessionOrigin? = state.ownerId?.let { ownerId ->
        SessionOrigin(state.generation, ownerId)
    }

    fun requireGeneration(expectedGeneration: Long) {
        if (state.generation != expectedGeneration) throw SessionBoundaryChangedException()
    }

    fun requireCurrent(origin: SessionOrigin) {
        if (state.generation != origin.generation || state.ownerId != origin.ownerId) {
            throw SessionBoundaryChangedException()
        }
    }

    fun beginRestore(ownerId: String): SessionOrigin {
        require(ownerId.isNotBlank())
        val current = currentOrigin()
        if (current != null && current.ownerId == ownerId) {
            state = state.copy(recoverable = true)
            return current
        }
        state = State(
            generation = state.generation + 1,
            ownerId = ownerId,
            recoverable = true,
        )
        return requireNotNull(currentOrigin())
    }

    fun commitLogin(ownerId: String): SessionOrigin {
        require(ownerId.isNotBlank())
        state = State(
            generation = state.generation + 1,
            ownerId = ownerId,
            recoverable = false,
        )
        return requireNotNull(currentOrigin())
    }

    fun confirmAuthenticated(origin: SessionOrigin, ownerId: String) {
        requireCurrent(origin)
        if (ownerId != origin.ownerId) throw SessionBoundaryChangedException()
        state = state.copy(recoverable = false)
    }

    fun markRecoverable(origin: SessionOrigin) {
        requireCurrent(origin)
        state = state.copy(recoverable = true)
    }

    fun clear(origin: SessionOrigin) {
        requireCurrent(origin)
        clearCurrent()
    }

    fun clearCurrent() {
        state = State(generation = state.generation + 1)
    }

    private data class State(
        val generation: Long = 0,
        val ownerId: String? = null,
        val recoverable: Boolean = false,
    )
}
