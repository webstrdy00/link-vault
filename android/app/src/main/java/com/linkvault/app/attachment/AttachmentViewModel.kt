package com.linkvault.app.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linkvault.app.auth.AccountAuthenticationRequiredException
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.library.LibraryActiveAsset
import com.linkvault.app.library.LibraryItemDetail
import com.linkvault.app.library.parseLibraryDetailResponse
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class AttachmentLocalStage {
    IDLE,
    PREPARING,
    OCR_RUNNING,
    QUEUED,
    FAILED,
}

internal enum class AttachmentCommandKind {
    DELETE,
    OCR,
    METADATA,
}

internal data class AttachmentDeleteConfirmation(
    val assetId: String,
    val expectedVersion: Long,
)

internal data class AttachmentQueueReview(
    val operationId: String,
    val reviewedVersion: Long,
)

internal data class AttachmentCommandReview(
    val requestId: String,
    val kind: AttachmentCommandKind,
    val targetAssetId: String?,
    val reviewedVersion: Long,
    val targetStillCurrent: Boolean,
)

internal data class AttachmentUiState(
    val item: LibraryItemDetail,
    val sessionValid: Boolean = true,
    val localStage: AttachmentLocalStage = AttachmentLocalStage.IDLE,
    val localOcrFailed: Boolean = false,
    val attachmentQueueLoaded: Boolean = false,
    val outboxLoaded: Boolean = false,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val commandEntries: List<OutboxEntry> = emptyList(),
    val preview: Bitmap? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val deleteConfirmation: AttachmentDeleteConfirmation? = null,
    val queueReview: AttachmentQueueReview? = null,
    val commandReview: AttachmentCommandReview? = null,
    val queuedIncomingOperationId: String? = null,
    val queuedIncomingUri: String? = null,
    val actionRunning: Boolean = false,
    val message: String? = null,
    val actionError: String? = null,
    val refreshGeneration: Long = 0L,
) {
    val canMutateActiveAsset: Boolean
        get() = sessionValid && item.version != null && !actionRunning &&
            attachmentQueueLoaded && outboxLoaded &&
            (localStage == AttachmentLocalStage.IDLE ||
                localStage == AttachmentLocalStage.FAILED) &&
            pendingAttachments.isEmpty() && commandEntries.isEmpty()

    val canChooseImage: Boolean
        get() = canMutateActiveAsset

    val canRetryOcr: Boolean
        get() = canMutateActiveAsset && item.activeAsset != null && item.ocrState == "failed"

    val supportsMetadataRetry: Boolean
        get() = item.version != null &&
            (item.metadataState == "partial" || item.metadataState == "failed") &&
            item.extractionMeta.errorCode?.let(RETRYABLE_METADATA_ERRORS::contains) == true

    val canRetryMetadata: Boolean
        get() = canMutateActiveAsset && supportsMetadataRetry
}

