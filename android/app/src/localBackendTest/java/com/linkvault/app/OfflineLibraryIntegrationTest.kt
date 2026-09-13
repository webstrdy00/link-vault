package com.linkvault.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.storage.OutboxState
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.VaultDatabase
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs only with localBackendTests=true and real disposable local Auth fixtures. */
class OfflineLibraryIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun argument(name: String) = checkNotNull(arguments.getString(name))
    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
    private fun launchIntent() = Intent(app, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    @Test
    fun queueWhileOffline(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:54321")
        val sessionClient = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            defaultLogLevel = LogLevel.NONE
            install(Auth)
        }
        try {
            app.accountClient.signOut()
            val retainedOwner = java.util.UUID.randomUUID().toString()
            val retainedDatabase = VaultDatabase.create(app)
            try {
                val now = System.currentTimeMillis()
                retainedDatabase.outboxDao().insertImmutable(OutboxEntry(
                    requestId = java.util.UUID.randomUUID().toString(),
                    ownerId = retainedOwner,
                    method = "POST",
                    path = "/items",
                    payloadJson = "{\"url\":\"https://example.com/retained-before-login\"}",
                    state = OutboxState.WAITING_LOGIN,
                    attemptCount = 1,
                    createdAt = now,
                    expiresAt = now + 86_400_000L,
                    nextAttemptAt = now,
                ))
            } finally {
                retainedDatabase.close()
            }
            assertEquals(null, app.accountClient.sessionUserId())
            app.accountClient.signOut()
            assertTrue(app.outboxRepository.observeOutbox(retainedOwner).first().isEmpty())
            sessionClient.auth.awaitInitialization()
            sessionClient.auth.signInWith(Email) {
                email = argument("fixtureEmail")
                password = argument("fixturePassword")
            }
            app.accountClient.restoreAccount()
            val owner = checkNotNull(app.accountClient.sessionUserId())
            ActivityScenario.launch<MainActivity>(launchIntent()).use {
                compose.onNodeWithText("보관함").performScrollTo().performClick()
                awaitText("온라인 캐시 기준")
                compose.onNodeWithText("뒤로").performClick()
                shell("svc wifi disable")
                shell("svc data disable")
                val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                compose.waitUntil(20_000) { connectivity.activeNetwork == null }
                compose.onNodeWithText("공유 텍스트 또는 원문 URL").performScrollTo()
                    .performTextReplacement(argument("fixtureUrl"))
                compose.onNodeWithText("선택한 링크 보관").performScrollTo().performClick()
                awaitText("서버에 보관")
                compose.onNodeWithText("나중에 찾을 메모").performScrollTo()
                    .performTextReplacement("오프라인 재시작 보관 검증")
                compose.onNodeWithText("서버에 보관").performScrollTo().assertIsEnabled().performClick()
                val pending = withTimeout(15_000) {
                    app.outboxRepository.observeOutbox(owner).first { rows -> rows.isNotEmpty() }
                }.single()
                assertEquals(owner, pending.ownerId)
                assertTrue(pending.payloadJson.contains("오프라인 재시작 보관 검증"))
                assertTrue(pending.state != OutboxState.SAVED)
                File(app.filesDir, "m2-fixture-receipt.json").writeText(buildJsonObject {
                    put("requestId", pending.requestId)
                    put("ownerId", pending.ownerId)
                    put("payloadJson", pending.payloadJson)
                }.toString())
                // Keep real GoTrue credentials; expire only the SDK's local expiry metadata.
                // The host restarts offline and observes the actual refresh-failure UI.
                val session = checkNotNull(sessionClient.auth.currentSessionOrNull())
                sessionClient.auth.sessionManager.saveSession(
                    session.copy(expiresAt = Clock.System.now() - 1.seconds),
                )
            }
        } finally {
            // Preserve the real SDK session for the host's force-stop/restart phase.
            sessionClient.close()
        }
    }

    @Test
    fun verifyRestoredSaveAndEdit(): Unit = runBlocking {
        app.accountClient.restoreAccount()
        val owner = checkNotNull(app.accountClient.sessionUserId())
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            compose.onNodeWithText("보관함").performScrollTo().performClick()
            awaitText("오프라인 재시작 보관 검증", substring = true)
            compose.onNodeWithTag("detail-${argument("fixtureItemId")}").performScrollTo().performClick()
            awaitText("서버 처리 상태")
            compose.onNodeWithText("오프라인 재시작 보관 검증").assertExists()
            // A wrong queued owner must be rejected before any HTTP request can write it.
            val rejected = runCatching {
                app.accountClient.libraryRequest(
                    expectedOwnerId = "00000000-0000-0000-0000-000000000001",
                    path = "/items",
                    method = "POST",
                    body = "{\"url\":\"https://example.com/wrong-owner\"}",
                    requestId = java.util.UUID.randomUUID().toString(),
                )
            }.exceptionOrNull()
            assertTrue(rejected is com.linkvault.app.auth.AccountClientException)
            assertEquals(owner, app.accountClient.sessionUserId())
            compose.onNodeWithText("제목·메모 수정").performScrollTo().performClick()
            val current = app.accountClient.libraryRequest(owner, "/items/${argument("fixtureItemId")}")
            app.accountClient.libraryRequest(
                expectedOwnerId = owner,
                path = "/items/${argument("fixtureItemId")}",
                method = "PATCH",
                requestId = java.util.UUID.randomUUID().toString(),
                body = buildJsonObject {
                    put("expected_version", current.getValue("version").jsonPrimitive.long)
                    put("note", "외부에서 먼저 수정한 메모")
                }.toString(),
            )
            compose.onNodeWithText("나중에 찾을 메모").performScrollTo()
                .performTextReplacement("재시작 후 편집 검증")
            compose.onNodeWithText("수정 요청 보관").performScrollTo().performClick()
            awaitText("변경 충돌", substring = true)
            compose.onAllNodesWithText("최신 내용 확인")[0].performScrollTo().performClick()
            awaitText("외부에서 먼저 수정한 메모", substring = true)
            compose.onNodeWithText("다시 저장").performScrollTo().performClick()
            awaitText("제목·메모를 저장했어요.")
            compose.onNodeWithText("수정 닫기").performScrollTo().performClick()
            scenario.recreate()
            awaitText("재시작 후 편집 검증")
            compose.onNodeWithText("뒤로").performClick()
            shell("svc wifi disable")
            shell("svc data disable")
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            compose.waitUntil(20_000) { connectivity.activeNetwork == null }
            val cancelledRequest = java.util.UUID.randomUUID().toString()
            app.outboxRepository.enqueue(
                owner, cancelledRequest, "POST", "/items",
                "{\"url\":\"https://example.com/discard-$cancelledRequest\"}",
            )
            compose.onNodeWithText("회원 계정").performScrollTo().performClick()
            awaitText("로그아웃")
            compose.onNodeWithText("로그아웃").performScrollTo().performClick()
            awaitText("로그아웃할까요?")
            compose.onNodeWithText("취소").performScrollTo().performClick()
            assertTrue(app.outboxRepository.observeOutbox(owner).first().any { it.requestId == cancelledRequest })
            compose.onNodeWithText("로그아웃").performScrollTo().performClick()
            awaitText("로그아웃 및 기기 자료 삭제")
            compose.onNodeWithText("로그아웃 및 기기 자료 삭제").performScrollTo().performClick()
            withTimeout(40_000) {
                while (app.accountClient.sessionUserId() != null) delay(50)
            }
            assertTrue(app.outboxRepository.observeOutbox(owner).first().isEmpty())
            assertTrue(app.outboxRepository.cachedItems(owner).first().isEmpty())
            File(app.filesDir, "m2-fixture-receipt.json").delete()
            shell("svc wifi enable")
            shell("svc data enable")
        }
    }
}
