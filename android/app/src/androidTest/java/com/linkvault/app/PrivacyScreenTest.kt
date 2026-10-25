package com.linkvault.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivacyScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun loggedOutAccountOpensLocalPolicyWithoutExternalNavigation() {
        compose.runOnIdle {
            assertFalse((compose.activity.application as LinkVaultApplication).accountClient.hasSession())
        }
        Intents.init()
        try {
            openPrivacy()
            compose.onNodeWithTag("privacy-title").assertIsDisplayed()
            compose.onNodeWithText("LinkFileBox 운영자", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.privacy_operator))
                .assertExists()
            assertTrue(Intents.getIntents().none { it.action == Intent.ACTION_VIEW })
        } finally {
            Intents.release()
        }
    }

    @Test
    fun headingsAndRetentionLimitsAreReadableInsideApp() {
        openPrivacy()
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNodeWithTag("privacy-title").assert(heading)
        listOf(
            R.string.privacy_operator_title,
            R.string.privacy_data_title,
            R.string.privacy_providers_title,
            R.string.privacy_retention_title,
            R.string.privacy_requests_title,
            R.string.privacy_unresolved_title,
        ).forEach { title ->
            compose.onNodeWithText(compose.activity.getString(title))
                .performScrollTo()
                .assert(heading)
                .assertIsDisplayed()
        }
        listOf(
            R.string.privacy_status,
            R.string.privacy_data,
            R.string.privacy_providers,
            R.string.privacy_deletion,
            R.string.privacy_retention_targets,
            R.string.privacy_retention_records,
            R.string.privacy_retention_backups,
            R.string.privacy_requests,
            R.string.privacy_unresolved,
        ).forEach { text ->
            compose.onNodeWithText(compose.activity.getString(text))
                .performScrollTo()
                .assertIsDisplayed()
        }
        compose.onNodeWithText("Google 계정은 삭제되지 않습니다.", substring = true).assertExists()
        compose.onNodeWithText("30일 경과와 삭제 기록 내보내기 확인", substring = true).assertExists()
        compose.onNodeWithText("모든 사본의 삭제나 완료 시각을 보장하지 않습니다.", substring = true)
            .assertExists()
        compose.onNodeWithText("순환 목표를 넘겨 보존됩니다.", substring = true).assertExists()
        compose.onNodeWithText("법적 적합성을 보증하지 않습니다.", substring = true).assertExists()
    }

    @Test
    fun recreationAndBothBackActionsReturnToAccount() {
        openPrivacy()
        compose.onNodeWithText(compose.activity.getString(R.string.privacy_retention_title))
            .performScrollTo()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("privacy-title").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("privacy-screen").assertDoesNotExist()
        compose.onNodeWithTag("account-privacy").assertIsDisplayed()
        compose.onNodeWithTag("root-account").assertIsSelected()

        compose.onNodeWithTag("account-privacy").performClick()
        compose.onNodeWithTag("privacy-deletion-link").performScrollTo()
        compose.onNodeWithTag("privacy-back").assertIsDisplayed().performClick()
        compose.onNodeWithText("계정").assertIsDisplayed()
        compose.onNodeWithTag("privacy-screen").assertDoesNotExist()
    }

    @Test
    fun switchingRootsDoesNotReopenPreviousPolicy() {
        openPrivacy()
        compose.onNodeWithTag("root-library").performClick()
        compose.onNodeWithTag("privacy-screen").assertDoesNotExist()
        compose.onNodeWithTag("root-account").performClick()
        compose.onNodeWithText("계정").assertIsDisplayed()
        compose.onNodeWithTag("privacy-screen").assertDoesNotExist()
    }

    @Test
    fun webGuidanceDispatchesOnlyCanonicalHttpsUrls() {
        openPrivacy()
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithTag("privacy-web-link").performScrollTo().performClick()
            Intents.intended(
                allOf(hasAction(Intent.ACTION_VIEW), hasData("https://linkfilebox.com/privacy/")),
            )
            compose.onNodeWithTag("privacy-deletion-link").performScrollTo().performClick()
            Intents.intended(
                allOf(
                    hasAction(Intent.ACTION_VIEW),
                    hasData("https://linkfilebox.com/account-deletion/"),
                ),
            )
            compose.onNodeWithTag("privacy-screen").assertIsDisplayed()
        } finally {
            Intents.release()
        }
    }

    private fun openPrivacy() {
        compose.onNodeWithTag("root-account").performClick()
        compose.onNodeWithTag("account-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-screen").assertIsDisplayed()
    }
}
