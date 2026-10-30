package com.linkvault.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun openSingleSavedItem(): String {
        val itemCard = androidx.compose.ui.test.SemanticsMatcher("library item card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("detail-") == true
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(itemCard, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val node = compose.onNode(itemCard, useUnmergedTree = true)
        val itemId = checkNotNull(node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag))
            .removePrefix("detail-")
        node
            .performScrollTo()
            .performClick()
        return itemId
    }

    private suspend fun verifyDetailEditing(itemId: String, scenario: ActivityScenario<MainActivity>) {
        val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val owner = checkNotNull(app.accountClient.sessionUserId())
        val originalContent = "현재 저장된 내용\n두 번째 줄도 유지합니다."
        suspend fun currentItem() = app.accountClient.libraryRequest(owner, "/items/$itemId")
        suspend fun awaitItem(predicate: (JsonObject) -> Boolean) = withTimeout(30_000) {
            while (!predicate(currentItem())) delay(500)
        }
        fun openEditor() {
            compose.onNodeWithTag("detail-more").performScrollTo().performClick()
            compose.onNodeWithText("제목/메모 수정").performClick()
            awaitText("변경 사항 저장")
        }

        clickRoot("root-library")
        awaitText("상세 편집 검증")
        compose.onNodeWithTag("detail-$itemId").performScrollTo().performClick()
        awaitText(originalContent)
        val travelId = app.accountClient.libraryRequest(owner, "/categories")
            .getValue("categories").jsonArray.single {
                it.jsonObject.getValue("name").jsonPrimitive.content == "여행"
            }.jsonObject.getValue("id").jsonPrimitive.content
        compose.onNodeWithTag("detail-more").performScrollTo().performClick()
        compose.onNodeWithTag("detail-manage-categories").performClick()
        compose.onNodeWithTag("category-editor").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(hasTestTag("classification-choice-$travelId")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("classification-choice-$travelId").performScrollTo().performClick()
        compose.onNodeWithText("선택 저장").performScrollTo().performClick()
        awaitItem { item -> item.getValue("category_refs").jsonArray.any {
            it.jsonObject.getValue("id").jsonPrimitive.content == travelId
        } }
        compose.onNodeWithTag("category-editor-close").performClick()
        awaitText("여행")

        openEditor()
        compose.onNodeWithText("제목").assertTextContains("상세 편집 검증")
        compose.onNodeWithText("내용").assertTextContains(originalContent)
        compose.onNodeWithText("수정 취소").performScrollTo().performClick()
        assertEquals(originalContent, currentItem().getValue("note").jsonPrimitive.content)
        openEditor()
        compose.onNodeWithText("제목").performScrollTo().performTextReplacement("수정한 제목")
        compose.onNodeWithText("변경 사항 저장").performScrollTo().performClick()
        awaitItem { it.getValue("user_title").jsonPrimitive.content == "수정한 제목" }
        assertEquals(originalContent, currentItem().getValue("note").jsonPrimitive.content)
        compose.onNodeWithTag("detail-back").performScrollTo().performClick()
        awaitText(originalContent)
        openEditor()
        compose.onNodeWithText("내용").assertTextContains(originalContent)
            .performScrollTo().performTextReplacement("수정한 내용\n줄바꿈도 보존합니다.")
        compose.onNodeWithText("변경 사항 저장").performScrollTo().performClick()
        awaitItem { it.getValue("note").jsonPrimitive.content == "수정한 내용\n줄바꿈도 보존합니다." }
        assertEquals("수정한 제목", currentItem().getValue("user_title").jsonPrimitive.content)
        compose.onNodeWithTag("detail-back").performScrollTo().performClick()
        awaitText("수정한 내용\n줄바꿈도 보존합니다.")
        scenario.recreate()
        clickRoot("root-library")
        awaitText("수정한 제목")
        if (compose.onAllNodes(hasTestTag("detail-back")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("detail-back").performScrollTo().performClick()
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(hasTestTag("detail-$itemId")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("detail-$itemId").performScrollTo().performClick()
        awaitText("수정한 내용\n줄바꿈도 보존합니다.")
        awaitText("여행")
    }

    private fun verifySourceBrowsing(ids: Map<String, String>) {
        val resultCard = androidx.compose.ui.test.SemanticsMatcher("source search result") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search-item-") == true
        }
        fun awaitResults(expected: Set<String>) {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(resultCard, useUnmergedTree = true).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("search-item-") }
                    .toSet() == expected
            }
            assertEquals("", compose.onNodeWithTag("search-query").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
        clickRoot("root-discovery")
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(hasTestTag("search-query")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search-query").performScrollTo()
            .performTextReplacement("분명히없는검색어")
        compose.onNodeWithTag("search-submit").performClick()

        ids.forEach { (source, id) ->
            clickRoot("root-library")
            compose.onNodeWithTag("library-source-$source").performScrollTo().performClick()
            awaitResults(setOf(id))
        }
        clickRoot("root-library")
        compose.onNodeWithTag("library-source-instagram").performScrollTo().performClick()
        val instagramId = ids.getValue("instagram")
        awaitResults(setOf(instagramId))
        compose.onNodeWithTag("search-item-$instagramId").performScrollTo().performClick()
        awaitText("원문 열기")
        compose.onNodeWithTag("detail-back").performScrollTo().performClick()
        awaitResults(setOf(instagramId))

        clickRoot("root-library")
        compose.onNodeWithTag("library-source-all").performScrollTo().performClick()
        awaitResults(ids.values.toSet())
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
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                clickRoot("root-account")
                compose.waitUntil(timeoutMillis = 20_000) {
                    compose.onAllNodesWithText("로그인했어요").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText("승인 상태 다시 확인").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithText("승인 상태 다시 확인").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("승인 상태 다시 확인").performScrollTo().performClick()
                }
                awaitText("로그인했어요")
                val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
                val owner = checkNotNull(app.accountClient.sessionUserId())
                val sourceIds = arguments.getString("fixtureSourceIds").orEmpty()
                if (sourceIds.isNotBlank()) {
                    verifySourceBrowsing(sourceIds.split(",").associate {
                        val (source, id) = it.split(":", limit = 2)
                        source to id
                    })
                    return@use
                }
                val seededItemId = arguments.getString("fixtureSeededItemId").orEmpty()
                if (arguments.getString("fixtureDetailEdit") == "true") {
                    check(seededItemId.isNotBlank())
                    verifyDetailEditing(seededItemId, scenario)
                    return@use
                }
                if (seededItemId.isNotBlank()) {
                    clickRoot("root-library")
                    awaitText("목록 삭제 검증")
                    assertNull(app.outboxRepository.readCachedDetail(owner, seededItemId))
                    compose.onNodeWithTag("list-delete-$seededItemId").performScrollTo().performClick()
                    awaitText("링크를 삭제할까요?")
                    compose.onNodeWithText("취소").performClick()
                    assertNull(app.outboxRepository.readCachedDetail(owner, seededItemId))
                    assertEquals(seededItemId, app.accountClient.libraryRequest(owner, "/items/$seededItemId")
                        .getValue("id").jsonPrimitive.content)
                    compose.onNodeWithTag("detail-back").assertDoesNotExist()
                    compose.onNodeWithTag("list-delete-$seededItemId").performScrollTo().performClick()
                    compose.onNodeWithTag("confirm-item-delete").performClick()
                    compose.waitUntil(timeoutMillis = 30_000) {
                        compose.onAllNodes(hasTestTag("detail-$seededItemId")).fetchSemanticsNodes().isEmpty()
                    }
                    scenario.recreate()
                    clickRoot("root-library")
                    compose.onNodeWithTag("detail-$seededItemId").assertDoesNotExist()
                    assertTrue(app.accountClient.libraryRequest(owner, "/items")
                        .toString().contains(seededItemId).not())
                    return@use
                }
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
                val itemId = openSingleSavedItem()
                compose.onNodeWithTag("detail-back").assertExists()
                compose.onNodeWithText("처리 정보 보기").assertDoesNotExist()
                compose.onNodeWithText("저장된 내용 보기").assertDoesNotExist()
                compose.onNodeWithText("이미지 첨부").assertDoesNotExist()
                awaitText(expectedTitle, substring = true, timeoutMillis = 180_000)
                withTimeout(180_000) {
                    while (app.accountClient.libraryRequest(owner, "/items/$itemId")
                            ["classification_state"]?.jsonPrimitive?.content == "pending") {
                        delay(1_000)
                    }
                }
                if (fixtureKind == "instagram-travel") awaitText("여행", timeoutMillis = 180_000)
                if (arguments.getString("fixtureDelete") == "true") {
                    compose.onNodeWithTag("detail-back").performScrollTo().performClick()
                    compose.onNodeWithTag("list-delete-$itemId").performScrollTo().performClick()
                    awaitText("링크를 삭제할까요?")
                    compose.onNodeWithText("취소").performClick()
                    compose.onNodeWithTag("detail-$itemId").assertExists()
                    assertEquals(itemId, app.accountClient.libraryRequest(owner, "/items/$itemId")
                        .getValue("id").jsonPrimitive.content)
                    compose.onNodeWithTag("list-delete-$itemId").performScrollTo().performClick()
                    compose.onNodeWithTag("confirm-item-delete").performClick()
                    compose.waitUntil(timeoutMillis = 30_000) {
                        compose.onAllNodes(hasTestTag("detail-$itemId")).fetchSemanticsNodes().isEmpty()
                    }
                    scenario.recreate()
                    clickRoot("root-library")
                    compose.onNodeWithTag("detail-$itemId").assertDoesNotExist()
                    assertTrue(app.accountClient.libraryRequest(owner, "/items")
                        .toString().contains(itemId).not())
                }
            }
        } finally {
            sessionClient.auth.clearSession()
            sessionClient.close()
        }
    }
}
