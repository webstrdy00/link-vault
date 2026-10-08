package com.linkvault.app.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linkvault.app.auth.AccountAuthenticationRequiredException
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.auth.AccountSessionState
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.storage.CachedCategories
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiscoveryViewModel internal constructor(
    private val client: AccountClient,
    private val outbox: OutboxRepository,
    private val ownerId: String?,
    private val sessionGeneration: Long,
    sessionInitialized: Boolean,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        DiscoveryUiState(
            availability = DiscoveryAvailability.Checking,
            displayZoneId = zoneId.id,
        ),
    )
    val uiState: StateFlow<DiscoveryUiState> = mutableUiState.asStateFlow()

    private var searchJob: Job? = null
    private var categoriesJob: Job? = null
    private var categoryMutationJob: Job? = null
    private var outboxJob: Job? = null
    private var aliasJob: Job? = null
    private var searchGeneration = 0L
    private var visibilityGeneration = 0L
    private var isVisible = false
    private var screenScope: CoroutineScope? = null
    private val handledSavedRequestIds = mutableSetOf<String>()
    private val replacedRequestIds = mutableSetOf<String>()

    init {
        when {
            !client.isConfigured -> mutableUiState.value = mutableUiState.value.copy(
                availability = DiscoveryAvailability.SignInRequired(
                    client.configurationMessage ?: "로그인 설정이 필요해요.",
                ),
                isSearchLoading = false,
                isCategoriesLoading = false,
            )
            !sessionInitialized -> mutableUiState.value = mutableUiState.value.copy(
                availability = DiscoveryAvailability.Checking,
                isSearchLoading = false,
                isCategoriesLoading = false,
            )
            ownerId.isNullOrBlank() || !identityMatches() -> {
                mutableUiState.value = mutableUiState.value.copy(
                    availability = DiscoveryAvailability.SignInRequired("로그인이 필요해요."),
                    isSearchLoading = false,
                    isCategoriesLoading = false,
                )
            }
            else -> {
                mutableUiState.value = mutableUiState.value.copy(
                    availability = DiscoveryAvailability.Ready,
                    isSearchLoading = false,
                    isCategoriesLoading = false,
                )
            }
        }
    }

    fun setVisible(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        visibilityGeneration += 1
        if (!visible) {
            cancelScreenWork()
            searchGeneration += 1
            val current = mutableUiState.value
            mutableUiState.value = current.copy(
                searchGeneration = searchGeneration,
                isSearchLoading = false,
                isCategoriesLoading = false,
                isCategoryMutationInProgress = false,
                categoryReviewRequestId = null,
                aliasDisclosure = DiscoveryAliasDisclosure.None,
            )
            if (
                ownerId != null &&
                client.sessionState.value.initialized &&
                !identityMatches()
            ) {
                invalidateIdentity()
            }
            return
        }
        if (mutableUiState.value.availability !is DiscoveryAvailability.Ready) return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val parent = viewModelScope.coroutineContext[Job]
        screenScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(parent))
        val requestOwner = requireNotNull(ownerId)
        val visibilityToken = currentRequestToken(requestOwner)
        screenScope?.launch {
            try {
                outbox.observeDeletedItemIds(requestOwner).collect { deletedIds ->
                    if (!responseBelongsTo(visibilityToken)) return@collect
                    mutableUiState.value = mutableUiState.value.withoutDeletedItems(deletedIds.toSet())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (responseBelongsTo(visibilityToken)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        items = emptyList(),
                        aliasDisclosure = DiscoveryAliasDisclosure.None,
                        searchError = "삭제 상태를 확인하지 못했어요. 다시 시도해 주세요.",
                    )
                }
            }
        }
        observeCategoryOutbox(requestOwner)
        refreshCategories()
        if (mutableUiState.value.appliedQuery == null) {
            applyFilterIntent(mutableUiState.value.filters)
        } else {
            restartAppliedSearch(clearExisting = true)
        }
    }

    fun syncSessionState(session: AccountSessionState) {
        if (
            session.ownerId != ownerId ||
            session.generation != sessionGeneration
        ) {
            if (session.initialized) invalidateIdentity()
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            sessionRecoveryMessage = if (session.recoverable) {
                "로그인 연결을 복구하고 있어요. 마지막으로 확인한 자료를 표시합니다."
            } else {
                null
            },
        )
    }

    fun updateQuery(query: String) = updateFilters { it.copy(query = query) }

    fun selectCategory(categoryId: String?) = updateFilters {
        it.copy(categoryId = categoryId, unclassified = false)
    }

    fun setUnclassified(enabled: Boolean) = updateFilters {
        it.copy(
            categoryId = if (enabled) null else it.categoryId,
            unclassified = enabled,
        )
    }

    fun selectSource(source: DiscoverySource) = updateFilters { it.copy(source = source) }

    fun updateDateFrom(date: String) = updateFilters { it.copy(dateFrom = date) }

    fun updateDateTo(date: String) = updateFilters { it.copy(dateTo = date) }

    fun setAliases(enabled: Boolean) = updateFilters { it.copy(aliases = enabled) }

    fun setNeedsCues(enabled: Boolean) = updateFilters { it.copy(needsCues = enabled) }

    fun submitSearch() {
        applyFilterIntent(mutableUiState.value.filters)
    }

    fun clearFiltersKeepingQuery() {
        val filters = DiscoveryFilterInput(query = mutableUiState.value.filters.query)
        mutableUiState.value = mutableUiState.value.copy(filters = filters, filterIssues = emptyList())
        applyFilterIntent(filters)
    }

    fun showItemsNeedingCues() {
        val filters = mutableUiState.value.filters.copy(needsCues = true)
        mutableUiState.value = mutableUiState.value.copy(filters = filters, filterIssues = emptyList())
        applyFilterIntent(filters)
    }

    fun retrySearch() {
        val state = mutableUiState.value
        val snapshot = state.appliedQuery ?: return
        if (
            !isVisible ||
            !identityMatches() ||
            searchJob?.isActive == true ||
            state.searchError == null
        ) {
            return
        }
        val offset = state.failedOffset ?: if (state.items.isEmpty()) 0 else state.nextOffset
        searchGeneration += 1
        mutableUiState.value = state.copy(
            searchGeneration = searchGeneration,
            isSearchLoading = true,
            searchError = null,
        )
        loadPage(
            snapshot = snapshot,
            requestGeneration = searchGeneration,
            offset = offset,
            replace = offset == 0,
        )
    }

    fun loadMore() {
        val state = mutableUiState.value
        val snapshot = state.appliedQuery ?: return
        if (
            !isVisible ||
            !identityMatches() ||
            !state.hasMore ||
            state.isSearchLoading ||
            state.searchError != null
        ) {
            return
        }
        loadPage(
            snapshot = snapshot,
            requestGeneration = state.searchGeneration,
            offset = state.nextOffset,
            replace = false,
        )
    }

    fun refreshCategories() {
        if (
            !isVisible ||
            mutableUiState.value.availability !is DiscoveryAvailability.Ready
        ) {
            return
        }
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val token = currentRequestToken(requestOwner)
        categoriesJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            isCategoriesLoading = true,
            categoryError = null,
        )
        val job = screenScope?.launch {
            try {
                fetchLiveCategories(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountAuthenticationRequiredException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (responseBelongsTo(token)) {
                    showCachedCategoriesOrFailure(token, error.discoveryMessage())
                }
            } catch (error: Exception) {
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                } else {
                    showCachedCategoriesOrFailure(token, error.discoveryMessage())
                }
            }
        } ?: return
        categoriesJob = job
        job.invokeOnCompletion {
            if (categoriesJob === job) categoriesJob = null
        }
    }

    fun updateCreateCategoryName(name: String) {
        mutableUiState.value = mutableUiState.value.copy(
            createCategoryName = name,
            createCategoryIssue = null,
            categoryMutationError = null,
        )
    }

    fun createCategory() {
        if (categoryMutationJob?.isActive == true) return
        val state = mutableUiState.value
        val issue = validateDiscoveryCategoryName(state.createCategoryName)
        if (issue != null) {
            mutableUiState.value = state.copy(createCategoryIssue = issue.message)
            return
        }
        val activeOrQueuedCustomCount = state.categories.count { it.isCustom } +
            state.categoryOutboxEntries.count { entry ->
                entry.method == "POST" && entry.path == "/categories" && entry.state !in setOf(
                    OutboxState.SAVED,
                    OutboxState.FAILED,
                    OutboxState.CONFLICT,
                    OutboxState.EXPIRED,
                )
            }
        if (activeOrQueuedCustomCount >= DISCOVERY_CUSTOM_CATEGORY_LIMIT) {
            mutableUiState.value = state.copy(
                createCategoryIssue = "직접 만든 분류는 최대 ${DISCOVERY_CUSTOM_CATEGORY_LIMIT}개까지 사용할 수 있어요.",
            )
            return
        }
        enqueueCategoryIntent(
            method = "POST",
            path = "/categories",
            payloadJson = discoveryCategoryPayload(state.createCategoryName),
            queuedMessage = "분류 만들기 요청을 이 기기에 보관했어요. 서버 확인 전 상태입니다.",
        ) {
            mutableUiState.value = mutableUiState.value.copy(
                createCategoryName = "",
                createCategoryIssue = null,
            )
        }
    }

    fun beginRenameCategory(categoryId: String) {
        val category = mutableUiState.value.categories.firstOrNull {
            it.id == categoryId && it.isCustom
        } ?: return
        mutableUiState.value = mutableUiState.value.copy(
            renameCategory = category,
            renameCategoryName = category.name,
            renameCategoryIssue = null,
            categoryMutationError = null,
        )
    }

    fun updateRenameCategoryName(name: String) {
        if (mutableUiState.value.renameCategory == null) return
        mutableUiState.value = mutableUiState.value.copy(
            renameCategoryName = name,
            renameCategoryIssue = null,
        )
    }

    fun cancelRenameCategory() {
        mutableUiState.value = mutableUiState.value.copy(
            renameCategory = null,
            renameCategoryName = "",
            renameCategoryIssue = null,
        )
    }

    fun renameCategory() {
        if (categoryMutationJob?.isActive == true) return
        val state = mutableUiState.value
        val category = state.renameCategory?.takeIf { it.isCustom } ?: return
        val issue = validateDiscoveryCategoryName(state.renameCategoryName)
        if (issue != null) {
            mutableUiState.value = state.copy(renameCategoryIssue = issue.message)
            return
        }
        enqueueCategoryIntent(
            method = "PATCH",
            path = "/categories/${category.id}",
            payloadJson = discoveryCategoryPayload(state.renameCategoryName),
            queuedMessage = "분류 이름 변경 요청을 이 기기에 보관했어요. 서버 확인 전 상태입니다.",
            onQueued = ::cancelRenameCategory,
        )
    }

    fun requestDeleteCategory(categoryId: String) {
        val category = mutableUiState.value.categories.firstOrNull {
            it.id == categoryId && it.isCustom
        } ?: return
        mutableUiState.value = mutableUiState.value.copy(
            deleteCategory = category,
            categoryMutationError = null,
        )
    }

    fun cancelDeleteCategory() {
        mutableUiState.value = mutableUiState.value.copy(deleteCategory = null)
    }

    fun confirmDeleteCategory() {
        if (categoryMutationJob?.isActive == true) return
        val category = mutableUiState.value.deleteCategory?.takeIf { it.isCustom } ?: return
        enqueueCategoryIntent(
            method = "DELETE",
            path = "/categories/${category.id}",
            payloadJson = "{}",
            queuedMessage = "분류 삭제 요청을 이 기기에 보관했어요. 링크는 삭제하지 않습니다.",
            onQueued = ::cancelDeleteCategory,
        )
    }

    fun retryCategoryRequest(requestId: String) {
        mutateCategoryEntry(requestId) { requestOwner, entry, token ->
            if (entry.state == OutboxState.EXPIRED || entry.state == OutboxState.CONFLICT) return@mutateCategoryEntry
            outbox.retry(requestOwner, requestId)
            if (!responseBelongsTo(token)) return@mutateCategoryEntry
            mutableUiState.value = mutableUiState.value.copy(
                categoryMutationNotice = "같은 요청 ID와 내용을 그대로 다시 전송합니다.",
                categoryMutationError = null,
            )
        }
    }

    fun discardCategoryRequest(requestId: String) {
        mutateCategoryEntry(requestId) { requestOwner, _, token ->
            outbox.discard(requestOwner, requestId)
            if (!responseBelongsTo(token)) return@mutateCategoryEntry
            mutableUiState.value = mutableUiState.value.copy(
                categoryMutationNotice = "대기 요청을 버렸어요.",
                categoryMutationError = null,
            )
        }
    }

    fun discardAndEditCategoryRequest(requestId: String) {
        mutateCategoryEntry(requestId) { requestOwner, entry, token ->
            val name = entry.categoryPayloadName()
            outbox.discard(requestOwner, requestId)
            if (!responseBelongsTo(token)) return@mutateCategoryEntry
            showCategoryIntentForEditing(
                entry = entry,
                name = name,
                notice = "실패한 요청을 버렸어요. 이름을 확인한 뒤 새 요청을 만들어 주세요.",
            )
        }
    }

    fun reviewCategoryRequest(requestId: String) {
        if (!isVisible) return
        val entry = currentCategoryEntry(requestId) ?: return
        if (entry.state == OutboxState.EXPIRED) {
            mutableUiState.value = mutableUiState.value.copy(
                categoryReconfirmation = entry.toReconfirmation(),
                categoryMutationError = null,
            )
            return
        }
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val token = currentRequestToken(requestOwner)
        categoriesJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            categoryReviewRequestId = requestId,
            categoryMutationError = null,
        )
        val job = screenScope?.launch {
            try {
                fetchLiveCategories(token)
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                    return@launch
                }
                val current = currentCategoryEntry(requestId) ?: return@launch
                if (
                    current.errorCode == "CATEGORY_NAME_EXISTS" ||
                    current.errorCode == "CATEGORY_LIMIT_REACHED"
                ) {
                    val name = current.categoryPayloadName()
                    outbox.discard(requestOwner, requestId)
                    if (!responseBelongsTo(token)) return@launch
                    showCategoryIntentForEditing(
                        entry = current,
                        name = name,
                        notice = if (current.errorCode == "CATEGORY_LIMIT_REACHED") {
                            "최신 분류를 확인하고 실패한 요청을 버렸어요. 기존 분류를 정리한 뒤 새 요청을 만들어 주세요."
                        } else {
                            "최신 분류를 확인하고 실패한 요청을 버렸어요. 다른 이름으로 새 요청을 만들어 주세요."
                        },
                    )
                    mutableUiState.value = mutableUiState.value.copy(categoryReviewRequestId = null)
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        categoryReviewRequestId = null,
                        categoryReconfirmation = current.toReconfirmation(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountAuthenticationRequiredException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (responseBelongsTo(token)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        categoryReviewRequestId = null,
                        categoryMutationError = error.discoveryMessage(),
                    )
                }
            } catch (error: Exception) {
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        categoryReviewRequestId = null,
                        categoryMutationError = error.discoveryMessage(),
                    )
                }
            }
        } ?: return
        categoriesJob = job
        job.invokeOnCompletion {
            if (categoriesJob === job) categoriesJob = null
        }
    }

    fun cancelCategoryReconfirmation() {
        mutableUiState.value = mutableUiState.value.copy(categoryReconfirmation = null)
    }

    fun confirmCategoryReconfirmation() {
        if (!isVisible || categoryMutationJob?.isActive == true) return
        val reconfirmation = mutableUiState.value.categoryReconfirmation ?: return
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val token = currentRequestToken(requestOwner)
        val newRequestId = UUID.randomUUID().toString()
        mutableUiState.value = mutableUiState.value.copy(
            isCategoryMutationInProgress = true,
            categoryMutationError = null,
        )
        val job = screenScope?.launch {
            try {
                outbox.enqueue(
                    ownerId = requestOwner,
                    requestId = newRequestId,
                    method = reconfirmation.method,
                    path = reconfirmation.path,
                    payloadJson = reconfirmation.payloadJson,
                )
                if (!responseBelongsTo(token)) return@launch
                outbox.discard(requestOwner, reconfirmation.requestId)
                if (!responseBelongsTo(token)) return@launch
                replacedRequestIds += reconfirmation.requestId
                mutableUiState.value = mutableUiState.value.copy(
                    isCategoryMutationInProgress = false,
                    categoryReconfirmation = null,
                    categoryMutationNotice = "확인한 내용으로 새 요청 ID를 만들었어요. 서버 확인 전 상태입니다.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountAuthenticationRequiredException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (responseBelongsTo(token)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isCategoryMutationInProgress = false,
                        categoryMutationError = error.localQueueMessage(),
                    )
                }
            } catch (error: Exception) {
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        isCategoryMutationInProgress = false,
                        categoryMutationError = error.localQueueMessage(),
                    )
                }
            }
        } ?: return
        categoryMutationJob = job
        job.invokeOnCompletion {
            if (categoryMutationJob === job) categoryMutationJob = null
        }
    }

    fun openAliasDisclosure(itemId: String) {
        if (!isVisible) return
        val state = mutableUiState.value
        val snapshot = state.appliedQuery ?: return
        val query = snapshot.query ?: return
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        aliasJob?.cancel()
        val requestGeneration = state.searchGeneration
        val requestToken = currentRequestToken(requestOwner)
        mutableUiState.value = state.copy(aliasDisclosure = DiscoveryAliasDisclosure.Loading(itemId))
        val job = screenScope?.launch {
            try {
                val response = client.libraryRequest(
                    expectedOwnerId = requestOwner,
                    path = "/items/$itemId?q=${encodeDiscoveryQueryComponent(query)}",
                )
                val detail = parseDiscoveryAliasDetail(response)
                val deletedIds = outbox.observeDeletedItemIds(requestOwner).first().toSet()
                if (itemId in deletedIds) {
                    if (responseBelongsTo(requestToken)) {
                        mutableUiState.value = mutableUiState.value.withoutDeletedItems(deletedIds)
                    }
                    return@launch
                }
                if (
                    detail.itemId != itemId || !responseBelongsTo(requestToken) ||
                    !isCurrentDiscoveryResponse(
                        token = DiscoverySearchToken(
                            ownerId = requestOwner,
                            generation = requestGeneration,
                            queryKey = snapshot.queryKey,
                            offset = 0,
                        ),
                        ownerId = ownerId,
                        generation = mutableUiState.value.searchGeneration,
                        queryKey = mutableUiState.value.appliedQuery?.queryKey,
                    )
                ) {
                    if (!identityMatches()) invalidateIdentity()
                    return@launch
                }
                mutableUiState.value = mutableUiState.value.copy(
                    aliasDisclosure = DiscoveryAliasDisclosure.Loaded(detail),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountAuthenticationRequiredException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (responseBelongsTo(requestToken)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        aliasDisclosure = DiscoveryAliasDisclosure.Failed(
                            itemId = itemId,
                            message = error.discoveryMessage(),
                        ),
                    )
                }
            } catch (error: Exception) {
                if (!responseBelongsTo(requestToken)) {
                    if (!identityMatches()) invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        aliasDisclosure = DiscoveryAliasDisclosure.Failed(
                            itemId = itemId,
                            message = error.discoveryMessage(),
                        ),
                    )
                }
            }
        } ?: return
        aliasJob = job
        job.invokeOnCompletion {
            if (aliasJob === job) aliasJob = null
        }
    }

    fun closeAliasDisclosure() {
        aliasJob?.cancel()
        aliasJob = null
        mutableUiState.value = mutableUiState.value.copy(
            aliasDisclosure = DiscoveryAliasDisclosure.None,
        )
    }

    private fun updateFilters(update: (DiscoveryFilterInput) -> DiscoveryFilterInput) {
        mutableUiState.value = mutableUiState.value.copy(
            filters = update(mutableUiState.value.filters),
            filterIssues = emptyList(),
        )
    }

    private fun applyFilterIntent(filters: DiscoveryFilterInput) {
        if (
            !isVisible ||
            mutableUiState.value.availability !is DiscoveryAvailability.Ready
        ) {
            return
        }
        val preparation = prepareDiscoveryQuery(filters, zoneId)
        if (preparation is DiscoveryQueryPreparation.Invalid) {
            mutableUiState.value = mutableUiState.value.copy(filterIssues = preparation.issues)
            return
        }
        val snapshot = (preparation as DiscoveryQueryPreparation.Valid).snapshot
        val state = mutableUiState.value
        val current = state.appliedQuery
        if (current?.queryKey == snapshot.queryKey) {
            if (state.searchError != null) retrySearch()
            return
        }
        startSearchIntent(snapshot)
    }

    private fun startSearchIntent(snapshot: DiscoveryQuerySnapshot) {
        if (!isVisible) return
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        searchJob?.cancel()
        aliasJob?.cancel()
        searchGeneration += 1
        mutableUiState.value = mutableUiState.value.copy(
            appliedQuery = snapshot,
            searchGeneration = searchGeneration,
            items = emptyList(),
            hasMore = false,
            nextOffset = 0,
            isSearchLoading = true,
            searchError = null,
            failedOffset = null,
            filterIssues = emptyList(),
            aliasDisclosure = DiscoveryAliasDisclosure.None,
        )
        loadPage(snapshot, searchGeneration, offset = 0, replace = true)
    }

    private fun restartAppliedSearch(clearExisting: Boolean = true) {
        if (!isVisible) return
        val snapshot = mutableUiState.value.appliedQuery ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        searchJob?.cancel()
        aliasJob?.cancel()
        searchGeneration += 1
        val current = mutableUiState.value
        mutableUiState.value = current.copy(
            searchGeneration = searchGeneration,
            items = if (clearExisting) emptyList() else current.items,
            hasMore = if (clearExisting) false else current.hasMore,
            nextOffset = if (clearExisting) 0 else current.nextOffset,
            isSearchLoading = true,
            searchError = null,
            failedOffset = null,
            aliasDisclosure = DiscoveryAliasDisclosure.None,
        )
        loadPage(snapshot, searchGeneration, offset = 0, replace = true)
    }

    private fun loadPage(
        snapshot: DiscoveryQuerySnapshot,
        requestGeneration: Long,
        offset: Int,
        replace: Boolean,
    ) {
        if (!isVisible) return
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val token = DiscoverySearchToken(
            ownerId = requestOwner,
            generation = requestGeneration,
            queryKey = snapshot.queryKey,
            offset = offset,
        )
        val requestToken = currentRequestToken(requestOwner)
        mutableUiState.value = mutableUiState.value.copy(
            isSearchLoading = true,
            searchError = null,
            failedOffset = null,
        )
        val job = screenScope?.launch {
            try {
                val response = client.libraryRequest(
                    expectedOwnerId = requestOwner,
                    path = snapshot.itemsPath(limit = DISCOVERY_PAGE_SIZE, offset = offset),
                )
                val page = parseDiscoveryItemsResponse(response)
                val deletedIds = outbox.observeDeletedItemIds(requestOwner).first().toSet()
                val current = mutableUiState.value
                if (
                    !responseBelongsTo(requestToken) ||
                    !isCurrentDiscoveryResponse(
                        token = token,
                        ownerId = ownerId,
                        generation = current.searchGeneration,
                        queryKey = current.appliedQuery?.queryKey,
                    )
                ) {
                    if (!identityMatches()) invalidateIdentity()
                    return@launch
                }
                val existing = if (replace) emptyList() else current.items
                mutableUiState.value = current.copy(
                    items = existing + page.items,
                    hasMore = page.hasMore,
                    nextOffset = offset + page.items.size,
                    isSearchLoading = false,
                    searchError = null,
                    failedOffset = null,
                ).withoutDeletedItems(deletedIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountAuthenticationRequiredException) {
                val current = mutableUiState.value
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (
                    responseBelongsTo(requestToken) &&
                    isCurrentDiscoveryResponse(
                        token = token,
                        ownerId = ownerId,
                        generation = current.searchGeneration,
                        queryKey = current.appliedQuery?.queryKey,
                    )
                ) {
                    mutableUiState.value = current.copy(
                        isSearchLoading = false,
                        searchError = error.discoveryMessage(),
                        failedOffset = offset,
                    )
                }
            } catch (error: Exception) {
                val current = mutableUiState.value
                if (!responseBelongsTo(requestToken)) {
                    if (!identityMatches()) invalidateIdentity()
                } else if (
                    isCurrentDiscoveryResponse(
                        token = token,
                        ownerId = ownerId,
                        generation = current.searchGeneration,
                        queryKey = current.appliedQuery?.queryKey,
                    )
                ) {
                    mutableUiState.value = current.copy(
                        isSearchLoading = false,
                        searchError = error.discoveryMessage(),
                        failedOffset = offset,
                    )
                }
            }
        } ?: return
        searchJob = job
        job.invokeOnCompletion {
            if (searchJob === job) searchJob = null
        }
    }

    private suspend fun fetchLiveCategories(token: DiscoveryRequestToken) {
        val response = client.libraryRequest(
            expectedOwnerId = token.ownerId,
            path = "/categories",
        )
        val parsed = parseDiscoveryCategoriesResponse(response)
        if (!responseBelongsTo(token)) {
            if (!identityMatches()) invalidateIdentity()
            return
        }
        outbox.cacheCategories(token.ownerId, response)
        if (!responseBelongsTo(token)) {
            if (!identityMatches()) invalidateIdentity()
            return
        }
        val fetchedAt = currentTimeMillis()
        mutableUiState.value = mutableUiState.value.copy(
            categories = parsed.categories,
            categoryCount = parsed.count,
            unclassifiedCount = parsed.unclassifiedCount,
            isCategoriesLoading = false,
            categoryError = null,
            isShowingCachedCategories = false,
            categoriesFetchedAt = null,
            categorySnapshotFetchedAt = fetchedAt,
        )
    }

    private suspend fun showCachedCategoriesOrFailure(
        token: DiscoveryRequestToken,
        message: String,
    ) {
        val cached = runCatching { outbox.readCachedCategories(token.ownerId) }.getOrNull()
        if (!responseBelongsTo(token)) {
            if (!identityMatches()) invalidateIdentity()
            return
        }
        val current = mutableUiState.value
        val cachedSnapshot = cached?.parseCategoryListOrNull()?.let { categoryList ->
            DiscoveryCategorySnapshot(categoryList = categoryList, fetchedAt = cached.fetchedAt)
        }
        val inMemorySnapshot = current.categorySnapshotFetchedAt?.let { fetchedAt ->
            DiscoveryCategorySnapshot(
                categoryList = DiscoveryCategoryList(
                    categories = current.categories,
                    count = current.categoryCount,
                    unclassifiedCount = current.unclassifiedCount,
                ),
                fetchedAt = fetchedAt,
            )
        }
        val fallback = resolveDiscoveryCategoryFallback(cachedSnapshot, inMemorySnapshot)
        mutableUiState.value = if (fallback == null) {
            current.copy(
                categories = emptyList(),
                categoryCount = 0,
                unclassifiedCount = 0,
                isCategoriesLoading = false,
                categoryError = message,
                isShowingCachedCategories = false,
                categoriesFetchedAt = null,
                categorySnapshotFetchedAt = null,
            )
        } else {
            val snapshot = fallback.snapshot
            current.copy(
                categories = snapshot.categoryList.categories,
                categoryCount = snapshot.categoryList.count,
                unclassifiedCount = snapshot.categoryList.unclassifiedCount,
                isCategoriesLoading = false,
                categoryError = message,
                isShowingCachedCategories = true,
                categoriesFetchedAt = snapshot.fetchedAt,
                categorySnapshotFetchedAt = snapshot.fetchedAt,
            )
        }
    }

    private fun enqueueCategoryIntent(
        method: String,
        path: String,
        payloadJson: String,
        queuedMessage: String,
        onQueued: () -> Unit,
    ) {
        if (!isVisible) return
        val requestOwner = ownerId ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val token = currentRequestToken(requestOwner)
        val requestId = UUID.randomUUID().toString()
        mutableUiState.value = mutableUiState.value.copy(
            isCategoryMutationInProgress = true,
            categoryMutationError = null,
            categoryMutationNotice = null,
        )
        val job = screenScope?.launch {
            try {
                outbox.enqueue(
                    ownerId = requestOwner,
                    requestId = requestId,
                    method = method,
                    path = path,
                    payloadJson = payloadJson,
                )
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                    return@launch
                }
                onQueued()
                mutableUiState.value = mutableUiState.value.copy(
                    isCategoryMutationInProgress = false,
                    categoryMutationNotice = queuedMessage,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountAuthenticationRequiredException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (responseBelongsTo(token)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isCategoryMutationInProgress = false,
                        categoryMutationError = error.localQueueMessage(),
                    )
                }
            } catch (error: Exception) {
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        isCategoryMutationInProgress = false,
                        categoryMutationError = error.localQueueMessage(),
                    )
                }
            }
        } ?: return
        categoryMutationJob = job
        job.invokeOnCompletion {
            if (categoryMutationJob === job) categoryMutationJob = null
        }
    }

    private fun observeCategoryOutbox(requestOwner: String) {
        val token = currentRequestToken(requestOwner)
        outboxJob = screenScope?.launch {
            outbox.observeOutbox(requestOwner).collect { entries ->
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                    return@collect
                }
                val categoryEntries = entries.filter { entry ->
                    entry.requestId !in replacedRequestIds && entry.ownerId == requestOwner &&
                        (entry.path == "/categories" || entry.path.startsWith("/categories/"))
                }
                mutableUiState.value = mutableUiState.value.copy(
                    categoryOutboxEntries = categoryEntries,
                )
                val newlySaved = categoryEntries.filter {
                    it.state == OutboxState.SAVED && it.requestId !in handledSavedRequestIds
                }
                if (newlySaved.isNotEmpty()) {
                    val acknowledged = mutableListOf<OutboxEntry>()
                    for (entry in newlySaved) {
                        if (!responseBelongsTo(token)) return@collect
                        try {
                            outbox.acknowledge(requestOwner, entry.requestId)
                            if (!responseBelongsTo(token)) return@collect
                            handledSavedRequestIds += entry.requestId
                            acknowledged += entry
                        } catch (_: AccountAuthenticationRequiredException) {
                            if (!identityMatches()) invalidateIdentity()
                            return@collect
                        } catch (_: Exception) {
                            // Keep the durable receipt eligible on the next outbox emission.
                        }
                    }
                    if (acknowledged.isNotEmpty() && responseBelongsTo(token)) {
                        val deletedCategoryIds = acknowledged
                            .asSequence()
                            .filter { it.method == "DELETE" }
                            .map { it.path.removePrefix("/categories/") }
                            .toSet()
                        val current = mutableUiState.value
                        mutableUiState.value = current.copy(
                            filters = if (
                                current.filters.categoryId?.let(deletedCategoryIds::contains) == true
                            ) {
                                current.filters.copy(categoryId = null)
                            } else {
                                current.filters
                            },
                            categoryMutationNotice = "분류 변경이 서버에 반영됐어요.",
                        )
                        refreshCategories()
                        val applied = mutableUiState.value.appliedQuery
                        if (
                            applied != null &&
                            applied.categoryId?.let(deletedCategoryIds::contains) == true
                        ) {
                            startSearchIntent(applied.withoutCategoryFilter())
                        } else {
                            restartAppliedSearch()
                        }
                    }
                }
            }
        }
    }

    private fun mutateCategoryEntry(
        requestId: String,
        mutation: suspend (String, OutboxEntry, DiscoveryRequestToken) -> Unit,
    ) {
        if (!isVisible) return
        val requestOwner = ownerId ?: return
        val entry = currentCategoryEntry(requestId) ?: return
        if (!identityMatches()) {
            invalidateIdentity()
            return
        }
        val token = currentRequestToken(requestOwner)
        screenScope?.launch {
            try {
                mutation(requestOwner, entry, token)
                if (!responseBelongsTo(token)) return@launch
            } catch (error: AccountAuthenticationRequiredException) {
                if (!identityMatches()) {
                    invalidateIdentity()
                } else if (responseBelongsTo(token)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        categoryMutationError = error.localQueueMessage(),
                    )
                }
            } catch (error: Exception) {
                if (!responseBelongsTo(token)) {
                    if (!identityMatches()) invalidateIdentity()
                } else {
                    mutableUiState.value = mutableUiState.value.copy(
                        categoryMutationError = error.localQueueMessage(),
                    )
                }
            }
        }
    }

    private fun currentCategoryEntry(requestId: String): OutboxEntry? =
        mutableUiState.value.categoryOutboxEntries.firstOrNull {
            it.requestId == requestId && it.ownerId == ownerId &&
                (it.path == "/categories" || it.path.startsWith("/categories/"))
        }

    private fun showCategoryIntentForEditing(
        entry: OutboxEntry,
        name: String?,
        notice: String,
    ) {
        when {
            entry.method == "POST" && entry.path == "/categories" && name != null -> {
                mutableUiState.value = mutableUiState.value.copy(
                    createCategoryName = name,
                    createCategoryIssue = null,
                    categoryMutationNotice = notice,
                    categoryMutationError = null,
                )
            }
            entry.method == "PATCH" && name != null -> {
                val categoryId = entry.path.removePrefix("/categories/")
                val category = mutableUiState.value.categories.firstOrNull {
                    it.id == categoryId && it.isCustom
                }
                mutableUiState.value = if (category == null) {
                    mutableUiState.value.copy(
                        categoryMutationNotice = notice,
                        categoryMutationError = "이름을 바꿀 분류가 최신 목록에 없어요.",
                    )
                } else {
                    mutableUiState.value.copy(
                        renameCategory = category,
                        renameCategoryName = name,
                        renameCategoryIssue = null,
                        categoryMutationNotice = notice,
                        categoryMutationError = null,
                    )
                }
            }
            else -> mutableUiState.value = mutableUiState.value.copy(
                categoryMutationNotice = notice,
                categoryMutationError = null,
            )
        }
    }

    private fun currentRequestToken(requestOwner: String) = DiscoveryRequestToken(
        ownerId = requestOwner,
        sessionGeneration = sessionGeneration,
        visibilityGeneration = visibilityGeneration,
    )

    private fun responseBelongsTo(token: DiscoveryRequestToken): Boolean {
        val session = client.sessionState.value
        return isCurrentDiscoveryRequest(
            token = token,
            ownerId = session.ownerId,
            sessionGeneration = session.generation,
            visibilityGeneration = visibilityGeneration,
            isVisible = isVisible,
        )
    }

    private fun identityMatches(): Boolean {
        val session = client.sessionState.value
        return ownerId != null &&
            session.ownerId == ownerId &&
            session.generation == sessionGeneration
    }

    private fun cancelScreenWork() {
        screenScope?.cancel()
        screenScope = null
        searchJob?.cancel()
        categoriesJob?.cancel()
        categoryMutationJob?.cancel()
        outboxJob?.cancel()
        aliasJob?.cancel()
        searchJob = null
        categoriesJob = null
        categoryMutationJob = null
        outboxJob = null
        aliasJob = null
    }

    private fun invalidateIdentity() {
        isVisible = false
        visibilityGeneration += 1
        cancelScreenWork()
        searchGeneration += 1
        mutableUiState.value = DiscoveryUiState(
            availability = DiscoveryAvailability.SignInRequired(
                "로그인 계정이 변경되었거나 세션이 만료됐어요. 다시 로그인해 주세요.",
            ),
            displayZoneId = zoneId.id,
            isSearchLoading = false,
            isCategoriesLoading = false,
            searchGeneration = searchGeneration,
        )
    }

    companion object {
        fun factory(
            client: AccountClient,
            outbox: OutboxRepository,
            ownerId: String?,
            sessionGeneration: Long,
            sessionInitialized: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(DiscoveryViewModel::class.java))
                return DiscoveryViewModel(
                    client = client,
                    outbox = outbox,
                    ownerId = ownerId,
                    sessionGeneration = sessionGeneration,
                    sessionInitialized = sessionInitialized,
                ) as T
            }
        }
    }
}

