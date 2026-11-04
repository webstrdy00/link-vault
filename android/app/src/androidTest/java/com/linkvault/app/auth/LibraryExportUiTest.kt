package com.linkvault.app.auth

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.linkvault.app.MainActivity
import com.linkvault.app.ui.VaultTheme
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryExportUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val stores = mutableListOf<ViewModelStore>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private val session = MutableStateFlow(exportSession())
    private val downloads = mutableListOf<LibraryExportRequest>()
    private val destinations = mutableListOf<Uri>()
    private val deleted = CopyOnWriteArrayList<Uri>()
    private var launches = 0

    @After
    fun clearCoordinators() {
        releases.forEach { it.complete(Unit) }
        compose.runOnIdle { stores.forEach { it.clear() } }
    }

    @Test
    fun staleOwnerOrGenerationCannotLaunchPicker() {
        val coordinator = coordinator()
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest().copy(ownerId = "another-owner")) { launches++ }
            assertEquals(LibraryExportStatus.ERROR, coordinator.uiState.value.status)
            coordinator.launchPicker(exportRequest().copy(generation = 8)) { launches++ }
            session.value = exportSession().copy(ownerId = null)
            coordinator.launchPicker(exportRequest()) { launches++ }
            assertEquals(0, launches)
            assertTrue(downloads.isEmpty())
        }
    }

    @Test
    fun cancellationReservesPickerUntilNullCallbackAndMakesNoDownload() {
        val coordinator = coordinator()
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("first")) { launches++ }
            coordinator.cancel()
            coordinator.launchPicker(exportRequest("second")) { launches++ }
            assertEquals(1, launches)
            assertTrue(coordinator.uiState.value.pickerPending)
            assertTrue(coordinator.uiState.value.isBusy)
            coordinator.onDestinationResult(null, "first")
            assertFalse(coordinator.uiState.value.isBusy)
            coordinator.launchPicker(exportRequest("second")) { launches++ }
            assertEquals(2, launches)
            coordinator.onDestinationResult(null, "second")
            assertTrue(downloads.isEmpty())
            assertTrue(deleted.isEmpty())
        }
    }

    @Test
    fun invalidatedPickerCleanupBlocksFreshLaunchUntilUriCanSafelyBeReused() {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = gate()
        val uri = Uri.parse("content://export-test/reused-document")
        val coordinator = coordinator(discard = {
            deleted += it
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
            true
        })
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("cancelled")) { launches++ }
            coordinator.leaveAccount()
            coordinator.onDestinationResult(uri, "cancelled")
        }
        compose.waitUntil { cleanupStarted.isCompleted }
        compose.runOnIdle {
            assertTrue(coordinator.uiState.value.cleaningUp)
            assertTrue(coordinator.uiState.value.isBusy)
            coordinator.launchPicker(exportRequest("fresh")) { launches++ }
            assertEquals(1, launches)
            assertTrue(downloads.isEmpty())
            releaseCleanup.complete(Unit)
        }
        compose.waitUntil { !coordinator.uiState.value.isBusy }
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("fresh")) { launches++ }
            coordinator.onDestinationResult(uri, "fresh")
            assertEquals(2, launches)
            assertEquals(listOf("fresh"), downloads.map { it.requestId })
            assertEquals(listOf(uri), destinations)
            assertEquals(LibraryExportStatus.SUCCESS, coordinator.uiState.value.status)
            assertEquals(listOf(uri), deleted.toList())
        }
    }

    @Test
    fun identifiedStaleCallbackCannotConsumeNewSlotOrDeleteItsReusedTarget() {
        val finish = gate()
        val uri = Uri.parse("content://export-test/same-target")
        val coordinator = coordinator(export = { _, _, _ -> finish.await(); 17L })
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("old")) { launches++ }
            coordinator.cancel()
            coordinator.onDestinationResult(null, "old")
            coordinator.launchPicker(exportRequest("new")) { launches++ }
            coordinator.onDestinationResult(uri, "old")
            assertTrue(coordinator.uiState.value.pickerPending)
            assertTrue(downloads.isEmpty())
            coordinator.onDestinationResult(uri, "new")
            coordinator.onDestinationResult(uri, "old")
            coordinator.onDestinationResult(uri)
            assertEquals(listOf("new"), downloads.map { it.requestId })
            assertTrue(deleted.isEmpty())
            finish.complete(Unit)
        }
        compose.waitUntil { coordinator.uiState.value.status == LibraryExportStatus.SUCCESS }
        compose.runOnIdle {
            coordinator.onDestinationResult(uri)
            assertTrue(deleted.isEmpty())
            assertEquals(1, downloads.size)
        }
    }

    @Test
    fun sessionChangeInvalidatesPendingPickerAndNeverDownloadsItsLateUri() {
        val coordinator = coordinator()
        val uri = Uri.parse("content://export-test/stale-session")
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("old-session")) { launches++ }
            session.value = exportSession().copy(ownerId = "next-owner", generation = 8)
        }
        compose.waitUntil { coordinator.uiState.value.status == LibraryExportStatus.CANCELLED }
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("next").copy(ownerId = "next-owner", generation = 8)) {
                launches++
            }
            assertEquals(1, launches)
            coordinator.onDestinationResult(uri, "old-session")
        }
        compose.waitUntil { deleted.contains(uri) && !coordinator.uiState.value.isBusy }
        compose.runOnIdle { assertTrue(downloads.isEmpty()) }
    }

    @Test
    fun ownerAndSameOwnerGenerationChangesCancelRunningTransfers() {
        for (next in listOf(
            exportSession().copy(ownerId = "next-owner", generation = 8),
            exportSession().copy(generation = 8),
        )) {
            val cancelled = CompletableDeferred<Unit>()
            compose.runOnIdle { session.value = exportSession() }
            val coordinator = coordinator(export = { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            })
            compose.runOnIdle {
                coordinator.launchPicker(exportRequest()) { launches++ }
                coordinator.onDestinationResult(Uri.parse("content://export-test/session-${next.ownerId}"))
                assertEquals(LibraryExportStatus.EXPORTING, coordinator.uiState.value.status)
                session.value = next
            }
            compose.waitUntil { cancelled.isCompleted && !coordinator.uiState.value.isBusy }
            compose.runOnIdle {
                assertEquals(LibraryExportStatus.CANCELLED, coordinator.uiState.value.status)
                assertEquals(null, coordinator.uiState.value.exportedBytes)
            }
        }
    }

    @Test
    fun cancelledActionReturningSuccessIsDiscardedAndCannotPublishSuccess() {
        val uri = Uri.parse("content://export-test/raced-return")
        val coordinator = coordinator(export = { _, _, _ ->
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                19L
            }
        })
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest()) { launches++ }
            coordinator.onDestinationResult(uri)
            coordinator.cancel()
        }
        compose.waitUntil { deleted.contains(uri) && !coordinator.uiState.value.isBusy }
        compose.runOnIdle {
            assertEquals(LibraryExportStatus.CANCELLED, coordinator.uiState.value.status)
            assertEquals(null, coordinator.uiState.value.exportedBytes)
        }
    }

    @Test
    fun cancelledSuccessfulReturnStillReportsRefusedCleanup() {
        val coordinator = coordinator(
            export = { _, _, _ ->
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    19L
                }
            },
            discard = { deleted += it; false },
        )
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest()) { launches++ }
            coordinator.onDestinationResult(Uri.parse("content://export-test/refused-race-cleanup"))
            coordinator.cancel()
        }
        compose.waitUntil { coordinator.uiState.value.cleanupFailed && !coordinator.uiState.value.isBusy }
        compose.runOnIdle { assertEquals(LibraryExportStatus.CANCELLED, coordinator.uiState.value.status) }
    }

    @Test
    fun completedActionWithStaleSessionIsDiscardedWithoutSuccess() {
        val uri = Uri.parse("content://export-test/stale-return")
        val coordinator = coordinator(export = { _, _, _ ->
            session.value = exportSession().copy(generation = 8)
            19L
        })
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest()) { launches++ }
            coordinator.onDestinationResult(uri)
        }
        compose.waitUntil { deleted.contains(uri) && !coordinator.uiState.value.isBusy }
        compose.runOnIdle { assertEquals(LibraryExportStatus.CANCELLED, coordinator.uiState.value.status) }
    }

    @Test
    fun launcherFailureClearsSlotAndAllowsExplicitRetry() {
        val coordinator = coordinator()
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("failed-launch")) { throw IllegalStateException("provider") }
            assertEquals(LibraryExportStatus.ERROR, coordinator.uiState.value.status)
            assertFalse(coordinator.uiState.value.isBusy)
            coordinator.launchPicker(exportRequest("retry")) { launches++ }
            coordinator.onDestinationResult(Uri.parse("content://export-test/retry"), "retry")
            assertEquals(1, launches)
            assertEquals(listOf("retry"), downloads.map { it.requestId })
            assertEquals(31L, coordinator.uiState.value.exportedBytes)
        }
    }

    @Test
    fun launcherCancellationClearsSlotAndPreservesCancellation() {
        val coordinator = coordinator()
        val cancellation = CancellationException("picker launch cancelled")
        compose.runOnIdle {
            var caught: CancellationException? = null
            try {
                coordinator.launchPicker(exportRequest("cancelled-launch")) { throw cancellation }
            } catch (error: CancellationException) {
                caught = error
            }
            assertSame(cancellation, caught)
            assertEquals(LibraryExportStatus.CANCELLED, coordinator.uiState.value.status)
            assertFalse(coordinator.uiState.value.isBusy)
            coordinator.launchPicker(exportRequest("retry")) { launches++ }
            assertEquals(1, launches)
            coordinator.onDestinationResult(null, "retry")
            assertFalse(coordinator.uiState.value.isBusy)
        }
    }

    @Test
    fun providerFailureIsSafeAndCleanupWarningDoesNotHideOriginalFailure() {
        val uri = Uri.parse("content://export-test/provider-failure")
        val coordinator = coordinator(export = { _, destination, warning ->
            warning(destination)
            throw LibraryExportDestinationException(IOException("private/path and private content"))
        })
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest()) { launches++ }
            coordinator.onDestinationResult(uri)
        }
        compose.waitUntil { coordinator.uiState.value.cleanupFailed }
        compose.runOnIdle {
            val state = coordinator.uiState.value
            assertEquals(LibraryExportStatus.ERROR, state.status)
            assertFalse(state.message.orEmpty().contains("private"))
            assertFalse(state.isBusy)
        }
    }

    @Test
    fun oldCleanupWarningCannotMutateNewerRequestOrNewerSession() {
        var oldWarning: ((Uri) -> Unit)? = null
        val coordinator = coordinator(export = { request, _, warning ->
            if (request.requestId == "old") {
                oldWarning = warning
                throw AccountClientException("일시적인 연결 오류예요.", retryable = true)
            }
            7L
        })
        compose.runOnIdle {
            coordinator.launchPicker(exportRequest("old")) { launches++ }
            coordinator.onDestinationResult(Uri.parse("content://export-test/old"))
            coordinator.launchPicker(exportRequest("new")) { launches++ }
            coordinator.onDestinationResult(Uri.parse("content://export-test/new"))
            requireNotNull(oldWarning)(Uri.parse("content://export-test/old"))
            assertEquals(LibraryExportStatus.SUCCESS, coordinator.uiState.value.status)
            assertFalse(coordinator.uiState.value.cleanupFailed)
            session.value = exportSession().copy(generation = 8)
            requireNotNull(oldWarning)(Uri.parse("content://export-test/old"))
            assertFalse(coordinator.uiState.value.cleanupFailed)
        }
    }

    @Test
    fun processOrphanUriIsOnlyDeletedAndRefusedDeletionIsVisible() {
        val coordinator = coordinator(discard = { deleted += it; false })
        val uri = Uri.parse("content://export-test/process-orphan")
        compose.runOnIdle { coordinator.onDestinationResult(uri) }
        compose.waitUntil { coordinator.uiState.value.cleanupFailed && !coordinator.uiState.value.isBusy }
        compose.runOnIdle {
            assertEquals(listOf(uri), deleted.toList())
            assertTrue(downloads.isEmpty())
            assertEquals(0, launches)
        }
    }

    @Test
    fun activeAccountExplainsExportAndBlocksLogoutDeletionUntilCleanupFinishes() {
        var state by mutableStateOf(LibraryExportUiState())
        var exportClicks = 0
        var cancelClicks = 0
        var logoutClicks = 0
        var deleteClicks = 0
        compose.setContent {
            VaultTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ActiveContent(
                        summary = AccountSummary(null, AccountLimits(1000, 100), AccountUsage(14, 0, 0)),
                        exportState = state,
                        onExport = {
                            exportClicks++
                            state = LibraryExportUiState(status = LibraryExportStatus.EXPORTING)
                        },
                        onCancelExport = {
                            cancelClicks++
                            state = LibraryExportUiState(status = LibraryExportStatus.CANCELLED, cleaningUp = true)
                        },
                        onLogout = { logoutClicks++ },
                        onDeleteAccount = { deleteClicks++ },
                    )
                }
            }
        }
        compose.onNodeWithText("서버에 저장된 모든 링크를 JSON 파일로 저장해요.").assertExists()
        compose.onNodeWithText("URL, 전체 제목, 직접 입력한 제목·메모, 출처, 분류 이름, 저장 시각을 포함해요.")
            .assertExists()
        compose.onNodeWithText("이 기기의 저장 대기열과 이미지·OCR·본문은 포함하지 않아요.").assertExists()
        compose.onNodeWithTag("library-export-start").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("library-export-start").assertIsNotEnabled()
        compose.onNodeWithTag("account-logout").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-delete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("library-export-cancel").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("library-export-start").assertIsNotEnabled()
        compose.onNodeWithTag("account-logout").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-delete").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(cleaningUp = false) }
        compose.onNodeWithTag("library-export-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("account-logout").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("account-delete").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, exportClicks)
            assertEquals(1, cancelClicks)
            assertEquals(1, logoutClicks)
            assertEquals(1, deleteClicks)
        }
    }

    @Test
    fun pickerCancellationSuccessErrorAndPartialCleanupHaveSeparateFeedback() {
        var state by mutableStateOf(
            LibraryExportUiState(
                status = LibraryExportStatus.CANCELLED,
                pickerPending = true,
                message = "내보내기를 취소했어요.",
            ),
        )
        compose.setContent {
            VaultTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LibraryExportContent(state, onExport = {}, onCancel = {})
                }
            }
        }
        compose.onNodeWithTag("library-export-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("library-export-success").assertDoesNotExist()
        compose.onNodeWithTag("library-export-error").assertDoesNotExist()
        compose.onNodeWithText("이전 선택 결과가 돌아오기 전에는", substring = true).assertExists()
        compose.onNodeWithTag("library-export-cancel").assertIsNotEnabled()
        compose.runOnIdle {
            state = LibraryExportUiState(status = LibraryExportStatus.SUCCESS, message = "JSON 파일로 내보냈어요.")
        }
        compose.onNodeWithTag("library-export-success").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("library-export-error").assertDoesNotExist()
        compose.onNodeWithTag("library-export-start").assertIsEnabled()
        compose.onNodeWithTag("library-export-cancel").assertDoesNotExist()
        compose.runOnIdle {
            state = LibraryExportUiState(
                status = LibraryExportStatus.ERROR,
                message = "연결을 확인해 주세요.",
                cleanupFailed = true,
            )
        }
        compose.onNodeWithTag("library-export-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("library-export-success").assertDoesNotExist()
        compose.onNodeWithTag("library-export-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("자동 정리가 보장되지는 않아요.", substring = true).assertExists()
        compose.onNodeWithTag("library-export-start").assertIsEnabled()
    }

    private fun coordinator(
        export: suspend (LibraryExportRequest, Uri, (Uri) -> Unit) -> Long = { _, _, _ -> 31L },
        discard: suspend (Uri) -> Boolean = { deleted += it; true },
    ): LibraryExportCoordinator = compose.runOnIdle {
        val store = ViewModelStore().also { stores += it }
        ViewModelProvider(store, exportFactory {
            LibraryExportCoordinator(
                session,
                export = { request, destination, warning ->
                    downloads += request
                    destinations += destination
                    export(request, destination, warning)
                },
                discard = discard,
            )
        })[LibraryExportCoordinator::class.java]
    }

    private fun gate() = CompletableDeferred<Unit>().also { releases += it }
}

class LibraryExportLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pendingPickerAndTransferRemainInActivityStoreAcrossRecreation() {
        val session = MutableStateFlow(exportSession())
        val finish = CompletableDeferred<Unit>()
        var creations = 0
        var downloads = 0
        val factory = exportFactory {
            creations++
            LibraryExportCoordinator(session, export = { _, _, _ -> downloads++; finish.await(); 23L }, discard = { true })
        }
        lateinit var original: LibraryExportCoordinator
        compose.runOnIdle {
            original = ViewModelProvider(compose.activity, factory)["export-lifecycle-test", LibraryExportCoordinator::class.java]
            original.launchPicker(exportRequest("rotation")) {}
        }
        try {
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                val retained = ViewModelProvider(compose.activity, factory)["export-lifecycle-test", LibraryExportCoordinator::class.java]
                assertSame(original, retained)
                assertTrue(retained.uiState.value.pickerPending)
                retained.onDestinationResult(Uri.parse("content://export-test/rotation"), "rotation")
                assertEquals(LibraryExportStatus.EXPORTING, retained.uiState.value.status)
            }
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                val retained = ViewModelProvider(compose.activity, factory)["export-lifecycle-test", LibraryExportCoordinator::class.java]
                assertSame(original, retained)
                assertEquals(LibraryExportStatus.EXPORTING, retained.uiState.value.status)
                assertEquals(1, creations)
                assertEquals(1, downloads)
                finish.complete(Unit)
            }
            compose.waitUntil { original.uiState.value.status == LibraryExportStatus.SUCCESS }
            compose.runOnIdle { assertEquals(23L, original.uiState.value.exportedBytes) }
        } finally {
            finish.complete(Unit)
        }
    }
}

private fun exportFactory(createCoordinator: () -> LibraryExportCoordinator): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryExportCoordinator::class.java))
            return createCoordinator() as T
        }
    }

private fun exportSession() = AccountSessionState(generation = 7, ownerId = "export-owner", initialized = true)

private fun exportRequest(id: String = "request") = LibraryExportRequest(
    requestId = id,
    ownerId = "export-owner",
    generation = 7,
    fileName = "linkfilebox-library.json",
)
