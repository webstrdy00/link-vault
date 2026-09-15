package com.linkvault.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBoundaryTest {
    @Test
    fun `session state publishes restore readiness without advancing identity`() = runBlocking {
        val boundary = SessionBoundary()

        assertEquals(AccountSessionState(), boundary.sessionState.value)

        val origin = boundary.locked { beginRestore(OWNER_A) }
        assertEquals(
            AccountSessionState(
                generation = origin.generation,
                ownerId = OWNER_A,
                recoverable = true,
            ),
            boundary.sessionState.value,
        )

        boundary.locked { markInitialized() }
        assertEquals(
            AccountSessionState(
                generation = origin.generation,
                ownerId = OWNER_A,
                recoverable = true,
                initialized = true,
            ),
            boundary.sessionState.value,
        )

        boundary.locked { confirmAuthenticated(origin, OWNER_A) }
        assertEquals(
            AccountSessionState(
                generation = origin.generation,
                ownerId = OWNER_A,
                initialized = true,
            ),
            boundary.sessionState.value,
        )

        val repeatedOrigin = boundary.locked { beginRestore(OWNER_A) }
        assertEquals(origin, repeatedOrigin)
        assertEquals(
            AccountSessionState(
                generation = origin.generation,
                ownerId = OWNER_A,
                recoverable = true,
                initialized = true,
            ),
            boundary.sessionState.value,
        )
    }

    @Test
    fun `ready signed out state preserves generation until an identity commit`() = runBlocking {
        val boundary = SessionBoundary()

        boundary.locked { markInitialized() }
        assertEquals(
            AccountSessionState(initialized = true),
            boundary.sessionState.value,
        )

        val origin = boundary.locked { commitLogin(OWNER_A) }
        assertEquals(
            AccountSessionState(
                generation = origin.generation,
                ownerId = OWNER_A,
                initialized = true,
            ),
            boundary.sessionState.value,
        )
    }

    @Test
    fun `delayed response from A is rejected after logout and login B`() = runBlocking {
        val boundary = SessionBoundary()
        val originA = boundary.locked { commitLogin(OWNER_A) }
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()

        val delayedResponse = async {
            requestStarted.complete(Unit)
            releaseResponse.await()
            runCatching {
                boundary.locked { requireCurrent(originA) }
            }.exceptionOrNull()
        }

        requestStarted.await()
        boundary.locked {
            clear(originA)
            commitLogin(OWNER_B)
        }
        releaseResponse.complete(Unit)

        assertTrue(delayedResponse.await() is SessionBoundaryChangedException)
        assertEquals(OWNER_B, boundary.ownerId())
        assertEquals(
            AccountSessionState(
                generation = originA.generation + 2,
                ownerId = OWNER_B,
            ),
            boundary.sessionState.value,
        )
    }

    @Test
    fun `auth mutex makes replacement login wait for refresh commit`() = runBlocking {
        val boundary = SessionBoundary()
        val originA = boundary.locked { commitLogin(OWNER_A) }
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val loginEntered = CompletableDeferred<Unit>()

        val refresh = async {
            boundary.locked {
                requireCurrent(originA)
                refreshEntered.complete(Unit)
                releaseRefresh.await()
                confirmAuthenticated(originA, OWNER_A)
            }
        }
        refreshEntered.await()

        val replacementLogin = async {
            boundary.locked {
                loginEntered.complete(Unit)
                commitLogin(OWNER_B)
            }
        }
        yield()
        assertFalse(loginEntered.isCompleted)

        releaseRefresh.complete(Unit)
        refresh.await()
        val originB = replacementLogin.await()

        assertTrue(loginEntered.isCompleted)
        assertEquals(OWNER_B, originB.ownerId)
        assertEquals(OWNER_B, boundary.ownerId())
    }

    @Test
    fun `same login refresh preserves request generation`() = runBlocking {
        val boundary = SessionBoundary()
        val origin = boundary.locked { commitLogin(OWNER_A) }

        boundary.locked { confirmAuthenticated(origin, OWNER_A) }
        val afterRefresh = boundary.locked {
            requireCurrent(origin)
            currentOrigin()
        }

        assertEquals(origin, afterRefresh)
    }

    @Test
    fun `recoverable refresh failure preserves identity helpers`() = runBlocking {
        val boundary = SessionBoundary()
        val origin = boundary.locked { commitLogin(OWNER_A) }

        boundary.locked { markRecoverable(origin) }

        assertTrue(boundary.hasSession())
        assertEquals(OWNER_A, boundary.ownerId())
        assertTrue(boundary.isRecoverable())
        assertEquals(origin, boundary.currentOrigin())
        assertEquals(
            AccountSessionState(
                generation = origin.generation,
                ownerId = OWNER_A,
                recoverable = true,
            ),
            boundary.sessionState.value,
        )
    }

    @Test
    fun `clearing only succeeds for the originating generation`() = runBlocking {
        val boundary = SessionBoundary()
        val firstOrigin = boundary.locked { commitLogin(OWNER_A) }
        val secondOrigin = boundary.locked {
            clear(firstOrigin)
            commitLogin(OWNER_A)
        }

        val staleClear = runCatching {
            boundary.locked { clear(firstOrigin) }
        }.exceptionOrNull()

        assertNotNull(staleClear)
        assertTrue(staleClear is SessionBoundaryChangedException)
        assertEquals(secondOrigin, boundary.currentOrigin())
        assertEquals(OWNER_A, boundary.ownerId())
        assertEquals(
            AccountSessionState(
                generation = secondOrigin.generation,
                ownerId = OWNER_A,
            ),
            boundary.sessionState.value,
        )

        boundary.locked { markInitialized() }
        boundary.locked { clear(secondOrigin) }
        assertFalse(boundary.hasSession())
        assertNull(boundary.ownerId())
        assertEquals(
            AccountSessionState(
                generation = secondOrigin.generation + 1,
                initialized = true,
            ),
            boundary.sessionState.value,
        )
    }

    private companion object {
        const val OWNER_A = "00000000-0000-0000-0000-00000000000a"
        const val OWNER_B = "00000000-0000-0000-0000-00000000000b"
    }
}
