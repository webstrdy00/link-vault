package com.linkvault.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.test.FixtureImageProvider
import com.linkvault.app.attachment.AttachmentOcrState
import com.linkvault.app.attachment.AttachmentStage
import com.linkvault.app.attachment.PendingAttachment
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in emulator suite backed by real local Auth, Edge API, Storage, Room, WorkManager, and ML Kit. */
class EnrichmentIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun argument(name: String): String = checkNotNull(arguments.getString(name))

    private fun fixtureItemId(): String = argument("fixtureItemId").also {
        check(UUID.fromString(it).toString().equals(it, ignoreCase = true))
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = UI_TIMEOUT_MILLIS,
    ) {
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodesWithText(text, substring = substring)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            shell("screencap -p /data/local/tmp/link-vault-enrichment.png")
            val queue = runBlocking {
                app.attachmentRepository.observe(argument("fixtureOwnerId"), fixtureItemId()).first()
                    .joinToString(",") { "${it.stage.name}:${it.errorCode ?: "NONE"}" }
            }
            throw AssertionError("UI state timeout; queue=${queue.ifEmpty { "NONE" }}", error)
        }
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS) {
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            shell("screencap -p /data/local/tmp/link-vault-enrichment.png")
            throw error
        }
    }

    private fun clickTag(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    private fun clickRoot(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()
    }

    private fun awaitPreviewImage() {
        compose.waitUntil(SERVER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithContentDescription(
                "첨부 이미지 미리보기",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(
            "첨부 이미지 미리보기",
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
    }

    private fun setNetwork(enabled: Boolean) {
        val verb = if (enabled) "enable" else "disable"
        shell("svc wifi $verb")
        shell("svc data $verb")
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        compose.waitUntil(NETWORK_TIMEOUT_MILLIS) {
            (connectivity.activeNetwork != null) == enabled
        }
    }

    private fun mainIntent() = Intent(app, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    private fun shareIntent(uri: Uri): Intent {
        app.grantUriPermission(
            app.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        return Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, argument("fixtureUrl"))
            clipData = ClipData.newUri(app.contentResolver, "fixture image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createFixture(name: String, secondLine: String): File {
        val directory = File(app.cacheDir, FIXTURE_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$name.png")
        val bitmap = Bitmap.createBitmap(FIXTURE_WIDTH, FIXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = FIXTURE_TEXT_SIZE
                typeface = Typeface.DEFAULT
            }
            canvas.drawText("LINK VAULT", FIXTURE_TEXT_X, FIXTURE_FIRST_BASELINE, paint)
            canvas.drawText(secondLine, FIXTURE_TEXT_X, FIXTURE_SECOND_BASELINE, paint)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        check(file.isFile && file.length() in 1L..2_000_000L)
        return file
    }

    private fun fixtureUri(file: File): Uri = FixtureImageProvider.uriForFile(file)

    private suspend fun awaitPending(
        ownerId: String,
        itemId: String,
        predicate: (PendingAttachment) -> Boolean,
    ): PendingAttachment {
        try {
            return withTimeout(QUEUE_TIMEOUT_MILLIS) {
                app.attachmentRepository.observe(ownerId, itemId)
                    .first { rows -> rows.any(predicate) }
                    .first(predicate)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            shell("screencap -p /data/local/tmp/link-vault-enrichment.png")
            val stages = app.attachmentRepository.observe(ownerId, itemId).first()
                .map { it.stage.name }.distinct().joinToString(",")
            throw AssertionError("Attachment queue timeout; stages=${stages.ifEmpty { "NONE" }}", error)
        }
    }

    private fun queuedFile(pending: PendingAttachment): File = File(
        File(app.noBackupFilesDir, "attachment_queue"),
        pending.localFileName,
    )

    private fun assertRecognizedFixture(pending: PendingAttachment, koreanWord: String) {
        assertEquals(AttachmentOcrState.READY, pending.ocrState)
        val recognized = checkNotNull(pending.ocrText)
        val condensedLatin = recognized.uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
        assertTrue("real Latin OCR result is retained", condensedLatin.contains("LINKVAULT"))
        assertTrue("real Korean OCR result is retained", recognized.contains(koreanWord))
    }

    private fun awaitDraftConsumed() {
        compose.waitUntil(QUEUE_TIMEOUT_MILLIS) {
            app.incomingImageStore.draft.value == null
        }
    }

    private fun openFixtureDetail() {
        val itemId = fixtureItemId()
        awaitTag("detail-$itemId")
        clickTag("detail-$itemId")
        awaitText("처리 정보 보기")
        compose.onNodeWithText("처리 정보 보기").performScrollTo().performClick()
        awaitText("처리 정보")
        awaitText("링크 정보 ·", substring = true)
    }

    private fun assertMetadataUiIsIndependent() {
        if (compose.onAllNodesWithText("세부 정보 보기").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("세부 정보 보기").performScrollTo().performClick()
        }
        val possibleMessages = listOf(
            "메타데이터 조회를 완료했어요. 페이지 전체 보존을 의미하지는 않아요.",
            "일부 메타데이터만 저장됐어요.",
            "이 주소는 자동 메타데이터 조회를 지원하지 않아요.",
            "메타데이터 조회를 완료하지 못했어요.",
            "서버에서 메타데이터를 조회하고 있어요.",
            "메타데이터 상태를 확인할 수 없어요.",
        )
        assertTrue(
            "metadata has a separate bounded UI state",
            possibleMessages.any { message ->
                compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
            },
        )
    }

    private suspend fun currentItem(): JsonObject = app.accountClient.libraryRequest(
        expectedOwnerId = argument("fixtureOwnerId"),
        path = "/items/${fixtureItemId()}",
    )

    private suspend fun awaitActiveAsset(differentFrom: String? = null): Pair<JsonObject, JsonObject> =
        withTimeout(SERVER_TIMEOUT_MILLIS) {
            while (true) {
                val item = try {
                    currentItem()
                } catch (error: com.linkvault.app.auth.AccountClientException) {
                    if (!error.retryable) throw error
                    delay(500)
                    continue
                }
                val asset = item["active_asset"] as? JsonObject
                if (asset != null && asset["id"]?.jsonPrimitive?.content != differentFrom) {
                    return@withTimeout item to asset
                }
                delay(500)
            }
            error("unreachable")
        }

    private fun openSearch() {
        clickRoot("root-discovery")
        awaitTag("search-query")
    }

    private fun submitSearch(query: String) {
        compose.onNodeWithTag("search-query").performTextReplacement(query)
        clickTag("search-submit")
    }

    private fun writeReceipt(update: JSONObject.() -> Unit) {
        val file = File(app.filesDir, RECEIPT_FILE)
        val receipt = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        receipt.update()
        file.writeText(receipt.toString())
    }

    @Test
    fun queueSharedImageOffline(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        val ownerId = argument("fixtureOwnerId")
        val itemId = fixtureItemId()
        val expectedVersion = argument("fixtureVersion").toLong()
        val sessionClient = createSupabaseClient(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            defaultLogLevel = LogLevel.NONE
            install(Auth)
        }
        try {
            app.accountClient.signOut()
            sessionClient.auth.awaitInitialization()
            sessionClient.auth.signInWith(Email) {
                email = argument("fixtureEmail")
                password = argument("fixturePassword")
            }
            app.accountClient.restoreAccount()
            assertEquals(ownerId, app.accountClient.sessionUserId())

            val original = createFixture("seoul", "서울 여행")
            val originalBytes = original.readBytes()
            val sharedUri = fixtureUri(original)
            assertEquals("content", sharedUri.scheme)
            val generation = app.incomingImageStore.generation()
            ActivityScenario.launch<MainActivity>(shareIntent(sharedUri)).use { scenario ->
                awaitText("공유 이미지를 임시로 가져왔습니다.")
                val captured = checkNotNull(app.incomingImageStore.draft.value)
                assertEquals(generation, app.incomingImageStore.generation())
                assertEquals("image/png", captured.mimeType)
                assertTrue(captured.byteCount > 0L)
                assertTrue(File(checkNotNull(captured.uri.path)).readBytes().contentEquals(originalBytes))

                compose.onNodeWithText("공유 이미지를 첨부할 항목 선택")
                    .performScrollTo()
                    .performClick()
                openFixtureDetail()
                assertMetadataUiIsIndependent()
                assertTrue(
                    "opening a target never starts shared-image preparation",
                    app.attachmentRepository.observe(ownerId, itemId).first().isEmpty(),
                )
                assertTrue(app.incomingImageStore.draft.value != null)
                awaitTag("attachment-import-shared")

                setNetwork(enabled = false)
                clickTag("attachment-import-shared")
                val pending = awaitPending(ownerId, itemId) { true }
                assertEquals(ownerId, pending.ownerId)
                assertEquals(itemId, pending.itemId)
                assertEquals(expectedVersion, pending.baseExpectedVersion)
                assertEquals("image/png", pending.mimeType)
                assertRecognizedFixture(pending, "서울")
                assertTrue(pending.localFileName.startsWith("$ownerId/"))
                assertTrue("same-owner prepared file is durable", queuedFile(pending).isFile)
                assertTrue("the fixture provider source remains unchanged", original.readBytes().contentEquals(originalBytes))
                awaitDraftConsumed()
                awaitText("기기에 보관됨 · 전송 대기 중")

                scenario.recreate()
                awaitTag("attachment-queue-status")
                val restored = awaitPending(ownerId, itemId) {
                    it.operationId == pending.operationId
                }
                assertEquals(pending.operationId, restored.operationId)
                assertEquals(pending.ownerId, restored.ownerId)
                assertEquals(pending.itemId, restored.itemId)
                assertEquals(pending.localFileName, restored.localFileName)
                assertEquals(pending.ocrText, restored.ocrText)
                assertTrue("Room row and same-owner file survive recreation", queuedFile(restored).isFile)

                writeReceipt {
                    put("ownerId", ownerId)
                    put("itemId", itemId)
                    put("initialOperationId", pending.operationId)
                    put("initialBaseVersion", pending.baseExpectedVersion)
                    put("initialOcrText", pending.ocrText)
                    put("initialPreparedBytes", queuedFile(pending).length())
                }
            }
        } finally {
            // The host deliberately force-stops this still-offline process before phase two.
            sessionClient.close()
        }
    }

    @Test
    fun restoreUploadSearchAndReplace(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        val ownerId = argument("fixtureOwnerId")
        val itemId = fixtureItemId()
        try {
            app.accountClient.restoreAccount()
        } catch (error: com.linkvault.app.auth.AccountClientException) {
            assertTrue("offline account restoration remains retryable", error.retryable)
        }
        assertEquals(ownerId, app.accountClient.sessionUserId())
        val receiptFile = File(app.filesDir, RECEIPT_FILE)
        val receipt = JSONObject(receiptFile.readText())
        val initialOperationId = receipt.getString("initialOperationId")
        val retained = awaitPending(ownerId, itemId) { it.operationId == initialOperationId }
        assertTrue("force-stop retains the Room row", retained.stage != AttachmentStage.SAVED)
        assertTrue("force-stop retains the same-owner prepared file", queuedFile(retained).isFile)
        assertRecognizedFixture(retained, "서울")

        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            clickRoot("root-library")
            openFixtureDetail()
            awaitText("이미지 첨부 ·", substring = true)
            setNetwork(enabled = true)
            awaitText("서버에 저장된 활성 이미지가 있어요.", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            awaitTag("attachment-preview", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            awaitPreviewImage()
            assertMetadataUiIsIndependent()

            val (firstItem, firstAsset) = awaitActiveAsset()
            val firstAssetId = firstAsset.getValue("id").jsonPrimitive.content
            val firstTextRevision = firstItem.getValue("text_revision").jsonPrimitive.long
            val stable = withTimeout(SERVER_TIMEOUT_MILLIS) {
                while (true) {
                    val candidate = currentItem()
                    if (candidate["classification_state"]?.jsonPrimitive?.content !in
                        setOf("pending", "queued", "running", "retry")
                    ) return@withTimeout candidate
                    delay(250)
                }
                error("unreachable")
            }
            val failedOcr = app.accountClient.libraryRequest(
                expectedOwnerId = ownerId,
                path = "/items/$itemId/assets/$firstAssetId/ocr",
                method = "PATCH",
                body = """{"expected_version":${stable.getValue("version").jsonPrimitive.long},"ocr_state":"failed"}""",
                requestId = UUID.randomUUID().toString(),
            )
            assertEquals(firstAssetId, (failedOcr.getValue("active_asset") as JsonObject)
                .getValue("id").jsonPrimitive.content)
            compose.onNodeWithText("보관함 목록").performScrollTo().performClick()
            openFixtureDetail()
            awaitText("OCR은 실패했지만 서버의 이미지는 정상적으로 유지돼요.")
            clickTag("attachment-ocr-retry")
            awaitText("OCR 텍스트가 저장돼 있어요.", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            val retriedOcr = currentItem()
            assertEquals("ready", retriedOcr.getValue("ocr_state").jsonPrimitive.content)
            assertEquals(firstAssetId, (retriedOcr.getValue("active_asset") as JsonObject)
                .getValue("id").jsonPrimitive.content)
            compose.waitUntil(UI_TIMEOUT_MILLIS) {
                File(app.noBackupFilesDir, "attachment_queue").walkTopDown().none { file -> file.isFile }
            }
            openSearch()
            submitSearch("LINK 서울")
            awaitTag("search-item-$itemId", timeoutMillis = SERVER_TIMEOUT_MILLIS)

            writeReceipt {
                put("initialAssetId", firstAssetId)
                put("initialTextRevision", firstTextRevision)
            }
        }

        val replacement = createFixture("jeju", "제주 여행")
        val replacementUri = fixtureUri(replacement)
        assertEquals("content", replacementUri.scheme)
        ActivityScenario.launch<MainActivity>(shareIntent(replacementUri)).use {
            awaitText("공유 이미지를 임시로 가져왔습니다.")
            compose.onNodeWithText("공유 이미지를 첨부할 항목 선택")
                .performScrollTo()
                .performClick()
            openFixtureDetail()
            awaitText("서버에 저장된 활성 이미지가 있어요.")
            val beforeReplacement = currentItem()
            val expectedReplacementVersion = beforeReplacement.getValue("version").jsonPrimitive.long
            val firstAssetId = (beforeReplacement.getValue("active_asset") as JsonObject)
                .getValue("id").jsonPrimitive.content
            assertTrue(app.incomingImageStore.draft.value != null)
            awaitTag("attachment-import-shared")

            setNetwork(enabled = false)
            clickTag("attachment-import-shared")
            val replacementPending = awaitPending(ownerId, itemId) {
                it.operationId != initialOperationId && it.ocrText?.contains("제주") == true
            }
            assertEquals(expectedReplacementVersion, replacementPending.baseExpectedVersion)
            assertRecognizedFixture(replacementPending, "제주")
            assertTrue(replacementPending.localFileName.startsWith("$ownerId/"))
            assertTrue(queuedFile(replacementPending).isFile)
            awaitDraftConsumed()

            setNetwork(enabled = true)
            val (replacementItem, replacementAsset) = awaitActiveAsset(differentFrom = firstAssetId)
            val replacementAssetId = replacementAsset.getValue("id").jsonPrimitive.content
            val replacementTextRevision = replacementItem.getValue("text_revision").jsonPrimitive.long
            assertNotEquals(firstAssetId, replacementAssetId)
            assertTrue(replacementTextRevision > firstItemTextRevision())
            awaitTag("attachment-preview", timeoutMillis = SERVER_TIMEOUT_MILLIS)

            openSearch()
            submitSearch("LINK 제주")
            awaitTag("search-item-$itemId", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            submitSearch("서울")
            awaitText("조건에 맞는 항목이 없어요.", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            compose.onNodeWithTag("search-item-$itemId").assertDoesNotExist()

            writeReceipt {
                put("replacementOperationId", replacementPending.operationId)
                put("replacementBaseVersion", replacementPending.baseExpectedVersion)
                put("replacementOcrText", replacementPending.ocrText)
                put("replacementAssetId", replacementAssetId)
                put("replacementTextRevision", replacementTextRevision)
            }
        }
    }

    private fun firstItemTextRevision(): Long = JSONObject(
        File(app.filesDir, RECEIPT_FILE).readText(),
    ).getLong("initialTextRevision")

    @Test
    fun deleteActiveImageThroughInlineConfirmation(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        app.accountClient.restoreAccount()
        assertEquals(argument("fixtureOwnerId"), app.accountClient.sessionUserId())
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            clickRoot("root-library")
            openFixtureDetail()
            awaitTag("attachment-preview", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            compose.onNodeWithTag("attachment-delete", useUnmergedTree = true)
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            compose.onNodeWithTag("attachment-confirm-delete", useUnmergedTree = true)
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            awaitText("첨부 이미지가 없어요.", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            writeReceipt { put("deleteConfirmed", true) }
        }
    }

    private companion object {
        const val FIXTURE_DIRECTORY = "fixture_images"
        const val RECEIPT_FILE = "enrichment-fixture-receipt.json"
        const val FIXTURE_WIDTH = 1_600
        const val FIXTURE_HEIGHT = 800
        const val FIXTURE_TEXT_SIZE = 150f
        const val FIXTURE_TEXT_X = 110f
        const val FIXTURE_FIRST_BASELINE = 280f
        const val FIXTURE_SECOND_BASELINE = 570f
        const val UI_TIMEOUT_MILLIS = 45_000L
        const val NETWORK_TIMEOUT_MILLIS = 30_000L
        const val QUEUE_TIMEOUT_MILLIS = 120_000L
        const val SERVER_TIMEOUT_MILLIS = 180_000L
    }
}