internal class AttachmentViewModel(
    private val client: AccountClient,
    private val outbox: OutboxRepository,
    private val attachments: AttachmentRepository,
    private val ownerId: String?,
    private val sessionGeneration: Long,
    initialItem: LibraryItemDetail,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableUiState = MutableStateFlow(AttachmentUiState(item = initialItem))
    val uiState: StateFlow<AttachmentUiState> = mutableUiState.asStateFlow()

    private val itemId = initialItem.id
    private val consumedOutboxReceipts = mutableSetOf<String>()
    private val consumedAttachmentReceipts = mutableSetOf<String>()
    private val replacedOutboxRequests = mutableSetOf<String>()
    private var visible = false
    private var deliveredRefreshGeneration = 0L
    private var awaitingMetadata = false
    private var awaitingAssetId: String? = null
    private var awaitingAssetAfterVersion: Long? = null
    private var awaitingDeletedAssetId: String? = null
    private var preparationJob: Job? = null
    private var actionJob: Job? = null
    private var attachmentJob: Job? = null
    private var outboxJob: Job? = null
    private var pollingJob: Job? = null
    private var previewJob: Job? = null
    private var previewKey: PreviewKey? = null

    init {
        if (ownerId.isNullOrBlank() || !identityMatches()) invalidateSession()
        observeSession()
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (!isVisible) {
            attachmentJob?.cancel()
            attachmentJob = null
            outboxJob?.cancel()
            outboxJob = null
            pollingJob?.cancel()
            pollingJob = null
            clearPreview()
            mutableUiState.value = mutableUiState.value.copy(
                attachmentQueueLoaded = false,
                outboxLoaded = false,
            )
            return
        }
        if (!identityMatches() || !mutableUiState.value.sessionValid) return
        ownerId?.let { owner ->
            observeAttachments(owner)
            observeOutbox(owner)
        }
        loadPreviewIfNeeded()
        startPollingIfNeeded()
    }

    fun takeRefreshCallback(generation: Long): Boolean {
        if (!identityMatches() || !mutableUiState.value.sessionValid ||
            generation <= deliveredRefreshGeneration
        ) {
            return false
        }
        deliveredRefreshGeneration = generation
        return true
    }

    fun updateItem(item: LibraryItemDetail) {
        if (!identityMatches() || item.id != itemId) return
        val current = mutableUiState.value.item
        if (!isAtLeastVersion(item, current)) return
        val oldKey = current.previewKey()
        val newKey = item.previewKey()
        val activeId = item.activeAsset?.id
        if (oldKey != newKey) clearPreview()
        val awaitedAsset = awaitingAssetId
        if (awaitedAsset != null &&
            (activeId == awaitedAsset ||
                isNewerVersion(item.version, awaitingAssetAfterVersion))
        ) {
            if (activeId != awaitedAsset) {
                mutableUiState.value = mutableUiState.value.copy(
                    message = "완료된 이미지보다 더 새로운 활성 이미지가 있어 이전 완료를 적용하지 않았어요.",
                )
            }
            awaitingAssetId = null
            awaitingAssetAfterVersion = null
        }
        if (awaitingDeletedAssetId != null && activeId != awaitingDeletedAssetId) {
            awaitingDeletedAssetId = null
        }
        if (awaitingMetadata && item.metadataState != "pending") awaitingMetadata = false
        val deleteConfirmation = mutableUiState.value.deleteConfirmation?.takeIf {
            activeId == it.assetId
        }
        val previousQueueReview = mutableUiState.value.queueReview
        val previousCommandReview = mutableUiState.value.commandReview
        val queueReview = previousQueueReview?.takeIf { review ->
            item.version == review.reviewedVersion &&
                mutableUiState.value.pendingAttachments.any {
                    it.operationId == review.operationId
                }
        }
        val commandReview = previousCommandReview?.takeIf { review ->
            item.version == review.reviewedVersion
        }?.let { review ->
            review.copy(
                targetStillCurrent = review.targetAssetId == null || review.targetAssetId == activeId,
            )
        }
        mutableUiState.value = mutableUiState.value.copy(
            item = item,
            deleteConfirmation = deleteConfirmation,
            queueReview = queueReview,
            commandReview = commandReview,
            message = if (
                (previousQueueReview != null && item.version != previousQueueReview.reviewedVersion) ||
                (previousCommandReview != null &&
                    item.version != previousCommandReview.reviewedVersion)
            ) {
                "링크 버전이 다시 바뀌었어요. 최신 내용을 다시 확인해 주세요."
            } else {
                mutableUiState.value.message
            },
        )
        if (visible && oldKey != newKey) loadPreviewIfNeeded()
        startPollingIfNeeded()
    }

    fun selectImage(
        context: Context,
        uri: Uri,
        isIncomingImage: Boolean = false,
    ) {
        if (!identityMatches() || !mutableUiState.value.canChooseImage) return
        val boundOwner = ownerId ?: return
        val expectedVersion = mutableUiState.value.item.version ?: return
        mutableUiState.value = mutableUiState.value.copy(
            localStage = AttachmentLocalStage.PREPARING,
            localOcrFailed = false,
            message = null,
            actionError = null,
        )
        preparationJob = viewModelScope.launch {
            var preparedFile: File? = null
            var queueOwnsFile = false
            try {
                val token = attachments.beginPreparation(boundOwner)
                preparedFile = token.file
                if (!identityMatches()) return@launch
                val prepared = ImagePreparation.prepareImage(
                    context.applicationContext,
                    uri,
                    token.file,
                )
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    localStage = AttachmentLocalStage.OCR_RUNNING,
                )
                val ocr = OcrProcessor.recognize(context.applicationContext, prepared.file)
                if (!identityMatches()) return@launch
                attachments.enqueuePrepared(
                    ownerId = boundOwner,
                    itemId = itemId,
                    expectedVersion = expectedVersion,
                    file = prepared.file,
                    mimeType = prepared.mimeType,
                    ocrState = ocr.state.toAttachmentOcrState(),
                    ocrText = ocr.text,
                    ocrTruncated = ocr.truncated,
                    operationId = token.operationId,
                    expectedSessionGeneration = token.sessionGeneration,
                )
                queueOwnsFile = true
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    localStage = AttachmentLocalStage.QUEUED,
                    localOcrFailed = ocr.state == OcrState.FAILED,
                    queuedIncomingOperationId = token.operationId.takeIf { isIncomingImage },
                    queuedIncomingUri = uri.toString().takeIf { isIncomingImage },
                    message = if (ocr.state == OcrState.FAILED) {
                        "이미지는 전송 대기열에 보관했어요. OCR은 실패했지만 이미지 업로드는 계속돼요."
                    } else {
                        "이미지와 기기 OCR 결과를 전송 대기열에 보관했어요."
                    },
                    actionError = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    localStage = AttachmentLocalStage.FAILED,
                    localOcrFailed = false,
                    actionError = error.attachmentMessage("이미지를 준비해 대기열에 보관하지 못했어요."),
                )
            } finally {
                if (!queueOwnsFile) runCatching { preparedFile?.delete() }
            }
        }
    }

    fun requestDelete() {
        if (!identityMatches() || !mutableUiState.value.canMutateActiveAsset) return
        val item = mutableUiState.value.item
        val asset = item.activeAsset ?: return
        val version = item.version ?: return
        mutableUiState.value = mutableUiState.value.copy(
            deleteConfirmation = AttachmentDeleteConfirmation(asset.id, version),
            actionError = null,
        )
    }

    fun cancelDelete() {
        if (!mutableUiState.value.actionRunning) {
            mutableUiState.value = mutableUiState.value.copy(deleteConfirmation = null)
        }
    }

    fun confirmDelete() {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val confirmation = mutableUiState.value.deleteConfirmation ?: return
        if (mutableUiState.value.item.activeAsset?.id != confirmation.assetId) {
            mutableUiState.value = mutableUiState.value.copy(
                deleteConfirmation = null,
                actionError = "이미지가 바뀌어 이전 삭제 확인을 취소했어요.",
            )
            return
        }
        enqueueOutboxCommand(
            kind = AttachmentCommandKind.DELETE,
            method = "DELETE",
            path = "/items/$itemId/assets/${confirmation.assetId}",
            body = expectedVersionBody(confirmation.expectedVersion),
            replacesRequestId = null,
        )
    }

    fun retryMetadata() {
        if (!identityMatches() || !mutableUiState.value.canRetryMetadata) return
        val version = mutableUiState.value.item.version ?: return
        enqueueOutboxCommand(
            kind = AttachmentCommandKind.METADATA,
            method = "POST",
            path = "/items/$itemId/retry-metadata",
            body = expectedVersionBody(version),
            replacesRequestId = null,
        )
    }

    fun retryOcr(context: Context) {
        if (!identityMatches() || !mutableUiState.value.canRetryOcr) return
        val boundOwner = ownerId ?: return
        val item = mutableUiState.value.item
        val asset = item.activeAsset ?: return
        val expectedVersion = item.version ?: return
        mutableUiState.value = mutableUiState.value.copy(
            localStage = AttachmentLocalStage.OCR_RUNNING,
            actionRunning = true,
            message = null,
            actionError = null,
        )
        actionJob = viewModelScope.launch {
            var temporaryFile: File? = null
            var downloadedBytes: ByteArray? = null
            try {
                val bytes = client.downloadActiveAsset(boundOwner, itemId, asset.id)
                downloadedBytes = bytes
                if (bytes.size.toLong() != asset.byteSize) {
                    throw IOException("Downloaded image size differs from active asset metadata.")
                }
                if (!identityMatches() || mutableUiState.value.item.activeAsset?.id != asset.id) {
                    invalidateOldAssetAction()
                    return@launch
                }
                val token = attachments.beginPreparation(boundOwner)
                temporaryFile = token.file
                if (!identityMatches()) return@launch
                writePrivateOcrFile(token.file, bytes)
                val ocrFile = token.file
                bytes.fill(0)
                downloadedBytes = null
                val ocr = OcrProcessor.recognize(context.applicationContext, ocrFile)
                if (!identityMatches() || mutableUiState.value.item.activeAsset?.id != asset.id) {
                    invalidateOldAssetAction()
                    return@launch
                }
                val body = buildOcrBody(expectedVersion, ocr)
                val requestId = UUID.randomUUID().toString()
                outbox.enqueue(
                    ownerId = boundOwner,
                    requestId = requestId,
                    method = "PATCH",
                    path = "/items/$itemId/assets/${asset.id}/ocr",
                    payloadJson = body,
                )
                if (!identityMatches()) {
                    return@launch
                }
                if (mutableUiState.value.item.activeAsset?.id != asset.id) {
                    invalidateOldAssetAction()
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    localStage = AttachmentLocalStage.IDLE,
                    localOcrFailed = ocr.state == OcrState.FAILED,
                    actionRunning = false,
                    message = if (ocr.state == OcrState.FAILED) {
                        "OCR을 완료하지 못한 상태를 저장 대기열에 보관했어요. 이미지는 그대로 유지돼요."
                    } else {
                        "새 OCR 결과를 저장 대기열에 보관했어요."
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    localStage = AttachmentLocalStage.IDLE,
                    actionRunning = false,
                    actionError = error.attachmentMessage("현재 이미지를 받아 OCR을 다시 실행하지 못했어요."),
                )
            } finally {
                downloadedBytes?.fill(0)
                runCatching { temporaryFile?.delete() }
            }
        }
    }

    fun retryAttachment(operationId: String) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.pendingAttachments.firstOrNull {
            it.operationId == operationId && it.stage == AttachmentStage.FAILED
        } ?: return
        launchAction("이미지 전송을 다시 대기열에 넣지 못했어요.") {
            attachments.retry(boundOwner, entry.operationId)
            mutableUiState.value = mutableUiState.value.copy(message = "이미지 전송을 다시 대기열에 넣었어요.")
        }
    }

    fun discardAttachment(operationId: String) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.pendingAttachments.firstOrNull {
            it.operationId == operationId && it.stage in DISCARDABLE_ATTACHMENT_STAGES
        } ?: return
        launchAction("이미지 대기 작업을 버리지 못했어요.") {
            attachments.discard(boundOwner, entry.operationId)
            mutableUiState.value = mutableUiState.value.copy(
                queueReview = null,
                localStage = AttachmentLocalStage.IDLE,
                localOcrFailed = false,
                message = "이미지 대기 작업과 기기 사본을 버렸어요. 서버의 기존 이미지는 그대로예요.",
            )
        }
    }

    fun reviewAttachmentConflict(operationId: String) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val entry = mutableUiState.value.pendingAttachments.firstOrNull {
            it.operationId == operationId &&
                it.stage == AttachmentStage.CONFLICT
        } ?: return
        loadLatest(
            fallback = "최신 링크를 불러와 이미지 첨부를 다시 확인하지 못했어요.",
        ) { latest ->
            mutableUiState.value = mutableUiState.value.copy(
                queueReview = AttachmentQueueReview(entry.operationId, latest.version!!),
                message = "최신 링크를 확인했어요. 아래 버튼을 눌러야 새 요청 ID로 다시 첨부해요.",
                refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
            )
        }
    }

    fun confirmAttachmentAgain() {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val boundOwner = ownerId ?: return
        val review = mutableUiState.value.queueReview ?: return
        val entry = mutableUiState.value.pendingAttachments.firstOrNull {
            it.operationId == review.operationId &&
                it.stage == AttachmentStage.CONFLICT
        } ?: return
        launchAction("이미지 첨부를 새 요청으로 다시 만들지 못했어요.") {
            val replacement = attachments.confirmLatest(
                ownerId = boundOwner,
                oldOperationId = entry.operationId,
                newExpectedVersion = review.reviewedVersion,
            )
            require(replacement.operationId != entry.operationId) {
                "A confirmed upload must use a new operation ID."
            }
            mutableUiState.value = mutableUiState.value.copy(
                queueReview = null,
                localStage = AttachmentLocalStage.QUEUED,
                message = "확인한 최신 버전으로 새 이미지 첨부 요청을 만들었어요.",
            )
        }
    }

    fun retryCommand(requestId: String) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.commandEntries.firstOrNull {
            it.requestId == requestId && it.state == OutboxState.FAILED &&
                it.errorCode?.let(RETRYABLE_COMMAND_ERRORS::contains) == true
        } ?: return
        launchAction("요청을 다시 대기열에 넣지 못했어요.") {
            outbox.retry(boundOwner, entry.requestId)
            mutableUiState.value = mutableUiState.value.copy(message = "요청을 다시 대기열에 넣었어요.")
        }
    }

    fun discardCommand(requestId: String) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.commandEntries.firstOrNull {
            it.requestId == requestId && it.state in DISCARDABLE_OUTBOX_STATES
        } ?: return
        launchAction("요청을 버리지 못했어요.") {
            outbox.discard(boundOwner, entry.requestId)
            mutableUiState.value = mutableUiState.value.copy(
                commandReview = null,
                message = "대기 요청을 버렸어요. 서버에 완료된 변경은 되돌리지 않았어요.",
            )
        }
    }

    fun reviewCommandConflict(requestId: String) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val entry = mutableUiState.value.commandEntries.firstOrNull {
            it.requestId == requestId &&
                it.state in setOf(OutboxState.CONFLICT, OutboxState.EXPIRED)
        } ?: return
        val kind = entry.attachmentCommandKind(itemId) ?: return
        val targetAssetId = entry.targetAssetId(itemId)
        loadLatest("최신 링크를 불러와 요청을 다시 확인하지 못했어요.") { latest ->
            val targetStillCurrent = targetAssetId == null || latest.activeAsset?.id == targetAssetId
            mutableUiState.value = mutableUiState.value.copy(
                commandReview = AttachmentCommandReview(
                    requestId = entry.requestId,
                    kind = kind,
                    targetAssetId = targetAssetId,
                    reviewedVersion = latest.version!!,
                    targetStillCurrent = targetStillCurrent,
                ),
                message = if (targetStillCurrent) {
                    "최신 링크를 확인했어요. 아래 버튼을 눌러야 새 요청 ID로 다시 전송해요."
                } else {
                    "대상 이미지가 바뀌었어요. 이전 요청은 현재 이미지에 적용하지 않아요."
                },
                refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
            )
        }
    }

    fun confirmCommandAgain() {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val review = mutableUiState.value.commandReview ?: return
        val entry = mutableUiState.value.commandEntries.firstOrNull {
            it.requestId == review.requestId &&
                it.state in setOf(OutboxState.CONFLICT, OutboxState.EXPIRED)
        } ?: return
        if (!review.targetStillCurrent) return
        val body = rebuildCommandBody(entry, review.reviewedVersion) ?: return
        enqueueOutboxCommand(
            kind = review.kind,
            method = entry.method,
            path = entry.path,
            body = body,
            replacesRequestId = entry.requestId,
        )
    }

    private fun enqueueOutboxCommand(
        kind: AttachmentCommandKind,
        method: String,
        path: String,
        body: String,
        replacesRequestId: String?,
    ) {
        if (!identityMatches() || mutableUiState.value.actionRunning) return
        val boundOwner = ownerId ?: return
        mutableUiState.value = mutableUiState.value.copy(
            actionRunning = true,
            actionError = null,
            message = null,
        )
        actionJob = viewModelScope.launch {
            try {
                val requestId = UUID.randomUUID().toString()
                outbox.enqueue(boundOwner, requestId, method, path, body)
                if (!identityMatches()) return@launch
                if (replacesRequestId != null) {
                    replacedOutboxRequests += replacesRequestId
                    outbox.discard(boundOwner, replacesRequestId)
                    if (!identityMatches()) return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    actionRunning = false,
                    deleteConfirmation = if (kind == AttachmentCommandKind.DELETE) null else {
                        mutableUiState.value.deleteConfirmation
                    },
                    commandReview = null,
                    message = when (kind) {
                        AttachmentCommandKind.DELETE -> "이미지 삭제 요청을 이 기기에 보관했어요. 기존 이미지는 서버 처리가 끝날 때까지 보여요."
                        AttachmentCommandKind.OCR -> "OCR 저장 요청을 이 기기에 보관했어요."
                        AttachmentCommandKind.METADATA -> "메타데이터 다시 조회 요청을 이 기기에 보관했어요."
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (replacesRequestId != null) replacedOutboxRequests -= replacesRequestId
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    actionRunning = false,
                    actionError = error.attachmentMessage("요청을 이 기기에 보관하지 못했어요."),
                )
            }
        }
    }

    private fun observeAttachments(boundOwner: String) {
        if (!visible || attachmentJob?.isActive == true) return
        attachmentJob = viewModelScope.launch {
            attachments.observe(boundOwner, itemId).collect { pending ->
                if (!visible || !identityMatches()) return@collect
                mutableUiState.value = mutableUiState.value.copy(
                    attachmentQueueLoaded = true,
                    pendingAttachments = pending,
                    localStage = when {
                        mutableUiState.value.localStage in setOf(
                            AttachmentLocalStage.PREPARING,
                            AttachmentLocalStage.OCR_RUNNING,
                        ) -> mutableUiState.value.localStage
                        pending.any { it.stage in QUEUED_ATTACHMENT_STAGES } -> AttachmentLocalStage.QUEUED
                        pending.isNotEmpty() -> AttachmentLocalStage.IDLE
                        mutableUiState.value.localStage == AttachmentLocalStage.QUEUED ->
                            AttachmentLocalStage.IDLE
                        else -> mutableUiState.value.localStage
                    },
                )
                pending.filter { it.stage == AttachmentStage.SAVED }.forEach { entry ->
                    consumeAttachmentReceipt(boundOwner, entry)
                }
            }
        }
    }

    private suspend fun consumeAttachmentReceipt(boundOwner: String, entry: PendingAttachment) {
        if (!consumedAttachmentReceipts.add(entry.operationId)) return
        try {
            val assetId = entry.serverAssetId
                ?: throw IllegalArgumentException("Completed attachment is missing its asset ID.")
            awaitingAssetId = assetId
            awaitingAssetAfterVersion = entry.baseExpectedVersion
            mutableUiState.value = mutableUiState.value.copy(
                localStage = AttachmentLocalStage.IDLE,
                message = "이미지 업로드를 서버가 완료했어요. 새 활성 이미지를 확인하고 있어요.",
                actionError = null,
                refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
            )
            attachments.discard(boundOwner, entry.operationId)
            startPollingIfNeeded()
        } catch (error: CancellationException) {
            consumedAttachmentReceipts -= entry.operationId
            throw error
        } catch (error: Exception) {
            consumedAttachmentReceipts -= entry.operationId
            if (!identityMatches()) return
            mutableUiState.value = mutableUiState.value.copy(
                actionError = error.attachmentMessage("완료된 이미지 업로드를 화면에 반영하지 못했어요."),
            )
        }
    }

    private fun observeOutbox(boundOwner: String) {
        if (!visible || outboxJob?.isActive == true) return
        outboxJob = viewModelScope.launch {
            outbox.observeOutbox(boundOwner).collect { entries ->
                if (!visible || !identityMatches()) return@collect
                val relevant = entries
                    .filterNot { it.requestId in replacedOutboxRequests }
                    .filter { it.attachmentCommandKind(itemId) != null }
                mutableUiState.value = mutableUiState.value.copy(
                    outboxLoaded = true,
                    commandEntries = relevant,
                )
                relevant.filter { it.state == OutboxState.SAVED }.forEach { entry ->
                    consumeOutboxReceipt(boundOwner, entry)
                }
            }
        }
    }

    private suspend fun consumeOutboxReceipt(boundOwner: String, entry: OutboxEntry) {
        if (!consumedOutboxReceipts.add(entry.requestId)) return
        val kind = entry.attachmentCommandKind(itemId) ?: return
        val resultJson = entry.resultJson
        if (resultJson == null) {
            consumedOutboxReceipts -= entry.requestId
            return
        }
        try {
            val response = json.parseToJsonElement(resultJson).jsonObject
            when (kind) {
                AttachmentCommandKind.DELETE -> applyDeleteReceipt(entry, response)
                AttachmentCommandKind.OCR -> applyOcrReceipt(boundOwner, entry, response)
                AttachmentCommandKind.METADATA -> applyMetadataReceipt(response)
            }
            outbox.acknowledge(boundOwner, entry.requestId)
            startPollingIfNeeded()
        } catch (error: CancellationException) {
            consumedOutboxReceipts -= entry.requestId
            throw error
        } catch (_: AccountAuthenticationRequiredException) {
            consumedOutboxReceipts -= entry.requestId
            invalidateSession()
        } catch (error: Exception) {
            consumedOutboxReceipts -= entry.requestId
            if (!identityMatches()) return
            mutableUiState.value = mutableUiState.value.copy(
                actionError = error.attachmentMessage("서버 완료 응답을 화면에 반영하지 못했어요."),
            )
        }
    }

    private fun applyDeleteReceipt(entry: OutboxEntry, response: JsonObject) {
        val targetAssetId = entry.targetAssetId(itemId)
            ?: throw IllegalArgumentException("Delete receipt path is invalid.")
        val receiptAssetId = (response["asset_id"] as? JsonPrimitive)?.contentOrNull
        if (receiptAssetId != targetAssetId) throw IllegalArgumentException("Delete receipt asset differs.")
        if (mutableUiState.value.item.activeAsset?.id == targetAssetId) {
            awaitingDeletedAssetId = targetAssetId
            mutableUiState.value = mutableUiState.value.copy(
                message = "서버가 이미지 삭제를 접수했어요. 완료될 때까지 기존 이미지를 보여요.",
                actionError = null,
                refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
            )
        } else {
            mutableUiState.value = mutableUiState.value.copy(
                message = "이전 이미지의 삭제 완료 응답이라 현재 이미지에는 적용하지 않았어요.",
                actionError = null,
            )
        }
    }

    private suspend fun applyOcrReceipt(
        boundOwner: String,
        entry: OutboxEntry,
        response: JsonObject,
    ) {
        val targetAssetId = entry.targetAssetId(itemId)
            ?: throw IllegalArgumentException("OCR receipt path is invalid.")
        if (mutableUiState.value.item.activeAsset?.id != targetAssetId) {
            mutableUiState.value = mutableUiState.value.copy(
                message = "이전 이미지의 OCR 완료 응답이라 현재 이미지에는 적용하지 않았어요.",
                actionError = null,
            )
            return
        }
        val detail = parseLibraryDetailResponse(response)
        if (detail.id != itemId || detail.activeAsset?.id != targetAssetId) {
            throw IllegalArgumentException("OCR receipt item is invalid.")
        }
        if (!isAtLeastVersion(detail, mutableUiState.value.item)) {
            mutableUiState.value = mutableUiState.value.copy(
                message = "더 최신인 링크 상세가 있어 이전 OCR 완료 응답을 다시 적용하지 않았어요.",
                actionError = null,
            )
            return
        }
        outbox.cacheDetail(boundOwner, response)
        if (!identityMatches()) return
        updateItem(detail)
        if (!identityMatches()) return
        mutableUiState.value = mutableUiState.value.copy(
            localOcrFailed = detail.ocrState == "failed",
            message = if (detail.ocrState == "failed") {
                "OCR 실패 상태를 저장했어요. 서버의 이미지는 그대로예요."
            } else {
                "새 OCR 결과를 저장했어요."
            },
            actionError = null,
            refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
        )
    }

    private fun applyMetadataReceipt(response: JsonObject) {
        val jobId = (response["job_id"] as? JsonPrimitive)?.contentOrNull
        if (jobId.isNullOrBlank()) throw IllegalArgumentException("Metadata receipt is missing job ID.")
        UUID.fromString(jobId)
        awaitingMetadata = true
        mutableUiState.value = mutableUiState.value.copy(
            message = "서버가 메타데이터 다시 조회를 접수했어요. 완료 상태를 확인하고 있어요.",
            actionError = null,
            refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
        )
    }

    private fun loadLatest(
        fallback: String,
        onLoaded: (LibraryItemDetail) -> Unit,
    ) {
        val boundOwner = ownerId ?: return
        mutableUiState.value = mutableUiState.value.copy(actionRunning = true, actionError = null)
        actionJob = viewModelScope.launch {
            try {
                val response = client.libraryRequest(boundOwner, "/items/$itemId")
                val latest = parseLibraryDetailResponse(response)
                if (!identityMatches() || latest.id != itemId || latest.version == null ||
                    !isAtLeastVersion(latest, mutableUiState.value.item)
                ) {
                    throw IllegalStateException("Latest item does not belong to this screen.")
                }
                outbox.cacheDetail(boundOwner, response)
                updateItem(latest)
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(actionRunning = false)
                onLoaded(latest)
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateSession()
            } catch (error: Exception) {
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    actionRunning = false,
                    actionError = error.attachmentMessage(fallback),
                )
            }
        }
    }

    private fun launchAction(fallback: String, block: suspend () -> Unit) {
        mutableUiState.value = mutableUiState.value.copy(
            actionRunning = true,
            actionError = null,
        )
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                block()
                if (identityMatches()) {
                    mutableUiState.value = mutableUiState.value.copy(actionRunning = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    actionRunning = false,
                    actionError = error.attachmentMessage(fallback),
                )
            }
        }
    }

    private fun startPollingIfNeeded() {
        if (!visible || pollingJob?.isActive == true || !shouldPoll()) return
        val boundOwner = ownerId ?: return
        pollingJob = viewModelScope.launch {
            val deadline = System.nanoTime() + POLL_DURATION_MILLIS * NANOS_PER_MILLISECOND
            while (visible && identityMatches() && shouldPoll()) {
                val remainingBeforeDelay = (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND
                if (remainingBeforeDelay <= 0L) break
                delay(POLL_INTERVAL_MILLIS.coerceAtMost(remainingBeforeDelay))
                if (!visible || !identityMatches() || !shouldPoll()) return@launch
                val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND
                if (remaining <= 0L) break
                try {
                    val response = withTimeoutOrNull(remaining) {
                        client.libraryRequest(boundOwner, "/items/$itemId")
                    } ?: break
                    val latest = parseLibraryDetailResponse(response)
                    if (!visible || !identityMatches() || latest.id != itemId ||
                        !isAtLeastVersion(latest, mutableUiState.value.item)
                    ) {
                        return@launch
                    }
                    outbox.cacheDetail(boundOwner, response)
                    if (!visible || !identityMatches()) return@launch
                    updateItem(latest)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: AccountAuthenticationRequiredException) {
                    invalidateSession()
                    return@launch
                } catch (_: Exception) {
                    // Transient failures stay inside this bounded, visible-only poll.
                }
            }
            if (visible && identityMatches() && shouldPoll()) {
                mutableUiState.value = mutableUiState.value.copy(
                    message = "서버 처리가 계속되고 있어요. 상세를 다시 열면 상태를 다시 확인해요.",
                )
            }
        }
    }

    private fun shouldPoll(): Boolean = mutableUiState.value.item.metadataState == "pending" ||
        awaitingMetadata || awaitingAssetId != null || awaitingDeletedAssetId != null

    private fun loadPreviewIfNeeded() {
        if (!visible || !identityMatches()) return
        val boundOwner = ownerId ?: return
        val item = mutableUiState.value.item
        val asset = item.activeAsset ?: return
        val key = item.previewKey() ?: return
        if (previewKey == key && (mutableUiState.value.preview != null || previewJob?.isActive == true)) {
            return
        }
        clearPreview()
        previewKey = key
        mutableUiState.value = mutableUiState.value.copy(
            previewLoading = true,
            previewError = null,
        )
        previewJob = viewModelScope.launch {
            var bytes: ByteArray? = null
            try {
                val downloaded = client.downloadActiveAsset(boundOwner, itemId, asset.id)
                bytes = downloaded
                val bitmap = decodePreview(downloaded, asset)
                    ?: throw IOException("The downloaded image cannot be decoded safely.")
                if (!visible || !identityMatches() || mutableUiState.value.item.previewKey() != key) {
                    bitmap.recycle()
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    preview = bitmap,
                    previewLoading = false,
                    previewError = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateSession()
            } catch (error: Exception) {
                if (!visible || !identityMatches() || mutableUiState.value.item.previewKey() != key) {
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    previewLoading = false,
                    previewError = error.attachmentMessage("이미지 미리보기를 안전하게 열지 못했어요."),
                )
            } finally {
                bytes?.fill(0)
            }
        }
    }

    private fun clearPreview() {
        previewJob?.cancel()
        previewJob = null
        previewKey = null
        mutableUiState.value.preview?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        mutableUiState.value = mutableUiState.value.copy(
            preview = null,
            previewLoading = false,
            previewError = null,
        )
    }

    private fun invalidateOldAssetAction() {
        mutableUiState.value = mutableUiState.value.copy(
            localStage = AttachmentLocalStage.IDLE,
            actionRunning = false,
            message = "작업 중 이미지가 교체되어 이전 이미지의 결과를 적용하지 않았어요.",
        )
    }

    private fun observeSession() {
        viewModelScope.launch {
            client.sessionState.collect { session ->
                if (session.ownerId != ownerId || session.generation != sessionGeneration) {
                    invalidateSession()
                }
            }
        }
    }

    private fun invalidateSession() {
        if (!mutableUiState.value.sessionValid) return
        visible = false
        preparationJob?.cancel()
        preparationJob = null
        actionJob?.cancel()
        actionJob = null
        attachmentJob?.cancel()
        attachmentJob = null
        outboxJob?.cancel()
        outboxJob = null
        pollingJob?.cancel()
        pollingJob = null
        clearPreview()
        awaitingMetadata = false
        awaitingAssetId = null
        awaitingAssetAfterVersion = null
        awaitingDeletedAssetId = null
        mutableUiState.value = AttachmentUiState(
            item = LibraryItemDetail(id = itemId, url = ""),
            sessionValid = false,
        )
    }

    private fun identityMatches(): Boolean {
        val session = client.sessionState.value
        return !ownerId.isNullOrBlank() && session.ownerId == ownerId &&
            session.generation == sessionGeneration
    }

    private fun isAtLeastVersion(candidate: LibraryItemDetail, current: LibraryItemDetail): Boolean {
        val currentVersion = current.version ?: return true
        val candidateVersion = candidate.version ?: return false
        return candidateVersion >= currentVersion
    }

    private fun isNewerVersion(candidate: Long?, baseline: Long?): Boolean =
        candidate != null && baseline != null && candidate > baseline

    override fun onCleared() {
        mutableUiState.value.preview?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 5_000L
        private const val POLL_DURATION_MILLIS = 60_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun factory(
            client: AccountClient,
            outbox: OutboxRepository,
            attachments: AttachmentRepository,
            ownerId: String?,
            sessionGeneration: Long,
            initialItem: LibraryItemDetail,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AttachmentViewModel::class.java))
                return AttachmentViewModel(
                    client = client,
                    outbox = outbox,
                    attachments = attachments,
                    ownerId = ownerId,
                    sessionGeneration = sessionGeneration,
                    initialItem = initialItem,
                ) as T
            }
        }
    }
}