data class DiscoveryUiState(
    val availability: DiscoveryAvailability,
    val displayZoneId: String,
    val filters: DiscoveryFilterInput = DiscoveryFilterInput(),
    val appliedQuery: DiscoveryQuerySnapshot? = null,
    val filterIssues: List<DiscoveryFilterIssue> = emptyList(),
    val searchGeneration: Long = 0,
    val items: List<LibraryItemSummary> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val isSearchLoading: Boolean = true,
    val searchError: String? = null,
    val failedOffset: Int? = null,
    val categories: List<DiscoveryCategory> = emptyList(),
    val categoryCount: Int = 0,
    val unclassifiedCount: Int = 0,
    val isCategoriesLoading: Boolean = true,
    val categoryError: String? = null,
    val isShowingCachedCategories: Boolean = false,
    val categoriesFetchedAt: Long? = null,
    val categorySnapshotFetchedAt: Long? = null,
    val createCategoryName: String = "",
    val createCategoryIssue: String? = null,
    val renameCategory: DiscoveryCategory? = null,
    val renameCategoryName: String = "",
    val renameCategoryIssue: String? = null,
    val deleteCategory: DiscoveryCategory? = null,
    val isCategoryMutationInProgress: Boolean = false,
    val categoryMutationNotice: String? = null,
    val categoryMutationError: String? = null,
    val categoryOutboxEntries: List<OutboxEntry> = emptyList(),
    val categoryReviewRequestId: String? = null,
    val categoryReconfirmation: DiscoveryCategoryReconfirmation? = null,
    val aliasDisclosure: DiscoveryAliasDisclosure = DiscoveryAliasDisclosure.None,
    val sessionRecoveryMessage: String? = null,
)

