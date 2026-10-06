package com.linkvault.app.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLogoutStateTest {
    @Test
    fun `remote cleanup failure after local clearing shows login with the warning`() = runBlocking {
        val boundary = SessionBoundary()
        boundary.locked { commitLogin(OWNER_A) }
        val confirmation = confirmation(boundary.sessionState.value)

        boundary.locked { clearCurrent() }
        val result = logoutResultState(
            confirmation = confirmation,
            session = boundary.sessionState.value,
            error = cleanupWarning(),
        )

        assertEquals(AccountUiState.Login(CLEANUP_WARNING), result)
        assertFalse(boundary.hasSession())
    }

    @Test
    fun `local data or authentication clear failures keep logout retryable`() {
        val session = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val confirmation = confirmation(session)
        for (message in listOf(
            "기기 대기 자료를 정리하지 못해 로그아웃하지 않았어요. 다시 시도해 주세요.",
            "기기 자료는 지웠지만 로그인 정보를 완전히 지우지 못했어요. 다시 시도해 주세요.",
        )) {
            val result = logoutResultState(
                confirmation = confirmation,
                session = session,
                error = AccountClientException(
                    message = message,
                    retryable = true,
                    code = "LOCAL_DATA_CLEAR_FAILED",
                ),
            )

            assertTrue(result is AccountUiState.ConfirmLogout)
            val retry = result as AccountUiState.ConfirmLogout
            assertTrue(retry.canConfirm)
            assertTrue(retry.matchesSession(session))
            assertEquals(message, retry.message)
            assertEquals(confirmation.previous, logoutPreviousState(retry, session))
        }
    }

    @Test
    fun `cancellation after external logout never restores active or pending access`() = runBlocking {
        val boundary = SessionBoundary()
        boundary.locked { commitLogin(OWNER_A) }
        val originalSession = boundary.sessionState.value
        boundary.locked { clearCurrent() }

        for (previous in listOf(active(), AccountUiState.PendingApproval)) {
            val confirmation = confirmation(originalSession, previous).copy(
                message = CLEANUP_WARNING,
            )

            assertEquals(
                AccountUiState.Login(CLEANUP_WARNING),
                logoutPreviousState(confirmation, boundary.sessionState.value),
            )
        }
    }

    @Test
    fun `same owner login with a new generation cannot confirm or restore old access`() = runBlocking {
        val boundary = SessionBoundary()
        boundary.locked { commitLogin(OWNER_A) }
        val confirmation = confirmation(boundary.sessionState.value)
        boundary.locked {
            clearCurrent()
            commitLogin(OWNER_A)
        }
        val replacementSession = boundary.sessionState.value

        assertFalse(confirmation.matchesSession(replacementSession))
        val restored = logoutPreviousState(confirmation, replacementSession)
        assertTrue(restored is AccountUiState.Error)
        assertTrue((restored as AccountUiState.Error).canRetry)
    }

    @Test
    fun `different owner cannot reuse confirmation even if generation matches`() {
        val originalSession = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val confirmation = confirmation(originalSession)
        val replacementSession = originalSession.copy(ownerId = OWNER_B)

        assertFalse(confirmation.matchesSession(replacementSession))
        assertTrue(logoutPreviousState(confirmation, replacementSession) is AccountUiState.Error)
    }

    @Test
    fun `retry confirmation binds a safe previous state to the replacement session`() = runBlocking {
        val boundary = SessionBoundary()
        boundary.locked { commitLogin(OWNER_A) }
        val obsolete = confirmation(boundary.sessionState.value).copy(
            message = "로그인 계정 상태가 변경됐어요. 기기 자료를 다시 확인해 주세요.",
            canConfirm = false,
        )
        boundary.locked {
            clearCurrent()
            commitLogin(OWNER_A)
        }
        val replacementSession = boundary.sessionState.value
        val safePrevious = logoutPreviousState(obsolete, replacementSession)
        val retry = confirmation(replacementSession, safePrevious)

        assertTrue(retry.matchesSession(replacementSession))
        val cancelledRetry = logoutPreviousState(retry, replacementSession)
        assertTrue(cancelledRetry is AccountUiState.Error)
        assertEquals(obsolete.message, (cancelledRetry as AccountUiState.Error).message)
        assertTrue(cancelledRetry.canRetry)
    }

    @Test
    fun `refresh and restore readiness changes preserve a valid previous state`() {
        val originalSession = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val refreshedSession = originalSession.copy(recoverable = true, initialized = true)
        for (previous in listOf(active(), AccountUiState.PendingApproval)) {
            val confirmation = confirmation(originalSession, previous)

            assertEquals(
                previous,
                logoutRequestPreviousState(previous, originalSession, refreshedSession),
            )
            assertTrue(confirmation.matchesSession(refreshedSession))
            assertEquals(previous, logoutPreviousState(confirmation, refreshedSession))
        }
    }

    @Test
    fun `unbound active and pending screens cannot become a restorable previous state`() {
        val session = AccountSessionState(generation = 7, ownerId = OWNER_A)
        for (previous in listOf(active(), AccountUiState.PendingApproval)) {
            val safePrevious = logoutRequestPreviousState(previous, null, session)
            val confirmation = confirmation(session, safePrevious)
            val cancelled = logoutPreviousState(confirmation, session)

            assertTrue(cancelled is AccountUiState.Error)
            assertTrue((cancelled as AccountUiState.Error).canRetry)
        }
    }

    @Test
    fun `previous access from an older same owner session is not rebound to the new session`() {
        val displayedSession = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val replacementSession = displayedSession.copy(generation = 9)
        val safePrevious = logoutRequestPreviousState(active(), displayedSession, replacementSession)
        val confirmation = confirmation(replacementSession, safePrevious)

        assertTrue(confirmation.matchesSession(replacementSession))
        val cancelled = logoutPreviousState(confirmation, replacementSession)
        assertTrue(cancelled is AccountUiState.Error)
        assertTrue((cancelled as AccountUiState.Error).canRetry)
    }

    @Test
    fun `already signed out screen cannot save stale active access into device cleanup confirmation`() {
        val displayedSession = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val signedOutSession = AccountSessionState(generation = 8)
        val safePrevious = logoutRequestPreviousState(active(), displayedSession, signedOutSession)
        val confirmation = confirmation(signedOutSession, safePrevious)

        assertEquals(AccountUiState.Login(), logoutPreviousState(confirmation, signedOutSession))
    }

    @Test
    fun `signed out device data failure before clearing can still retry`() {
        val session = AccountSessionState(generation = 7, initialized = true)
        val confirmation = confirmation(session, AccountUiState.Login())
        val message = "기기 대기 자료를 정리하지 못해 로그아웃하지 않았어요. 다시 시도해 주세요."
        val result = logoutResultState(
            confirmation = confirmation,
            session = session,
            error = AccountClientException(message = message, retryable = true),
        )

        assertTrue(result is AccountUiState.ConfirmLogout)
        assertTrue((result as AccountUiState.ConfirmLogout).canConfirm)
        assertEquals(message, result.message)
    }

    @Test
    fun `signed out confirmation still rejects an advanced generation`() {
        val session = AccountSessionState(generation = 7, initialized = true)
        val confirmation = confirmation(session, AccountUiState.Login("이전 로그인 화면"))
        val changedSession = session.copy(generation = 9)

        assertFalse(confirmation.matchesSession(changedSession))
        assertEquals(AccountUiState.Login(), logoutPreviousState(confirmation, changedSession))
    }

    @Test
    fun `successful logout does not retain a warning from an earlier failed attempt`() {
        val originalSession = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val confirmation = confirmation(originalSession).copy(message = "이전 정리 실패")
        val signedOutSession = AccountSessionState(generation = 8)

        assertEquals(
            AccountUiState.Login(),
            logoutResultState(confirmation, signedOutSession, error = null),
        )
    }

    @Test
    fun `cleanup warning during replacement login remains visible without old access`() = runBlocking {
        val boundary = SessionBoundary()
        boundary.locked { commitLogin(OWNER_A) }
        val confirmation = confirmation(boundary.sessionState.value)
        boundary.locked {
            clearCurrent()
            commitLogin(OWNER_B)
        }
        val result = logoutResultState(confirmation, boundary.sessionState.value, cleanupWarning())

        assertEquals(AccountUiState.Error(CLEANUP_WARNING, canRetry = true), result)
        assertEquals(OWNER_B, boundary.ownerId())
    }

    @Test
    fun `successful signout raced by same owner login does not claim that session is signed out`() {
        val originalSession = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val confirmation = confirmation(originalSession)
        val replacementSession = originalSession.copy(generation = 9)
        val result = logoutResultState(confirmation, replacementSession, error = null)

        assertTrue(result is AccountUiState.Error)
        assertTrue((result as AccountUiState.Error).canRetry)
    }

    @Test
    fun `nonretryable failure with unchanged identity does not advertise logout retry`() {
        val session = AccountSessionState(generation = 7, ownerId = OWNER_A)
        val result = logoutResultState(
            confirmation = confirmation(session),
            session = session,
            error = AccountClientException(message = "로그인 설정 오류", retryable = false),
        )

        assertEquals(AccountUiState.Error("로그인 설정 오류", canRetry = false), result)
    }

    @Test
    fun `unexpected failure uses the correct account or device data retry warning`() {
        for ((ownerId, message) in listOf(
            OWNER_A to "로그아웃하지 못했어요. 다시 시도해 주세요.",
            null to "기기 대기 자료를 정리하지 못했어요. 다시 시도해 주세요.",
        )) {
            val session = AccountSessionState(generation = 7, ownerId = ownerId)
            val confirmation = confirmation(
                session,
                if (ownerId == null) AccountUiState.Login() else active(),
            )
            val result = logoutResultState(confirmation, session, IllegalStateException())

            assertTrue(result is AccountUiState.ConfirmLogout)
            assertTrue((result as AccountUiState.ConfirmLogout).canConfirm)
            assertEquals(message, result.message)
        }
    }

    private fun confirmation(
        session: AccountSessionState,
        previous: AccountUiState = active(),
    ) = AccountUiState.ConfirmLogout(
        previous = previous,
        pendingCount = 3,
        expectedSessionOwner = session.ownerId,
        expectedSessionGeneration = session.generation,
    )

    private fun active() = AccountUiState.Active(
        AccountSummary(profileId = "profile-a", limits = null, usage = null),
    )

    private fun cleanupWarning() = AccountClientException(
        message = CLEANUP_WARNING,
        retryable = false,
    )

    private companion object {
        const val OWNER_A = "00000000-0000-0000-0000-00000000000a"
        const val OWNER_B = "00000000-0000-0000-0000-00000000000b"
        const val CLEANUP_WARNING =
            "이 기기의 로그인 정보는 지웠지만 서버 또는 Google 로그인 상태를 정리하지 못했어요."
    }
}
