package com.linkvault.app.attachment

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.library.LibraryActiveAsset
import com.linkvault.app.library.LibraryItemDetail
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState

@Composable
fun AttachmentControls(
    item: LibraryItemDetail,
    client: AccountClient,
    outbox: OutboxRepository,
    attachments: AttachmentRepository,
    onRefreshItem: () -> Unit,
    incomingImageUri: Uri? = null,
    onIncomingImageConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnIncomingImageConsumed by rememberUpdatedState(onIncomingImageConsumed)
    val accountSession by client.sessionState.collectAsState()
    val ownerId = accountSession.ownerId
    val viewModelKey = remember(
        client,
        accountSession.generation,
        ownerId,
        item.id,
    ) {
        "attachment:${System.identityHashCode(client)}:${accountSession.generation}:" +
            "${ownerId ?: "signed-out"}:${item.id}"
    }
    val factory = remember(
        client,
        outbox,
        attachments,
        accountSession.generation,
        ownerId,
        item.id,
    ) {
        AttachmentViewModel.factory(
            client = client,
            outbox = outbox,
            attachments = attachments,
            ownerId = ownerId,
            sessionGeneration = accountSession.generation,
            initialItem = item,
        )
    }
    val attachmentViewModel: AttachmentViewModel = viewModel(
        key = viewModelKey,
        factory = factory,
    )
    val state by attachmentViewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) attachmentViewModel.selectImage(context.applicationContext, uri)
    }

    LaunchedEffect(attachmentViewModel, item) {
        attachmentViewModel.updateItem(item)
    }
    LaunchedEffect(
        state.queuedIncomingOperationId,
        state.queuedIncomingUri,
        incomingImageUri,
    ) {
        if (state.queuedIncomingOperationId != null &&
            state.queuedIncomingUri == incomingImageUri?.toString()
        ) {
            currentOnIncomingImageConsumed()
        }
    }
    LaunchedEffect(attachmentViewModel, state.refreshGeneration) {
        if (attachmentViewModel.takeRefreshCallback(state.refreshGeneration)) onRefreshItem()
    }
    DisposableEffect(attachmentViewModel) {
        attachmentViewModel.setVisible(true)
        onDispose { attachmentViewModel.setVisible(false) }
    }

    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "이미지 첨부",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (!state.sessionValid) {
            Text(
                text = "로그인이 바뀌어 첨부 자료를 화면에서 지웠어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        AttachmentStatusContent(state)
        ActivePreview(state)
        AttachmentButtons(
            state = state,
            incomingImageUri = incomingImageUri,
            onImportShared = { incoming ->
                attachmentViewModel.selectImage(
                    context = context.applicationContext,
                    uri = incoming,
                    isIncomingImage = true,
                )
            },
            onPick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDelete = attachmentViewModel::requestDelete,
            onRetryOcr = { attachmentViewModel.retryOcr(context.applicationContext) },
            onRetryMetadata = attachmentViewModel::retryMetadata,
        )

        state.deleteConfirmation?.let {
            DeleteConfirmation(
                enabled = !state.actionRunning,
                onConfirm = attachmentViewModel::confirmDelete,
                onCancel = attachmentViewModel::cancelDelete,
            )
        }

        state.pendingAttachments.forEach { pending ->
            PendingAttachmentContent(
                pending = pending,
                review = state.queueReview?.takeIf { it.operationId == pending.operationId },
                enabled = !state.actionRunning,
                onRetry = { attachmentViewModel.retryAttachment(pending.operationId) },
                onReview = { attachmentViewModel.reviewAttachmentConflict(pending.operationId) },
                onConfirmAgain = attachmentViewModel::confirmAttachmentAgain,
                onDiscard = { attachmentViewModel.discardAttachment(pending.operationId) },
            )
        }

        state.commandEntries.forEach { entry ->
            PendingCommandContent(
                entry = entry,
                review = state.commandReview?.takeIf { it.requestId == entry.requestId },
                enabled = !state.actionRunning,
                onRetry = { attachmentViewModel.retryCommand(entry.requestId) },
                onReview = { attachmentViewModel.reviewCommandConflict(entry.requestId) },
                onConfirmAgain = attachmentViewModel::confirmCommandAgain,
                onDiscard = { attachmentViewModel.discardCommand(entry.requestId) },
            )
        }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.actionError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AttachmentStatusContent(state: AttachmentUiState) {
    var detailsExpanded by rememberSaveable(state.item.id) { mutableStateOf(false) }
    val metadataNeedsAttention = when (state.item.metadataState) {
        "ready", "unsupported" -> false
        else -> true
    }
    val hasDetails = state.item.activeAsset != null ||
        !metadataNeedsAttention ||
        state.item.extractionMeta.hasTruncatedText

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AttachmentInfoSection(
            title = "이미지 상태",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("attachment-queue-status"),
        ) {
            when {
                state.item.activeAsset != null ->
                    Text("서버에 저장된 활성 이미지가 있어요.")
                state.localStage != AttachmentLocalStage.IDLE ||
                    state.pendingAttachments.isNotEmpty() ->
                    Text("새 이미지를 준비하거나 전송하고 있어요.")
                state.attachmentQueueLoaded && state.outboxLoaded ->
                    Text("첨부 이미지가 없어요.")
            }
            if (!state.attachmentQueueLoaded || !state.outboxLoaded) {
                StatusLine("이 기기의 대기 작업을 확인하고 있어요.", busy = true)
            }
            when (state.localStage) {
                AttachmentLocalStage.PREPARING -> StatusLine(
                    message = "선택한 원본은 바꾸지 않고 기기 안에서 업로드 사본을 준비하고 있어요.",
                    busy = true,
                )
                AttachmentLocalStage.OCR_RUNNING ->
                    Text("업로드 사본을 기기에 준비했어요.")
                AttachmentLocalStage.QUEUED -> Text("기기에 보관됨 · 전송 대기 중")
                AttachmentLocalStage.FAILED -> Text(
                    "기기에서 이미지를 준비하지 못했어요.",
                    color = MaterialTheme.colorScheme.error,
                )
                AttachmentLocalStage.IDLE -> Unit
            }
            if (state.pendingAttachments.any { it.stage in ATTACHMENT_UPLOADING_STAGES }) {
                StatusLine("서버로 이미지를 전송하고 있어요.", busy = true)
            }
            if (state.pendingAttachments.any { it.stage in ATTACHMENT_FAILED_STAGES }) {
                Text(
                    "완료하지 못한 이미지 작업이 있어요. 아래에서 검토하거나 다시 시도해 주세요.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        AttachmentInfoSection(title = "텍스트 인식 (OCR)") {
            when {
                state.localStage == AttachmentLocalStage.OCR_RUNNING ->
                    StatusLine("기기에서 OCR을 실행하고 있어요.", busy = true)
                state.item.activeAsset != null -> Text(
                    ocrStatusMessage(state.item),
                    style = if (state.item.ocrState == "failed") {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (state.item.ocrState == "failed") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                state.localOcrFailed -> Text(
                    "OCR은 실패했지만 이미지 준비나 업로드 실패를 뜻하지 않아요.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    "이미지를 첨부하면 글자를 인식해 검색 가능한 텍스트로 저장해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.localOcrFailed && state.item.activeAsset != null) {
                Text(
                    "OCR은 실패했지만 이미지 준비나 업로드 실패를 뜻하지 않아요.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (metadataNeedsAttention) {
            AttachmentInfoSection(title = "링크 정보") {
                if (state.item.metadataState == "pending") {
                    StatusLine(metadataStatusMessage(state.item), busy = true)
                } else {
                    Text(
                        metadataStatusMessage(state.item),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (hasDetails) {
            TextButton(
                onClick = { detailsExpanded = !detailsExpanded },
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(if (detailsExpanded) "세부 정보 숨기기" else "세부 정보 보기")
            }
        }

        if (detailsExpanded && hasDetails) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = EditorialShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "세부 정보",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        state.item.activeAsset?.let { asset ->
                            Text(activeAssetDescription(asset))
                        }
                        if (!metadataNeedsAttention) {
                            Text(metadataStatusMessage(state.item))
                        }
                        if (state.item.extractionMeta.hasTruncatedText) {
                            Text(
                                "수집된 메타데이터 일부는 저장 길이 제한에 맞춰 잘렸어요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentInfoSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                content()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StatusLine(message: String, busy: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ActivePreview(state: AttachmentUiState) {
    state.item.activeAsset ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("attachment-preview"),
        shape = EditorialShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "미리보기",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                state.preview != null -> Image(
                    bitmap = state.preview.asImageBitmap(),
                    contentDescription = "첨부 이미지 미리보기",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit,
                )
                state.previewLoading ->
                    StatusLine("미리보기를 안전하게 여는 중이에요.", busy = true)
                state.previewError != null -> Text(
                    state.previewError,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AttachmentButtons(
    state: AttachmentUiState,
    incomingImageUri: Uri?,
    onImportShared: (Uri) -> Unit,
    onPick: () -> Unit,
    onDelete: () -> Unit,
    onRetryOcr: () -> Unit,
    onRetryMetadata: () -> Unit,
) {
    val hasActiveAsset = state.item.activeAsset != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (incomingImageUri != null) {
            Button(
                onClick = { onImportShared(incomingImageUri) },
                enabled = state.canChooseImage &&
                    state.queuedIncomingUri != incomingImageUri.toString(),
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("attachment-import-shared"),
            ) {
                Text("공유 이미지 첨부")
            }
        }
        Button(
            onClick = onPick,
            enabled = state.canChooseImage,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(if (hasActiveAsset) "attachment-replace" else "attachment-pick"),
        ) {
            Text(if (hasActiveAsset) "교체" else "이미지 첨부")
        }
        if (hasActiveAsset) {
            OutlinedButton(
                onClick = onDelete,
                enabled = state.canMutateActiveAsset,
                shape = EditorialShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("attachment-delete"),
            ) {
                Text("삭제")
            }
        }
        if (state.item.activeAsset != null && state.item.ocrState == "failed") {
            OutlinedButton(
                onClick = onRetryOcr,
                enabled = state.canRetryOcr,
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("attachment-ocr-retry"),
            ) {
                Text("OCR 다시 시도")
            }
        }
        if (state.supportsMetadataRetry) {
            OutlinedButton(
                onClick = onRetryMetadata,
                enabled = !state.actionRunning,
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("metadata-retry"),
            ) {
                Text("메타데이터 다시 조회")
            }
        }
        if (state.item.version == null) {
            Text(
                text = "첨부를 바꾸려면 서버 버전이 포함된 최신 상세 정보가 필요해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeleteConfirmation(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        shape = EditorialShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "이 이미지를 삭제할까요? 확인한 버전으로만 요청하며, 서버 처리가 끝날 때까지 기존 이미지를 보여요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onConfirm,
                enabled = enabled,
                shape = EditorialShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("attachment-confirm-delete"),
            ) {
                Text("삭제 확인")
            }
            TextButton(
                onClick = onCancel,
                enabled = enabled,
                shape = EditorialShape,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("취소")
            }
        }
    }
}

@Composable
private fun PendingAttachmentContent(
    pending: PendingAttachment,
    review: AttachmentQueueReview?,
    enabled: Boolean,
    onRetry: () -> Unit,
    onReview: () -> Unit,
    onConfirmAgain: () -> Unit,
    onDiscard: () -> Unit,
) {
    val needsAttention = pending.stage in ATTACHMENT_FAILED_STAGES
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = EditorialShape,
        colors = CardDefaults.cardColors(
            containerColor = if (needsAttention) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.bodyMedium.copy(
                    color = if (needsAttention) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
            ) {
                Text(
                    "이미지 첨부 · ${pending.stage.queueMessage()}",
                    fontWeight = FontWeight.SemiBold,
                )
                if (pending.ocrState == AttachmentOcrState.FAILED) {
                    Text(
                        "OCR은 실패했지만 이미지 업로드는 계속할 수 있어요.",
                        color = if (needsAttention) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                pending.errorCode?.let { errorCode ->
                    Text(
                        attachmentFailureMessage(errorCode),
                        style = if (needsAttention) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (needsAttention) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                when (pending.stage) {
                    AttachmentStage.FAILED -> {
                        OutlinedButton(
                            onClick = onRetry,
                            enabled = enabled,
                            shape = EditorialShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("전송 다시 시도")
                        }
                        TextButton(
                            onClick = onDiscard,
                            enabled = enabled,
                            shape = EditorialShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("기기 사본과 대기 작업 버리기")
                        }
                    }
                    AttachmentStage.CONFLICT -> {
                        if (review == null) {
                            OutlinedButton(
                                onClick = onReview,
                                enabled = enabled,
                                shape = EditorialShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("최신 내용 확인")
                            }
                        } else {
                            Text("준비한 기기 사본은 보존돼 있어요. 자동으로 버전을 바꾸지 않았어요.")
                            Button(
                                onClick = onConfirmAgain,
                                enabled = enabled,
                                shape = EditorialShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("확인한 버전으로 새 첨부 요청")
                            }
                        }
                        TextButton(
                            onClick = onDiscard,
                            enabled = enabled,
                            shape = EditorialShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("기기 사본과 대기 작업 버리기")
                        }
                    }
                    AttachmentStage.EXPIRED -> {
                        Text("만료된 기기 사본은 다시 사용할 수 없어요. 버린 뒤 이미지를 새로 선택해 주세요.")
                        TextButton(
                            onClick = onDiscard,
                            enabled = enabled,
                            shape = EditorialShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("만료된 대기 작업 버리기")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun PendingCommandContent(
    entry: OutboxEntry,
    review: AttachmentCommandReview?,
    enabled: Boolean,
    onRetry: () -> Unit,
    onReview: () -> Unit,
    onConfirmAgain: () -> Unit,
    onDiscard: () -> Unit,
) {
    val needsAttention = when (entry.state) {
        OutboxState.FAILED,
        OutboxState.CONFLICT,
        OutboxState.EXPIRED,
        -> true
        else -> false
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = EditorialShape,
        colors = CardDefaults.cardColors(
            containerColor = if (needsAttention) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.bodyMedium.copy(
                    color = if (needsAttention) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
            ) {
                Text(
                    "${entry.attachmentCommandLabel()} · ${entry.state.commandQueueMessage()}",
                    fontWeight = FontWeight.SemiBold,
                )
                entry.errorCode?.let { errorCode ->
                    Text(
                        commandFailureMessage(errorCode),
                        style = if (needsAttention) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (needsAttention) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                when (entry.state) {
                    OutboxState.FAILED -> {
                        if (entry.errorCode?.let(RETRYABLE_COMMAND_ERRORS::contains) == true) {
                            OutlinedButton(
                                onClick = onRetry,
                                enabled = enabled,
                                shape = EditorialShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("요청 다시 시도")
                            }
                        }
                        TextButton(
                            onClick = onDiscard,
                            enabled = enabled,
                            shape = EditorialShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("요청 버리기")
                        }
                    }
                    OutboxState.CONFLICT,
                    OutboxState.EXPIRED,
                    -> {
                        if (review == null) {
                            OutlinedButton(
                                onClick = onReview,
                                enabled = enabled,
                                shape = EditorialShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("최신 내용 확인")
                            }
                        } else if (review.targetStillCurrent) {
                            Text("자동으로 최신 버전에 다시 적용하지 않았어요.")
                            Button(
                                onClick = onConfirmAgain,
                                enabled = enabled,
                                shape = EditorialShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("확인한 버전으로 새 요청")
                            }
                        } else {
                            Text(
                                "대상 이미지가 이미 바뀌어 이 요청을 현재 이미지에 적용할 수 없어요.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(
                            onClick = onDiscard,
                            enabled = enabled,
                            shape = EditorialShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("요청 버리기")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun activeAssetDescription(asset: LibraryActiveAsset): String =
    "${asset.mimeType.removePrefix("image/").uppercase()} · ${asset.width}×${asset.height} · " +
        formatBytes(asset.byteSize)

private fun ocrStatusMessage(item: LibraryItemDetail): String = when (item.ocrState) {
    "ready" -> if (item.activeAsset?.ocrTruncated == true) {
        "OCR 텍스트를 저장 길이 제한에 맞춰 저장했어요."
    } else {
        "OCR 텍스트가 저장돼 있어요."
    }
    "failed" -> "OCR은 실패했지만 서버의 이미지는 정상적으로 유지돼요."
    "not_requested" -> "저장된 OCR 텍스트가 없어요."
    "pending" -> "OCR 상태를 확인하고 있어요."
    else -> "OCR 상태를 확인할 수 없어요."
}

private fun metadataStatusMessage(item: LibraryItemDetail): String = when (item.metadataState) {
    "ready" -> "메타데이터 조회를 완료했어요. 페이지 전체 보존을 의미하지는 않아요."
    "partial" -> "일부 메타데이터만 저장됐어요."
    "unsupported" -> "이 주소는 자동 메타데이터 조회를 지원하지 않아요."
    "failed" -> "메타데이터 조회를 완료하지 못했어요."
    "pending" -> "서버에서 메타데이터를 조회하고 있어요."
    else -> "메타데이터 상태를 확인할 수 없어요."
}

private fun AttachmentStage.queueMessage(): String = when (this) {
    AttachmentStage.RESERVE -> "서버 공간 예약 대기 중"
    AttachmentStage.UPLOAD -> "이미지 전송 중"
    AttachmentStage.COMPLETE -> "서버 검증 완료 요청 중"
    AttachmentStage.SAVED -> "서버 업로드 완료 적용 중"
    AttachmentStage.RETRY -> "연결되면 다시 전송"
    AttachmentStage.WAITING_LOGIN -> "로그인 후 전송"
    AttachmentStage.CONFLICT -> "최신 버전과 충돌 · 확인 필요"
    AttachmentStage.EXPIRED -> "요청 만료 · 확인 필요"
    AttachmentStage.FAILED -> "이미지 전송 실패"
}

private fun OutboxEntry.attachmentCommandLabel(): String = when {
    method == "DELETE" -> "이미지 삭제"
    method == "PATCH" -> "OCR 저장"
    else -> "메타데이터 다시 조회"
}

private fun OutboxState.commandQueueMessage(): String = when (this) {
    OutboxState.PENDING -> "전송 대기 중"
    OutboxState.RUNNING -> "서버에 전송 중"
    OutboxState.RETRY -> "연결되면 다시 전송"
    OutboxState.WAITING_LOGIN -> "로그인 후 전송"
    OutboxState.FAILED -> "서버 요청 실패"
    OutboxState.CONFLICT -> "최신 버전과 충돌"
    OutboxState.EXPIRED -> "요청 만료 · 최신 내용 확인 필요"
    OutboxState.SAVED -> "서버 응답 적용 중"
}

private fun attachmentFailureMessage(code: String): String = when (code) {
    "VERSION_CONFLICT" -> "다른 변경이 먼저 저장되어 최신 내용 확인이 필요해요."
    "ASSET_RESERVATION_EXISTS" -> "이미 다른 이미지 첨부가 진행 중이에요."
    "STORAGE_LIMIT_REACHED" -> "이미지 저장 용량 한도에 도달했어요."
    "RESERVATION_EXPIRED" -> "서버 이미지 공간 예약이 만료됐어요."
    "RATE_LIMITED" -> "요청이 많아 잠시 뒤 다시 시도해야 해요."
    else -> "이미지 작업을 완료하지 못했어요."
}

private fun commandFailureMessage(code: String): String = when (code) {
    "VERSION_CONFLICT" -> "다른 변경이 먼저 저장되어 최신 내용 확인이 필요해요."
    "ASSET_NOT_ACTIVE" -> "대상 이미지가 더 이상 현재 이미지가 아니에요."
    "RATE_LIMITED" -> "요청이 많아 잠시 뒤 다시 시도해야 해요."
    else -> "서버 요청을 완료하지 못했어요."
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private val ATTACHMENT_UPLOADING_STAGES = setOf(
    AttachmentStage.UPLOAD,
    AttachmentStage.COMPLETE,
)
private val ATTACHMENT_FAILED_STAGES = setOf(
    AttachmentStage.FAILED,
    AttachmentStage.CONFLICT,
    AttachmentStage.EXPIRED,
)

private val EditorialShape = RoundedCornerShape(8.dp)
