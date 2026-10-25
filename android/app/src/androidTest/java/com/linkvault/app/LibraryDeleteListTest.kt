package com.linkvault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.linkvault.app.library.LibraryAvailability
import com.linkvault.app.library.LibraryDeleteBlockReason
import com.linkvault.app.library.LibraryDeleteContent
import com.linkvault.app.library.LibraryDeleteStatus
import com.linkvault.app.library.LibraryDeleteUiState
import com.linkvault.app.library.LibraryDetailState
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.library.LibraryListContent
import com.linkvault.app.library.LibrarySaveForm
import com.linkvault.app.library.LibraryUiState
import com.linkvault.app.ui.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryDeleteListTest {
    @get:Rule
    val compose = createComposeRule()

    private val item = LibraryItemSummary(
        id = "00000000-0000-4000-8000-000000000161",
        version = 4,
        url = "https://example.com/list-delete-fixture",
        displayTitle = "목록에서 삭제할 링크",
    )
    private var state by mutableStateOf(listState())
    private var openedId: String? = null
    private var deleteId: String? = null
    private var confirmCalls = 0
    private var cancelCalls = 0
    private var reviewCalls = 0

    @Test
    fun rowDeleteDoesNotOpenDetailAndCancelDoesNotConfirm() {
        showList()

        compose.onNodeWithContentDescription("목록에서 삭제할 링크 삭제")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText("링크를 삭제할까요?").assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("confirm-item-delete").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(item.id, deleteId)
            assertEquals(null, openedId)
            assertEquals(LibraryDetailState.None, state.detail)
            assertEquals(1, cancelCalls)
            assertEquals(0, confirmCalls)
        }
        compose.onNodeWithTag("list-delete-${item.id}").assertIsEnabled()
        compose.onNodeWithTag("detail-${item.id}").performClick()
        compose.runOnIdle { assertEquals(item.id, openedId) }
    }

    @Test
    fun confirmInvokesOnceAndBusyStateDisablesFurtherRowDeletes() {
        showList()

        compose.onNodeWithTag("list-delete-${item.id}").performClick()
        compose.onNodeWithTag("confirm-item-delete").performClick()
        compose.onNodeWithTag("confirm-item-delete").assertDoesNotExist()
        compose.onNodeWithTag("list-delete-${item.id}").assertIsNotEnabled()
        compose.onNodeWithText("삭제를 준비하고 있어요.").assertExists()
        compose.runOnIdle {
            assertEquals(1, confirmCalls)
            assertEquals(null, openedId)
            assertEquals(LibraryDetailState.None, state.detail)
        }
    }

    @Test
    fun conflictRequiresLatestReviewAndExplicitConfirmationAgain() {
        state = listState(deletion(LibraryDeleteStatus.Blocked("request", LibraryDeleteBlockReason.VERSION_CONFLICT)))
        showList()

        compose.onNodeWithTag("list-delete-${item.id}").assertIsNotEnabled()
        compose.onNodeWithTag("confirm-item-delete").assertDoesNotExist()
        compose.onNodeWithText("다른 변경이 먼저 저장됐어요. 최신 내용을 확인한 뒤 다시 삭제해 주세요.")
            .assertExists()
        compose.onNodeWithTag("review-item-delete").performScrollTo().performClick()
        compose.onNodeWithText("최신 내용 삭제 확인").assertExists()
        compose.runOnIdle {
            assertEquals(1, reviewCalls)
            assertEquals(0, confirmCalls)
            assertEquals(null, openedId)
        }
        compose.onNodeWithTag("confirm-item-delete").performClick()
        compose.runOnIdle { assertEquals(1, confirmCalls) }
    }

    @Test
    fun queuedFailureAndExpiredFeedbackRemainVisibleOnList() {
        state = listState(deletion(LibraryDeleteStatus.Queued("request")))
        showList()
        compose.onNodeWithText("삭제를 준비했어요. 연결되면 동기화합니다.").assertExists()
        compose.onNodeWithTag("list-delete-${item.id}").assertIsNotEnabled()

        compose.runOnIdle {
            state = listState(deletion(LibraryDeleteStatus.Failed("request", "삭제 요청을 전송하지 못했어요.")))
        }
        compose.onNodeWithText("삭제 요청을 전송하지 못했어요.").assertExists()
        compose.onNodeWithTag("confirm-item-delete").assertDoesNotExist()

        compose.runOnIdle {
            state = listState(deletion(LibraryDeleteStatus.Blocked("request", LibraryDeleteBlockReason.EXPIRED)))
        }
        compose.onNodeWithText("삭제 대기 시간이 지나 최신 내용을 다시 확인해야 해요.").assertExists()
        compose.onNodeWithTag("review-item-delete").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, confirmCalls) }
    }

    @Test
    fun offlinePreparationErrorStaysInConfirmationWithoutRemovingRow() {
        state = listState(
            deletion(LibraryDeleteStatus.Confirming).copy(error = "연결 후 최신 내용을 확인해 주세요."),
        )
        showList()

        compose.onNodeWithText("연결 후 최신 내용을 확인해 주세요.").assertExists()
        compose.onNodeWithText("오프라인에서는 연결된 뒤 삭제되며, 그전까지 링크가 보일 수 있어요.")
            .assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("detail-${item.id}").assertExists()
        compose.runOnIdle {
            assertEquals(0, confirmCalls)
            assertEquals(listOf(item), state.items)
        }
    }

    @Test
    fun missingVersionDisablesDeleteWithoutDisablingDetail() {
        state = listState().copy(items = listOf(item.copy(version = null)))
        showList()

        compose.onNodeWithTag("list-delete-${item.id}").assertIsNotEnabled()
        compose.onNodeWithTag("detail-${item.id}").assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(item.id, openedId)
            assertEquals(null, deleteId)
            state = listState().copy(items = listOf(item.copy(version = 0)))
        }
        compose.onNodeWithTag("list-delete-${item.id}").assertIsNotEnabled()
    }

    private fun listState(delete: LibraryDeleteUiState? = null) = LibraryUiState(
        availability = LibraryAvailability.Ready,
        form = LibrarySaveForm(initialUrl = null, sharedText = ""),
        items = listOf(item),
        isListLoading = false,
        delete = delete,
    )

    private fun deletion(status: LibraryDeleteStatus) = LibraryDeleteUiState(
        itemId = item.id,
        expectedVersion = 4,
        status = status,
    )

    private fun showList() {
        compose.setContent {
            VaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        LibraryListContent(
                            state = state,
                            onOpenDetail = { openedId = it },
                            onDelete = {
                                deleteId = it
                                state = state.copy(delete = deletion(LibraryDeleteStatus.Confirming))
                            },
                            onRetry = {},
                            onLoadMore = {},
                            onAdd = null,
                        )
                        LibraryDeleteContent(
                            delete = state.delete,
                            onCancel = {
                                cancelCalls++
                                state = state.copy(delete = null)
                            },
                            onConfirm = {
                                confirmCalls++
                                state = state.copy(delete = deletion(LibraryDeleteStatus.Queuing))
                            },
                            onLoadLatest = {
                                reviewCalls++
                                state = state.copy(
                                    delete = deletion(
                                        LibraryDeleteStatus.ReadyToConfirm("request", LibraryDeleteBlockReason.VERSION_CONFLICT),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
