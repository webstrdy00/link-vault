package com.linkvault.app

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun clickTag(tag: String, scrollTo: Boolean = true) {
        awaitTag(tag)
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        if (scrollTo) node.performScrollTo()
        node.performClick()
    }

    private fun clickRoot(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun openDetailCategories() {
        awaitTag("detail-back")
        awaitText("원문 열기")
        clickTag("detail-more")
        clickTag("detail-manage-categories", scrollTo = false)
        awaitText("카테고리")
    }

    private fun openCategoryManagement() {
        compose.onNodeWithText("분류 관리").performScrollTo().performClick()
        awaitTag("category-name-field")
    }

    private fun closeCategoryManagement() {
        compose.onNodeWithText("닫기").performClick()
        awaitNoText("새 분류 이름")
    }

    private fun applyNeedsCuesFilter() {
        compose.onNodeWithText("필터").performScrollTo().performClick()
        awaitText("검색 필터")
        awaitText("단서 보완이 필요한 항목만")
        val uncheckedToggle = SemanticsMatcher("unchecked filter toggle") { node ->
            node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }
        awaitMatcher(uncheckedToggle)
        compose.onNode(uncheckedToggle, useUnmergedTree = true).performClick()
        clickTag("search-filter-apply", scrollTo = false)
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
                clickRoot("root-account")
                awaitText("로그인했어요")
                clickRoot("root-library")
                clickRoot("root-discovery")

                awaitTag("search-query")
                compose.onNodeWithTag("search-query").performTextReplacement("카톡 프사")
                clickTag("search-submit")
                awaitTag("search-item-$literalItemId")
                awaitTag("search-item-$aliasItemId")
                assertLiteralBeforeAlias(literalItemId, aliasItemId)
                awaitText("별칭으로 찾음")

                openCategoryManagement()
                val workCategoryId = categoryId("업무·학습")
                closeCategoryManagement()
                compose.onNodeWithText("일치 표현 확인").performScrollTo().performClick()
                awaitText("사용자 제목: 카카오톡")
                awaitText("사용자 제목: 프로필 사진")
                compose.onNodeWithText("닫기").performClick()

                clickTag("search-item-$aliasItemId")
                awaitText("카카오톡 프로필 사진 엑셀")
                openDetailCategories()
                clickTag("current-category-$workCategoryId")
                awaitText("분류에 반영된 표현 · 엑셀")
                assertWorkExplanation(readItem(application, aliasItemId))
                compose.onNodeWithText("신뢰도", substring = true).assertDoesNotExist()
                compose.onNodeWithText("점수", substring = true).assertDoesNotExist()

                clickRoot("root-discovery")
                openCategoryManagement()
                compose.onNodeWithTag("category-name-field")
                    .performScrollTo()
                    .performTextReplacement(CUSTOM_CATEGORY_NAME)
                clickTag("category-create")
                awaitText("분류 변경이 서버에 반영됐어요.")
                val customCategoryId = categoryId(CUSTOM_CATEGORY_NAME)
                closeCategoryManagement()

                clickTag("search-item-$aliasItemId")
                openDetailCategories()
                compose.onNodeWithText("카테고리 관리").performScrollTo().performClick()
                awaitText("직접 선택 (1 / 5)")
                clickTag("classification-choice-$workCategoryId")
                clickTag("classification-choice-$customCategoryId")
                awaitText("직접 선택 (1 / 5)")
                compose.onNodeWithText("선택 저장").performScrollTo().performClick()
                awaitText("분류를 저장했어요.")
                assertEquals(
                    true,
                    readItem(application, aliasItemId)["manual_override"]
                        ?.jsonPrimitive?.booleanOrNull,
                )
                awaitTag("current-category-$customCategoryId")
                compose.onNodeWithTag(
                    "current-category-$workCategoryId",
                    useUnmergedTree = true,
                ).assertDoesNotExist()

                compose.onNodeWithText("자동 분류 다시 적용").performScrollTo().performClick()
                awaitText("직접 만든 카테고리는 유지하고 기본 카테고리를 다시 정리해요.")
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
                    compose.onNodeWithText("다시 적용")
                        .performScrollTo()
                        .assertIsEnabled()
                        .performClick()
                    awaitTag("current-category-$workCategoryId")
                    awaitTag("current-category-$customCategoryId")
                    assertEquals(
                        false,
                        readItem(application, aliasItemId)["manual_override"]
                            ?.jsonPrimitive?.booleanOrNull,
                    )
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
                awaitText("분류에 반영된 표현 · 엑셀")
                assertWorkExplanation(readItem(application, aliasItemId))

                clickRoot("root-discovery")
                awaitTag("search-query")
                compose.onNodeWithTag("search-query").performTextReplacement("")
                applyNeedsCuesFilter()
                clickTag("search-item-$cueItemId")
                openDetailCategories()
                awaitText(CUE_PROMPT)
                compose.onNodeWithText("나중에").performScrollTo().performClick()
                awaitText("현재 내용의 찾기 질문을 나중으로 미뤘어요.")
                awaitNoText(CUE_PROMPT)
                val dismissed = readItem(application, cueItemId)
                assertEquals(true, dismissed["cue_prompt_dismissed"]?.jsonPrimitive?.booleanOrNull)

                scenario.recreate()
                compose.waitForIdle()
                awaitTag("detail-back")
                compose.onNodeWithText("처리 정보 보기").assertDoesNotExist()
                openDetailCategories()
                assertEquals(
                    true,
                    readItem(application, cueItemId)["cue_prompt_dismissed"]
                        ?.jsonPrimitive?.booleanOrNull,
                )
                compose.onNodeWithText(CUE_PROMPT).assertDoesNotExist()

                clickTag("detail-more")
                awaitText("제목·메모 수정")
                compose.onNodeWithText("제목·메모 수정").performScrollTo().performClick()
                awaitText("변경 사항 저장")
                compose.onNodeWithText("메모 (선택 사항)")
                    .performScrollTo()
                    .performTextReplacement("메모")
                compose.onNodeWithText("변경 사항 저장").performScrollTo().performClick()
                awaitText("제목·메모를 저장했어요.")
                compose.onNodeWithText("상세로 돌아가기").performScrollTo().performClick()
                openDetailCategories()
                awaitText(CUE_PROMPT)
                val revised = readItem(application, cueItemId)
                assertEquals("메모", revised["note"]?.jsonPrimitive?.contentOrNull)
                assertEquals(false, revised["cue_prompt_dismissed"]?.jsonPrimitive?.booleanOrNull)
                assertEquals("limited", revised["cue_state"]?.jsonPrimitive?.contentOrNull)
                assertTrue(
                    "editing the note advances the dismissed text revision",
                    checkNotNull(revised["text_revision"]?.jsonPrimitive?.longOrNull) >
                        checkNotNull(dismissed["text_revision"]?.jsonPrimitive?.longOrNull),
                )
            }
        } finally {
            sessionClient.auth.clearSession()
            sessionClient.close()
        }
    }

    private suspend fun readItem(
        application: LinkVaultApplication,
        itemId: String,
    ): JsonObject = application.accountClient.libraryRequest(
        expectedOwnerId = checkNotNull(application.accountClient.sessionUserId()),
        path = "/items/$itemId",
    )

    private fun assertWorkExplanation(item: JsonObject) {
        assertTrue(
            "automatic work classification retains the matched user-title expression",
            item["classification_explanations"]?.jsonArray.orEmpty().any { element ->
                val explanation = element.jsonObject
                explanation["category_code"]?.jsonPrimitive?.contentOrNull == "work" &&
                    explanation["field"]?.jsonPrimitive?.contentOrNull == "user_title" &&
                    explanation["expression"]?.jsonPrimitive?.contentOrNull == "엑셀"
            },
        )
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
        const val CUE_PROMPT = "검색을 위한 메모"
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
