package com.linkvault.app

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ColdShareFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var fixture: CaptureCompletionFixture

    @Before
    fun setUp() {
        fixture = CaptureCompletionFixture()
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun coldSharedSubjectAndSingleUrlSurviveRecreationWithoutSaving(): Unit = runBlocking {
        val url = "https://example.com/cold-article"
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, "공유한 기사 제목")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithTag("capture-screen").assertDoesNotExist()
            compose.onNodeWithText("선택한 링크 보관").assertDoesNotExist()
            compose.onNodeWithText(url).assertExists()
            compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("공유한 앱으로 돌아가기").assertDoesNotExist()
            assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
            scenario.recreate()
            compose.onNodeWithTag("capture-screen").assertDoesNotExist()
            compose.onNodeWithText(url).assertExists()
            compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("제목 (선택 사항)").assertDoesNotExist()
            compose.onNodeWithText("메모 (선택 사항)").assertDoesNotExist()
            compose.onNodeWithText("공유한 앱으로 돌아가기").assertDoesNotExist()
            assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
            fixture.awaitDraftWrites()
            assertEquals("공유한 기사 제목\n$url", fixture.outbox
                .readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)?.text)
            compose.onNodeWithText("링크 입력으로 돌아가기").performClick()
            compose.onNodeWithText("공유 텍스트 또는 원문 URL")
                .assertTextContains("공유한 기사 제목\n$url")
            compose.onNodeWithText("URL 후보 1개").assertExists()
            compose.onNodeWithText("선택한 링크 보관").performScrollTo().assertIsEnabled()
        }
    }

    @Test
    fun coldSubjectOnlyShareStillExtractsUrl(): Unit = runBlocking {
        val url = "https://example.com/subject-only"
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, url)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithTag("capture-screen").assertDoesNotExist()
            compose.onNodeWithText(url).assertExists()
            compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("공유한 앱으로 돌아가기").assertDoesNotExist()
            assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
            compose.onNodeWithText("링크 입력으로 돌아가기").performClick()
            compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(url)
            compose.onNodeWithText("URL 후보 1개").assertExists()
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
        }
    }

    @Test
    fun initialShareAndSelectedLinkSurviveRecreation() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/first https://example.com/second")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithTag("capture-screen").assertIsDisplayed()
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("https://example.com/second").performScrollTo().performClick()
            scenario.recreate()
            compose.onNodeWithText("URL 후보 2개").assertExists()
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
        }
    }

    @Test
    fun coldImageShareWithoutUrlCannotOpenOriginal() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/jpeg"
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithTag("capture-screen").assertIsDisplayed()
            compose.onNodeWithText("이미지 공유 요청을 받았습니다.", substring = true).assertExists()
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
        }
    }
}