private data class PreviewKey(val assetId: String, val version: Long?)

private fun LibraryItemDetail.previewKey(): PreviewKey? =
    activeAsset?.let { PreviewKey(it.id, version) }

private fun OcrState.toAttachmentOcrState(): AttachmentOcrState = when (this) {
    OcrState.READY -> AttachmentOcrState.READY
    OcrState.FAILED -> AttachmentOcrState.FAILED
}

private fun expectedVersionBody(version: Long): String = buildJsonObject {
    put("expected_version", version)
}.toString()

private fun buildOcrBody(expectedVersion: Long, ocr: OcrResult): String = buildJsonObject {
    put("expected_version", expectedVersion)
    put("ocr_state", if (ocr.state == OcrState.READY) "ready" else "failed")
    put("ocr_text", ocr.text?.let(::JsonPrimitive) ?: JsonNull)
    put("ocr_truncated", ocr.truncated)
}.toString()

private fun rebuildCommandBody(entry: OutboxEntry, reviewedVersion: Long): String? {
    val itemId = entry.itemIdFromAttachmentPath() ?: return null
    return when (entry.attachmentCommandKind(itemId)) {
        AttachmentCommandKind.DELETE,
        AttachmentCommandKind.METADATA,
        -> expectedVersionBody(reviewedVersion)

        AttachmentCommandKind.OCR -> {
            val old = runCatching {
                Json.parseToJsonElement(entry.payloadJson).jsonObject
            }.getOrNull() ?: return null
            val state = old["ocr_state"]?.jsonPrimitive?.contentOrNull ?: return null
            if (state !in setOf("ready", "failed")) return null
            val text = if (state == "ready") {
                (old["ocr_text"] as? JsonPrimitive)?.takeIf { it.isString }
                    ?: return null
            } else {
                JsonNull
            }
            val truncated = (old["ocr_truncated"] as? JsonPrimitive)?.booleanOrNull
                ?: return null
            if (state == "failed" && truncated) return null
            buildJsonObject {
                put("expected_version", reviewedVersion)
                put("ocr_state", state)
                put("ocr_text", text)
                put("ocr_truncated", truncated)
            }.toString()
        }

        null -> null
    }
}

