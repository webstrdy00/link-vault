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
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.filters.SdkSuppress
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import com.linkvault.app.attachment.IncomingImageCaptureException
import com.linkvault.app.attachment.IncomingImageCaptureFailure
import com.linkvault.app.attachment.IncomingImageStore
import com.linkvault.app.test.FixtureImageProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