sealed interface DiscoveryAvailability {
    data object Checking : DiscoveryAvailability
    data object Ready : DiscoveryAvailability
    data class SignInRequired(val message: String) : DiscoveryAvailability
}

sealed interface DiscoveryAliasDisclosure {
    data object None : DiscoveryAliasDisclosure
    data class Loading(val itemId: String) : DiscoveryAliasDisclosure
    data class Loaded(val detail: DiscoveryAliasDetail) : DiscoveryAliasDisclosure
    data class Failed(val itemId: String, val message: String) : DiscoveryAliasDisclosure
}

internal fun DiscoveryUiState.withoutDeletedItems(deletedItemIds: Set<String>): DiscoveryUiState {
    val disclosedItemId = when (val disclosure = aliasDisclosure) {
        DiscoveryAliasDisclosure.None -> null
        is DiscoveryAliasDisclosure.Loading -> disclosure.itemId
        is DiscoveryAliasDisclosure.Loaded -> disclosure.detail.itemId
        is DiscoveryAliasDisclosure.Failed -> disclosure.itemId
    }
    return copy(
        items = items.filterNot { it.id in deletedItemIds },
        aliasDisclosure = if (disclosedItemId != null && disclosedItemId in deletedItemIds) {
            DiscoveryAliasDisclosure.None
        } else {
            aliasDisclosure
        },
    )
}