private fun OutboxEntry.attachmentCommandKind(itemId: String): AttachmentCommandKind? {
    val itemPath = "/items/$itemId"
    return when {
        method == "POST" && path == "$itemPath/retry-metadata" -> AttachmentCommandKind.METADATA
        method == "DELETE" && path.matchesAssetPath(itemPath, suffix = "") -> AttachmentCommandKind.DELETE
        method == "PATCH" && path.matchesAssetPath(itemPath, suffix = "/ocr") -> AttachmentCommandKind.OCR
        else -> null
    }
}

private fun OutboxEntry.targetAssetId(itemId: String): String? {
    val prefix = "/items/$itemId/assets/"
    if (!path.startsWith(prefix)) return null
    val tail = path.removePrefix(prefix).removeSuffix("/ocr")
    return tail.takeIf { it.isCanonicalUuid() }
}

private fun OutboxEntry.itemIdFromAttachmentPath(): String? {
    val parts = path.split('/')
    return parts.getOrNull(2)?.takeIf { it.isCanonicalUuid() }
}

private fun String.matchesAssetPath(itemPath: String, suffix: String): Boolean {
    val prefix = "$itemPath/assets/"
    if (!startsWith(prefix) || !endsWith(suffix)) return false
    val assetId = removePrefix(prefix).let { value ->
        if (suffix.isEmpty()) value else value.removeSuffix(suffix)
    }
    return assetId.isCanonicalUuid()
}

