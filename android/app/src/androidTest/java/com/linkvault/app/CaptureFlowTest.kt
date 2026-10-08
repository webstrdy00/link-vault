package com.linkvault.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CaptureFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var launchIntent: Intent

    @Before
    fun rememberScenarioIntent() {
        compose.runOnUiThread { launchIntent = Intent(compose.activity.intent) }
    }

    @After
    fun restoreScenarioIdentityForCleanup() {
        // ActivityScenario matches lifecycle events by the launch intent's filter.
        // Real shares replace Activity.intent; restore only after all assertions,
        // so the rule can observe destruction without altering production behavior.
        compose.runOnUiThread { compose.activity.intent = launchIntent }
    }

    private fun share(text: String?, mimeType: String = "text/plain", multiple: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(compose.activity, MainActivity::class.java).apply {
                    action = if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                    type = mimeType
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (text != null) putExtra(Intent.EXTRA_TEXT, text)
                },
            )
        }
        compose.waitForIdle()
    }

    private fun openCapture() {
        compose.onNodeWithTag("root-capture").performClick()
    }

    @Test
    fun normalLaunchStartsAtLibraryAndOffersCaptureEntry() {
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(3)
        compose.onNodeWithTag("root-library").assertIsSelected()
        compose.onNodeWithText("내 보관함").assertExists()
        compose.onNodeWithText("뒤로").assertDoesNotExist()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertDoesNotExist()
        openCapture()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertExists()
    }

    @Test
    fun librarySearchEntryAndSettingsTabSurviveRecreation() {
        compose.onNodeWithTag("library-search").performClick()
        compose.onNodeWithTag("root-discovery").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("root-discovery").assertIsSelected()
        compose.onNodeWithTag("root-account").performClick()
        compose.onNodeWithText("계정").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("root-account").assertIsSelected()
        compose.onNodeWithTag("root-library").performClick()
        compose.onNodeWithText("내 보관함").assertExists()
    }

    @Test
    fun settingsFontLicenseIsLocalAndDismissible() {
        compose.onNodeWithTag("root-account").performClick()
        compose.onNodeWithTag("font-license").performScrollTo().performClick()
        compose.onNodeWithTag("font-license-dialog").assertExists()
        compose.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true).assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("font-license-dialog").assertDoesNotExist()
        compose.onNodeWithTag("root-account").assertIsSelected()
    }

    @Test
    fun visitingSettingsPreservesSharedInput() {
        share("https://example.com/remember")
        compose.onNodeWithTag("root-account").performClick()
        compose.onNodeWithText("계정").assertExists()
        openCapture()
        compose.onNodeWithText("URL 후보 1개").assertExists()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
    }

    @Test
    fun oversizedReplacementCannotOpenPreviousValidUrl() {
        openCapture()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL")
            .performTextReplacement("https://example.com/previous")
        compose.onNodeWithText("공유 텍스트 또는 원문 URL")
            .performTextReplacement("x".repeat(20_001))
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun multipleSharedLinksRequireChoiceAndNewIntentClearsOldChoice() {
        share("첫 링크 https://example.com/first 두 번째 https://example.com/second")
        compose.onNodeWithText("URL 후보 2개").assertExists()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("https://example.com/second").performScrollTo().performClick()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
        share("https://example.org/third https://example.org/fourth")
        compose.onNodeWithText("https://example.com/second").assertDoesNotExist()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun manuallyEnteredUrlSurvivesActivityRecreation() {
        openCapture()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL")
            .performTextReplacement("https://example.com/한국어")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("URL 후보 1개").assertExists()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
    }

    @Test
    fun imageOnlyShareRequiresUrlAndDoesNotEnableOpen() {
        share(null, mimeType = "image/png", multiple = true)
        compose.onNodeWithText("이미지 공유 요청을 받았습니다.", substring = true).assertExists()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").performScrollTo()
            .performTextReplacement("https://example.com/image-source")
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
    }

    @Test
    fun openOriginalDispatchesExactSelectedUrl() {
        share("https://example.com/article?item=42")
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithText("원문 열기").performScrollTo().performClick()
            Intents.intended(
                allOf(hasAction(Intent.ACTION_VIEW), hasData("https://example.com/article?item=42")),
            )
        } finally {
            Intents.release()
        }
    }

    @Test
    fun credentialBearingUrlCannotBeOpened() {
        share("https://user:password@example.com/private")
        compose.onNodeWithText("웹 주소 형식이 올바르지 않습니다.", substring = true).assertExists()
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
    }
}