data class DiscoveryCategoryReconfirmation(
    val requestId: String,
    val method: String,
    val path: String,
    internal val payloadJson: String,
    val description: String,
)

private fun OutboxEntry.toReconfirmation() = DiscoveryCategoryReconfirmation(
    requestId = requestId,
    method = method,
    path = path,
    payloadJson = payloadJson,
    description = when (method) {
        "POST" -> "분류 만들기"
        "PATCH" -> "분류 이름 변경"
        "DELETE" -> "분류와 링크 연결 삭제"
        else -> "분류 변경"
    },
)

private fun CachedCategories.parseCategoryListOrNull(): DiscoveryCategoryList? = runCatching {
    parseDiscoveryCategoriesResponse(Json.parseToJsonElement(responseJson).jsonObject)
}.getOrNull()

private fun OutboxEntry.categoryPayloadName(): String? = runCatching {
    Json.parseToJsonElement(payloadJson).jsonObject["name"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

internal fun OutboxEntry.isCategoryRequestRetryableInUi(): Boolean =
    errorCode !in setOf(
        "ACCOUNT_DELETING",
        "BETA_ACCESS_REQUIRED",
        "CATEGORY_LIMIT_REACHED",
        "CATEGORY_NAME_EXISTS",
        "CATEGORY_NOT_FOUND",
        "IDEMPOTENCY_MISMATCH",
        "INVALID_BODY",
        "INVALID_REQUEST_BODY",
        "SYSTEM_CATEGORY_READONLY",
    )

internal fun OutboxEntry.categoryFailureMessage(): String = when (errorCode) {
    "CATEGORY_LIMIT_REACHED" -> "직접 만든 분류 30개를 모두 사용했어요. 기존 분류를 정리해 주세요."
    "CATEGORY_NAME_EXISTS" -> "같거나 같은 이름으로 처리되는 분류가 이미 있어요. 이름을 바꿔 새 요청을 만들어 주세요."
    "CATEGORY_NOT_FOUND" -> "변경할 분류를 찾지 못했어요. 요청을 버리고 최신 분류를 확인해 주세요."
    "SYSTEM_CATEGORY_READONLY" -> "기본 분류의 이름을 바꾸거나 삭제할 수 없어요."
    "BETA_ACCESS_REQUIRED" -> "이 계정은 아직 베타 이용 승인을 받지 못했어요."
    "ACCOUNT_DELETING" -> "계정 삭제가 진행 중이라 분류를 변경할 수 없어요."
    "IDEMPOTENCY_MISMATCH" -> "같은 요청 ID에 다른 내용이 연결돼 있어요. 최신 목록을 확인하고 새 요청을 만들어야 합니다."
    "VERSION_CONFLICT" -> "서버의 최신 상태와 충돌했어요. 최신 분류를 확인하고 새 요청을 만들어야 합니다."
    "RATE_LIMITED" -> "요청이 너무 많아요. 서버가 안내한 시각 이후 같은 요청으로 다시 시도합니다."
    "DEPENDENCY_UNAVAILABLE" -> "서버가 일시적으로 요청을 처리하지 못했어요. 같은 요청으로 다시 시도합니다."
    "INVALID_BODY", "INVALID_REQUEST_BODY" -> "분류 이름이 올바르지 않아요. 실패한 요청을 버리고 이름을 수정해 주세요."
    else -> "분류 변경을 서버에 반영하지 못했어요."
}

private fun Throwable.localQueueMessage(): String = when ((this as? AccountClientException)?.code) {
    "UNAUTHENTICATED", "SESSION_CHANGED" -> "로그인 계정이 변경되어 요청을 보관하지 않았어요."
    else -> "기기에 분류 변경 요청을 보관하지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요."
}

private fun Throwable.discoveryMessage(): String = when ((this as? AccountClientException)?.code) {
    "BETA_ACCESS_REQUIRED" -> "이 계정은 아직 베타 이용 승인을 받지 못했어요."
    "ACCOUNT_DELETING" -> "계정 삭제가 진행 중이라 보관함을 사용할 수 없어요."
    "QUERY_LIMIT" -> "검색어는 200자와 10개 단어 한도 안에서 입력해 주세요."
    "INVALID_QUERY", "INVALID_PATH", "INVALID_METHOD" -> "검색 조건이 올바르지 않아요. 입력한 조건을 다시 확인해 주세요."
    "RATE_LIMITED" -> "검색 요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
    "DEPENDENCY_UNAVAILABLE", "SESSION_REFRESH_UNAVAILABLE" -> "서버에 연결하지 못했어요. 네트워크 연결을 확인하고 다시 시도해 주세요."
    else -> "서버에서 자료를 불러오지 못했어요. 네트워크 연결을 확인하고 다시 시도해 주세요."
}