private fun String.isCanonicalUuid(): Boolean = runCatching {
    UUID.fromString(this).toString().equals(this, ignoreCase = true)
}.getOrDefault(false)

private suspend fun writePrivateOcrFile(file: File, bytes: ByteArray): Unit =
    withContext(Dispatchers.IO) {
        require(
            bytes.isNotEmpty() &&
                bytes.size.toLong() <= ImagePreparation.MAX_OUTPUT_BYTES,
        ) {
            "Downloaded image exceeds the private OCR limit."
        }
        check(file.createNewFile()) { "The private OCR destination already exists." }
        try {
            file.outputStream().use { it.write(bytes) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

private suspend fun decodePreview(bytes: ByteArray, asset: LibraryActiveAsset): Bitmap? =
    withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size.toLong() > ImagePreparation.MAX_OUTPUT_BYTES ||
            bytes.size.toLong() != asset.byteSize ||
            asset.mimeType !in SUPPORTED_PREVIEW_MIME_TYPES
        ) {
            return@withContext null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width != asset.width || height != asset.height ||
            width.toLong() * height.toLong() > ImagePreparation.MAX_PIXELS ||
            bounds.outMimeType !in SUPPORTED_PREVIEW_MIME_TYPES
        ) {
            return@withContext null
        }
        var sample = 1
        while ((width / sample).toLong() * (height / sample).toLong() > MAX_PREVIEW_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        try {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            if (decoded.isRecycled || decoded.width <= 0 || decoded.height <= 0 ||
                decoded.width.toLong() * decoded.height.toLong() > MAX_PREVIEW_PIXELS
            ) {
                if (!decoded.isRecycled) decoded.recycle()
                null
            } else {
                decoded
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

private fun Throwable.attachmentMessage(fallback: String): String = when (this) {
    is AccountClientException -> when (code) {
        "VERSION_CONFLICT" -> "다른 변경이 먼저 저장됐어요. 최신 내용을 확인해 주세요."
        "ASSET_NOT_ACTIVE" -> "대상 이미지가 더 이상 현재 이미지가 아니에요."
        "ASSET_RESERVATION_EXISTS" -> "이미 다른 이미지 첨부가 진행 중이에요."
        "STORAGE_LIMIT_REACHED" -> "이미지 저장 용량 한도에 도달했어요."
        "RATE_LIMITED" -> "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
        "ITEM_NOT_FOUND", "ITEM_DELETED" -> "보관한 링크를 찾지 못했어요."
        else -> message.takeIf(String::isNotBlank) ?: fallback
    }
    is ImagePreparationException -> when (reason) {
        ImagePreparationFailure.SOURCE_UNAVAILABLE -> "선택한 이미지를 읽을 수 없어요."
        ImagePreparationFailure.SOURCE_TOO_LARGE -> "선택한 이미지 파일이 너무 커요."
        ImagePreparationFailure.UNSUPPORTED_FORMAT -> "지원하는 JPEG, PNG, WebP 이미지를 선택해 주세요."
        ImagePreparationFailure.ANIMATED_IMAGE -> "움직이는 이미지는 첨부할 수 없어요."
        ImagePreparationFailure.INVALID_IMAGE -> "이미지를 안전하게 열지 못했어요."
        ImagePreparationFailure.PIXEL_LIMIT_EXCEEDED -> "이미지 해상도가 너무 커요."
        ImagePreparationFailure.OUTPUT_TOO_LARGE -> "업로드용으로 줄인 이미지가 여전히 너무 커요."
        ImagePreparationFailure.OUTPUT_WRITE_FAILED,
        ImagePreparationFailure.INVALID_DESTINATION,
        -> "기기 안에 업로드용 이미지를 준비하지 못했어요."
    }
    else -> fallback
}

internal val RETRYABLE_METADATA_ERRORS = setOf(
    "METADATA_TIMEOUT",
    "NETWORK_ERROR",
    "RATE_LIMITED",
    "INTERNAL_ERROR",
)

private val QUEUED_ATTACHMENT_STAGES = setOf(
    AttachmentStage.RESERVE,
    AttachmentStage.RETRY,
    AttachmentStage.WAITING_LOGIN,
)
private val DISCARDABLE_ATTACHMENT_STAGES = setOf(
    AttachmentStage.FAILED,
    AttachmentStage.CONFLICT,
    AttachmentStage.EXPIRED,
)
private val DISCARDABLE_OUTBOX_STATES = setOf(
    OutboxState.FAILED,
    OutboxState.CONFLICT,
    OutboxState.EXPIRED,
)
internal val RETRYABLE_COMMAND_ERRORS = setOf(
    "DEPENDENCY_UNAVAILABLE",
    "RATE_LIMITED",
    "SESSION_REFRESH_UNAVAILABLE",
)
private val SUPPORTED_PREVIEW_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private const val MAX_PREVIEW_PIXELS = 2_000_000L
