package com.linkvault.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linkvault.app.auth.AccountAuthenticationRequiredException
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.storage.CachedItem
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LibraryViewModel internal constructor(
    private val client: AccountClient,
    private val outbox: OutboxRepository,
    private val ownerId: String?,
    initialUrl: String?,
    sharedText: String,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableUiState = MutableStateFlow(
        LibraryUiState(
            availability = LibraryAvailability.Checking,
            form = LibrarySaveForm(initialUrl = initialUrl, sharedText = sharedText),
        ),
    )
    val uiState: StateFlow<LibraryUiState> = mutableUiState.asStateFlow()

    private var listJob: Job? = null
    private var saveJob: Job? = null
    private var detailJob: Job? = null
    private var editJob: Job? = null
    private var restoreJob: Job? = null
    private var cacheJob: Job? = null
    private var outboxJob: Job? = null
    private var pendingSaveRequestId: String? = null
    private var pendingEditRequestId: String? = null
    private var cachedItems: List<LibraryItemSummary> = emptyList()
    private var cachedFetchedAt: Long? = null
    private val preparedReceipts = mutableSetOf<String>()
    private val replacedRequestIds = mutableSetOf<String>()

    init {
        when {
            !client.isConfigured -> mutableUiState.value = mutableUiState.value.copy(
                availability = LibraryAvailability.SignInRequired(
                    client.configurationMessage ?: "로그인 설정이 필요해요.",
                ),
                isListLoading = false,
            )

            ownerId.isNullOrBlank() -> restoreUnboundSession()

            !identityMatches() -> mutableUiState.value = mutableUiState.value.copy(
                availability = LibraryAvailability.SignInRequired("로그인이 필요해요."),
                isListLoading = false,
            )

            else -> {
                mutableUiState.value = mutableUiState.value.copy(
                    availability = LibraryAvailability.Ready,
                )
                observeCache(ownerId)
                observeOutbox(ownerId)
                restoreAndLoad(ownerId)
            }
        }
    }

    fun updateSharedInput(initialUrl: String?, sharedText: String) {
        val currentForm = mutableUiState.value.form
        if (currentForm.initialUrl == initialUrl && currentForm.sharedText == sharedText) return
        saveJob?.cancel()
        saveJob = null
        pendingSaveRequestId = null
        mutableUiState.value = mutableUiState.value.copy(
            form = LibrarySaveForm(initialUrl = initialUrl, sharedText = sharedText),
            saveStatus = LibrarySaveStatus.Idle,
            detail = LibraryDetailState.None,
            edit = null,
        )
    }

    fun updateTitle(title: String) {
        if (!formCanChange()) return
        mutableUiState.value = mutableUiState.value.copy(
            form = mutableUiState.value.form.copy(title = title),
            saveStatus = LibrarySaveStatus.Idle,
        )
    }

    fun updateNote(note: String) {
        if (!formCanChange()) return
        mutableUiState.value = mutableUiState.value.copy(
            form = mutableUiState.value.form.copy(note = note),
            saveStatus = LibrarySaveStatus.Idle,
        )
    }

    fun save() {
        if (saveJob?.isActive == true || mutableUiState.value.availability !is LibraryAvailability.Ready) {
            return
        }
        val boundOwner = ownerId
        if (boundOwner == null || !identityMatches()) {
            invalidateIdentity()
            return
        }
        val preparation = prepareLibrarySave(
            form = mutableUiState.value.form,
            ownerId = boundOwner,
            requestId = UUID.randomUUID().toString(),
        )
        when (preparation) {
            is LibrarySavePreparation.Invalid -> {
                mutableUiState.value = mutableUiState.value.copy(
                    saveStatus = LibrarySaveStatus.Invalid(preparation.issues),
                )
            }

            is LibrarySavePreparation.Valid -> enqueueSave(preparation.operation)
        }
    }

    fun retrySave() {
        val failure = mutableUiState.value.saveStatus as? LibrarySaveStatus.Failed ?: return
        val requestId = failure.requestId ?: return
        retryOperation(requestId)
    }

    fun editAfterSaveFailure() {
        val failure = mutableUiState.value.saveStatus as? LibrarySaveStatus.Failed ?: return
        val requestId = failure.requestId
        if (requestId == null) {
            mutableUiState.value = mutableUiState.value.copy(saveStatus = LibrarySaveStatus.Idle)
        } else {
            discardOperation(requestId)
        }
    }

    fun reconfirmExpiredSave(requestId: String) {
        if (saveJob?.isActive == true) return
        val entry = mutableUiState.value.outboxEntries.firstOrNull {
            it.requestId == requestId && it.ownerId == ownerId && it.state == OutboxState.EXPIRED
        } ?: return
        if (entry.method != "POST" || entry.path != "/items") return
        val boundOwner = ownerId ?: return
        val newRequestId = UUID.randomUUID().toString()
        pendingSaveRequestId = newRequestId
        mutableUiState.value = mutableUiState.value.copy(saveStatus = LibrarySaveStatus.Saving)
        replacedRequestIds += requestId
        val job = viewModelScope.launch {
            try {
                outbox.enqueue(
                    ownerId = boundOwner,
                    requestId = newRequestId,
                    method = entry.method,
                    path = entry.path,
                    payloadJson = entry.payloadJson,
                )
                outbox.discard(boundOwner, requestId)
                mutableUiState.value = mutableUiState.value.copy(
                    saveStatus = LibrarySaveStatus.Queued(newRequestId),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                    return@launch
                }
                replacedRequestIds -= requestId
                pendingSaveRequestId = null
                mutableUiState.value = mutableUiState.value.copy(
                    saveStatus = LibrarySaveStatus.Failed(
                        requestId = requestId,
                        message = error.localQueueMessage(),
                        canRetrySameRequest = false,
                        needsReconfirmation = true,
                    ),
                )
            }
        }
        saveJob = job
        job.invokeOnCompletion {
            if (saveJob === job) saveJob = null
        }
    }

    fun refresh() {
        if (mutableUiState.value.availability !is LibraryAvailability.Ready) return
        val boundOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        viewModelScope.launch {
            try {
                outbox.resumeOwner(boundOwner)
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (_: Exception) {
                // A list refresh still has value when worker scheduling is temporarily unavailable.
            }
        }
        loadItems(reset = true)
    }

    fun retryList() {
        val state = mutableUiState.value
        loadItems(reset = state.isShowingCache || state.items.isEmpty())
    }

    fun loadMore() {
        val state = mutableUiState.value
        if (state.isShowingCache || !state.hasMore || state.isListLoading || state.listError != null) return
        loadItems(reset = false)
    }

    fun openDetail(itemId: String) {
        if (detailJob?.isActive == true || !identityMatches()) {
            if (!identityMatches()) invalidateIdentity()
            return
        }
        val requestOwner = ownerId ?: return
        mutableUiState.value = mutableUiState.value.copy(
            detail = LibraryDetailState.Loading(itemId),
            edit = null,
        )
        val job = viewModelScope.launch {
            try {
                val response = client.libraryRequest(
                    expectedOwnerId = requestOwner,
                    path = "/items/$itemId",
                )
                val item = parseLibraryDetailResponse(response)
                outbox.cacheDetail(requestOwner, response)
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    detail = LibraryDetailState.Loaded(item),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    showCachedDetailOrFailure(
                        requestOwner = requestOwner,
                        itemId = itemId,
                        message = error.detailMessage(),
                    )
                }
            }
        }
        detailJob = job
        job.invokeOnCompletion {
            if (detailJob === job) detailJob = null
        }
    }

    fun retryDetail() {
        val itemId = (mutableUiState.value.detail as? LibraryDetailState.Failed)?.itemId ?: return
        openDetail(itemId)
    }

    fun closeDetail() {
        detailJob?.cancel()
        editJob?.cancel()
        detailJob = null
        editJob = null
        mutableUiState.value = mutableUiState.value.copy(
            detail = LibraryDetailState.None,
            edit = null,
        )
    }

    fun beginEdit() {
        val detail = mutableUiState.value.detail as? LibraryDetailState.Loaded ?: return
        val version = detail.item.version ?: return
        mutableUiState.value = mutableUiState.value.copy(
            edit = LibraryEditUiState(
                itemId = detail.item.id,
                expectedVersion = version,
                originalTitle = detail.item.userTitle,
                originalNote = detail.item.note,
                form = LibraryEditForm(
                    title = detail.item.userTitle.orEmpty(),
                    note = detail.item.note.orEmpty(),
                ),
            ),
        )
    }

    fun cancelEdit() {
        val edit = mutableUiState.value.edit ?: return
        if (!edit.status.allowsEditing() && edit.status !is LibraryEditStatus.Saved) return
        editJob?.cancel()
        editJob = null
        mutableUiState.value = mutableUiState.value.copy(edit = null)
    }

    fun updateEditTitle(title: String) {
        updateEditForm { it.copy(title = title) }
    }

    fun updateEditNote(note: String) {
        updateEditForm { it.copy(note = note) }
    }

    fun saveEdit() {
        if (editJob?.isActive == true) return
        val edit = mutableUiState.value.edit ?: return
        if (!edit.status.allowsEditing()) return
        val boundOwner = ownerId
        if (boundOwner == null || !identityMatches()) {
            invalidateIdentity()
            return
        }
        val preparation = prepareLibraryEdit(
            ownerId = boundOwner,
            itemId = edit.itemId,
            requestId = UUID.randomUUID().toString(),
            expectedVersion = edit.expectedVersion,
            originalTitle = edit.originalTitle,
            originalNote = edit.originalNote,
            form = edit.form,
        )
        when (preparation) {
            is LibraryEditPreparation.Invalid -> mutableUiState.value = mutableUiState.value.copy(
                edit = edit.copy(status = LibraryEditStatus.Invalid(preparation.issues)),
            )

            is LibraryEditPreparation.Valid -> enqueueEdit(preparation.operation)
        }
    }

    fun loadLatestForEdit() {
        if (editJob?.isActive == true) return
        val edit = mutableUiState.value.edit ?: return
        val blocked = edit.status as? LibraryEditStatus.Blocked ?: return
        val boundOwner = ownerId
        if (boundOwner == null || !identityMatches()) {
            invalidateIdentity()
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            edit = edit.copy(
                status = LibraryEditStatus.LoadingLatest(blocked.requestId, blocked.reason),
                latestError = null,
            ),
        )
        val job = viewModelScope.launch {
            try {
                val response = client.libraryRequest(
                    expectedOwnerId = boundOwner,
                    path = "/items/${edit.itemId}",
                )
                val latest = parseLibraryDetailResponse(response)
                if (latest.version == null) {
                    throw IllegalStateException("The detail response has no version.")
                }
                outbox.cacheDetail(boundOwner, response)
                if (!responseBelongsTo(boundOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    detail = LibraryDetailState.Loaded(latest),
                    edit = edit.copy(
                        status = LibraryEditStatus.ReadyToConfirm(
                            requestId = blocked.requestId,
                            reason = blocked.reason,
                            latest = latest,
                        ),
                        latestError = null,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        edit = edit.copy(
                            status = blocked,
                            latestError = error.detailMessage(),
                        ),
                    )
                }
            }
        }
        editJob = job
        job.invokeOnCompletion {
            if (editJob === job) editJob = null
        }
    }

    fun confirmEditAgain() {
        if (editJob?.isActive == true) return
        val edit = mutableUiState.value.edit ?: return
        val ready = edit.status as? LibraryEditStatus.ReadyToConfirm ?: return
        val latestVersion = ready.latest.version ?: return
        val boundOwner = ownerId
        if (boundOwner == null || !identityMatches()) {
            invalidateIdentity()
            return
        }
        val preparation = prepareLibraryEdit(
            ownerId = boundOwner,
            itemId = edit.itemId,
            requestId = UUID.randomUUID().toString(),
            expectedVersion = latestVersion,
            originalTitle = ready.latest.userTitle,
            originalNote = ready.latest.note,
            form = edit.form,
        )
        if (preparation is LibraryEditPreparation.Invalid) {
            val noChange = preparation.issues.singleOrNull()?.message == "변경된 제목이나 메모가 없어요."
            if (!noChange) {
                mutableUiState.value = mutableUiState.value.copy(
                    edit = edit.copy(status = LibraryEditStatus.Invalid(preparation.issues)),
                )
                return
            }
            replacedRequestIds += ready.requestId
            val job = viewModelScope.launch {
                try {
                    outbox.discard(boundOwner, ready.requestId)
                    mutableUiState.value = mutableUiState.value.copy(
                        detail = LibraryDetailState.Loaded(ready.latest),
                        edit = edit.copy(status = LibraryEditStatus.Saved("최신 내용과 이미 같아요.")),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: AccountAuthenticationRequiredException) {
                    invalidateIdentity()
                } catch (error: Exception) {
                    if (!identityMatches()) {
                        invalidateIdentity()
                        return@launch
                    }
                    replacedRequestIds -= ready.requestId
                    mutableUiState.value = mutableUiState.value.copy(
                        edit = edit.copy(latestError = error.localQueueMessage()),
                    )
                }
            }
            editJob = job
            job.invokeOnCompletion {
                if (editJob === job) editJob = null
            }
            return
        }

        val operation = (preparation as LibraryEditPreparation.Valid).operation
        pendingEditRequestId = operation.requestId
        replacedRequestIds += ready.requestId
        mutableUiState.value = mutableUiState.value.copy(
            edit = edit.copy(status = LibraryEditStatus.Queuing),
        )
        val job = viewModelScope.launch {
            try {
                outbox.enqueue(
                    ownerId = boundOwner,
                    requestId = operation.requestId,
                    method = "PATCH",
                    path = "/items/${operation.itemId}",
                    payloadJson = operation.body,
                )
                outbox.discard(boundOwner, ready.requestId)
                mutableUiState.value = mutableUiState.value.copy(
                    edit = edit.copy(
                        expectedVersion = latestVersion,
                        originalTitle = ready.latest.userTitle,
                        originalNote = ready.latest.note,
                        status = LibraryEditStatus.Queued(operation.requestId),
                        latestError = null,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                    return@launch
                }
                pendingEditRequestId = null
                replacedRequestIds -= ready.requestId
                mutableUiState.value = mutableUiState.value.copy(
                    edit = edit.copy(
                        status = ready,
                        latestError = error.localQueueMessage(),
                    ),
                )
            }
        }
        editJob = job
        job.invokeOnCompletion {
            if (editJob === job) editJob = null
        }
    }

    fun retryOperation(requestId: String) {
        mutateOutbox(requestId) { boundOwner -> outbox.retry(boundOwner, requestId) }
    }

    fun discardOperation(requestId: String) {
        mutateOutbox(requestId) { boundOwner ->
            outbox.discard(boundOwner, requestId)
            if (pendingSaveRequestId == requestId) {
                pendingSaveRequestId = null
                mutableUiState.value = mutableUiState.value.copy(saveStatus = LibrarySaveStatus.Idle)
            }
            val edit = mutableUiState.value.edit
            if (edit?.status?.requestIdOrNull() == requestId) {
                pendingEditRequestId = null
                mutableUiState.value = mutableUiState.value.copy(
                    edit = if (edit.status is LibraryEditStatus.Blocked ||
                        edit.status is LibraryEditStatus.ReadyToConfirm
                    ) {
                        null
                    } else {
                        edit.copy(status = LibraryEditStatus.Idle)
                    },
                )
            }
        }
    }

    fun acknowledgeSavedReceipts(receiptIds: Set<String>) {
        if (receiptIds.isEmpty()) return
        val boundOwner = ownerId ?: return
        viewModelScope.launch {
            receiptIds.forEach { requestId ->
                try {
                    outbox.acknowledge(boundOwner, requestId)
                    preparedReceipts -= requestId
                    mutableUiState.value = mutableUiState.value.copy(
                        receiptsAwaitingAcknowledgement =
                            mutableUiState.value.receiptsAwaitingAcknowledgement - requestId,
                    )
                } catch (_: AccountAuthenticationRequiredException) {
                    invalidateIdentity()
                    return@launch
                } catch (_: Exception) {
                    // The SAVED receipt remains durable and will be offered again.
                }
            }
        }
    }

    private fun enqueueSave(operation: LibrarySaveOperation) {
        if (saveJob?.isActive == true) return
        pendingSaveRequestId = operation.requestId
        mutableUiState.value = mutableUiState.value.copy(saveStatus = LibrarySaveStatus.Saving)
        val job = viewModelScope.launch {
            try {
                outbox.enqueue(
                    ownerId = operation.ownerId,
                    requestId = operation.requestId,
                    method = "POST",
                    path = "/items",
                    payloadJson = operation.body,
                )
                mutableUiState.value = mutableUiState.value.copy(
                    saveStatus = LibrarySaveStatus.Queued(operation.requestId),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                    return@launch
                }
                pendingSaveRequestId = null
                mutableUiState.value = mutableUiState.value.copy(
                    saveStatus = LibrarySaveStatus.Failed(
                        requestId = null,
                        message = error.localQueueMessage(),
                        canRetrySameRequest = false,
                    ),
                )
            }
        }
        saveJob = job
        job.invokeOnCompletion {
            if (saveJob === job) saveJob = null
        }
    }

    private fun enqueueEdit(operation: LibraryEditOperation) {
        if (editJob?.isActive == true) return
        val current = mutableUiState.value.edit ?: return
        pendingEditRequestId = operation.requestId
        mutableUiState.value = mutableUiState.value.copy(
            edit = current.copy(status = LibraryEditStatus.Queuing),
        )
        val job = viewModelScope.launch {
            try {
                outbox.enqueue(
                    ownerId = operation.ownerId,
                    requestId = operation.requestId,
                    method = "PATCH",
                    path = "/items/${operation.itemId}",
                    payloadJson = operation.body,
                )
                mutableUiState.value = mutableUiState.value.copy(
                    edit = current.copy(status = LibraryEditStatus.Queued(operation.requestId)),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                    return@launch
                }
                pendingEditRequestId = null
                mutableUiState.value = mutableUiState.value.copy(
                    edit = current.copy(
                        status = LibraryEditStatus.Failed(null, error.localQueueMessage()),
                    ),
                )
            }
        }
        editJob = job
        job.invokeOnCompletion {
            if (editJob === job) editJob = null
        }
    }

    private fun restoreAndLoad(requestOwner: String) {
        val job = viewModelScope.launch {
            try {
                client.restoreAccount()
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                outbox.resumeOwner(requestOwner)
                loadItems(reset = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                if (identityMatches()) {
                    showCachedList("로그인 상태를 확인하지 못했어요. 다시 시도해 주세요.")
                } else {
                    invalidateIdentity()
                }
            } catch (error: Exception) {
                if (identityMatches()) {
                    showCachedList(error.listMessage())
                } else {
                    invalidateIdentity()
                }
            }
        }
        restoreJob = job
        job.invokeOnCompletion {
            if (restoreJob === job) restoreJob = null
        }
    }

    private fun restoreUnboundSession() {
        val job = viewModelScope.launch {
            try {
                val access = client.restoreAccount()
                val restoredOwner = client.sessionUserId()
                if (access == null || restoredOwner.isNullOrBlank()) {
                    mutableUiState.value = mutableUiState.value.copy(
                        availability = LibraryAvailability.SignInRequired("로그인이 필요해요."),
                        isListLoading = false,
                    )
                } else {
                    // LibraryScreen keys the ViewModel by owner. This state change
                    // triggers creation of the correctly owner-bound instance.
                    mutableUiState.value = mutableUiState.value.copy(isListLoading = false)
                }
            } catch (_: AccountAuthenticationRequiredException) {
                val restoredOwner = client.sessionUserId()
                mutableUiState.value = if (restoredOwner.isNullOrBlank()) {
                    mutableUiState.value.copy(
                        availability = LibraryAvailability.SignInRequired(
                            "로그인 상태를 확인하지 못했어요. 다시 로그인해 주세요.",
                        ),
                        isListLoading = false,
                    )
                } else {
                    mutableUiState.value.copy(isListLoading = false)
                }
            } catch (error: Exception) {
                val restoredOwner = client.sessionUserId()
                mutableUiState.value = if (restoredOwner.isNullOrBlank()) {
                    mutableUiState.value.copy(
                        availability = LibraryAvailability.SignInRequired(
                            (error as? AccountClientException)?.safeLibraryMessage()
                                ?: "로그인 상태를 확인하지 못했어요. 네트워크 연결을 확인해 주세요.",
                        ),
                        isListLoading = false,
                    )
                } else {
                    mutableUiState.value.copy(isListLoading = false)
                }
            }
        }
        restoreJob = job
        job.invokeOnCompletion {
            if (restoreJob === job) restoreJob = null
        }
    }

    private fun loadItems(reset: Boolean) {
        val current = mutableUiState.value
        if (listJob?.isActive == true || current.availability !is LibraryAvailability.Ready) return
        val requestOwner = ownerId
        if (requestOwner == null || !identityMatches()) {
            invalidateIdentity()
            return
        }
        val offset = if (reset) 0 else current.nextOffset
        mutableUiState.value = current.copy(
            isListLoading = true,
            listError = null,
        )
        val job = viewModelScope.launch {
            try {
                val response = client.libraryRequest(
                    expectedOwnerId = requestOwner,
                    path = "/items?limit=$PAGE_SIZE&offset=$offset",
                )
                val page = parseLibraryListResponse(response)
                val rawItems = response["items"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                outbox.cacheList(requestOwner, rawItems, replace = reset)
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                val existing = if (reset || mutableUiState.value.isShowingCache) {
                    emptyList()
                } else {
                    mutableUiState.value.items
                }
                val knownIds = existing.asSequence().map { it.id }.toHashSet()
                val appended = page.items.filter { knownIds.add(it.id) }
                mutableUiState.value = mutableUiState.value.copy(
                    items = existing + appended,
                    hasMore = page.hasMore,
                    nextOffset = offset + page.items.size,
                    isListLoading = false,
                    listError = null,
                    isShowingCache = false,
                    cacheFetchedAt = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                if (identityMatches()) {
                    showCachedList("로그인 상태를 확인하지 못했어요. 다시 시도해 주세요.")
                } else {
                    invalidateIdentity()
                }
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    showCachedList(error.listMessage())
                }
            }
        }
        listJob = job
        job.invokeOnCompletion {
            if (listJob === job) listJob = null
        }
    }

    private fun observeCache(requestOwner: String) {
        cacheJob = viewModelScope.launch {
            outbox.cachedItems(requestOwner).collect { rows ->
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@collect
                }
                val parsedRows = rows.mapNotNull { row -> row.parseSummaryOrNull() }
                cachedItems = parsedRows.map { it.first }
                cachedFetchedAt = parsedRows.maxOfOrNull { it.second }
                val state = mutableUiState.value
                if (state.isShowingCache || (state.items.isEmpty() && state.isListLoading)) {
                    mutableUiState.value = state.copy(
                        items = cachedItems,
                        hasMore = false,
                        nextOffset = 0,
                        isShowingCache = cachedItems.isNotEmpty(),
                        cacheFetchedAt = cachedFetchedAt,
                    )
                }
            }
        }
    }

    private fun observeOutbox(requestOwner: String) {
        outboxJob = viewModelScope.launch {
            outbox.observeOutbox(requestOwner).collect { entries ->
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@collect
                }
                val visibleEntries = entries.filterNot { it.requestId in replacedRequestIds }
                mutableUiState.value = mutableUiState.value.copy(outboxEntries = visibleEntries)
                updateSaveFromOutbox(visibleEntries)
                updateEditFromOutbox(visibleEntries)
                restoreBlockedEdit(visibleEntries)
                visibleEntries.filter { it.state == OutboxState.SAVED }.forEach { entry ->
                    prepareSavedReceipt(entry)
                }
            }
        }
    }

    private fun updateSaveFromOutbox(entries: List<OutboxEntry>) {
        val state = mutableUiState.value
        if (state.saveStatus is LibrarySaveStatus.Saved) return
        val requestId = pendingSaveRequestId ?: entries
            .asSequence()
            .filter { it.method == "POST" && it.path == "/items" }
            .filter { it.payloadUrlOrNull() == state.form.initialUrl }
            .maxByOrNull { it.createdAt }
            ?.requestId
            ?.also { pendingSaveRequestId = it }
            ?: return
        val entry = entries.firstOrNull { it.requestId == requestId } ?: return
        val nextStatus = when (entry.state) {
            OutboxState.PENDING,
            OutboxState.RUNNING,
            OutboxState.RETRY,
            OutboxState.WAITING_LOGIN,
            -> LibrarySaveStatus.Queued(entry.requestId)

            OutboxState.FAILED -> LibrarySaveStatus.Failed(
                requestId = entry.requestId,
                message = entry.failureMessage(),
                canRetrySameRequest = true,
            )

            OutboxState.CONFLICT -> LibrarySaveStatus.Failed(
                requestId = entry.requestId,
                message = "저장 요청이 서버의 최신 내용과 충돌했어요.",
                canRetrySameRequest = false,
            )

            OutboxState.EXPIRED -> LibrarySaveStatus.Failed(
                requestId = entry.requestId,
                message = "저장 요청이 24시간을 지나 만료됐어요. 내용을 확인한 뒤 새 요청으로 보관해 주세요.",
                canRetrySameRequest = false,
                needsReconfirmation = true,
            )

            OutboxState.SAVED -> state.saveStatus
        }
        mutableUiState.value = mutableUiState.value.copy(saveStatus = nextStatus)
    }

    private fun updateEditFromOutbox(entries: List<OutboxEntry>) {
        val edit = mutableUiState.value.edit ?: return
        if (edit.status is LibraryEditStatus.LoadingLatest ||
            edit.status is LibraryEditStatus.ReadyToConfirm
        ) {
            return
        }
        val requestId = pendingEditRequestId ?: edit.status.requestIdOrNull() ?: return
        val entry = entries.firstOrNull {
            it.requestId == requestId && it.method == "PATCH"
        } ?: return
        val nextStatus = when (entry.state) {
            OutboxState.PENDING,
            OutboxState.RUNNING,
            OutboxState.RETRY,
            OutboxState.WAITING_LOGIN,
            -> LibraryEditStatus.Queued(entry.requestId)

            OutboxState.FAILED -> LibraryEditStatus.Failed(
                requestId = entry.requestId,
                message = entry.failureMessage(),
            )

            OutboxState.CONFLICT -> LibraryEditStatus.Blocked(
                requestId = entry.requestId,
                reason = LibraryEditBlockReason.VERSION_CONFLICT,
            )

            OutboxState.EXPIRED -> LibraryEditStatus.Blocked(
                requestId = entry.requestId,
                reason = LibraryEditBlockReason.EXPIRED,
            )

            OutboxState.SAVED -> edit.status
        }
        mutableUiState.value = mutableUiState.value.copy(
            edit = edit.copy(status = nextStatus),
        )
    }

    private suspend fun restoreBlockedEdit(entries: List<OutboxEntry>) {
        val entry = entries
            .asSequence()
            .filter { it.method == "PATCH" && it.path.startsWith("/items/") }
            .filter { it.state == OutboxState.CONFLICT || it.state == OutboxState.EXPIRED }
            .maxByOrNull { it.createdAt }
            ?: return
        val current = mutableUiState.value.edit
        if (current?.status?.requestIdOrNull() == entry.requestId) {
            val reason = if (entry.state == OutboxState.CONFLICT) {
                LibraryEditBlockReason.VERSION_CONFLICT
            } else {
                LibraryEditBlockReason.EXPIRED
            }
            if (current.status !is LibraryEditStatus.Blocked &&
                current.status !is LibraryEditStatus.LoadingLatest &&
                current.status !is LibraryEditStatus.ReadyToConfirm
            ) {
                mutableUiState.value = mutableUiState.value.copy(
                    edit = current.copy(status = LibraryEditStatus.Blocked(entry.requestId, reason)),
                )
            }
            return
        }

        val itemId = entry.path.removePrefix("/items/")
        val patch = runCatching { parseLibraryEditPatch(entry.payloadJson) }.getOrNull() ?: return
        val cached = runCatching { outbox.readCachedDetail(entry.ownerId, itemId) }.getOrNull()
        if (!responseBelongsTo(entry.ownerId)) {
            invalidateIdentity()
            return
        }
        val original = cached?.parseDetailOrNull()
        val form = LibraryEditForm(
            title = if (patch.changesTitle) patch.title.orEmpty() else original?.userTitle.orEmpty(),
            note = if (patch.changesNote) patch.note.orEmpty() else original?.note.orEmpty(),
        )
        val reason = if (entry.state == OutboxState.CONFLICT) {
            LibraryEditBlockReason.VERSION_CONFLICT
        } else {
            LibraryEditBlockReason.EXPIRED
        }
        mutableUiState.value = mutableUiState.value.copy(
            detail = original?.let {
                LibraryDetailState.Loaded(it, isCached = true, fetchedAt = cached?.fetchedAt)
            } ?: mutableUiState.value.detail,
            edit = LibraryEditUiState(
                itemId = itemId,
                expectedVersion = patch.expectedVersion,
                originalTitle = original?.userTitle,
                originalNote = original?.note,
                form = form,
                status = LibraryEditStatus.Blocked(entry.requestId, reason),
            ),
        )
    }

    private suspend fun prepareSavedReceipt(entry: OutboxEntry) {
        if (!preparedReceipts.add(entry.requestId)) return
        val resultJson = entry.resultJson
        if (resultJson == null) {
            preparedReceipts -= entry.requestId
            return
        }
        try {
            val response = json.parseToJsonElement(resultJson).jsonObject
            when {
                entry.method == "POST" && entry.path == "/items" -> {
                    val result = parseLibrarySaveResponse(response)
                    val withoutSavedItem = mutableUiState.value.items.filterNot {
                        it.id == result.item.id
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        items = listOf(result.item) + withoutSavedItem,
                    )
                    if (pendingSaveRequestId == entry.requestId ||
                        mutableUiState.value.form.initialUrl == result.item.url
                    ) {
                        pendingSaveRequestId = null
                        mutableUiState.value = mutableUiState.value.copy(
                            saveStatus = LibrarySaveStatus.Saved(result),
                        )
                    }
                }

                entry.method == "PATCH" && entry.path.startsWith("/items/") -> {
                    val detail = parseLibraryDetailResponse(response)
                    if (pendingEditRequestId == entry.requestId ||
                        mutableUiState.value.edit?.itemId == detail.id
                    ) {
                        pendingEditRequestId = null
                        mutableUiState.value = mutableUiState.value.copy(
                            detail = LibraryDetailState.Loaded(detail),
                            edit = mutableUiState.value.edit?.copy(
                                expectedVersion = detail.version ?: mutableUiState.value.edit!!.expectedVersion,
                                originalTitle = detail.userTitle,
                                originalNote = detail.note,
                                form = LibraryEditForm(
                                    title = detail.userTitle.orEmpty(),
                                    note = detail.note.orEmpty(),
                                ),
                                status = LibraryEditStatus.Saved("제목·메모를 저장했어요."),
                            ),
                        )
                    }
                }
            }
            mutableUiState.value = mutableUiState.value.copy(
                receiptsAwaitingAcknowledgement =
                    mutableUiState.value.receiptsAwaitingAcknowledgement + entry.requestId,
            )
        } catch (_: AccountAuthenticationRequiredException) {
            preparedReceipts -= entry.requestId
            invalidateIdentity()
        } catch (_: Exception) {
            preparedReceipts -= entry.requestId
        }
    }

    private suspend fun showCachedDetailOrFailure(
        requestOwner: String,
        itemId: String,
        message: String,
    ) {
        val cached = runCatching { outbox.readCachedDetail(requestOwner, itemId) }.getOrNull()
        if (!responseBelongsTo(requestOwner)) {
            invalidateIdentity()
            return
        }
        val item = cached?.parseDetailOrNull()
        mutableUiState.value = mutableUiState.value.copy(
            detail = if (item == null) {
                LibraryDetailState.Failed(itemId, message)
            } else {
                LibraryDetailState.Loaded(item, isCached = true, fetchedAt = cached?.fetchedAt)
            },
        )
    }

    private fun showCachedList(message: String) {
        val state = mutableUiState.value
        val fallback = cachedItems.ifEmpty { state.items }
        mutableUiState.value = state.copy(
            items = fallback,
            hasMore = false,
            nextOffset = 0,
            isListLoading = false,
            listError = message,
            isShowingCache = cachedItems.isNotEmpty() || state.isShowingCache,
            cacheFetchedAt = cachedFetchedAt ?: state.cacheFetchedAt,
        )
    }

    private fun updateEditForm(update: (LibraryEditForm) -> LibraryEditForm) {
        val edit = mutableUiState.value.edit ?: return
        if (!edit.status.allowsEditing()) return
        mutableUiState.value = mutableUiState.value.copy(
            edit = edit.copy(form = update(edit.form), status = LibraryEditStatus.Idle),
        )
    }

    private fun mutateOutbox(requestId: String, mutation: suspend (String) -> Unit) {
        val boundOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        viewModelScope.launch {
            try {
                mutation(boundOwner)
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    listError = error.localQueueMessage(),
                )
            }
        }
    }

    private fun formCanChange(): Boolean = when (val status = mutableUiState.value.saveStatus) {
        LibrarySaveStatus.Idle,
        is LibrarySaveStatus.Invalid,
        -> true

        is LibrarySaveStatus.Failed -> status.requestId == null
        LibrarySaveStatus.Saving,
        is LibrarySaveStatus.Queued,
        is LibrarySaveStatus.Saved,
        -> false
    }

    private fun responseBelongsTo(requestOwner: String): Boolean =
        requestOwner == ownerId && identityMatches()

    private fun identityMatches(): Boolean =
        ownerId != null && client.sessionUserId() == ownerId

    private fun invalidateIdentity() {
        listJob?.cancel()
        saveJob?.cancel()
        detailJob?.cancel()
        editJob?.cancel()
        restoreJob?.cancel()
        cacheJob?.cancel()
        outboxJob?.cancel()
        listJob = null
        saveJob = null
        detailJob = null
        editJob = null
        restoreJob = null
        cacheJob = null
        outboxJob = null
        pendingSaveRequestId = null
        pendingEditRequestId = null
        cachedItems = emptyList()
        mutableUiState.value = LibraryUiState(
            availability = LibraryAvailability.SignInRequired(
                "로그인 계정이 변경되었거나 세션이 만료됐어요. 다시 로그인해 주세요.",
            ),
            form = LibrarySaveForm(initialUrl = null, sharedText = ""),
            isListLoading = false,
        )
    }

    companion object {
        private const val PAGE_SIZE = 20

        fun factory(
            client: AccountClient,
            outbox: OutboxRepository,
            ownerId: String?,
            initialUrl: String?,
            sharedText: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
                return LibraryViewModel(client, outbox, ownerId, initialUrl, sharedText) as T
            }
        }
    }
}

data class LibraryUiState(
    val availability: LibraryAvailability,
    val form: LibrarySaveForm,
    val saveStatus: LibrarySaveStatus = LibrarySaveStatus.Idle,
    val items: List<LibraryItemSummary> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val isListLoading: Boolean = true,
    val listError: String? = null,
    val isShowingCache: Boolean = false,
    val cacheFetchedAt: Long? = null,
    val detail: LibraryDetailState = LibraryDetailState.None,
    val edit: LibraryEditUiState? = null,
    val outboxEntries: List<OutboxEntry> = emptyList(),
    val receiptsAwaitingAcknowledgement: Set<String> = emptySet(),
)

sealed interface LibraryAvailability {
    data object Checking : LibraryAvailability
    data object Ready : LibraryAvailability
    data class SignInRequired(val message: String) : LibraryAvailability
}

sealed interface LibrarySaveStatus {
    data object Idle : LibrarySaveStatus
    data object Saving : LibrarySaveStatus
    data class Queued(val requestId: String) : LibrarySaveStatus
    data class Invalid(val issues: List<LibraryFormIssue>) : LibrarySaveStatus
    data class Failed(
        val message: String,
        val canRetrySameRequest: Boolean,
        val requestId: String? = null,
        val needsReconfirmation: Boolean = false,
    ) : LibrarySaveStatus

    data class Saved(val result: LibrarySaveResult) : LibrarySaveStatus
}

sealed interface LibraryDetailState {
    data object None : LibraryDetailState
    data class Loading(val itemId: String) : LibraryDetailState
    data class Loaded(
        val item: LibraryItemDetail,
        val isCached: Boolean = false,
        val fetchedAt: Long? = null,
    ) : LibraryDetailState

    data class Failed(val itemId: String, val message: String) : LibraryDetailState
}

data class LibraryEditUiState(
    val itemId: String,
    val expectedVersion: Long,
    val originalTitle: String?,
    val originalNote: String?,
    val form: LibraryEditForm,
    val status: LibraryEditStatus = LibraryEditStatus.Idle,
    val latestError: String? = null,
)

sealed interface LibraryEditStatus {
    data object Idle : LibraryEditStatus
    data object Queuing : LibraryEditStatus
    data class Invalid(val issues: List<LibraryFormIssue>) : LibraryEditStatus
    data class Queued(val requestId: String) : LibraryEditStatus
    data class Failed(val requestId: String?, val message: String) : LibraryEditStatus
    data class Blocked(
        val requestId: String,
        val reason: LibraryEditBlockReason,
    ) : LibraryEditStatus

    data class LoadingLatest(
        val requestId: String,
        val reason: LibraryEditBlockReason,
    ) : LibraryEditStatus

    data class ReadyToConfirm(
        val requestId: String,
        val reason: LibraryEditBlockReason,
        val latest: LibraryItemDetail,
    ) : LibraryEditStatus

    data class Saved(val message: String) : LibraryEditStatus
}

enum class LibraryEditBlockReason {
    VERSION_CONFLICT,
    EXPIRED,
}

private fun LibraryEditStatus.allowsEditing(): Boolean =
    this is LibraryEditStatus.Idle ||
        this is LibraryEditStatus.Invalid ||
        (this is LibraryEditStatus.Failed && requestId == null)

private fun LibraryEditStatus.requestIdOrNull(): String? = when (this) {
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

private fun CachedItem.parseSummaryOrNull(): Pair<LibraryItemSummary, Long>? = runCatching {
    parseLibrarySummaryResponse(Json.parseToJsonElement(responseJson).jsonObject) to fetchedAt
}.getOrNull()

private fun CachedItem.parseDetailOrNull(): LibraryItemDetail? = runCatching {
    parseLibraryDetailResponse(Json.parseToJsonElement(responseJson).jsonObject)
}.getOrNull()

private fun OutboxEntry.payloadUrlOrNull(): String? = runCatching {
    Json.parseToJsonElement(payloadJson).jsonObject["url"]?.jsonPrimitive?.content
}.getOrNull()

private fun OutboxEntry.failureMessage(): String = when (errorCode) {
    "VERSION_CONFLICT" -> "변경 충돌이 발생했어요. 최신 내용을 확인해 주세요."
    "BETA_ACCESS_REQUIRED" -> "이 계정은 아직 베타 이용 승인을 받지 못했어요."
    "ACCOUNT_DELETING" -> "계정 삭제가 진행 중이라 보관함을 사용할 수 없어요."
    "ITEM_NOT_FOUND" -> "보관한 링크를 찾지 못했어요. 목록을 새로고침해 주세요."
    "INVALID_BODY", "INVALID_REQUEST_BODY" -> "보관할 내용을 서버가 받지 못했어요. 입력 내용을 확인해 주세요."
    "ITEM_LIMIT_REACHED" -> "보관 가능한 링크 수를 모두 사용했어요."
    "IDEMPOTENCY_MISMATCH" -> "같은 요청 ID에 다른 내용이 연결되어 저장하지 않았어요."
    "RATE_LIMITED" -> "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
    "DEPENDENCY_UNAVAILABLE" -> "서버가 일시적으로 요청을 처리할 수 없어요. 다시 시도해 주세요."
    else -> errorMessage?.takeIf(String::isNotBlank)
        ?: "서버 저장에 실패했어요. 내용을 확인한 뒤 다시 시도해 주세요."
}

private fun Throwable.localQueueMessage(): String =
    (this as? AccountClientException)?.safeLibraryMessage()
        ?: "기기에 저장 요청을 보관하지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요."

private fun Throwable.listMessage(): String =
    (this as? AccountClientException)?.safeLibraryMessage()
        ?: "서버 목록을 불러오지 못했어요. 이 기기의 마지막 동기화 자료를 표시합니다."

private fun Throwable.detailMessage(): String =
    (this as? AccountClientException)?.safeLibraryMessage()
        ?: "상세 정보를 읽지 못했어요. 다시 시도해 주세요."

private fun AccountClientException.safeLibraryMessage(): String = when (code) {
    "BETA_ACCESS_REQUIRED" -> "이 계정은 아직 베타 이용 승인을 받지 못했어요."
    "ACCOUNT_DELETING" -> "계정 삭제가 진행 중이라 보관함을 사용할 수 없어요."
    "ITEM_NOT_FOUND" -> "보관한 링크를 찾지 못했어요. 목록을 새로고침해 주세요."
    "VERSION_CONFLICT" -> "변경 충돌이 발생했어요. 최신 내용을 확인해 주세요."
    "INVALID_BODY" -> "보관할 내용을 서버가 받지 못했어요. 입력 내용을 확인해 주세요."
    "ITEM_LIMIT_REACHED" -> "보관 가능한 링크 수를 모두 사용했어요."
    "IDEMPOTENCY_MISMATCH" -> "같은 요청 ID에 다른 내용이 연결되어 저장하지 않았어요."
    "ITEM_DELETED" -> "이미 삭제된 링크는 다시 처리할 수 없어요."
    "RATE_LIMITED" -> "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
    "DEPENDENCY_UNAVAILABLE" -> "서버가 일시적으로 요청을 처리할 수 없어요. 다시 시도해 주세요."
    "INVALID_QUERY", "INVALID_PATH", "INVALID_METHOD", "INVALID_REQUEST_ID",
    "INVALID_REQUEST_BODY",
    -> "보관함 요청이 올바르지 않아요."

    else -> message.takeIf { candidate -> candidate.any { it in '\uAC00'..'\uD7A3' } }
        ?: "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
}
