package com.linkvault.app.library

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.attachment.AttachmentControls
import com.linkvault.app.attachment.AttachmentRepository
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun LibraryScreen(
    client: AccountClient,
    outbox: OutboxRepository,
    attachments: AttachmentRepository,
    entryId: String,
    initialUrl: String?,
    sharedText: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenOriginal: (String) -> Unit,
    initialItemId: String? = null,
    onDiscover: () -> Unit = {},
    incomingImageUri: Uri? = null,
    onIncomingImageConsumed: () -> Unit = {},
) {
    val ownerId = client.sessionUserId()
    val viewModelKey = remember(client, ownerId, entryId) {
        "library:${System.identityHashCode(client)}:${ownerId ?: "signed-out"}:$entryId"
    }
    val factory = remember(client, outbox, attachments, ownerId, initialUrl, sharedText, initialItemId) {
        LibraryViewModel.factory(
            client = client,
            outbox = outbox,
            attachments = attachments,
            ownerId = ownerId,
            initialUrl = initialUrl,
            sharedText = sharedText,
            initialItemId = initialItemId,
        )
    }
    val libraryViewModel: LibraryViewModel = viewModel(
        key = viewModelKey,
        factory = factory,
    )
    val state by libraryViewModel.uiState.collectAsState()

    LaunchedEffect(libraryViewModel, initialUrl, sharedText) {
        libraryViewModel.updateSharedInput(initialUrl, sharedText)
    }
    LaunchedEffect(libraryViewModel, state.receiptsAwaitingAcknowledgement) {
        libraryViewModel.acknowledgeSavedReceipts(state.receiptsAwaitingAcknowledgement)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("뒤로")
            }
            Text(
                text = "보관함",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.availability is LibraryAvailability.Ready) {
                    TextButton(onClick = onDiscover) {
                        Text("검색·분류")
                    }
                }
                if (
                    state.availability is LibraryAvailability.Ready &&
                    state.detail is LibraryDetailState.None
                ) {
                    TextButton(
                        onClick = libraryViewModel::refresh,
                        enabled = !state.isListLoading,
                    ) {
                        Text("새로고침")
                    }
                } else {
                    TextButton(onClick = {}, enabled = false) {
                        Text("새로고침")
                    }
                }
            }
        }
        HorizontalDivider()

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "저장 요청은 먼저 이 기기에 안전하게 보관돼요.",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "연결되면 로그인한 내 계정의 서버 보관함으로 전송합니다.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        state.deletionNotice?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        when (val availability = state.availability) {
            LibraryAvailability.Checking -> LoadingMessage("로그인 계정을 확인하고 있어요.")
            is LibraryAvailability.SignInRequired -> SignInRequiredContent(
                message = availability.message,
                onSignIn = onSignIn,
            )

            LibraryAvailability.Ready -> {
                if (state.outboxEntries.isNotEmpty()) {
                    OutboxContent(
                        entries = state.outboxEntries,
                        edit = state.edit,
                        delete = state.delete,
                        onRetry = libraryViewModel::retryOperation,
                        onDiscard = libraryViewModel::discardOperation,
                        onReconfirmSave = libraryViewModel::reconfirmExpiredSave,
                        onLoadLatest = libraryViewModel::loadLatestForEdit,
                        onLoadLatestDelete = libraryViewModel::loadLatestForDelete,
                    )
                    HorizontalDivider()
                }

                when (val detail = state.detail) {
                    LibraryDetailState.None -> {
                        if (initialUrl != null) {
                            val formMatchesInput = state.form.initialUrl == initialUrl &&
                                state.form.sharedText == sharedText
                            if (formMatchesInput) {
                                SaveFormContent(
                                    state = state,
                                    onTitleChange = libraryViewModel::updateTitle,
                                    onNoteChange = libraryViewModel::updateNote,
                                    onSave = libraryViewModel::save,
                                    onRetrySave = libraryViewModel::retrySave,
                                    onEditAfterFailure = libraryViewModel::editAfterSaveFailure,
                                    onReconfirmSave = libraryViewModel::reconfirmExpiredSave,
                                )
                                HorizontalDivider()
                            } else {
                                LoadingMessage("공유 내용을 준비하고 있어요.")
                            }
                        }
                        LibraryListContent(
                            state = state,
                            onOpenOriginal = onOpenOriginal,
                            onOpenDetail = libraryViewModel::openDetail,
                            onRetry = libraryViewModel::retryList,
                            onLoadMore = libraryViewModel::loadMore,
                        )
                    }

                    is LibraryDetailState.Loading -> DetailLoadingContent(
                        onBackToList = libraryViewModel::closeDetail,
                    )

                    is LibraryDetailState.Failed -> DetailFailureContent(
                        message = detail.message,
                        onBackToList = libraryViewModel::closeDetail,
                        onRetry = libraryViewModel::retryDetail,
                    )

                    is LibraryDetailState.Loaded -> {
                        DetailContent(
                            detail = detail,
                            edit = state.edit,
                            delete = state.delete,
                            onBackToList = libraryViewModel::closeDetail,
                            onOpenOriginal = onOpenOriginal,
                            onBeginEdit = libraryViewModel::beginEdit,
                            onEditTitleChange = libraryViewModel::updateEditTitle,
                            onEditNoteChange = libraryViewModel::updateEditNote,
                            onSaveEdit = libraryViewModel::saveEdit,
                            onCancelEdit = libraryViewModel::cancelEdit,
                            onLoadLatest = libraryViewModel::loadLatestForEdit,
                            onConfirmAgain = libraryViewModel::confirmEditAgain,
                            onBeginDelete = libraryViewModel::beginDelete,
                            onCancelDelete = libraryViewModel::cancelDeleteConfirmation,
                            onConfirmDelete = libraryViewModel::confirmDelete,
                            onLoadLatestDelete = libraryViewModel::loadLatestForDelete,
                        )
                        if (state.edit == null && state.delete == null) {
                            AttachmentControls(
                                item = detail.item,
                                client = client,
                                outbox = outbox,
                                attachments = attachments,
                                onRefreshItem = { libraryViewModel.refreshDetail(detail.item.id) },
                                incomingImageUri = incomingImageUri,
                                onIncomingImageConsumed = onIncomingImageConsumed,
                            )
                            ClassificationControls(
                                client = client,
                                outbox = outbox,
                                entryId = entryId,
                                item = detail.item,
                                onRefresh = {
                                    libraryViewModel.refreshDetail(detail.item.id)
                                },
                                onEditNote = libraryViewModel::beginEdit,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInRequiredContent(
    message: String,
    onSignIn: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "로그인 후 보관함을 사용할 수 있어요.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(message, color = MaterialTheme.colorScheme.error)
        Text("실제 로그인 세션과 서버의 베타 이용 승인이 필요합니다.")
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("로그인 이동")
        }
    }
}

@Composable
private fun SaveFormContent(
    state: LibraryUiState,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetrySave: () -> Unit,
    onEditAfterFailure: () -> Unit,
    onReconfirmSave: (String) -> Unit,
) {
    val form = state.form
    val validationIssues = remember(form) { validateLibrarySaveForm(form) }
    val saveStatus = state.saveStatus
    val fieldsEnabled = when (saveStatus) {
        LibrarySaveStatus.Idle,
        is LibrarySaveStatus.Invalid,
        -> true

        is LibrarySaveStatus.Failed -> saveStatus.requestId == null
        LibrarySaveStatus.Saving,
        is LibrarySaveStatus.Queued,
        is LibrarySaveStatus.Saved,
        -> false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "선택한 링크 보관",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("저장 요청을 이 기기에 먼저 보관한 뒤 서버로 안전하게 전송합니다.")
        SelectionContainer {
            Text(
                text = form.initialUrl.orEmpty(),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = fieldsEnabled,
            label = { Text("제목 (선택)") },
            isError = validationIssues.any { it.field == LibraryFormField.TITLE },
            supportingText = {
                Text("${form.title.codePointLength()} / $LIBRARY_TITLE_MAX_CODE_POINTS")
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = form.note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = fieldsEnabled,
            label = { Text("나중에 찾을 메모") },
            isError = validationIssues.any { it.field == LibraryFormField.NOTE },
            supportingText = {
                Text("${form.note.codePointLength()} / $LIBRARY_NOTE_MAX_CODE_POINTS")
            },
            minLines = 3,
            maxLines = 7,
        )

        if (form.sharedText.isNotBlank()) {
            val sharedCount = form.sharedText.codePointLength()
            Text(
                text = "공유 텍스트 ${sharedCount}자도 함께 보관합니다.",
                color = if (sharedCount > LIBRARY_SHARED_TEXT_MAX_CODE_POINTS) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        validationIssues.forEach { issue ->
            Text(
                text = issue.message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (saveStatus) {
            LibrarySaveStatus.Idle -> Unit
            LibrarySaveStatus.Saving -> LoadingMessage("저장 요청을 이 기기에 보관하고 있어요.")
            is LibrarySaveStatus.Queued -> Text(
                text = "저장 요청을 이 기기에 보관했어요. 연결되면 서버에 전송합니다.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            is LibrarySaveStatus.Invalid -> Unit
            is LibrarySaveStatus.Failed -> {
                Text(saveStatus.message, color = MaterialTheme.colorScheme.error)
                when {
                    saveStatus.needsReconfirmation && saveStatus.requestId != null -> {
                        Button(
                            onClick = { onReconfirmSave(saveStatus.requestId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("저장을 다시 확인")
                        }
                    }

                    saveStatus.canRetrySameRequest -> {
                        Text("같은 요청 ID와 저장 내용을 그대로 다시 전송합니다.")
                        Button(
                            onClick = onRetrySave,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("같은 요청 다시 시도")
                        }
                    }
                }
                OutlinedButton(
                    onClick = onEditAfterFailure,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("입력 수정")
                }
            }

            is LibrarySaveStatus.Saved -> {
                val resultMessage = if (saveStatus.result.duplicate) {
                    "이미 서버에 보관된 링크예요."
                } else {
                    "서버에 보관했어요."
                }
                Text(
                    text = resultMessage,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        val showSaveButton = saveStatus is LibrarySaveStatus.Idle ||
            saveStatus is LibrarySaveStatus.Invalid ||
            (saveStatus is LibrarySaveStatus.Failed && saveStatus.requestId == null)
        if (showSaveButton) {
            Button(
                onClick = onSave,
                enabled = validationIssues.isEmpty() && saveStatus !is LibrarySaveStatus.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("서버에 보관")
            }
        }
    }
}

@Composable
private fun OutboxContent(
    entries: List<OutboxEntry>,
    edit: LibraryEditUiState?,
    delete: LibraryDeleteUiState?,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onReconfirmSave: (String) -> Unit,
    onLoadLatest: () -> Unit,
    onLoadLatestDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "이 기기의 저장 요청",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        entries.forEach { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            entry.isItemDeleteForScreen() -> "링크 삭제"
                            entry.method == "PATCH" -> "제목·메모 수정"
                            else -> "새 링크 보관"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(entry.queueMessage())
                    if (entry.isItemDeleteForScreen() && entry.state != OutboxState.SAVED) {
                        Text(
                            text = "서버가 아직 삭제를 수락하지 않아 링크는 보관함에 그대로 표시됩니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.errorMessage?.takeUnless(String::isBlank)?.let { message ->
                        if (entry.state == OutboxState.FAILED) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    when (entry.state) {
                        OutboxState.FAILED -> {
                            Button(
                                onClick = { onRetry(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("다시 시도")
                            }
                            OutlinedButton(
                                onClick = { onDiscard(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("요청 버리기")
                            }
                        }

                        OutboxState.CONFLICT -> {
                            if (entry.isItemDeleteForScreen() &&
                                delete?.status?.requestIdForScreen() == entry.requestId
                            ) {
                                Button(
                                    onClick = onLoadLatestDelete,
                                    enabled = delete?.status !is LibraryDeleteStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("최신 버전 확인")
                                }
                            } else if (entry.method == "PATCH" &&
                                edit?.status?.requestIdForScreen() == entry.requestId
                            ) {
                                Button(
                                    onClick = onLoadLatest,
                                    enabled = edit.status !is LibraryEditStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("최신 내용 확인")
                                }
                            }
                            OutlinedButton(
                                onClick = { onDiscard(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("요청 버리기")
                            }
                        }

                        OutboxState.EXPIRED -> {
                            if (entry.method == "POST") {
                                Button(
                                    onClick = { onReconfirmSave(entry.requestId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("저장을 다시 확인")
                                }
                            } else if (entry.isItemDeleteForScreen() &&
                                delete?.status?.requestIdForScreen() == entry.requestId
                            ) {
                                Button(
                                    onClick = onLoadLatestDelete,
                                    enabled = delete?.status !is LibraryDeleteStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("최신 버전 확인")
                                }
                            } else if (edit?.status?.requestIdForScreen() == entry.requestId) {
                                Button(
                                    onClick = onLoadLatest,
                                    enabled = edit.status !is LibraryEditStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("최신 내용 확인")
                                }
                            }
                            OutlinedButton(
                                onClick = { onDiscard(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("요청 버리기")
                            }
                        }

                        OutboxState.PENDING,
                        OutboxState.RETRY,
                        OutboxState.WAITING_LOGIN,
                        -> OutlinedButton(
                            onClick = { onDiscard(entry.requestId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("요청 버리기")
                        }

                        OutboxState.RUNNING,
                        OutboxState.SAVED,
                        -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryListContent(
    state: LibraryUiState,
    onOpenOriginal: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "최근 보관 링크",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        if (state.isShowingCache) {
            val timestamp = state.cacheFetchedAt?.let(::formatCachedAt) ?: "동기화 시각 확인 불가"
            Text(
                text = "이 기기의 마지막 동기화 자료 · $timestamp",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.isListLoading && state.items.isEmpty()) {
            LoadingMessage("서버에서 보관함을 불러오고 있어요.")
        } else if (state.listError != null && state.items.isEmpty()) {
            ListFailure(message = state.listError, onRetry = onRetry)
        } else {
            if (state.items.isEmpty()) {
                Text(
                    if (state.isShowingCache) {
                        "이 기기에 동기화된 링크가 아직 없어요."
                    } else {
                        "서버에 보관된 링크가 아직 없어요."
                    },
                )
            } else {
                state.items.forEach { item ->
                    LibraryItemCard(
                        item = item,
                        onOpenOriginal = onOpenOriginal,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }

            state.listError?.let { message ->
                ListFailure(message = message, onRetry = onRetry)
            }

            if (state.isListLoading && state.items.isNotEmpty()) {
                LoadingMessage(
                    if (state.isShowingCache) {
                        "서버의 최신 보관함을 확인하고 있어요."
                    } else {
                        "다음 링크를 불러오고 있어요."
                    },
                )
            } else if (!state.isShowingCache && state.hasMore && state.listError == null) {
                OutlinedButton(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("더 불러오기")
                }
            }
        }
    }
}

@Composable
private fun LibraryItemCard(
    item: LibraryItemSummary,
    onOpenOriginal: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = item.displayTitle?.takeUnless(String::isBlank) ?: item.url,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            SelectionContainer {
                Text(
                    text = item.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.noteExcerpt?.takeUnless(String::isBlank)?.let { note ->
                Text("메모: $note")
            }
            Text(
                text = "출처 ${item.source ?: "확인 중"} · 메타데이터 ${item.metadataState ?: "확인 중"} · 분류 ${item.classificationState ?: "확인 중"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onOpenOriginal(item.url) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("원문 열기")
                }
                OutlinedButton(
                    onClick = { onOpenDetail(item.id) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("detail-${item.id}"),
                ) {
                    Text("상세")
                }
            }
        }
    }
}

@Composable
private fun ListFailure(
    message: String,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("목록 다시 시도")
        }
    }
}

@Composable
private fun DetailLoadingContent(onBackToList: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBackToList) {
            Text("보관함 목록")
        }
        LoadingMessage("상세 정보를 불러오고 있어요.")
    }
}

@Composable
private fun DetailFailureContent(
    message: String,
    onBackToList: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBackToList) {
            Text("보관함 목록")
        }
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("상세 다시 시도")
        }
    }
}

@Composable
private fun DetailContent(
    detail: LibraryDetailState.Loaded,
    edit: LibraryEditUiState?,
    delete: LibraryDeleteUiState?,
    onBackToList: () -> Unit,
    onOpenOriginal: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onEditTitleChange: (String) -> Unit,
    onEditNoteChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onLoadLatest: () -> Unit,
    onConfirmAgain: () -> Unit,
    onBeginDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onLoadLatestDelete: () -> Unit,
) {
    val item = detail.item
    val deleteStatus = delete?.status
    if (
        deleteStatus is LibraryDeleteStatus.Confirming ||
        deleteStatus is LibraryDeleteStatus.ReadyToConfirm
    ) {
        val reviewingConflict = deleteStatus is LibraryDeleteStatus.ReadyToConfirm
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = {
                Text(if (reviewingConflict) "최신 버전 삭제 확인" else "링크 삭제 확인")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (reviewingConflict) {
                        Text("서버의 최신 버전을 확인했습니다. 삭제하려면 다시 명시적으로 확인해야 합니다.")
                    }
                    Text("서버가 요청을 수락하면 링크를 보관함에서 즉시 숨깁니다.")
                    Text("서버에 저장된 사본과 앱이 소유한 첨부 파일은 백그라운드에서 물리적으로 정리되며, 완료 전까지 시간이 걸릴 수 있습니다.")
                    Text("이 작업은 되돌릴 수 없습니다. 사용자가 보관한 원본 파일은 삭제하지 않습니다.")
                    Text("오프라인이면 삭제 요청만 이 기기에 보관되고, 서버 수락 전까지 링크는 계속 표시됩니다.")
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmDelete,
                    modifier = Modifier.testTag("confirm-item-delete"),
                ) {
                    Text(if (reviewingConflict) "최신 버전 삭제" else "삭제 요청 보관")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text("취소")
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBackToList) {
            Text("보관함 목록")
        }
        if (detail.isCached) {
            Text(
                text = "이 기기의 마지막 동기화 자료 · ${detail.fetchedAt?.let(::formatCachedAt) ?: "동기화 시각 확인 불가"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SelectionContainer {
            Text(
                text = item.displayTitle?.takeUnless(String::isBlank) ?: item.url,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        DetailValue("사용자 제목", item.userTitle)
        DetailValue("수집된 제목", item.fetchedTitle)
        DetailValue("메모", item.note)
        DetailValue("설명", item.description)
        DetailValue("공유 텍스트", item.sharedText)
        DetailValue("본문", item.bodyText)

        if (edit == null) {
            Button(
                onClick = onBeginEdit,
                enabled = item.version != null && delete == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("제목·메모 수정")
            }
            if (item.version == null) {
                Text(
                    text = "수정하려면 서버 버전이 포함된 최신 상세 정보가 필요해요.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (delete == null) {
                OutlinedButton(
                    onClick = onBeginDelete,
                    enabled = item.version != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delete-item"),
                ) {
                    Text("링크 삭제")
                }
            } else {
                DeleteStatusContent(
                    delete = delete,
                    onLoadLatest = onLoadLatestDelete,
                )
            }
        } else {
            EditContent(
                edit = edit,
                onTitleChange = onEditTitleChange,
                onNoteChange = onEditNoteChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
                onLoadLatest = onLoadLatest,
                onConfirmAgain = onConfirmAgain,
            )
        }

        Text(
            text = "서버 처리 상태",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("메타데이터 ${item.metadataState ?: "확인 중"}")
        Text("OCR ${item.ocrState ?: "확인 중"}")
        Text("분류 ${item.classificationState ?: "확인 중"}")
        Text("후속 처리 ${item.cueState ?: "확인 중"}")
        if (item.categoryRefs.isNotEmpty()) {
            Text(
                "분류: " + item.categoryRefs.mapNotNull { it.name }.joinToString(),
            )
        }
        SelectionContainer {
            Text(item.url, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = { onOpenOriginal(item.url) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("원문 열기")
        }
    }
}

@Composable
private fun DeleteStatusContent(
    delete: LibraryDeleteUiState,
    onLoadLatest: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "링크 삭제",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            when (val status = delete.status) {
                LibraryDeleteStatus.Confirming -> Unit
                LibraryDeleteStatus.Queuing -> LoadingMessage("삭제 요청을 이 기기에 보관하고 있어요.")
                is LibraryDeleteStatus.Queued -> Text(
                    "삭제 요청을 이 기기에 보관했어요. 서버가 수락하기 전까지 링크는 계속 표시됩니다.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                is LibraryDeleteStatus.Failed -> Text(
                    status.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                is LibraryDeleteStatus.Blocked -> {
                    Text(
                        if (status.reason == LibraryDeleteBlockReason.VERSION_CONFLICT) {
                            "다른 변경으로 링크 버전이 달라졌습니다. 자동으로 삭제하지 않습니다."
                        } else {
                            "삭제 요청이 만료됐습니다. 최신 버전을 확인하고 다시 삭제를 확인해야 합니다."
                        },
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = onLoadLatest,
                        modifier = Modifier.fillMaxWidth().testTag("review-item-delete"),
                    ) {
                        Text("최신 버전 확인")
                    }
                }
                is LibraryDeleteStatus.LoadingLatest -> LoadingMessage(
                    "삭제 전에 서버의 최신 버전을 확인하고 있어요.",
                )
                is LibraryDeleteStatus.ReadyToConfirm -> Unit
            }
            delete.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun EditContent(
    edit: LibraryEditUiState,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onLoadLatest: () -> Unit,
    onConfirmAgain: () -> Unit,
) {
    val issues = remember(edit.form) { validateLibraryEditForm(edit.form) }
    val canEdit = edit.status is LibraryEditStatus.Idle ||
        edit.status is LibraryEditStatus.Invalid ||
        (edit.status is LibraryEditStatus.Failed && edit.status.requestId == null)

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "제목·메모 수정",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = edit.form.title,
                onValueChange = onTitleChange,
                enabled = canEdit,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("제목") },
                supportingText = {
                    Text("${edit.form.title.codePointLength()} / $LIBRARY_TITLE_MAX_CODE_POINTS · 비우면 삭제")
                },
                isError = issues.any { it.field == LibraryFormField.TITLE },
                singleLine = true,
            )
            OutlinedTextField(
                value = edit.form.note,
                onValueChange = onNoteChange,
                enabled = canEdit,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("나중에 찾을 메모") },
                supportingText = {
                    Text("${edit.form.note.codePointLength()} / $LIBRARY_NOTE_MAX_CODE_POINTS · 비우면 삭제")
                },
                isError = issues.any { it.field == LibraryFormField.NOTE },
                minLines = 3,
                maxLines = 7,
            )
            issues.forEach { issue ->
                Text(issue.message, color = MaterialTheme.colorScheme.error)
            }

            when (val status = edit.status) {
                LibraryEditStatus.Idle -> Unit
                LibraryEditStatus.Queuing -> LoadingMessage("수정 요청을 이 기기에 보관하고 있어요.")
                is LibraryEditStatus.Invalid -> status.issues.forEach { issue ->
                    if (issue !in issues) Text(issue.message, color = MaterialTheme.colorScheme.error)
                }

                is LibraryEditStatus.Queued -> Text(
                    text = "수정 요청을 이 기기에 보관했어요. 연결되면 서버에 전송합니다.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                is LibraryEditStatus.Failed -> Text(
                    text = status.message,
                    color = MaterialTheme.colorScheme.error,
                )

                is LibraryEditStatus.Blocked -> {
                    Text(
                        text = if (status.reason == LibraryEditBlockReason.VERSION_CONFLICT) {
                            "변경 충돌"
                        } else {
                            "수정 요청 만료"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        if (status.reason == LibraryEditBlockReason.VERSION_CONFLICT) {
                            "다른 변경이 먼저 저장됐어요. 자동으로 덮어쓰지 않습니다."
                        } else {
                            "수정 요청이 24시간을 지나 만료됐어요. 최신 내용을 먼저 확인해야 합니다."
                        },
                    )
                    DraftComparison(edit.form, latest = null)
                    Button(
                        onClick = onLoadLatest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("최신 내용 확인")
                    }
                }

                is LibraryEditStatus.LoadingLatest -> {
                    DraftComparison(edit.form, latest = null)
                    LoadingMessage("서버의 최신 내용을 불러오고 있어요.")
                }

                is LibraryEditStatus.ReadyToConfirm -> {
                    Text(
                        text = if (status.reason == LibraryEditBlockReason.VERSION_CONFLICT) {
                            "변경 충돌 비교"
                        } else {
                            "만료된 요청 다시 확인"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    DraftComparison(edit.form, status.latest)
                    Text("아래 버튼을 눌러야 새 요청 ID와 최신 버전으로 저장합니다.")
                    Button(
                        onClick = onConfirmAgain,
                        enabled = issues.isEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("다시 저장")
                    }
                }

                is LibraryEditStatus.Saved -> {
                    Text(
                        text = status.message,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("수정 닫기")
                    }
                }
            }

            edit.latestError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            if (canEdit) {
                Button(
                    onClick = onSave,
                    enabled = issues.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("수정 요청 보관")
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("수정 취소")
                }
            }
        }
    }
}

@Composable
private fun DraftComparison(form: LibraryEditForm, latest: LibraryItemDetail?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("내가 저장하려던 제목", fontWeight = FontWeight.SemiBold)
        SelectionContainer { Text(form.title.ifEmpty { "삭제" }) }
        Text("내가 저장하려던 메모", fontWeight = FontWeight.SemiBold)
        SelectionContainer { Text(form.note.ifEmpty { "삭제" }) }
        if (latest != null) {
            HorizontalDivider()
            Text("서버의 최신 제목", fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(latest.userTitle?.takeUnless(String::isEmpty) ?: "없음") }
            Text("서버의 최신 메모", fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(latest.note?.takeUnless(String::isEmpty) ?: "없음") }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(value?.takeUnless(String::isBlank) ?: "없음")
        }
    }
}

@Composable
private fun LoadingMessage(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp))
        Text(message)
    }
}

private fun OutboxState.queueMessage(): String = when (this) {
    OutboxState.PENDING -> "전송 대기 중"
    OutboxState.RUNNING -> "서버에 전송 중"
    OutboxState.RETRY -> "연결되면 다시 전송"
    OutboxState.WAITING_LOGIN -> "로그인 후 전송"
    OutboxState.FAILED -> "서버 저장 실패"
    OutboxState.CONFLICT -> "변경 충돌"
    OutboxState.EXPIRED -> "요청 만료 · 다시 확인 필요"
    OutboxState.SAVED -> "서버 저장 완료"
}

private fun OutboxEntry.queueMessage(): String {
    if (!isItemDeleteForScreen()) return state.queueMessage()
    return when (state) {
        OutboxState.PENDING -> "삭제 요청 전송 대기 중"
        OutboxState.RUNNING -> "삭제 요청을 서버에 전송 중"
        OutboxState.RETRY -> "연결되면 삭제 요청 다시 전송"
        OutboxState.WAITING_LOGIN -> "로그인 후 삭제 요청 전송"
        OutboxState.FAILED -> "서버가 삭제를 수락하지 않음"
        OutboxState.CONFLICT -> "최신 버전 확인 필요"
        OutboxState.EXPIRED -> "삭제 요청 만료 · 다시 확인 필요"
        OutboxState.SAVED -> if (isKnownMissingDeleteReceiptForScreen()) {
            "서버에 이미 없어 이 기기에서도 삭제 완료로 정리 중"
        } else {
            "서버가 삭제를 수락함 · 물리적 정리 진행 중"
        }
    }
}

private fun LibraryEditStatus.requestIdForScreen(): String? = when (this) {
    is LibraryEditStatus.Queued -> requestId
    is LibraryEditStatus.Failed -> requestId
    is LibraryEditStatus.Blocked -> requestId
    is LibraryEditStatus.LoadingLatest -> requestId
    is LibraryEditStatus.ReadyToConfirm -> requestId
    LibraryEditStatus.Idle,
    LibraryEditStatus.Queuing,
    is LibraryEditStatus.Invalid,
    is LibraryEditStatus.Saved,
    -> null
}

private fun LibraryDeleteStatus.requestIdForScreen(): String? = when (this) {
    is LibraryDeleteStatus.Queued -> requestId
    is LibraryDeleteStatus.Failed -> requestId
    is LibraryDeleteStatus.Blocked -> requestId
    is LibraryDeleteStatus.LoadingLatest -> requestId
    is LibraryDeleteStatus.ReadyToConfirm -> requestId
    LibraryDeleteStatus.Confirming,
    LibraryDeleteStatus.Queuing,
    -> null
}

private fun OutboxEntry.isItemDeleteForScreen(): Boolean {
    if (method != "DELETE" || !path.startsWith("/items/")) return false
    val itemId = path.removePrefix("/items/")
    return itemId.isNotBlank() && '/' !in itemId
}

private fun OutboxEntry.isKnownMissingDeleteReceiptForScreen(): Boolean = runCatching {
    Json.parseToJsonElement(checkNotNull(resultJson))
        .jsonObject["state"]
        ?.jsonPrimitive
        ?.contentOrNull == "already_deleted"
}.getOrDefault(false)

private fun formatCachedAt(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
