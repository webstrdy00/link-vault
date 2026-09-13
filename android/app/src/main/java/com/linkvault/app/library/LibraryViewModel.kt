package com.linkvault.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linkvault.app.auth.AccountAuthenticationRequiredException
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel internal constructor(
    private val client: AccountClient,
    private val ownerId: String?,
    initialUrl: String?,
    sharedText: String,
) : ViewModel() {
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
    private var pendingSave: LibrarySaveOperation? = null

    init {
        when {
            !client.isConfigured -> mutableUiState.value = mutableUiState.value.copy(
                availability = LibraryAvailability.SignInRequired(
                    client.configurationMessage ?: "로그인 설정이 필요해요.",
                ),
                isListLoading = false,
            )

            !identityMatches() -> mutableUiState.value = mutableUiState.value.copy(
                availability = LibraryAvailability.SignInRequired("로그인이 필요해요."),
                isListLoading = false,
            )

            else -> {
                mutableUiState.value = mutableUiState.value.copy(
                    availability = LibraryAvailability.Ready,
                )
                loadItems(reset = true)
            }
        }
    }

    fun updateSharedInput(initialUrl: String?, sharedText: String) {
        val currentForm = mutableUiState.value.form
        if (currentForm.initialUrl == initialUrl && currentForm.sharedText == sharedText) return
        saveJob?.cancel()
        saveJob = null
        pendingSave = null
        mutableUiState.value = mutableUiState.value.copy(
            form = LibrarySaveForm(initialUrl = initialUrl, sharedText = sharedText),
            saveStatus = LibrarySaveStatus.Idle,
            detail = LibraryDetailState.None,
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

            is LibrarySavePreparation.Valid -> {
                pendingSave = preparation.operation
                executeSave(preparation.operation)
            }
        }
    }

    fun retrySave() {
        if (saveJob?.isActive == true) return
        val operation = pendingSave ?: return
        if (operation.ownerId != ownerId || !identityMatches()) {
            invalidateIdentity()
            return
        }
        executeSave(operation)
    }

    fun editAfterSaveFailure() {
        val failure = mutableUiState.value.saveStatus as? LibrarySaveStatus.Failed ?: return
        if (!failure.canRetrySameRequest) return
        pendingSave = null
        mutableUiState.value = mutableUiState.value.copy(saveStatus = LibrarySaveStatus.Idle)
    }

    fun refresh() {
        if (mutableUiState.value.availability !is LibraryAvailability.Ready) return
        loadItems(reset = true)
    }

    fun retryList() {
        val state = mutableUiState.value
        if (state.items.isEmpty()) {
            loadItems(reset = true)
        } else {
            loadItems(reset = false)
        }
    }

    fun loadMore() {
        val state = mutableUiState.value
        if (!state.hasMore || state.isListLoading || state.listError != null) return
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
        )
        val job = viewModelScope.launch {
            try {
                val response = client.libraryRequest("/items/$itemId")
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                val item = parseLibraryDetailResponse(response)
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
            } catch (error: AccountClientException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        detail = LibraryDetailState.Failed(itemId, error.safeLibraryMessage()),
                    )
                }
            } catch (_: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        detail = LibraryDetailState.Failed(
                            itemId,
                            "상세 정보를 읽지 못했어요. 다시 시도해 주세요.",
                        ),
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
        detailJob = null
        mutableUiState.value = mutableUiState.value.copy(detail = LibraryDetailState.None)
    }

    private fun executeSave(operation: LibrarySaveOperation) {
        if (saveJob?.isActive == true) return
        mutableUiState.value = mutableUiState.value.copy(saveStatus = LibrarySaveStatus.Saving)
        val request = operation.requestArguments()
        val job = viewModelScope.launch {
            try {
                val response = client.libraryRequest(
                    path = request.path,
                    method = request.method,
                    body = request.body,
                    requestId = request.requestId,
                )
                if (!responseBelongsTo(operation.ownerId)) {
                    invalidateIdentity()
                    return@launch
                }
                val result = parseLibrarySaveResponse(response)
                if (!responseBelongsTo(operation.ownerId)) {
                    invalidateIdentity()
                    return@launch
                }
                pendingSave = null
                mutableUiState.value = mutableUiState.value.copy(
                    saveStatus = LibrarySaveStatus.Saved(result),
                )
                listJob?.cancel()
                listJob = null
                loadItems(reset = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: AccountClientException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    val canRetry = error.retryable
                    if (!canRetry) pendingSave = null
                    mutableUiState.value = mutableUiState.value.copy(
                        saveStatus = LibrarySaveStatus.Failed(
                            message = error.safeLibraryMessage(),
                            canRetrySameRequest = canRetry,
                        ),
                    )
                }
            } catch (_: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        saveStatus = LibrarySaveStatus.Failed(
                            message = "서버 응답을 확인하지 못했어요. 같은 요청으로 다시 시도해 주세요.",
                            canRetrySameRequest = true,
                        ),
                    )
                }
            }
        }
        saveJob = job
        job.invokeOnCompletion {
            if (saveJob === job) saveJob = null
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
            items = if (reset) emptyList() else current.items,
            hasMore = if (reset) false else current.hasMore,
            nextOffset = if (reset) 0 else current.nextOffset,
            isListLoading = true,
            listError = null,
        )
        val job = viewModelScope.launch {
            try {
                val response = client.libraryRequest("/items?limit=$PAGE_SIZE&offset=$offset")
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                val page = parseLibraryListResponse(response)
                if (!responseBelongsTo(requestOwner)) {
                    invalidateIdentity()
                    return@launch
                }
                val existing = if (reset) emptyList() else mutableUiState.value.items
                val knownIds = existing.asSequence().map { it.id }.toHashSet()
                val appended = page.items.filter { knownIds.add(it.id) }
                mutableUiState.value = mutableUiState.value.copy(
                    items = existing + appended,
                    hasMore = page.hasMore,
                    nextOffset = offset + page.items.size,
                    isListLoading = false,
                    listError = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateIdentity()
            } catch (error: AccountClientException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        isListLoading = false,
                        listError = error.safeLibraryMessage(),
                    )
                }
            } catch (_: Exception) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        isListLoading = false,
                        listError = "보관함 목록을 읽지 못했어요. 다시 시도해 주세요.",
                    )
                }
            }
        }
        listJob = job
        job.invokeOnCompletion {
            if (listJob === job) listJob = null
        }
    }

    private fun formCanChange(): Boolean = when (mutableUiState.value.saveStatus) {
        LibrarySaveStatus.Idle,
        is LibrarySaveStatus.Invalid,
        is LibrarySaveStatus.Failed,
        -> pendingSave == null

        LibrarySaveStatus.Saving,
        is LibrarySaveStatus.Saved,
        -> false
    }

    private fun responseBelongsTo(requestOwner: String): Boolean =
        requestOwner == ownerId && identityMatches()

    private fun identityMatches(): Boolean =
        ownerId != null && client.hasSession() && client.sessionUserId() == ownerId

    private fun invalidateIdentity() {
        listJob?.cancel()
        saveJob?.cancel()
        detailJob?.cancel()
        listJob = null
        saveJob = null
        detailJob = null
        pendingSave = null
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
            ownerId: String?,
            initialUrl: String?,
            sharedText: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
                return LibraryViewModel(client, ownerId, initialUrl, sharedText) as T
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
    val detail: LibraryDetailState = LibraryDetailState.None,
)

