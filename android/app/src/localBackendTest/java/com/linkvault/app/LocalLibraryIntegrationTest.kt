package com.linkvault.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
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

    private fun awaitText(text: String, substring: Boolean = false, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickRoot(tag: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun openSingleSavedItem() {
        val itemCard = androidx.compose.ui.test.SemanticsMatcher("library item card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("detail-") == true
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(itemCard, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(itemCard, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
    }

    @Test
    fun realAuthenticatedSessionSavesAndReloadsOriginal(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL in setOf(
            "http://10.0.2.2:18021",
            "http://127.0.0.1:18021",
        ))
        val arguments = InstrumentationRegistry.getArguments()
        val email = checkNotNull(arguments.getString("fixtureEmail"))
        val password = checkNotNull(arguments.getString("fixturePassword"))
        val fixtureUrl = checkNotNull(arguments.getString("fixtureUrl"))
        val fixtureKind = arguments.getString("fixtureKind") ?: "generic"
        val expectedTitle = when (fixtureKind) {
            "generic" -> "Example Domain"
            "threads" -> "You've got mail: a postcard from Mars!"
            "instagram-travel" -> "가을에 제주여행 가고 싶은 사람"
            else -> error("Unknown device test fixture")
        }
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
            ApplicationProvider.getApplicationContext<LinkVaultApplication>().accountClient.restoreAccount()
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                clickRoot("root-account")
                compose.waitUntil(timeoutMillis = 20_000) {
                    compose.onAllNodesWithText("로그인했어요").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText("승인 상태 다시 확인").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithText("승인 상태 다시 확인").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("승인 상태 다시 확인").performScrollTo().performClick()
                }
                awaitText("로그인했어요")
                clickRoot("root-capture")
                compose.onNodeWithText("공유 텍스트 또는 원문 URL").performScrollTo()
                    .performTextReplacement(fixtureUrl)
                compose.onNodeWithText("선택한 링크 보관").performScrollTo().performClick()
                awaitText("저장")
                compose.onNodeWithText("제목 (선택 사항)").assertDoesNotExist()
                compose.onNodeWithText("메모 (선택 사항)").assertDoesNotExist()
                compose.onNodeWithText("저장").performScrollTo().assertIsEnabled().performClick()
                awaitText("저장했어요.")
                compose.onNodeWithText("새로고침").performScrollTo().performClick()
                openSingleSavedItem()
                awaitText("처리 정보 보기")
                compose.onNodeWithText("처리 정보 보기").performScrollTo().performClick()
                awaitText("처리 정보")
                awaitText(expectedTitle, substring = true, timeoutMillis = 180_000)
                awaitText(if (fixtureKind == "generic") "링크 정보 · 일부만 가져옴" else "링크 정보 · 완료")
                val classificationLabel = if (fixtureKind == "instagram-travel") {
                    "자동 분류 · 자동 정리됨"
                } else {
                    "자동 분류 · 분류 없음"
                }
                compose.waitUntil(timeoutMillis = 180_000) {
                    compose.onAllNodesWithText(classificationLabel).fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            sessionClient.auth.clearSession()
            sessionClient.close()
        }
    }
}
