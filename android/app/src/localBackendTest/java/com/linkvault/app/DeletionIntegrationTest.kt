package com.linkvault.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
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
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxState
import com.linkvault.app.storage.VaultDatabase
import com.linkvault.app.attachment.AttachmentOcrState
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in deletion suite backed by the real local API, WorkManager, Room, and Auth session. */
class DeletionIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun argument(name: String): String = checkNotNull(arguments.getString(name))

    private fun uuidArgument(name: String): String = argument(name).also {
        check(UUID.fromString(it).toString().equals(it, ignoreCase = true))
    }

    private fun mainIntent() = Intent(app, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    private fun ActivityScenario<MainActivity>.restoreLifecycleTrackingIntent() {
        // Logout sanitizes Activity.intent, while ActivityScenario identifies lifecycle events
        // by the launch intent's filter. Restore only the fixture identity before lifecycle work.
        onActivity { activity -> activity.intent = mainIntent() }
    }

    private fun screenshot() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p $REMOTE_SCREENSHOT")
            .use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes() }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes() }
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
            screenshot()
            throw error
        }
    }

    private fun awaitAnyText(
        texts: Collection<String>,
        timeoutMillis: Long = UI_TIMEOUT_MILLIS,
    ): String {
        try {
            compose.waitUntil(timeoutMillis) {
                texts.any { text ->
                    compose.onAllNodesWithText(text, substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            screenshot()
            throw error
        }
        return texts.first { text ->
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS) {
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            screenshot()
            throw error
        }
    }

    private fun clickTag(tag: String) {
        awaitTag(tag)
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsEnabled().performClick()
    }

    private fun clickText(text: String) {
        awaitText(text)
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsEnabled().performClick()
    }

    private fun openLibrary() {
        clickTag("root-library")
    }

    private fun openDetail(itemId: String) {
        clickTag("detail-$itemId")
        awaitText("처리 정보 보기")
    }

    private fun beginDelete() {
        clickText("더보기")
        clickTag("delete-item")
    }

    private fun backToList() {
        clickText("보관함 목록")
    }

    private fun receiptFile() = File(app.filesDir, RECEIPT_FILE)

    private fun readReceipt(): JSONObject = JSONObject(receiptFile().readText())

    private fun writeReceipt(update: JSONObject.() -> Unit) {
        val file = receiptFile()
        val receipt = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        receipt.update()
        file.writeText(receipt.toString())
    }

    private suspend fun currentItem(itemId: String): JsonObject = app.accountClient.libraryRequest(
        expectedOwnerId = uuidArgument("fixtureOwnerId"),
        path = "/items/$itemId",
    )

    private suspend fun awaitDeleteEntries(
        ownerId: String,
        expectedItemIds: Set<String>,
    ): Map<String, OutboxEntry> = withTimeout(QUEUE_TIMEOUT_MILLIS) {
        app.outboxRepository.observeOutbox(ownerId).first { entries ->
            expectedItemIds.all { itemId ->
                entries.any { entry -> entry.method == "DELETE" && entry.path == "/items/$itemId" }
            }
        }.filter { entry ->
            entry.method == "DELETE" && entry.path.removePrefix("/items/") in expectedItemIds
        }.associateBy { entry -> entry.path.removePrefix("/items/") }
    }

    private suspend fun awaitItemTombstone(ownerId: String, itemId: String) {
        withTimeout(SERVER_TIMEOUT_MILLIS) {
            while (!app.outboxRepository.isItemDeleted(ownerId, itemId)) delay(250)
        }
    }

    private suspend fun awaitConflict(ownerId: String, requestId: String): OutboxEntry =
        withTimeout(SERVER_TIMEOUT_MILLIS) {
            app.outboxRepository.observeOutbox(ownerId).first { entries ->
                entries.any { entry ->
                    entry.requestId == requestId && entry.state == OutboxState.CONFLICT
                }
            }.first { entry -> entry.requestId == requestId }
        }

    private suspend fun launchFixture(
        block: suspend ActivityScenario<MainActivity>.() -> Unit,
    ) {
        val scenario = ActivityScenario.launch<MainActivity>(mainIntent())
        var primaryFailure: Throwable? = null
        try {
            scenario.block()
        } catch (error: Throwable) {
            primaryFailure = error
            screenshot()
            throw error
        } finally {
            try {
                try {
                    scenario.restoreLifecycleTrackingIntent()
                } finally {
                    scenario.close()
                }
            } catch (cleanup: Throwable) {
                if (primaryFailure == null) throw cleanup
                primaryFailure.addSuppressed(cleanup)
            }
        }
    }

    @Test
    fun queueConfirmedItemDeletesOffline(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        check(BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank())
        val ownerId = uuidArgument("fixtureOwnerId")
        val acceptedItemId = uuidArgument("acceptedItemId")
        val conflictItemId = uuidArgument("conflictItemId")
        val unaffectedItemId = uuidArgument("unaffectedItemId")
        val acceptedVersion = argument("acceptedVersion").toLong()
        val conflictVersion = argument("conflictVersion").toLong()
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
            val staleAccepted = currentItem(acceptedItemId)
            val staleConflict = currentItem(conflictItemId)

            launchFixture {
                openLibrary()
                openDetail(conflictItemId)
                backToList()
                openDetail(acceptedItemId)
                beginDelete()
                awaitText("링크를 삭제할까요?")
                clickText("취소")
                assertTrue(
                    "cancelling creates no delete request",
                    app.outboxRepository.observeOutbox(ownerId).first()
                        .none { it.method == "DELETE" && it.path == "/items/$acceptedItemId" },
                )
                beginDelete()
                setNetwork(enabled = false)
                clickTag("confirm-item-delete")
                awaitText("삭제를 준비했어요. 연결되면 동기화합니다.")
                assertFalse(app.outboxRepository.isItemDeleted(ownerId, acceptedItemId))
                assertTrue(app.outboxRepository.readCachedDetail(ownerId, acceptedItemId) != null)
                backToList()
                awaitTag("detail-$acceptedItemId")
                awaitText("삭제가 동기화될 때까지 이 링크가 보일 수 있어요.")

                openDetail(conflictItemId)
                beginDelete()
                clickTag("confirm-item-delete")
                awaitText("삭제를 준비했어요. 연결되면 동기화합니다.")
                backToList()
                awaitTag("detail-$conflictItemId")
                awaitTag("detail-$unaffectedItemId")

                val entries = awaitDeleteEntries(
                    ownerId,
                    setOf(acceptedItemId, conflictItemId),
                )
                val accepted = checkNotNull(entries[acceptedItemId])
                val conflict = checkNotNull(entries[conflictItemId])
                assertEquals("{\"expected_version\":$acceptedVersion}", accepted.payloadJson)
                assertEquals("{\"expected_version\":$conflictVersion}", conflict.payloadJson)
                assertTrue(accepted.state in setOf(OutboxState.PENDING, OutboxState.RETRY))
                assertTrue(conflict.state in setOf(OutboxState.PENDING, OutboxState.RETRY))
                assertFalse(app.outboxRepository.isItemDeleted(ownerId, unaffectedItemId))

                writeReceipt {
                    put("ownerId", ownerId)
                    put("acceptedItemId", acceptedItemId)
                    put("conflictItemId", conflictItemId)
                    put("acceptedDeleteRequestId", accepted.requestId)
                    put("conflictDeleteRequestId", conflict.requestId)
                    put("acceptedPayload", accepted.payloadJson)
                    put("conflictPayload", conflict.payloadJson)
                    put("staleAccepted", staleAccepted.toString())
                    put("staleConflict", staleConflict.toString())
                }
            }
        } finally {
            // The host force-stops this process while the two immutable DELETEs remain offline.
            sessionClient.close()
        }
    }

    @Test
    fun reconnectOriginalDeletesAndReviewConflict(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        val ownerId = uuidArgument("fixtureOwnerId")
        val acceptedItemId = uuidArgument("acceptedItemId")
        val conflictItemId = uuidArgument("conflictItemId")
        val unaffectedItemId = uuidArgument("unaffectedItemId")
        val reviewedVersion = argument("reviewedConflictVersion").toLong()
        val receipt = readReceipt()
        try {
            app.accountClient.restoreAccount()
        } catch (error: com.linkvault.app.auth.AccountClientException) {
            assertTrue("offline account restoration remains retryable", error.retryable)
        }
        assertEquals(ownerId, app.accountClient.sessionUserId())
        val restored = awaitDeleteEntries(ownerId, setOf(acceptedItemId, conflictItemId))
        val originalAccepted = checkNotNull(restored[acceptedItemId])
        val originalConflict = checkNotNull(restored[conflictItemId])
        assertEquals(receipt.getString("acceptedDeleteRequestId"), originalAccepted.requestId)
        assertEquals(receipt.getString("conflictDeleteRequestId"), originalConflict.requestId)
        assertEquals(receipt.getString("acceptedPayload"), originalAccepted.payloadJson)
        assertEquals(receipt.getString("conflictPayload"), originalConflict.payloadJson)

        launchFixture {
            setNetwork(enabled = true)
            app.outboxRepository.resumeOwner(ownerId)
            openLibrary()
            awaitItemTombstone(ownerId, acceptedItemId)
            awaitText(
                "링크를 목록에서 숨겼어요. 서버가 저장된 사본을 백그라운드에서 물리적으로 정리 중이며 되돌릴 수 없어요.",
                timeoutMillis = SERVER_TIMEOUT_MILLIS,
            )
            compose.onNodeWithTag("detail-$acceptedItemId").assertDoesNotExist()
            assertNull(app.outboxRepository.readCachedDetail(ownerId, acceptedItemId))
            assertTrue(app.outboxRepository.cachedItems(ownerId).first().none { it.itemId == acceptedItemId })

            awaitConflict(ownerId, originalConflict.requestId)
            openDetail(conflictItemId)
            awaitText("다른 변경이 먼저 저장됐어요. 최신 내용을 확인한 뒤 다시 삭제해 주세요.")
            clickTag("review-item-delete")
            awaitText("최신 내용 삭제 확인", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            setNetwork(enabled = false)
            clickTag("confirm-item-delete")
            val newEntry = withTimeout(QUEUE_TIMEOUT_MILLIS) {
                app.outboxRepository.observeOutbox(ownerId).first { entries ->
                    entries.any { entry ->
                        entry.method == "DELETE" &&
                            entry.path == "/items/$conflictItemId" &&
                            entry.requestId != originalConflict.requestId
                    }
                }.first { entry ->
                    entry.method == "DELETE" &&
                        entry.path == "/items/$conflictItemId" &&
                        entry.requestId != originalConflict.requestId
                }
            }
            assertNotEquals(originalConflict.requestId, newEntry.requestId)
            assertEquals("{\"expected_version\":$reviewedVersion}", newEntry.payloadJson)
            setNetwork(enabled = true)
            app.outboxRepository.resumeOwner(ownerId)
            awaitItemTombstone(ownerId, conflictItemId)
            compose.waitUntil(SERVER_TIMEOUT_MILLIS) {
                compose.onAllNodes(hasTestTag("detail-$conflictItemId"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            }
            assertFalse(app.outboxRepository.isItemDeleted(ownerId, unaffectedItemId))
            awaitTag("detail-$unaffectedItemId")

            val staleAccepted = Json.parseToJsonElement(receipt.getString("staleAccepted")).jsonObject
            app.outboxRepository.cacheList(ownerId, listOf(staleAccepted), replace = false)
            app.outboxRepository.cacheDetail(ownerId, staleAccepted)
            assertTrue(app.outboxRepository.cachedItems(ownerId).first().none { it.itemId == acceptedItemId })
            assertNull(app.outboxRepository.readCachedDetail(ownerId, acceptedItemId))
            assertEquals(
                setOf(unaffectedItemId),
                app.outboxRepository.retainActiveItemIds(
                    ownerId,
                    listOf(acceptedItemId, conflictItemId, unaffectedItemId),
                ),
            )

            clickTag("root-discovery")
            awaitTag("search-query")
            compose.onNodeWithTag("search-query").performTextReplacement(argument("deletedSearchTerm"))
            clickTag("search-submit")
            awaitText("조건에 맞는 항목이 없어요.", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            compose.onNodeWithTag("search-item-$acceptedItemId").assertDoesNotExist()
            compose.onNodeWithTag("search-item-$conflictItemId").assertDoesNotExist()

            writeReceipt {
                put("reviewedDeleteRequestId", newEntry.requestId)
                put("reviewedDeletePayload", newEntry.payloadJson)
                put("acceptedTombstone", true)
                put("conflictTombstone", true)
                put("lateCacheFenced", true)
            }
        }
    }

    @Test
    fun accountConfirmationCancellationAndProviderFailureDefer(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank())
        val ownerId = uuidArgument("fixtureOwnerId")
        val unaffectedItemId = uuidArgument("unaffectedItemId")
        app.accountClient.restoreAccount()
        assertEquals(ownerId, app.accountClient.sessionUserId())
        val unaffected = currentItem(unaffectedItemId)
        val beforeOutbox = app.outboxRepository.retainedCount()

        launchFixture {
            clickTag("root-account")
            awaitText("계정과 서버 자료 삭제")
            clickText("계정과 서버 자료 삭제")
            awaitText("계정과 서버 자료를 삭제할까요?")
            clickText("계정 유지")
            awaitText("계정과 서버 자료 삭제")
            assertEquals(ownerId, app.accountClient.sessionUserId())
            assertEquals(beforeOutbox, app.outboxRepository.retainedCount())

            clickText("계정과 서버 자료 삭제")
            clickText("Google 재인증 후 되돌릴 수 없는 삭제")
            val providerOutcomes = listOf(
                "Google 재인증을 취소해 계정 삭제를 시작하지 않았어요.",
                "계정 삭제를 위한 Google 재인증 화면을 열지 못했어요.",
            )
            delay(PROVIDER_LAUNCH_MILLIS)
            if (providerOutcomes.none { text ->
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }) {
                // Only cancel a visible provider window. A delayed unavailable
                // response can leave our own account screen in the foreground;
                // BACK there would navigate away instead of cancelling Google.
                val foregroundPackage = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.rootInActiveWindow?.packageName?.toString()
                if (foregroundPackage in setOf(
                        "com.google.android.gms",
                        "com.google.android.permissioncontroller",
                        "com.android.permissioncontroller",
                    )
                ) {
                    shell("input keyevent KEYCODE_BACK")
                }
            }
            awaitAnyText(
                providerOutcomes,
                timeoutMillis = PROVIDER_TIMEOUT_MILLIS,
            )
            assertEquals(ownerId, app.accountClient.sessionUserId())

            setNetwork(enabled = false)
            val preparation = app.attachmentRepository.beginPreparation(ownerId)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                preparation.file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            app.attachmentRepository.enqueuePrepared(
                ownerId = ownerId,
                itemId = unaffectedItemId,
                expectedVersion = argument("unaffectedVersion").toLong(),
                file = preparation.file,
                mimeType = "image/png",
                ocrState = AttachmentOcrState.READY,
                ocrText = "private account cleanup fixture",
                ocrTruncated = false,
                operationId = preparation.operationId,
                expectedSessionGeneration = preparation.sessionGeneration,
            )
            assertTrue(preparation.file.isFile)
            assertEquals(
                1,
                app.attachmentRepository.observe(ownerId, unaffectedItemId).first().size,
            )
            val pendingRequestId = UUID.randomUUID().toString()
            app.outboxRepository.enqueue(
                ownerId = ownerId,
                requestId = pendingRequestId,
                method = "PATCH",
                path = "/items/$unaffectedItemId",
                payloadJson = "{\"expected_version\":${argument("unaffectedVersion")},\"title\":\"must not restart\"}",
            )
            app.outboxRepository.cacheDetail(ownerId, unaffected)
            withTimeout(QUEUE_TIMEOUT_MILLIS) {
                app.outboxRepository.observeOutbox(ownerId).first { entries ->
                    entries.any { it.requestId == pendingRequestId }
                }
            }
            assertTrue(app.outboxRepository.readCachedDetail(ownerId, unaffectedItemId) != null)
            writeReceipt {
                put("accountPendingRequestId", pendingRequestId)
                put("accountPendingBeforeAccept", true)
                put("accountCleanupGenerationBefore", app.logoutGeneration.value)
            }
        }
    }

    @Test
    fun restoreServerAcceptedAccountDeletionClearsPrivateState(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.SUPABASE_URL == "http://10.0.2.2:18021")
        val ownerId = uuidArgument("fixtureOwnerId")
        val unaffectedItemId = uuidArgument("unaffectedItemId")
        val generationBefore = argument("accountCleanupGenerationBefore").toLong()
        launchFixture {
            clickTag("root-account")
            awaitText("계정 삭제가 접수됐어요", timeoutMillis = SERVER_TIMEOUT_MILLIS)
            awaitText("이 기기의 계정 자료와 로그인 정보도 정리했어요.")
            assertNull(app.accountClient.sessionUserId())
            assertFalse(app.accountClient.hasSession())
            assertEquals(0, app.outboxRepository.retainedCount())
            assertTrue(app.outboxRepository.cachedItems(ownerId).first().isEmpty())
            assertNull(app.outboxRepository.readCachedDetail(ownerId, unaffectedItemId))
            assertTrue(app.logoutGeneration.value > generationBefore)
            val database = VaultDatabase.create(app)
            try {
                assertTrue(database.attachmentDao().findItem(ownerId, unaffectedItemId).isEmpty())
            } finally {
                database.close()
            }
            assertTrue(
                File(app.noBackupFilesDir, "attachment_queue").walkTopDown().none { it.isFile },
            )

            restoreLifecycleTrackingIntent()
            this.recreate()
            awaitText("계정 삭제가 접수됐어요")
            assertNull(app.accountClient.sessionUserId())
            assertEquals(0, app.outboxRepository.retainedCount())
            assertTrue(app.outboxRepository.cachedItems(ownerId).first().isEmpty())
            writeReceipt {
                put("acceptedAccountLocalCleared", true)
                put("acceptedAccountOutboxRestarted", false)
            }
        }
    }

    @Test
    fun missingGoogleConfigurationDefersDeletion(): Unit = runBlocking {
        check(BuildConfig.DEBUG && BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank())
        launchFixture {
            clickTag("root-account")
            awaitText("로그인 설정이 필요해요")
            awaitText("Google 웹 클라이언트 ID 설정이 필요해요.")
            assertNull(app.accountClient.sessionUserId())
            assertEquals(0, app.outboxRepository.retainedCount())
        }
    }

    private companion object {
        const val RECEIPT_FILE = "deletion-fixture-receipt.json"
        const val REMOTE_SCREENSHOT = "/data/local/tmp/link-vault-deletion.png"
        const val UI_TIMEOUT_MILLIS = 45_000L
        const val NETWORK_TIMEOUT_MILLIS = 30_000L
        const val QUEUE_TIMEOUT_MILLIS = 120_000L
        const val SERVER_TIMEOUT_MILLIS = 180_000L
        const val PROVIDER_LAUNCH_MILLIS = 1_500L
        const val PROVIDER_TIMEOUT_MILLIS = 60_000L
    }
}