sealed interface LibraryAvailability {
    data object Checking : LibraryAvailability
    data object Ready : LibraryAvailability
    data class SignInRequired(val message: String) : LibraryAvailability
}

sealed interface LibrarySaveStatus {
    data object Idle : LibrarySaveStatus
    data object Saving : LibrarySaveStatus
    data class Invalid(val issues: List<LibraryFormIssue>) : LibrarySaveStatus
    data class Failed(
        val message: String,
        val canRetrySameRequest: Boolean,
    ) : LibrarySaveStatus

    data class Saved(val result: LibrarySaveResult) : LibrarySaveStatus
}

sealed interface LibraryDetailState {
    data object None : LibraryDetailState
    data class Loading(val itemId: String) : LibraryDetailState
    data class Loaded(val item: LibraryItemDetail) : LibraryDetailState
    data class Failed(val itemId: String, val message: String) : LibraryDetailState
}

private fun AccountClientException.safeLibraryMessage(): String = when (code) {
    "BETA_ACCESS_REQUIRED" -> "이 계정은 아직 베타 이용 승인을 받지 못했어요."
    "ACCOUNT_DELETING" -> "계정 삭제가 진행 중이라 보관함을 사용할 수 없어요."
    "ITEM_NOT_FOUND" -> "보관한 링크를 찾지 못했어요. 목록을 새로고침해 주세요."
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
