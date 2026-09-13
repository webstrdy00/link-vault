package com.linkvault.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
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
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.allOf
import org.junit.Rule
import org.junit.Test

class CapturePersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun sharedTextAndSelectionSurviveFreshActivity(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val text = "https://example.com/draft-first https://example.com/draft-second"
        val selected = "https://example.com/draft-second"
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        try {
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.onNodeWithText("공유 텍스트 또는 원문 URL").performScrollTo().performTextReplacement(text)
                compose.onNodeWithText(selected).performScrollTo().performClick()
                withTimeout(10_000) {
                    while (app.outboxRepository.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)?.selectedUrl != selected) {
                        delay(20)
                    }
                }
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("URL 후보 2개").fetchSemanticsNodes().isNotEmpty()
                }
                Intents.init()
                try {
                    Intents.intending(hasAction(Intent.ACTION_VIEW))
                        .respondWith(ActivityResult(Activity.RESULT_OK, null))
                    compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled().performClick()
                    Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(selected)))
                } finally {
                    Intents.release()
                }
            }
        } finally {
            app.outboxRepository.deleteDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)
        }
    }
}
