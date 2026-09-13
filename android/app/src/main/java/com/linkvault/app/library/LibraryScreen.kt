package com.linkvault.app.library

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.auth.AccountClient

@Composable
fun LibraryScreen(
    client: AccountClient,
    entryId: String,
    initialUrl: String?,
    sharedText: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenOriginal: (String) -> Unit,
) {
    val ownerId = client.sessionUserId()
    val viewModelKey = remember(client, ownerId, entryId) {
        "library:${System.identityHashCode(client)}:${ownerId ?: "signed-out"}:$entryId"
    }
    val factory = remember(client, ownerId, initialUrl, sharedText) {
        LibraryViewModel.factory(client, ownerId, initialUrl, sharedText)
    }
    val libraryViewModel: LibraryViewModel = viewModel(
        key = viewModelKey,
        factory = factory,
    )
    val state by libraryViewModel.uiState.collectAsState()

    LaunchedEffect(libraryViewModel, initialUrl, sharedText) {
        libraryViewModel.updateSharedInput(initialUrl, sharedText)
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
                    text = "M1 보관함은 온라인 전용이에요.",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "자동 메타데이터 수집과 분류는 이후 단계에서 연결됩니다.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
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

                    is LibraryDetailState.Loaded -> DetailContent(
                        item = detail.item,
                        onBackToList = libraryViewModel::closeDetail,
                        onOpenOriginal = onOpenOriginal,
                    )
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
) {
    val form = state.form
    val validationIssues = remember(form) { validateLibrarySaveForm(form) }
    val saveStatus = state.saveStatus
    val fieldsEnabled = when (saveStatus) {
        LibrarySaveStatus.Idle,
        is LibrarySaveStatus.Invalid,
        -> true

        is LibrarySaveStatus.Failed -> !saveStatus.canRetrySameRequest
        LibrarySaveStatus.Saving,
        is LibrarySaveStatus.Saved,
        -> false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "선택한 링크 보관",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("아래 원문 URL을 실제 서버에 보관합니다. 버튼을 눌러 확인해 주세요.")
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
            LibrarySaveStatus.Saving -> LoadingMessage("서버에 보관하고 있어요.")
            is LibrarySaveStatus.Invalid -> Unit
            is LibrarySaveStatus.Failed -> {
                Text(saveStatus.message, color = MaterialTheme.colorScheme.error)
                if (saveStatus.canRetrySameRequest) {
                    Text("응답이 확실하지 않아 요청 ID와 내용을 그대로 보관했습니다.")
                    Button(
                        onClick = onRetrySave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("같은 요청 다시 시도")
                    }
                    OutlinedButton(
                        onClick = onEditAfterFailure,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("입력 수정")
                    }
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

        val showSaveButton = saveStatus !is LibrarySaveStatus.Saved &&
            !(saveStatus is LibrarySaveStatus.Failed && saveStatus.canRetrySameRequest)
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

        if (state.isListLoading && state.items.isEmpty()) {
            LoadingMessage("서버에서 보관함을 불러오고 있어요.")
        } else if (state.listError != null && state.items.isEmpty()) {
            ListFailure(message = state.listError, onRetry = onRetry)
        } else {
            if (state.items.isEmpty()) {
                Text("서버에 보관된 링크가 아직 없어요.")
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
                LoadingMessage("다음 링크를 불러오고 있어요.")
            } else if (state.hasMore && state.listError == null) {
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
                    modifier = Modifier.weight(1f),
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
    item: LibraryItemDetail,
    onBackToList: () -> Unit,
    onOpenOriginal: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBackToList) {
            Text("보관함 목록")
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
