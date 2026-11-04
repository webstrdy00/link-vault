package com.linkvault.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.linkvault.app.attachment.AttachmentRepository
import com.linkvault.app.attachment.IncomingImageCaptureException
import com.linkvault.app.attachment.IncomingImageCaptureFailure
import com.linkvault.app.attachment.IncomingImageStore
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.SessionBoundary
import com.linkvault.app.library.LibrarySaveStatus
import com.linkvault.app.library.LibraryViewModel
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import com.linkvault.app.storage.UploadWorker
import com.linkvault.app.storage.VaultDatabase
import com.linkvault.app.test.FixtureImageProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                compose.onNodeWithTag("root-capture").performClick()
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
                    compose.onAllNodesWithText("이어쓰기").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("root-library").assertIsSelected()
                compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertDoesNotExist()
                compose.onNodeWithText("이어쓰기").performClick()
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

    @Test
    fun unconfirmedSingleShareDraftResumesFromLauncherWithoutShareReturn(): Unit = runBlocking {
        CaptureCompletionFixture().use { fixture ->
            val url = "https://example.com/unconfirmed-draft"
            val text = "공유한 초안\n$url"
            val shareIntent = Intent(fixture.app, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, "공유한 초안")
            }
            ActivityScenario.launch<MainActivity>(shareIntent).use {
                compose.onNodeWithText("선택한 링크 보관").assertDoesNotExist()
                compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
                fixture.awaitDraftWrites()
                assertEquals(text, fixture.outbox.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)?.text)
                assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
            }
            val launchIntent = Intent(fixture.app, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            ActivityScenario.launch<MainActivity>(launchIntent).use {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("이어쓰기").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("공유한 앱으로 돌아가기").assertDoesNotExist()
                compose.onNodeWithText("저장").assertDoesNotExist()
                compose.onNodeWithText("이어쓰기").performClick()
                compose.onNodeWithText("공유 텍스트 또는 원문 URL").assertTextContains(text)
                compose.onNodeWithText("선택한 링크 보관").performScrollTo().assertIsEnabled()
                assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
            }
        }
    }

    @Test
    fun directTextShareDoesNotAttachOrConsumeAnEarlierIncomingImage(): Unit = runBlocking {
        CaptureCompletionFixture().use { fixture ->
            val sourceDirectory = File(fixture.app.cacheDir, "fixture_images").apply {
                check(exists() || mkdirs())
            }
            val source = File(sourceDirectory, "unattached-text-share.png").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            fixture.app.incomingImageStore.clear()
            try {
                val image = fixture.app.incomingImageStore.capture(
                    FixtureImageProvider.uriForFile(source), fixture.app.incomingImageStore.generation(),
                )
                val intent = Intent(fixture.app, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://example.com/no-implicit-image")
                }
                ActivityScenario.launch<MainActivity>(intent).use {
                    compose.onNodeWithTag("capture-screen").assertDoesNotExist()
                    compose.onNodeWithText("선택한 링크 보관").assertDoesNotExist()
                    assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
                    compose.onNodeWithText("저장").performScrollTo().performClick()
                    val request = fixture.awaitRequest()
                    assertEquals(listOf(request.requestId), fixture.outbox
                        .observeOutbox(fixture.ownerId).first().map { it.requestId })
                    assertEquals(setOf("url", "shared_text"), Json
                        .parseToJsonElement(request.payloadJson).jsonObject.keys)
                    assertEquals(image.id, fixture.app.incomingImageStore.draft.value?.id)
                    assertTrue(source.isFile)
                }
            } finally {
                fixture.app.incomingImageStore.clear()
                source.delete()
            }
        }
    }

    @Test
    fun pendingCaptureCompletionSurvivesRecreationAndDoesNotRestoreOnFreshLaunch(): Unit = runBlocking {
        CaptureCompletionFixture().use { fixture ->
            val shareIntent = Intent(fixture.app, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://example.com/recreated-completion")
            }
            ActivityScenario.launch<MainActivity>(shareIntent).use { scenario ->
                try {
                    compose.onNodeWithText("선택한 링크 보관").assertDoesNotExist()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("저장").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(fixture.outbox.observeOutbox(fixture.ownerId).first().isEmpty())
                    compose.onNodeWithText("공유한 앱으로 돌아가기").assertDoesNotExist()
                    compose.onNodeWithText("저장").performScrollTo().performClick()
                    val request = fixture.awaitRequest()
                    scenario.recreate()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("공유한 앱으로 돌아가기").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(listOf(request.requestId), fixture.outbox
                        .observeOutbox(fixture.ownerId).first().map { it.requestId })
                    fixture.complete(request)
                    fixture.awaitAcknowledged(request.requestId, compose)
                    compose.onNodeWithText("공유한 앱으로 돌아가기").performScrollTo().assertIsEnabled()
                    scenario.onActivity { assertFalse(it.isFinishing) }
                    compose.onNodeWithTag("root-library").performClick()
                    compose.onNodeWithText("이어쓰기").assertDoesNotExist()
                    fixture.awaitDraftWrites()
                    assertNull(fixture.outbox.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID))
                } finally {
                    scenario.onActivity { it.intent = Intent(shareIntent) }
                }
            }
            val launchIntent = Intent(fixture.app, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            ActivityScenario.launch<MainActivity>(launchIntent).use {
                compose.onNodeWithText("공유한 앱으로 돌아가기").assertDoesNotExist()
                compose.onNodeWithText("이어쓰기").assertDoesNotExist()
                compose.onNodeWithTag("root-capture").performClick()
                compose.onNodeWithText("선택한 링크 보관").performScrollTo().assertIsNotEnabled()
            }
        }
    }

    @Test
    fun quotaRejectedCaptureReconfirmationAfterRecreationPreservesOwnerAndPayload(): Unit = runBlocking {
        assertQuotaReconfirmation(fromQueue = false)
    }

    @Test
    fun quotaRejectedQueueReconfirmationPreservesOwnerAndPayload(): Unit = runBlocking {
        assertQuotaReconfirmation(fromQueue = true)
    }

    @Test
    fun confirmedSaveFailuresRejectStaleSameIdRetries(): Unit = runBlocking {
        for (code in listOf("ITEM_LIMIT_REACHED", "URL_HASH_COLLISION", "IDEMPOTENCY_MISMATCH")) {
            withSaveViewModel { fixture, model ->
                compose.runOnUiThread { model.save() }
                val request = fixture.awaitRequest()
                fixture.fail(request, errorCode = "NETWORK_ERROR")
                withTimeout(10_000) {
                    model.uiState.first { (it.saveStatus as? LibrarySaveStatus.Failed)?.canRetrySameRequest == true }
                }
                awaitRetryMutations(model) {
                    // Commit the rejection while the UI still displays the earlier network failure.
                    runBlocking {
                        fixture.database.outboxDao().retry(
                            fixture.ownerId, request.requestId, System.currentTimeMillis(),
                        )
                        fixture.fail(request, errorCode = code)
                    }
                    assertTrue((model.uiState.value.saveStatus as LibrarySaveStatus.Failed).canRetrySameRequest)
                    model.retrySave()
                    model.retryOperation(request.requestId)
                }
                val retained = checkNotNull(fixture.database.outboxDao().findByRequestId(request.requestId))
                assertEquals(OutboxState.FAILED, retained.state)
                assertEquals(code, retained.errorCode)
                assertEquals(2, retained.attemptCount)
                assertEquals(request.payloadJson, retained.payloadJson)
                assertEquals(1, fixture.outbox.observeOutbox(fixture.ownerId).first().size)
                withTimeout(10_000) {
                    model.uiState.first {
                        val failure = it.saveStatus as? LibrarySaveStatus.Failed
                        failure != null && !failure.canRetrySameRequest &&
                            failure.needsReconfirmation == (code == "ITEM_LIMIT_REACHED")
                    }
                }
                awaitRetryMutations(model) {
                    model.retrySave()
                    model.retryOperation(request.requestId)
                }
                assertEquals(retained, fixture.database.outboxDao().findByRequestId(request.requestId))
            }
        }
    }

    @Test
    fun ambiguousSaveFailureRetriesSameRequestAndPayload(): Unit = runBlocking {
        withSaveViewModel { fixture, model ->
            compose.runOnUiThread { model.save() }
            val request = fixture.awaitRequest()
            fixture.fail(request, errorCode = "NETWORK_ERROR")
            withTimeout(10_000) {
                model.uiState.first { (it.saveStatus as? LibrarySaveStatus.Failed)?.canRetrySameRequest == true }
            }
            awaitRetryMutations(model) { model.retrySave() }
            val retained = fixture.outbox.observeOutbox(fixture.ownerId).first().single()
            assertEquals(request.requestId, retained.requestId)
            assertEquals(request.ownerId, retained.ownerId)
            assertEquals(request.payloadJson, retained.payloadJson)
            assertEquals(request.expiresAt, retained.expiresAt)
            assertEquals(OutboxState.PENDING, retained.state)
            assertEquals(1, retained.attemptCount)
            assertNull(retained.errorCode)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @TargetApi(Build.VERSION_CODES.Q)
    fun incomingImagePointerSurvivesRecreationAndRejectsOversizeAndStaleCapture() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val sourceDirectory = File(app.cacheDir, "fixture_images").apply {
            check(exists() || mkdirs())
        }
        val smallSource = File(sourceDirectory, "capture-store-small.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val oversizedSource = File(sourceDirectory, "capture-store-oversized.png").apply {
            outputStream().use { output ->
                output.write(ByteArray(IncomingImageStore.MAX_BYTES.toInt()))
                output.write(1)
            }
        }
        val now = AtomicLong(2_000_000L)
        val store = IncomingImageStore(app) { now.get() }
        try {
            store.clear()
            val smallUri = FixtureImageProvider.uriForFile(smallSource)
            val oversizedUri = FixtureImageProvider.uriForFile(oversizedSource)
            assertEquals("${app.packageName}.test.fixture-images", smallUri.authority)
            assertEquals("image/png", app.contentResolver.getType(smallUri))
            assertEquals("image/png", app.contentResolver.getType(oversizedUri))

            val captured = store.capture(smallUri, store.generation())
            val capturedFile = File(requireNotNull(captured.uri.path)).canonicalFile

            assertEquals(ContentResolver.SCHEME_FILE, captured.uri.scheme)
            assertEquals(smallSource.length(), captured.byteCount)
            assertEquals("${captured.id}.png", capturedFile.name)
            assertEquals(now.get(), captured.createdAtEpochMillis)
            assertEquals(
                now.get() + IncomingImageStore.EXPIRATION_MILLIS,
                captured.expiresAtEpochMillis,
            )
            assertTrue(capturedFile.isFile)
            assertTrue(
                capturedFile.path.startsWith(
                    File(app.filesDir, "incoming_images").canonicalPath + File.separator,
                ),
            )
            assertTrue("The sharing app's original must not be deleted", smallSource.isFile)

            val recreated = IncomingImageStore(app) { now.get() }
            assertEquals(captured, recreated.draft.value)

            val invalidUriFailure = try {
                recreated.capture(Uri.fromFile(smallSource), recreated.generation())
                null
            } catch (error: IncomingImageCaptureException) {
                error.reason
            }
            assertEquals(IncomingImageCaptureFailure.INVALID_URI, invalidUriFailure)
            assertEquals(captured.id, recreated.draft.value?.id)

            val unsupportedResolver = ContentResolver.wrap(UnsupportedMimeProvider())
            val unsupportedStore = IncomingImageStore(
                ResolverContext(app, unsupportedResolver),
            ) { now.get() }
            val unsupportedUri = Uri.parse("content://synthetic.invalid/unsupported.gif")
            assertEquals("image/gif", unsupportedResolver.getType(unsupportedUri))
            val unsupportedMimeFailure = try {
                unsupportedStore.capture(unsupportedUri, unsupportedStore.generation())
                null
            } catch (error: IncomingImageCaptureException) {
                error.reason
            }
            assertEquals(
                IncomingImageCaptureFailure.UNSUPPORTED_MIME_TYPE,
                unsupportedMimeFailure,
            )
            assertEquals(captured.id, unsupportedStore.draft.value?.id)

            val oversizeFailure = try {
                recreated.capture(oversizedUri, recreated.generation())
                null
            } catch (error: IncomingImageCaptureException) {
                error.reason
            }
            assertEquals(IncomingImageCaptureFailure.SOURCE_TOO_LARGE, oversizeFailure)
            assertEquals(captured.id, recreated.draft.value?.id)

            val staleGeneration = recreated.generation()
            recreated.clear()
            val staleFailure = try {
                recreated.capture(smallUri, staleGeneration)
                null
            } catch (error: IncomingImageCaptureException) {
                error.reason
            }
            assertEquals(IncomingImageCaptureFailure.STALE_CAPTURE, staleFailure)
            assertNull(recreated.draft.value)
            assertNull(IncomingImageStore(app) { now.get() }.draft.value)
            assertFalse(capturedFile.exists())
            assertTrue(smallSource.isFile)
        } finally {
            store.clear()
            smallSource.delete()
            oversizedSource.delete()
        }
    }

    @Test
    fun incomingImageExpiresAfterTwentyFourHoursAndCleanupDeletesPrivateCopy() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val sourceDirectory = File(app.cacheDir, "fixture_images").apply {
            check(exists() || mkdirs())
        }
        val source = File(sourceDirectory, "capture-store-expiry.png").apply {
            writeBytes(byteArrayOf(5, 6, 7))
        }
        val now = AtomicLong(5_000_000L)
        val store = IncomingImageStore(app) { now.get() }
        try {
            store.clear()
            val sourceUri = FixtureImageProvider.uriForFile(source)
            assertEquals("image/png", app.contentResolver.getType(sourceUri))
            val captured = store.capture(sourceUri, store.generation())
            val privateCopy = File(requireNotNull(captured.uri.path))
            assertTrue(privateCopy.isFile)

            now.addAndGet(IncomingImageStore.EXPIRATION_MILLIS)
            val recreated = IncomingImageStore(app) { now.get() }
            assertNull(recreated.draft.value)
            recreated.cleanupExpired()

            assertFalse(privateCopy.exists())
            assertNull(IncomingImageStore(app) { now.get() }.draft.value)
            assertTrue(source.isFile)
        } finally {
            store.clear()
            source.delete()
        }
    }

    @Test
    fun multipleImageShareRequiresOneSelectionAndRetainsItAcrossRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val sourceDirectory = File(app.cacheDir, "fixture_images").apply {
            check(exists() || mkdirs())
        }
        val sources = listOf(
            "capture-multiple-first.png",
            "capture-multiple-second.png",
            "capture-warm-first.png",
            "capture-warm-second.png",
            "capture-warm-third.png",
        ).mapIndexed { index, name ->
            File(sourceDirectory, name).apply {
                writeBytes(byteArrayOf(index.toByte()))
            }
        }
        app.incomingImageStore.clear()
        val uris = sources.map(FixtureImageProvider::uriForFile)
        assertTrue(uris.all { uri -> app.contentResolver.getType(uri) == "image/png" })
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/png"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.take(2)))
        }
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                compose.onNodeWithText("가져올 이미지 하나를 선택하세요.", substring = true)
                    .performScrollTo()
                    .assertExists()
                compose.onNodeWithText("선택한 이미지 가져오기")
                    .performScrollTo()
                    .assertIsNotEnabled()
                assertNull(app.incomingImageStore.draft.value)

                compose.onNodeWithText("이미지 2").performScrollTo().performClick()
                compose.onNodeWithText("선택한 이미지 가져오기")
                    .performScrollTo()
                    .assertIsEnabled()
                assertNull(app.incomingImageStore.draft.value)

                scenario.recreate()
                compose.onNodeWithText("선택한 이미지 가져오기")
                    .performScrollTo()
                    .assertIsEnabled()
                assertNull(app.incomingImageStore.draft.value)

                scenario.onActivity { activity ->
                    activity.startActivity(
                        Intent(activity, MainActivity::class.java).apply {
                            action = Intent.ACTION_SEND_MULTIPLE
                            type = "image/png"
                            addFlags(
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                            putParcelableArrayListExtra(
                                Intent.EXTRA_STREAM,
                                ArrayList(uris.drop(2)),
                            )
                        },
                    )
                }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("이미지 3개를 받았습니다.")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("선택한 이미지 가져오기")
                    .performScrollTo()
                    .assertIsNotEnabled()
                compose.onNodeWithText("이미지 3").performScrollTo().performClick()
                compose.onNodeWithText("선택한 이미지 가져오기")
                    .performScrollTo()
                    .assertIsEnabled()
                scenario.recreate()
                compose.onNodeWithText("선택한 이미지 가져오기")
                    .performScrollTo()
                    .assertIsEnabled()
                assertNull(app.incomingImageStore.draft.value)
            }
        } finally {
            app.incomingImageStore.clear()
            sources.forEach(File::delete)
        }
    }

    private suspend fun assertQuotaReconfirmation(fromQueue: Boolean) {
        CaptureCompletionFixture().use { fixture ->
            val text = "https://example.com/quota-reconfirmation reviewed shared text"
            val shareIntent = Intent(fixture.app, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            ActivityScenario.launch<MainActivity>(shareIntent).use { scenario ->
                try {
                    compose.onNodeWithText("선택한 링크 보관").assertDoesNotExist()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("저장").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("capture-optional-details").performScrollTo().performClick()
                    compose.onNodeWithText("제목 (선택 사항)").performScrollTo()
                        .performTextReplacement("확인한 제목")
                    compose.onNodeWithText("메모 (선택 사항)").performScrollTo()
                        .performTextReplacement("확인한 메모")
                    compose.onNodeWithText("저장").performScrollTo().performClick()
                    val request = fixture.awaitRequest()
                    val reviewed = Json.parseToJsonElement(request.payloadJson).jsonObject
                    assertEquals("확인한 제목", reviewed.getValue("title").jsonPrimitive.content)
                    assertEquals("확인한 메모", reviewed.getValue("note").jsonPrimitive.content)
                    assertEquals(text, reviewed.getValue("shared_text").jsonPrimitive.content)
                    fixture.fail(
                        request,
                        errorCode = "ITEM_LIMIT_REACHED",
                        errorMessage = "보관 가능한 링크 수를 모두 사용했어요.",
                    )
                    val actionTag = if (fromQueue) {
                        compose.onNodeWithTag("root-library").performClick()
                        "outbox-save-reconfirm-${request.requestId}"
                    } else {
                        "capture-save-reconfirm"
                    }
                    scenario.recreate()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag(actionTag).fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(
                        OutboxState.FAILED,
                        fixture.database.outboxDao().findByRequestId(request.requestId)?.state,
                    )
                    compose.onNodeWithText("다시 시도").assertDoesNotExist()
                    compose.onNodeWithTag(actionTag).performScrollTo().performClick()
                    val entries = withTimeout(10_000) {
                        fixture.outbox.observeOutbox(fixture.ownerId).first { entries ->
                            entries.none { it.requestId == request.requestId } &&
                                entries.any { it.method == "POST" && it.path == "/items" }
                        }
                    }
                    val replacement = entries.single()
                    assertFalse(request.requestId == replacement.requestId)
                    assertEquals(request.ownerId, replacement.ownerId)
                    assertEquals(request.method, replacement.method)
                    assertEquals(request.path, replacement.path)
                    assertEquals(request.payloadJson, replacement.payloadJson)
                    assertEquals(OutboxState.PENDING, replacement.state)
                    assertEquals(0, replacement.attemptCount)
                    assertNull(fixture.database.outboxDao().findByRequestId(request.requestId))
                    fixture.complete(replacement)
                    fixture.awaitAcknowledged(replacement.requestId, compose)
                    compose.onNodeWithTag("root-library").performClick()
                    compose.onNodeWithText("이어쓰기").assertDoesNotExist()
                    fixture.awaitDraftWrites()
                    assertNull(fixture.outbox.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID))
                } finally {
                    scenario.onActivity { it.intent = Intent(shareIntent) }
                }
            }
        }
    }

    private suspend fun withSaveViewModel(
        block: suspend (CaptureCompletionFixture, LibraryViewModel) -> Unit,
    ) {
        CaptureCompletionFixture().use { fixture ->
            val store = ViewModelStore()
            try {
                lateinit var model: LibraryViewModel
                compose.runOnUiThread {
                    model = ViewModelProvider(
                        store,
                        LibraryViewModel.factory(
                            client = fixture.client,
                            outbox = fixture.outbox,
                            attachments = fixture.attachments,
                            ownerId = fixture.ownerId,
                            sessionGeneration = fixture.client.sessionState.value.generation,
                            initialUrl = "https://example.com/retry-contract",
                            sharedText = "https://example.com/retry-contract",
                        ),
                    )[LibraryViewModel::class.java]
                }
                block(fixture, model)
            } finally {
                compose.runOnUiThread { store.clear() }
            }
        }
    }

    private suspend fun awaitRetryMutations(model: LibraryViewModel, action: () -> Unit) {
        var jobs = emptyList<Job>()
        compose.runOnUiThread {
            val scopeJob = checkNotNull(model.viewModelScope.coroutineContext[Job])
            val previous = scopeJob.children.toSet()
            action()
            jobs = scopeJob.children.filterNot { it in previous }.toList()
        }
        withTimeout(10_000) { jobs.forEach { it.join() } }
    }

    private class ResolverContext(
        base: Context,
        private val resolver: ContentResolver,
    ) : ContextWrapper(base) {
        override fun getContentResolver(): ContentResolver = resolver
    }

    private class UnsupportedMimeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "image/gif"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}

