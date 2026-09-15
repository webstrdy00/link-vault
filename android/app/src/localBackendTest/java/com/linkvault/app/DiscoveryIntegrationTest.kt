package com.linkvault.app

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.storage.OutboxEntry
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Opt-in M3 suite backed by real local GoTrue, Edge API, worker, and PostgreSQL. */
class DiscoveryIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoText(text: String) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitMatcher(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            compose.onAllNodes(matcher, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickTag(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
    }

    private fun categoryId(name: String): String {
        val categoryCard = SemanticsMatcher("category card tag") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.let(CATEGORY_CARD_TAG::matches) == true
        } and hasAnyDescendant(hasText(name))
        awaitMatcher(categoryCard)
        val tag = compose.onNode(categoryCard, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.TestTag]
        return tag.removePrefix("category-")
    }

    private fun assertLiteralBeforeAlias(literalItemId: String, aliasItemId: String) {
        val expected = listOf("search-item-$literalItemId", "search-item-$aliasItemId")
        val wanted = expected.toSet()
        val resultCards = SemanticsMatcher("fixture search result cards") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) in wanted
        }
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            compose.onAllNodes(resultCards, useUnmergedTree = true)
                .fetchSemanticsNodes().size == expected.size
        }
        val actual = compose.onAllNodes(resultCards, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
        assertEquals("literal result must be ordered before alias result", expected, actual)
    }

    private fun fixtureItemId(argumentsName: String): String {
        val value = checkNotNull(InstrumentationRegistry.getArguments().getString(argumentsName))
        check(UUID.fromString(value).toString().equals(value, ignoreCase = true))
        return value
    }

    @Test
    fun realDiscoveryControlsPersistWorkerBackedState(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        val arguments = InstrumentationRegistry.getArguments()
        val email = checkNotNull(arguments.getString("fixtureEmail"))
        val password = checkNotNull(arguments.getString("fixturePassword"))
        val literalItemId = fixtureItemId("literalItemId")
        val aliasItemId = fixtureItemId("aliasItemId")
        val cueItemId = fixtureItemId("cueItemId")
        val application = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val sessionClient = createSupabaseClient(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            defaultLogLevel = LogLevel.NONE
            install(Auth)
        }
        try {
            // Clear the product client's boundary first, then import only a real
            // local GoTrue email session through this test-only SDK client.
            application.accountClient.signOut()
            sessionClient.auth.awaitInitialization()
            sessionClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            val intent = Intent(application, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                compose.onNodeWithText("회원 계정").performScrollTo().performClick()
                awaitText("로그인했어요")
                compose.onNodeWithText("뒤로").performClick()
                compose.onNodeWithText("보관함").performScrollTo().performClick()
                awaitText("검색·분류")
                compose.onNodeWithText("검색·분류").performClick()

                awaitTag("search-query")
                compose.onNodeWithTag("search-query").performTextReplacement("카톡 프사")
                clickTag("search-submit")
                awaitTag("search-item-$literalItemId")
                awaitTag("search-item-$aliasItemId")
                assertLiteralBeforeAlias(literalItemId, aliasItemId)
                awaitText("별칭으로 찾음")

                val workCategoryId = categoryId("업무·학습")
                compose.onNodeWithText("일치 표현 확인").performScrollTo().performClick()
                awaitText("사용자 제목: 카카오톡")
                awaitText("사용자 제목: 프로필 사진")
                compose.onNodeWithText("닫기").performClick()

                clickTag("search-item-$aliasItemId")
                awaitText("카카오톡 프로필 사진 엑셀")
                clickTag("current-category-$workCategoryId")
                awaitText("표현: 엑셀 · 필드: user_title")
                compose.onNodeWithText("신뢰도", substring = true).assertDoesNotExist()
                compose.onNodeWithText("점수", substring = true).assertDoesNotExist()

                compose.onNodeWithText("검색·분류").performScrollTo().performClick()
                awaitTag("category-name-field")
                compose.onNodeWithTag("category-name-field")
                    .performScrollTo()
                    .performTextReplacement(CUSTOM_CATEGORY_NAME)
                clickTag("category-create")
                awaitText("분류 변경이 서버에 반영됐어요.")
                val customCategoryId = categoryId(CUSTOM_CATEGORY_NAME)

                clickTag("search-item-$aliasItemId")
                awaitText("직접 선택 (1 / 5)")
                clickTag("classification-choice-$workCategoryId")
                clickTag("classification-choice-$customCategoryId")
                awaitText("직접 선택 (1 / 5)")
                compose.onNodeWithText("선택한 분류 저장").performScrollTo().performClick()
                awaitText("분류를 저장했어요.")
                awaitText("직접 선택한 분류")
                awaitTag("current-category-$customCategoryId")
                compose.onNodeWithTag(
                    "current-category-$workCategoryId",
                    useUnmergedTree = true,
                ).assertDoesNotExist()

                compose.onNodeWithText("자동 분류 다시 적용").performScrollTo().performClick()
                awaitText("직접 만든 분류는 유지하고, 기본 분류를 다시 적용합니다.")
                val ownerId = checkNotNull(application.accountClient.sessionUserId())
                val reapplyTrace = ReapplyOutboxTrace(aliasItemId)
                val traceReady = CompletableDeferred<Unit>()
                val traceJob = launch(Dispatchers.IO) {
                    application.outboxRepository.observeOutbox(ownerId).collect { entries ->
                        reapplyTrace.record(entries)
                        traceReady.complete(Unit)
                    }
                }
                traceReady.await()
                try {
                    compose.onNodeWithText("확인")
                        .performScrollTo()
                        .assertIsEnabled()
                        .performClick()
                    awaitTag("current-category-$workCategoryId")
                    awaitTag("current-category-$customCategoryId")
                    awaitNoText("직접 선택한 분류")
                } catch (error: Throwable) {
                    val screenshot = captureFixtureScreenshot()
                    val serverState = readReapplyServerState(
                        application = application,
                        itemId = aliasItemId,
                        workCategoryId = workCategoryId,
                        customCategoryId = customCategoryId,
                    )
                    throw AssertionError(
                        "Reapply UI did not converge; ${reapplyTrace.summary()}; " +
                            "$serverState; screenshot=$screenshot",
                        error,
                    )
                } finally {
                    traceJob.cancelAndJoin()
                }
                clickTag("current-category-$workCategoryId")
                awaitText("표현: 엑셀 · 필드: user_title")

                compose.onNodeWithText("검색·분류").performScrollTo().performClick()
                awaitTag("search-query")
                compose.onNodeWithTag("search-query").performTextReplacement("")
                compose.onNodeWithText("단서 보완").performScrollTo().performClick()
                clickTag("search-item-$cueItemId")
                awaitText(CUE_PROMPT)
                compose.onNodeWithText("나중에").performScrollTo().performClick()
                awaitText("현재 내용의 찾기 질문을 나중으로 미뤘어요.")
                awaitNoText(CUE_PROMPT)

                scenario.recreate()
                compose.waitForIdle()
                awaitText("서버 처리 상태")
                awaitText("제목·메모 수정")
                compose.onNodeWithText(CUE_PROMPT).assertDoesNotExist()

                compose.onNodeWithText("제목·메모 수정").performScrollTo().performClick()
                awaitText("수정 요청 보관")
                compose.onNodeWithText("나중에 찾을 메모")
                    .performScrollTo()
                    .performTextReplacement("메모")
                compose.onNodeWithText("수정 요청 보관").performScrollTo().performClick()
                awaitText("제목·메모를 저장했어요.")
                compose.onNodeWithText("수정 닫기").performScrollTo().performClick()
                awaitText(CUE_PROMPT)
            }
        } finally {
            sessionClient.auth.clearSession()
            sessionClient.close()
        }
    }

    private fun captureFixtureScreenshot(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return runCatching {
            val command = instrumentation.uiAutomation.executeShellCommand(
                "screencap -p $REAPPLY_SCREENSHOT_PATH",
            )
            ParcelFileDescriptor.AutoCloseInputStream(command).use { output ->
                val buffer = ByteArray(1_024)
                while (output.read(buffer) != -1) {
                    // Drain the command pipe so the screenshot is complete before reporting it.
                }
            }
            REAPPLY_SCREENSHOT_PATH
        }.getOrElse {
            "UNAVAILABLE"
        }
    }

    private suspend fun readReapplyServerState(
        application: LinkVaultApplication,
        itemId: String,
        workCategoryId: String,
        customCategoryId: String,
    ): String {
        return try {
            val response = application.accountClient.libraryRequest(
                expectedOwnerId = checkNotNull(application.accountClient.sessionUserId()),
                path = "/items/$itemId",
            )
            val classificationState = response["classification_state"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf(FIXED_CLASSIFICATION_STATES::contains)
                ?: "OTHER"
            val manualOverride = response["manual_override"]
                ?.jsonPrimitive
                ?.booleanOrNull
                ?.toString()
                ?: "UNKNOWN"
            val categoryIds = response["category_refs"]
                ?.jsonArray
                ?.mapNotNull { category ->
                    category.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                }
                .orEmpty()
            val version = response["version"]?.jsonPrimitive?.longOrNull?.toString() ?: "UNKNOWN"
            "server_version=$version; classification_state=$classificationState; " +
                "manual_override=$manualOverride; work_present=${workCategoryId in categoryIds}; " +
                "custom_present=${customCategoryId in categoryIds}"
        } catch (error: Exception) {
            "server_state=UNAVAILABLE; server_error=${error.fixedDiagnosticCode()}"
        }
    }

    private class ReapplyOutboxTrace(private val itemId: String) {
        private val states = mutableListOf<String>()
        private var sawRequest = false
        private var acknowledged = false
        private var errorCode = "NONE"

        @Synchronized
        fun record(entries: List<OutboxEntry>) {
            val entry = entries
                .filter {
                    it.method == "POST" &&
                        it.path == "/items/$itemId/reclassify"
                }
                .maxByOrNull(OutboxEntry::createdAt)
            if (entry == null) {
                if (sawRequest) acknowledged = true
                return
            }
            sawRequest = true
            val state = entry.state.name
            if (states.lastOrNull() != state) states += state
            entry.errorCode?.let { code ->
                errorCode = code.takeIf(FIXED_DIAGNOSTIC_ERROR_CODES::contains) ?: "OTHER"
            }
        }

        @Synchronized
        fun summary(): String {
            val requestStatus = when {
                acknowledged -> "ACKNOWLEDGED"
                sawRequest -> "PRESENT"
                else -> "NOT_SEEN"
            }
            val stateTrace = states.takeIf { it.isNotEmpty() }?.joinToString(">") ?: "NONE"
            return "request=$requestStatus; outbox_states=$stateTrace; error_code=$errorCode"
        }
    }

    private fun Throwable.fixedDiagnosticCode(): String =
        (this as? AccountClientException)?.code.fixedDiagnosticCode()

    private fun String?.fixedDiagnosticCode(): String = when (this) {
        null -> "NONE"
        in FIXED_DIAGNOSTIC_ERROR_CODES -> this!!
        else -> "OTHER"
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 60_000L
        const val CUSTOM_CATEGORY_NAME = "에뮬레이터분류"
        const val CUE_PROMPT = "나중에 어떤 말로 찾을까요?"
        const val REAPPLY_SCREENSHOT_PATH = "/data/local/tmp/link-vault-reapply.png"
        val FIXED_CLASSIFICATION_STATES = setOf(
            "pending",
            "automatic",
            "manual",
            "unclassified",
        )
        val FIXED_DIAGNOSTIC_ERROR_CODES = setOf(
            "VERSION_CONFLICT",
            "REQUEST_EXPIRED",
            "UNAUTHENTICATED",
            "SESSION_CHANGED",
            "RATE_LIMITED",
            "DEPENDENCY_UNAVAILABLE",
            "INVALID_RESPONSE",
            "ITEM_NOT_FOUND",
            "ITEM_DELETED",
            "INVALID_BODY",
            "IDEMPOTENCY_MISMATCH",
        )
        val CATEGORY_CARD_TAG = Regex(
            "^category-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
    }
}
