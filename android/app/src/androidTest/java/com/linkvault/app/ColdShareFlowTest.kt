package com.linkvault.app

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

class ColdShareFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun initialShareAndSelectedLinkSurviveRecreation() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/first https://example.com/second")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
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
            compose.onNodeWithText("이미지 공유 요청을 받았습니다.", substring = true).assertExists()
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
        }
    }
}
