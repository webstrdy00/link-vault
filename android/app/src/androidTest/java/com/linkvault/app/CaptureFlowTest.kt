package com.linkvault.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import com.linkvault.app.library.LIBRARY_NOTE_MAX_CODE_POINTS
import com.linkvault.app.library.LIBRARY_TITLE_MAX_CODE_POINTS
import com.linkvault.app.library.LibraryAvailability
import com.linkvault.app.library.LibrarySaveForm
import com.linkvault.app.library.LibrarySaveStatus
import com.linkvault.app.library.LibraryUiState
import com.linkvault.app.library.LibraryViewModel
import com.linkvault.app.library.SaveFormContent
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxState
import com.linkvault.app.ui.VaultTheme
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun share(
        text: String?,
        mimeType: String = "text/plain",
        multiple: Boolean = false,
        subject: String? = null,
    ) {
        val deliveryId = java.util.UUID.randomUUID().toString()
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(compose.activity, MainActivity::class.java).apply {
                    action = if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                    type = mimeType
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("test-share-delivery", deliveryId)
                    if (text != null) putExtra(Intent.EXTRA_TEXT, text)
                    if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
                },
            )
        }
        // startActivity returns before the OS delivers onNewIntent; Compose idleness alone
        // does not prove that the new share replaced the previous Activity intent.
        compose.waitUntil(10_000) {
            compose.activity.intent.getStringExtra("test-share-delivery") == deliveryId
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
    fun sharedSubjectIsIncludedAndSingleLinkNeedsNoSelection() {
        val url = "https://example.com/article"
        share(url, subject = "읽을 거리")
        compose.onNodeWithTag("capture-screen").assertExists()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL")
            .assertTextContains("읽을 거리\n$url")
        compose.onNodeWithText("URL 후보 1개").assertExists()
        compose.onNodeWithText("선택한 링크 보관").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("제목 (선택 사항)").assertDoesNotExist()
        compose.onNodeWithText("메모 (선택 사항)").assertDoesNotExist()
    }

    @Test
    fun sharedSubjectAlreadyInTextIsNotDuplicated() {
        val text = "읽을   거리\nhttps://example.com/article"
        share(text, subject = "  읽을 거리  ")
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(text)
        compose.onNodeWithText("URL 후보 1개").assertExists()
    }

    @Test
    fun sharedSubjectMatchingOnlyUrlFragmentIsStillIncluded() {
        val url = "https://example.com/article"
        share(url, subject = "article")
        compose.onNodeWithText("공유 텍스트 또는 원문 URL")
            .assertTextContains("article\n$url")
    }

    @Test
    fun sharedSubjectWithSameUrlIsNotDuplicated() {
        val url = "https://example.com/article"
        share(url, subject = url)
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(url)
        compose.onNodeWithText("URL 후보 1개").assertExists()
    }

    @Test
    fun distinctCaseSensitiveSubjectUrlStillRequiresSelection() {
        val textUrl = "https://example.com/article"
        val subjectUrl = "https://example.com/Article"
        share(textUrl, subject = subjectUrl)
        compose.onNodeWithText("공유 텍스트 또는 원문 URL")
            .assertTextContains("$subjectUrl\n$textUrl")
        compose.onNodeWithText("URL 후보 2개").assertExists()
        compose.onNodeWithText("선택한 링크 보관").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun blankSharedSubjectDoesNotChangeText() {
        val url = "https://example.com/article"
        share(url, subject = " \n ")
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(url)
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

class LinkFirstSaveFormTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showForm(
        form: LibrarySaveForm = LibrarySaveForm(
            initialUrl = "https://example.com/article",
            sharedText = "공유된 제목\nhttps://example.com/article",
        ),
        onSave: (LibrarySaveForm) -> Unit = {},
    ): MutableState<LibraryUiState> {
        val state = mutableStateOf(
            LibraryUiState(availability = LibraryAvailability.Ready, form = form),
        )
        compose.setContent {
            VaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        SaveFormContent(
                            state = state.value,
                            onTitleChange = { title ->
                                state.value = state.value.copy(
                                    form = state.value.form.copy(title = title),
                                )
                            },
                            onNoteChange = { note ->
                                state.value = state.value.copy(
                                    form = state.value.form.copy(note = note),
                                )
                            },
                            onSave = { onSave(state.value.form) },
                            onRetrySave = {},
                            onEditAfterFailure = {},
                            onReconfirmSave = {},
                        )
                    }
                }
            }
        }
        return state
    }

    @Test
    fun linkOnlyFormWaitsForExplicitSaveWithoutTitleOrNote() {
        var savedForm: LibrarySaveForm? = null
        val state = showForm(onSave = { savedForm = it })
        compose.onNodeWithText("제목 (선택 사항)").assertDoesNotExist()
        compose.onNodeWithText("메모 (선택 사항)").assertDoesNotExist()
        compose.onNodeWithText("정보를 가져오지 못해도 링크는 보관돼요.", substring = true)
            .assertExists()
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertNull(savedForm) }
        compose.onNodeWithText("저장").performClick()
        compose.runOnIdle {
            assertEquals(state.value.form, savedForm)
            assertEquals("", savedForm?.title)
            assertEquals("", savedForm?.note)
            assertEquals("공유된 제목\nhttps://example.com/article", savedForm?.sharedText)
        }
    }

    @Test
    fun manualDetailsRemainUsableAndAreSavedWhenCollapsed() {
        var savedForm: LibrarySaveForm? = null
        showForm(onSave = { savedForm = it })
        compose.onNodeWithTag("capture-optional-details").performClick()
        compose.onNodeWithText("제목 (선택 사항)").performScrollTo()
            .performTextReplacement("내가 정한 제목")
        compose.onNodeWithText("메모 (선택 사항)").performScrollTo()
            .performTextReplacement("나중에 확인할 메모")
        compose.onNodeWithTag("capture-optional-details").performScrollTo().performClick()
        compose.onNodeWithText("제목 (선택 사항)").assertDoesNotExist()
        compose.onNodeWithText("메모 (선택 사항)").assertDoesNotExist()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("내가 정한 제목", savedForm?.title)
            assertEquals("나중에 확인할 메모", savedForm?.note)
        }
        compose.onNodeWithTag("capture-optional-details").performScrollTo().performClick()
        compose.onNodeWithText("제목 (선택 사항)").assertTextContains("내가 정한 제목")
        compose.onNodeWithText("메모 (선택 사항)").assertTextContains("나중에 확인할 메모")
    }

    @Test
    fun titleValidationRevealsDetailsAndAllowsCorrection() {
        val state = showForm()
        compose.runOnIdle {
            state.value = state.value.copy(
                form = state.value.form.copy(title = "제".repeat(LIBRARY_TITLE_MAX_CODE_POINTS + 1)),
            )
        }
        compose.onNodeWithText("제목 (선택 사항)").assertExists()
        compose.onNodeWithTag("capture-optional-details").assertIsNotEnabled()
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("제목 (선택 사항)").performScrollTo()
            .performTextReplacement("수정한 제목")
        compose.onNodeWithText("메모 (선택 사항)").assertExists()
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
    }

    @Test
    fun noteValidationRevealsDetailsAndAllowsCorrection() {
        showForm(
            form = LibrarySaveForm(
                initialUrl = "https://example.com/article",
                sharedText = "",
                note = "메".repeat(LIBRARY_NOTE_MAX_CODE_POINTS + 1),
            ),
        )
        compose.onNodeWithText("메모 (선택 사항)").assertExists()
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("메모 (선택 사항)").performScrollTo()
            .performTextReplacement("수정한 메모")
        compose.onNodeWithText("메모 (선택 사항)").assertTextContains("수정한 메모")
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
    }
}

class CaptureCompletionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
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
    fun savedReceiptClearsCaptureTextAndResumeBanner(): Unit = runBlocking {
        withCapture("https://example.com/completed-capture") {
            val request = submit()
            fixture.complete(request)
            awaitText("저장했어요.")
            fixture.awaitAcknowledged(request.requestId, compose)
            compose.onNodeWithTag("root-library").performClick()
            assertCaptureCleared()
        }
    }

    @Test
    fun duplicateReceiptClearsBannerAfterReturningToLibrary(): Unit = runBlocking {
        withCapture("https://example.com/duplicate-capture") {
            val request = submit()
            compose.onNodeWithTag("root-library").performClick()
            compose.onNodeWithText("이어쓰기").assertExists()
            fixture.complete(request, duplicate = true)
            fixture.awaitAcknowledged(request.requestId, compose)
            compose.waitForIdle()
            assertCaptureCleared()
        }
    }

    @Test
    fun pendingAndFailedRequestsKeepCaptureAndResumeBanner(): Unit = runBlocking {
        val text = "https://example.com/pending-capture"
        withCapture(text) {
            val request = submit()
            compose.onNodeWithTag("root-library").performClick()
            compose.onNodeWithText("이어쓰기").assertExists()
            assertEquals(OutboxState.PENDING, fixture.database.outboxDao()
                .findByRequestId(request.requestId)?.state)
            fixture.fail(request)
            awaitText("입력 내용을 확인해 주세요.")
            compose.onNodeWithText("이어쓰기").performClick()
            compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(text)
            fixture.awaitDraftWrites()
            assertEquals(text, fixture.outbox.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)?.text)
        }
    }

    @Test
    fun oldCompletionCannotEraseNewIdenticalShareOrItsSelection(): Unit = runBlocking {
        val firstUrl = "https://example.com/share-first"
        val secondUrl = "https://example.com/share-second"
        val text = "$firstUrl $secondUrl"
        withCapture(text) { scenario ->
            compose.onNodeWithText(secondUrl).performScrollTo().performClick()
            val request = submit()
            share(scenario, text)
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(firstUrl).performScrollTo().performClick()
            compose.onNodeWithTag("root-library").performClick()
            fixture.complete(request)
            fixture.awaitAcknowledged(request.requestId, compose)
            compose.waitForIdle()
            compose.onNodeWithText("이어쓰기").performClick()
            compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(text)
            compose.onNodeWithText("원문 열기").performScrollTo().assertIsEnabled()
            fixture.awaitDraftWrites()
            val draft = fixture.outbox.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)
            assertEquals(text, draft?.text)
            assertEquals(firstUrl, draft?.selectedUrl)
        }
    }

    @Test
    fun retryRemovalExplainsServerUncertaintyAndKeepsCapture(): Unit = runBlocking {
        val text = "https://example.com/retry-removal"
        withCapture(text) {
            val request = submit()
            compose.onNodeWithTag("root-library").performClick()
            fixture.fail(request, OutboxState.RETRY)
            awaitText("연결되면 다시 동기화")
            compose.onNodeWithText(
                "서버에 이미 반영됐을 수 있어요. 대기 요청 제거는 추가 재전송만 중단하며 서버의 변경을 취소하지 않아요.",
            ).assertExists()
            compose.onNodeWithText("대기 요청 제거").performScrollTo().performClick()
            fixture.awaitAcknowledged(request.requestId, compose)
            compose.onNodeWithText("이어쓰기").performClick()
            compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(text)
        }
    }

    @Test
    fun runningRequestCannotBeDiscardedOrReleaseSaveState(): Unit = runBlocking {
        val store = ViewModelStore()
        lateinit var model: LibraryViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(
                    store,
                    LibraryViewModel.factory(
                        client = fixture.client,
                        outbox = fixture.outbox,
                        attachments = fixture.attachments,
                        ownerId = fixture.ownerId,
                        sessionGeneration = fixture.client.sessionState.value.generation,
                        initialUrl = "https://example.com/discard-race",
                        sharedText = "https://example.com/discard-race",
                    ),
                )[LibraryViewModel::class.java]
                model.save()
            }
            val request = fixture.awaitRequest()
            fixture.claim(request)
            compose.runOnUiThread { model.discardOperation(request.requestId) }
            compose.waitUntil(10_000) { model.uiState.value.queueError != null }
            assertEquals(
                "이미 전송 중이거나 완료되어 대기 요청을 취소할 수 없습니다.",
                model.uiState.value.queueError,
            )
            assertEquals(LibrarySaveStatus.Queued(request.requestId), model.uiState.value.saveStatus)
            assertEquals(OutboxState.RUNNING, fixture.database.outboxDao()
                .findByRequestId(request.requestId)?.state)
            assertEquals("https://example.com/discard-race", model.uiState.value.form.initialUrl)
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    private suspend fun withCapture(
        text: String,
        block: suspend (ActivityScenario<MainActivity>) -> Unit,
    ) {
        val launchIntent = Intent(fixture.app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            try {
                share(scenario, text)
                block(scenario)
            } finally {
                scenario.onActivity { it.intent = Intent(launchIntent) }
            }
        }
    }

    private fun share(scenario: ActivityScenario<MainActivity>, text: String) {
        scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(Intent.EXTRA_TEXT, text)
            })
        }
        awaitText(text)
    }

    private suspend fun submit(): OutboxEntry {
        compose.onNodeWithText("선택한 링크 보관").performScrollTo().performClick()
        awaitText("저장")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        return fixture.awaitRequest()
    }

    private fun assertCaptureCleared() {
        compose.onNodeWithText("이어쓰기").assertDoesNotExist()
        compose.onNodeWithTag("root-capture").performClick()
        compose.onNodeWithText("공유 텍스트 또는 원문 URL").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithText("원문 열기").performScrollTo().assertIsNotEnabled()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
