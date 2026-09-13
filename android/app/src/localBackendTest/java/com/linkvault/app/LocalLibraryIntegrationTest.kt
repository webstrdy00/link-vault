package com.linkvault.app

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Opt-in suite: a real local GoTrue session, never a fake Google sign-in. */
class LocalLibraryIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun realAuthenticatedSessionSavesAndReloadsOriginal(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:54321")
        val arguments = InstrumentationRegistry.getArguments()
        val email = checkNotNull(arguments.getString("fixtureEmail"))
        val password = checkNotNull(arguments.getString("fixturePassword"))
        val fixtureUrl = checkNotNull(arguments.getString("fixtureUrl"))
        val sessionClient = createSupabaseClient(
            BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            defaultLogLevel = LogLevel.NONE
            install(Auth)
        }
        try {
            ApplicationProvider.getApplicationContext<LinkVaultApplication>().accountClient.signOut()
            sessionClient.auth.awaitInitialization()
            sessionClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.onNodeWithText("회원 계정").performScrollTo().performClick()
                awaitText("베타 이용 승인 대기 중이에요")
                compose.onNodeWithText("승인 상태 다시 확인").performScrollTo().performClick()
                awaitText("로그인했어요")
                compose.onNodeWithText("뒤로").performClick()
                compose.onNodeWithText("공유 텍스트 또는 원문 URL").performScrollTo()
                    .performTextReplacement(fixtureUrl)
                compose.onNodeWithText("선택한 링크 보관").performScrollTo().performClick()
                awaitText("서버에 보관")
                compose.onNodeWithText("나중에 찾을 메모").performScrollTo()
                    .performTextReplacement("에뮬레이터 실제 서버 저장 검증")
                compose.onNodeWithText("서버에 보관").performScrollTo().assertIsEnabled().performClick()
                awaitText("서버에 보관했어요.")
                compose.onNodeWithText("새로고침").performScrollTo().performClick()
                awaitText("상세")
                compose.onNodeWithText("상세").performScrollTo().performClick()
                awaitText("서버 처리 상태")
                compose.onNodeWithText("에뮬레이터 실제 서버 저장 검증").assertExists()
            }
        } finally {
            sessionClient.auth.clearSession()
            sessionClient.close()
        }
    }
}