/** Offline receipt fixture: real enqueue, Room, ViewModels and acknowledgement; no HTTP upload. */
internal class CaptureCompletionFixture : AutoCloseable {
    val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
    val ownerId = "f0000000-0000-4000-8000-000000000001"
    val database: VaultDatabase
    val client: AccountClient
    val outbox: OutboxRepository
    val attachments: AttachmentRepository
    private val originalDelegates = mutableMapOf<String, Any>()
    private val originalWorkManager: WorkManager

    init {
        awaitDraftWrites()
        check(!app.accountClient.hasSession()) { "Capture fixture requires a signed-out test app." }
        database = Room.inMemoryDatabaseBuilder(app, VaultDatabase::class.java).build()
        client = AccountClient(
            context = app,
            url = "https://capture-fixture.invalid",
            key = "fixture-public-key",
            googleWebClientId = "fixture-client",
            debug = true,
            localDataOwner = { ownerId },
            beforeSignOut = {},
            onSessionOwner = {},
        )
        // Keep list recovery offline without clearing the owner-bound session or making requests.
        val supabase = field(client, "supabase").get(client) as SupabaseClient
        @Suppress("UNCHECKED_CAST")
        val status = field(supabase.auth, "_sessionStatus").get(supabase.auth)
            as MutableStateFlow<SessionStatus>
        status.value = SessionStatus.RefreshFailure(
            RefreshFailureCause.NetworkError(IOException("Offline capture fixture")),
        )
        val boundary = field(client, "sessionBoundary").get(client) as SessionBoundary
        runBlocking {
            boundary.locked {
                beginRestore(ownerId)
                markInitialized()
            }
        }
        outbox = OutboxRepository(app, client, database)
        attachments = AttachmentRepository(app, client, outbox, database)
        originalWorkManager = WorkManager.getInstance(app)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? = if (workerClassName == UploadWorker::class.java.name) {
                        object : Worker(appContext, workerParameters) {
                            override fun doWork(): Result = Result.success()
                        }
                    } else {
                        null
                    }
                })
                .build(),
        )
        install("accountClient", client)
        install("outboxRepository", outbox)
        install("attachmentRepository", attachments)
    }

    suspend fun awaitRequest(): OutboxEntry = withTimeout(10_000) {
        database.outboxDao().observe(ownerId).first { entries ->
            entries.any { it.method == "POST" && it.path == "/items" }
        }.single { it.method == "POST" && it.path == "/items" }
    }

    suspend fun complete(entry: OutboxEntry, duplicate: Boolean = false) {
        val url = Json.parseToJsonElement(entry.payloadJson).jsonObject.getValue("url")
            .jsonPrimitive.content
        val result = buildJsonObject {
            put("duplicate", duplicate)
            put("item", buildJsonObject {
                put("id", "f0000000-0000-4000-8000-000000000002")
                put("version", 1)
                put("url", url)
                put("display_title", "완료한 링크")
            })
        }
        val claimed = claim(entry)
        check(database.outboxDao().completeSaved(
            ownerId, entry.requestId, checkNotNull(claimed.leaseUntil), result.toString(),
        ) == 1)
    }

    suspend fun fail(
        entry: OutboxEntry,
        state: OutboxState = OutboxState.FAILED,
        errorCode: String = if (state == OutboxState.RETRY) "DEPENDENCY_UNAVAILABLE" else "INVALID_BODY",
        errorMessage: String = "입력 내용을 확인해 주세요.",
    ) {
        val claimed = claim(entry)
        check(database.outboxDao().completeFailure(
            ownerId, entry.requestId, checkNotNull(claimed.leaseUntil), state,
            System.currentTimeMillis() + 60_000L,
            errorCode,
            errorMessage,
        ) == 1)
    }

    suspend fun claim(entry: OutboxEntry): OutboxEntry {
        val now = System.currentTimeMillis()
        return checkNotNull(database.outboxDao().claimNext(ownerId, now, now + 60_000L)).also {
            check(it.requestId == entry.requestId)
        }
    }

    fun awaitAcknowledged(requestId: String, compose: ComposeTestRule) {
        // Acknowledgement starts in LaunchedEffect after recomposition, so advance test frames.
        compose.waitUntil(10_000) {
            runBlocking { database.outboxDao().findByRequestId(requestId) == null }
        }
    }

    fun awaitDraftWrites() = runBlocking {
        val scope = field(app, "applicationScope").get(app) as CoroutineScope
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    private fun install(name: String, value: Any) {
        val delegate = field(app, "$name\$delegate")
        originalDelegates[name] = checkNotNull(delegate.get(app))
        delegate.set(app, lazyOf(value))
    }

    override fun close() {
        awaitDraftWrites()
        WorkManager.getInstance(app).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        originalDelegates.forEach { (name, original) -> field(app, "$name\$delegate").set(app, original) }
        WorkManagerTestInitHelper.closeWorkDatabase()
        val managerClass = Class.forName("androidx.work.impl.WorkManagerImpl")
        managerClass.getDeclaredMethod("setDelegate", managerClass).invoke(null, originalWorkManager)
        database.close()
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }
}
