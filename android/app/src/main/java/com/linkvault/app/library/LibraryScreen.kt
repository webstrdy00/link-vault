package com.linkvault.app.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.attachment.AttachmentControls
import com.linkvault.app.attachment.AttachmentRepository
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.discovery.DiscoverySource
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

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
    onAdd: (() -> Unit)? = null,
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
    val onBackFromDetail =
        if (initialItemId != null) onBack else libraryViewModel::closeDetail
    val navigateBack: () -> Unit = {
        when {
            state.edit != null -> libraryViewModel.cancelEdit()
            state.delete != null -> libraryViewModel.cancelDeleteConfirmation()
            state.detail !is LibraryDetailState.None -> onBackFromDetail()
            else -> onBack()
        }
    }
    BackHandler(
        enabled = state.availability is LibraryAvailability.Ready &&
            state.detail !is LibraryDetailState.None,
        onBack = navigateBack,
    )

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
        if (onAdd != null && state.detail is LibraryDetailState.None) {
            LibraryRootHeader(
                canRefresh = state.availability is LibraryAvailability.Ready &&
                    !state.isListLoading,
                onAdd = onAdd,
                onRefresh = libraryViewModel::refresh,
                onDiscover = onDiscover,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = navigateBack) {
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
        }

        state.deletionNotice?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
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
                            onOpenDetail = libraryViewModel::openDetail,
                            onRetry = libraryViewModel::retryList,
                            onLoadMore = libraryViewModel::loadMore,
                            onAdd = onAdd,
                        )
                    }

                    is LibraryDetailState.Loading -> DetailLoadingContent(
                        onBackToList = onBackFromDetail,
                    )

                    is LibraryDetailState.Failed -> DetailFailureContent(
                        message = detail.message,
                        onBackToList = onBackFromDetail,
                        onRetry = libraryViewModel::retryDetail,
                    )

                    is LibraryDetailState.Loaded -> {
                        DetailContent(
                            detail = detail,
                            edit = state.edit,
                            delete = state.delete,
                            onBackToList = onBackFromDetail,
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
internal fun LibraryRootHeader(
    canRefresh: Boolean,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onDiscover: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = BookmarkOutlineIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "link vault",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(
                onClick = onAdd,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("root-capture"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "링크 저장",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "내 보관함",
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = onRefresh,
                enabled = canRefresh,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "보관함 새로고침",
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Surface(
            onClick = onDiscover,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .height(48.dp)
                .testTag("library-search"),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "제목, 메모로 찾기",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
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
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("로그인")
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
            text = "링크 저장",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        SelectionContainer {
            Text(
                text = form.initialUrl.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = fieldsEnabled,
            label = { Text("제목 (선택 사항)") },
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
            label = { Text("메모 (선택 사항)") },
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
                text = "공유된 글 ${sharedCount}자 포함",
                style = MaterialTheme.typography.bodySmall,
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
            LibrarySaveStatus.Saving -> LoadingMessage("저장하고 있어요.")
            is LibrarySaveStatus.Queued -> Text(
                text = "오프라인에서도 안전하게 저장했어요. 연결되면 동기화합니다.",
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
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("다시 확인하고 저장")
                        }
                    }

                    saveStatus.canRetrySameRequest -> {
                        Button(
                            onClick = onRetrySave,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("다시 시도")
                        }
                    }
                }
                TextButton(
                    onClick = onEditAfterFailure,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("입력 수정")
                }
            }

            is LibrarySaveStatus.Saved -> {
                val resultMessage = if (saveStatus.result.duplicate) {
                    "이미 저장된 링크예요."
                } else {
                    "저장했어요."
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
                shape = MaterialTheme.shapes.small,
            ) {
                Text("저장")
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "동기화",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        entries.forEach { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
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
                            text = "삭제가 동기화될 때까지 이 링크가 보일 수 있어요.",
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
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text("다시 시도")
                            }
                            OutlinedButton(
                                onClick = { onDiscard(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text("변경 취소")
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
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text("최신 내용 확인")
                                }
                            } else if (entry.method == "PATCH" &&
                                edit?.status?.requestIdForScreen() == entry.requestId
                            ) {
                                Button(
                                    onClick = onLoadLatest,
                                    enabled = edit.status !is LibraryEditStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text("최신 내용 확인")
                                }
                            }
                            OutlinedButton(
                                onClick = { onDiscard(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text("변경 취소")
                            }
                        }

                        OutboxState.EXPIRED -> {
                            if (entry.method == "POST") {
                                Button(
                                    onClick = { onReconfirmSave(entry.requestId) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text("다시 확인하고 저장")
                                }
                            } else if (entry.isItemDeleteForScreen() &&
                                delete?.status?.requestIdForScreen() == entry.requestId
                            ) {
                                Button(
                                    onClick = onLoadLatestDelete,
                                    enabled = delete?.status !is LibraryDeleteStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text("최신 내용 확인")
                                }
                            } else if (edit?.status?.requestIdForScreen() == entry.requestId) {
                                Button(
                                    onClick = onLoadLatest,
                                    enabled = edit.status !is LibraryEditStatus.LoadingLatest,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text("최신 내용 확인")
                                }
                            }
                            OutlinedButton(
                                onClick = { onDiscard(entry.requestId) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text("변경 취소")
                            }
                        }

                        OutboxState.PENDING,
                        OutboxState.RETRY,
                        OutboxState.WAITING_LOGIN,
                        -> OutlinedButton(
                            onClick = { onDiscard(entry.requestId) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("변경 취소")
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
    onOpenDetail: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onAdd: (() -> Unit)?,
) {
    Column {
        if (state.isShowingCache) {
            val timestamp = state.cacheFetchedAt?.let(::formatCachedAt) ?: "동기화 시각 확인 불가"
            Text(
                text = "마지막 동기화 · $timestamp",
                modifier = Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.isListLoading && state.items.isEmpty()) {
            LoadingMessage("보관함을 불러오고 있어요.")
        } else if (state.listError != null && state.items.isEmpty()) {
            ListFailure(message = state.listError, onRetry = onRetry)
        } else {
            if (state.items.isEmpty()) {
                EmptyLibraryContent(
                    isShowingCache = state.isShowingCache,
                    onAdd = onAdd,
                )
            } else {
                Text(
                    text = "최근 보관한 링크",
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                state.items.forEach { item ->
                    LibraryItemCard(
                        item = item,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }

            state.listError?.let { message ->
                Box(modifier = Modifier.padding(top = 12.dp)) {
                    ListFailure(message = message, onRetry = onRetry)
                }
            }

            if (state.isListLoading && state.items.isNotEmpty()) {
                Box(modifier = Modifier.padding(top = 12.dp)) {
                    LoadingMessage(
                        if (state.isShowingCache) {
                            "최신 보관함을 확인하고 있어요."
                        } else {
                            "다음 링크를 불러오고 있어요."
                        },
                    )
                }
            } else if (!state.isShowingCache && state.hasMore && state.listError == null) {
                OutlinedButton(
                    onClick = onLoadMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("더 불러오기")
                }
            }
        }
    }
}

@Composable
internal fun LibraryItemCard(
    item: LibraryItemSummary,
    onOpenDetail: (String) -> Unit,
) {
    val source = item.source?.takeUnless(String::isBlank)?.let { value ->
        DiscoverySource.entries.firstOrNull { it.apiValue == value }?.displayName ?: value
    }
    val date = formatLibraryDate(item.updatedAt ?: item.createdAt)
    val categoryNames = item.categoryRefs.mapNotNull { it.name?.takeUnless(String::isBlank) }
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenDetail(item.id) }
                .testTag("detail-${item.id}")
                .padding(vertical = 18.dp),
        ) {
            if (source != null || date != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        source?.let { value ->
                            Surface(
                                modifier = Modifier.size(22.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = sourceMonogram(value),
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(
                                text = value,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    date?.let { value ->
                        Text(
                            text = value,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Text(
                text = item.displayTitle?.takeUnless(String::isBlank) ?: item.url,
                modifier = Modifier.padding(top = if (source != null || date != null) 8.dp else 0.dp),
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            item.noteExcerpt?.takeUnless(String::isBlank)?.let { note ->
                Text(
                    text = note,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (categoryNames.isNotEmpty() || item.hasAttachment == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = categoryNames.joinToString(" · "),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.hasAttachment == true) {
                        Text(
                            text = "첨부 있음",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
            item.processingProblem()?.let { problem ->
                Text(
                    text = problem,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EmptyLibraryContent(
    isShowingCache: Boolean,
    onAdd: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(width = 60.dp, height = 72.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = BookmarkOutlineIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            text = "다시 만나고 싶은 링크부터.",
            modifier = Modifier.padding(top = 12.dp),
            fontSize = 19.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (isShowingCache) {
                "마지막 동기화에는 보관한 링크가 없어요."
            } else {
                "링크를 붙여넣거나 다른 앱에서 공유해 보세요."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        if (onAdd != null) {
            Button(
                onClick = onAdd,
                modifier = Modifier.padding(top = 12.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("첫 링크 보관하기")
            }
        }
    }
}

private fun sourceMonogram(source: String): String {
    when (source) {
        "네이버 블로그" -> return "N"
        "Threads" -> return "Th"
        "Instagram" -> return "Ig"
    }
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return ""
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}

private val BookmarkOutlineIcon = ImageVector.Builder(
    name = "BookmarkOutline",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(6f, 3f)
        lineTo(18f, 3f)
        lineTo(18f, 21f)
        lineTo(12f, 17f)
        lineTo(6f, 21f)
        close()
    }
}.build()

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
            shape = MaterialTheme.shapes.small,
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
            shape = MaterialTheme.shapes.small,
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
    var showMoreMenu by rememberSaveable(item.id) { mutableStateOf(false) }
    var showSavedContent by rememberSaveable(item.id) { mutableStateOf(false) }
    var showProcessingDetails by rememberSaveable(item.id) { mutableStateOf(false) }
    val deleteStatus = delete?.status
    if (
        deleteStatus is LibraryDeleteStatus.Confirming ||
        deleteStatus is LibraryDeleteStatus.ReadyToConfirm
    ) {
        val reviewingConflict = deleteStatus is LibraryDeleteStatus.ReadyToConfirm
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = {
                Text(if (reviewingConflict) "최신 내용 삭제 확인" else "링크를 삭제할까요?")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (reviewingConflict) {
                        Text("다른 변경이 있어 최신 내용을 확인했어요. 삭제하려면 다시 확인해 주세요.")
                    }
                    Text("저장된 링크와 앱에 올린 첨부 파일이 삭제되며 되돌릴 수 없어요.")
                    Text("휴대전화에 있는 원본 파일은 삭제하지 않아요.")
                    Text("오프라인에서는 연결된 뒤 삭제되며, 그전까지 링크가 보일 수 있어요.")
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmDelete,
                    modifier = Modifier.testTag("confirm-item-delete"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("삭제")
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
        if (edit != null) {
            TextButton(onClick = onCancelEdit) {
                Text("상세로 돌아가기")
            }
            EditContent(
                edit = edit,
                onTitleChange = onEditTitleChange,
                onNoteChange = onEditNoteChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
                onLoadLatest = onLoadLatest,
                onConfirmAgain = onConfirmAgain,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBackToList) {
                Text("보관함 목록")
            }
            Box {
                TextButton(
                    onClick = { showMoreMenu = true },
                    enabled = delete == null,
                ) {
                    Text("더보기")
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("제목·메모 수정") },
                        onClick = {
                            showMoreMenu = false
                            onBeginEdit()
                        },
                        enabled = item.version != null,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "링크 삭제",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            onBeginDelete()
                        },
                        enabled = item.version != null,
                        modifier = Modifier.testTag("delete-item"),
                    )
                }
            }
        }
        if (detail.isCached) {
            Text(
                text = "마지막 동기화 · ${detail.fetchedAt?.let(::formatCachedAt) ?: "시각 확인 불가"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val sourceAndDate = listOfNotNull(
            item.source?.takeUnless(String::isBlank),
            formatLibraryDate(item.updatedAt ?: item.createdAt),
        )
        if (sourceAndDate.isNotEmpty()) {
            Text(
                text = sourceAndDate.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(
                text = item.displayTitle?.takeUnless(String::isBlank) ?: item.url,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item.note?.takeUnless(String::isBlank)?.let { note ->
            DetailValue("메모", note)
        }
        CategorySummary(
            names = item.categoryRefs.mapNotNull { it.name?.takeUnless(String::isBlank) },
        )
        SelectionContainer {
            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onOpenOriginal(item.url) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("원문 열기")
        }

        if (item.version == null) {
            Text(
                text = "최신 정보를 불러온 뒤 수정하거나 삭제할 수 있어요.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (delete != null) {
            DeleteStatusContent(
                delete = delete,
                onLoadLatest = onLoadLatestDelete,
            )
        }

        val hasSavedContent = listOf(
            item.userTitle,
            item.fetchedTitle,
            item.description,
            item.sharedText,
            item.bodyText,
        ).any { !it.isNullOrBlank() }
        if (hasSavedContent) {
            TextButton(onClick = { showSavedContent = !showSavedContent }) {
                Text(if (showSavedContent) "저장된 내용 접기" else "저장된 내용 보기")
            }
            if (showSavedContent) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailValueIfPresent("내가 정한 제목", item.userTitle)
                    DetailValueIfPresent("가져온 제목", item.fetchedTitle)
                    DetailValueIfPresent("설명", item.description)
                    DetailValueIfPresent("공유된 글", item.sharedText)
                    DetailValueIfPresent("본문", item.bodyText)
                }
            }
        }

        item.processingProblems().forEach { problem ->
            Text(
                text = problem,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(onClick = { showProcessingDetails = !showProcessingDetails }) {
            Text(if (showProcessingDetails) "처리 정보 접기" else "처리 정보 보기")
        }
        if (showProcessingDetails) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "처리 정보",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("링크 정보 · ${metadataStateLabel(item.metadataState)}")
                    Text("이미지 글자 · ${ocrStateLabel(item.ocrState)}")
                    Text("자동 분류 · ${classificationStateLabel(item.classificationState)}")
                    Text("검색 준비 · ${cueStateLabel(item.cueState)}")
                }
            }
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
                LibraryDeleteStatus.Queuing -> LoadingMessage("삭제를 준비하고 있어요.")
                is LibraryDeleteStatus.Queued -> Text(
                    "삭제를 준비했어요. 연결되면 동기화합니다.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                is LibraryDeleteStatus.Failed -> Text(
                    status.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                is LibraryDeleteStatus.Blocked -> {
                    Text(
                        if (status.reason == LibraryDeleteBlockReason.VERSION_CONFLICT) {
                            "다른 변경이 먼저 저장됐어요. 최신 내용을 확인한 뒤 다시 삭제해 주세요."
                        } else {
                            "삭제 대기 시간이 지나 최신 내용을 다시 확인해야 해요."
                        },
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = onLoadLatest,
                        modifier = Modifier.fillMaxWidth().testTag("review-item-delete"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("최신 내용 확인")
                    }
                }
                is LibraryDeleteStatus.LoadingLatest -> LoadingMessage(
                    "최신 내용을 확인하고 있어요.",
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                label = { Text("제목 (선택 사항)") },
                supportingText = {
                    Text("${edit.form.title.codePointLength()} / $LIBRARY_TITLE_MAX_CODE_POINTS")
                },
                isError = issues.any { it.field == LibraryFormField.TITLE },
                singleLine = true,
            )
            OutlinedTextField(
                value = edit.form.note,
                onValueChange = onNoteChange,
                enabled = canEdit,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("메모 (선택 사항)") },
                supportingText = {
                    Text("${edit.form.note.codePointLength()} / $LIBRARY_NOTE_MAX_CODE_POINTS")
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
                LibraryEditStatus.Queuing -> LoadingMessage("변경 사항을 저장하고 있어요.")
                is LibraryEditStatus.Invalid -> status.issues.forEach { issue ->
                    if (issue !in issues) Text(issue.message, color = MaterialTheme.colorScheme.error)
                }

                is LibraryEditStatus.Queued -> Text(
                    text = "변경 사항을 저장했어요. 연결되면 동기화합니다.",
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
                            "저장 대기 시간이 지나 최신 내용을 먼저 확인해야 해요."
                        },
                    )
                    DraftComparison(edit.form, latest = null)
                    Button(
                        onClick = onLoadLatest,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("최신 내용 확인")
                    }
                }

                is LibraryEditStatus.LoadingLatest -> {
                    DraftComparison(edit.form, latest = null)
                    LoadingMessage("최신 내용을 불러오고 있어요.")
                }

                is LibraryEditStatus.ReadyToConfirm -> {
                    Text(
                        text = if (status.reason == LibraryEditBlockReason.VERSION_CONFLICT) {
                            "변경 충돌 비교"
                        } else {
                            "변경 사항 다시 확인"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    DraftComparison(edit.form, status.latest)
                    Text("최신 내용과 비교한 뒤 변경 사항을 다시 저장해 주세요.")
                    Button(
                        onClick = onConfirmAgain,
                        enabled = issues.isEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
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
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("변경 사항 저장")
                }
                TextButton(
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("내가 저장하려던 제목", fontWeight = FontWeight.SemiBold)
        SelectionContainer { Text(form.title.ifEmpty { "삭제" }) }
        Text("내가 저장하려던 메모", fontWeight = FontWeight.SemiBold)
        SelectionContainer { Text(form.note.ifEmpty { "삭제" }) }
        if (latest != null) {
            HorizontalDivider()
            Text("최신 제목", fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(latest.userTitle?.takeUnless(String::isEmpty) ?: "없음") }
            Text("최신 메모", fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(latest.note?.takeUnless(String::isEmpty) ?: "없음") }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(value?.takeUnless(String::isBlank) ?: "없음")
        }
    }
}

@Composable
private fun DetailValueIfPresent(label: String, value: String?) {
    value?.takeUnless(String::isBlank)?.let {
        DetailValue(label, it)
    }
}

@Composable
private fun CategorySummary(names: List<String>) {
    if (names.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = names.joinToString(" · "),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingMessage(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(message)
    }
}

private fun LibraryItemSummary.processingProblem(): String? {
    val problems = buildList {
        if (metadataState == "failed") add("링크 정보를 가져오지 못했어요.")
        if (ocrState == "failed") add("이미지에서 글자를 읽지 못했어요.")
    }
    return problems.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun LibraryItemDetail.processingProblems(): List<String> = buildList {
    if (metadataState == "failed") add("링크 정보를 가져오지 못했어요.")
    if (ocrState == "failed") add("이미지에서 글자를 읽지 못했어요.")
}

private fun metadataStateLabel(state: String?): String = when (state) {
    "queued", "running" -> "가져오는 중"
    "ready" -> "완료"
    "partial" -> "일부만 가져옴"
    "unsupported" -> "가져올 수 없음"
    "failed" -> "실패"
    else -> "확인 중"
}

private fun ocrStateLabel(state: String?): String = when (state) {
    "queued", "running" -> "읽는 중"
    "ready" -> "완료"
    "not_requested" -> "해당 없음"
    "failed" -> "실패"
    else -> "확인 중"
}

private fun classificationStateLabel(state: String?): String = when (state) {
    "pending" -> "정리 중"
    "automatic" -> "자동 정리됨"
    "manual" -> "직접 정리됨"
    "unclassified" -> "분류 없음"
    else -> "확인 중"
}

private fun cueStateLabel(state: String?): String = when (state) {
    "pending" -> "준비 중"
    "missing", "limited" -> "메모 보완 가능"
    "available" -> "완료"
    else -> "확인 중"
}

private fun OutboxState.queueMessage(): String = when (this) {
    OutboxState.PENDING -> "동기화 대기 중"
    OutboxState.RUNNING -> "동기화 중"
    OutboxState.RETRY -> "연결되면 다시 동기화"
    OutboxState.WAITING_LOGIN -> "로그인 후 동기화"
    OutboxState.FAILED -> "동기화 실패"
    OutboxState.CONFLICT -> "변경 충돌"
    OutboxState.EXPIRED -> "다시 확인 필요"
    OutboxState.SAVED -> "동기화 완료"
}

private fun OutboxEntry.queueMessage(): String {
    if (!isItemDeleteForScreen()) return state.queueMessage()
    return when (state) {
        OutboxState.PENDING -> "삭제 동기화 대기 중"
        OutboxState.RUNNING -> "삭제 동기화 중"
        OutboxState.RETRY -> "연결되면 다시 동기화"
        OutboxState.WAITING_LOGIN -> "로그인 후 동기화"
        OutboxState.FAILED -> "삭제하지 못했어요"
        OutboxState.CONFLICT -> "최신 내용 확인 필요"
        OutboxState.EXPIRED -> "다시 확인 필요"
        OutboxState.SAVED -> "삭제 완료"
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

private fun formatCachedAt(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun formatLibraryDate(timestamp: String?): String? = timestamp
    ?.takeUnless(String::isBlank)
    ?.let { value ->
        runCatching {
            val instant = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(value))
            DateTimeFormatter.ofPattern("yyyy.MM.dd")
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }.getOrNull()
    }
