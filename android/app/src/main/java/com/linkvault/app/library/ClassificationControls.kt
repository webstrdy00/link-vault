package com.linkvault.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.auth.AccountAuthenticationRequiredException
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Composable
fun ClassificationControls(
    client: AccountClient,
    outbox: OutboxRepository,
    entryId: String,
    item: LibraryItemDetail,
    onRefresh: () -> Unit,
    onEditNote: () -> Unit,
) {
    val accountSession by client.sessionState.collectAsState()
    val ownerId = accountSession.ownerId
    val viewModelKey = remember(client, accountSession.generation, ownerId, entryId, item.id) {
        "classification:${System.identityHashCode(client)}:${accountSession.generation}:" +
            "${ownerId ?: "signed-out"}:$entryId:${item.id}"
    }
    val factory = remember(client, outbox, accountSession.generation, ownerId, item.id) {
        ClassificationControlsViewModel.factory(
            client = client,
            outbox = outbox,
            ownerId = ownerId,
            sessionGeneration = accountSession.generation,
            initialItem = item,
        )
    }
    val controlsViewModel: ClassificationControlsViewModel = viewModel(
        key = viewModelKey,
        factory = factory,
    )
    val state by controlsViewModel.uiState.collectAsState()

    LaunchedEffect(controlsViewModel, item) {
        controlsViewModel.updateItem(item)
    }
    LaunchedEffect(controlsViewModel, state.refreshGeneration) {
        if (controlsViewModel.takeRefreshCallback(state.refreshGeneration)) onRefresh()
    }
    DisposableEffect(controlsViewModel) {
        controlsViewModel.setVisible(true)
        onDispose { controlsViewModel.setVisible(false) }
    }

    HorizontalDivider()
    if (!state.sessionValid) {
        Text(
            text = "로그인이 필요해요.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "분류",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (state.item.manualOverride == true) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "직접 선택한 분류",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        CurrentCategoryReasons(
            item = state.item,
            expandedCategoryId = state.expandedCategoryId,
            onCategoryClick = controlsViewModel::toggleReason,
        )

        if (state.categoriesLoading && !state.hasCategorySnapshot) {
            Text("서버에서 분류 목록을 불러오고 있어요.")
        }
        if (state.categoriesFromCache) {
            val fetchedAt = state.categoriesFetchedAt?.let(::formatClassificationCacheTime)
                ?: "동기화 시각 확인 불가"
            Text(
                text = "이 기기의 마지막 분류 목록 · $fetchedAt",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        state.categoriesError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = controlsViewModel::reloadCategories,
                enabled = !state.categoriesLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("분류 목록 다시 시도")
            }
        }

        if (state.hasCategorySnapshot) {
            Text(
                text = "직접 선택 (${state.selectedCategoryIds.size} / $LIBRARY_CATEGORY_MAX_SELECTION)",
                fontWeight = FontWeight.SemiBold,
            )
            state.categories.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = category.id in state.selectedCategoryIds,
                        onCheckedChange = { controlsViewModel.toggleCategory(category.id) },
                        enabled = !state.categorySelectionLocked,
                        modifier = Modifier.testTag("classification-choice-${category.id}"),
                    )
                    Column {
                        Text(category.name)
                        category.kind?.takeUnless(String::isBlank)?.let { kind ->
                            Text(
                                text = kind,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            state.missingSelectedCategoryIds.forEach { categoryId ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = { controlsViewModel.toggleCategory(categoryId) },
                        enabled = !state.categorySelectionLocked,
                    )
                    Text(
                        text = "삭제되었거나 사용할 수 없는 분류 · $categoryId",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.missingSelectedCategoryIds.isNotEmpty()) {
                Text(
                    text = "사용할 수 없는 분류 선택을 해제한 뒤 다시 확인해 주세요.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.selectionError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            if (state.readyToReconfirmRequestId == null) {
                Button(
                    onClick = controlsViewModel::submitCategories,
                    enabled = state.categoryIntent.submitVersion != null &&
                        !state.hasUnresolvedControlRequest &&
                        state.missingSelectedCategoryIds.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("선택한 분류 저장")
                }
            } else {
                Text("최신 링크와 분류 목록을 확인했어요. 아래 버튼을 눌러야 새 요청 ID와 최신 버전으로 저장합니다.")
                Button(
                    onClick = controlsViewModel::confirmCategoriesAgain,
                    enabled = !state.categoryQueuing &&
                        state.categoryIntent.reviewedVersion != null &&
                        state.entries.none {
                            it.requestId != state.readyToReconfirmRequestId
                        } &&
                        state.missingSelectedCategoryIds.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("새 요청으로 분류 저장")
                }
            }
        }

        ControlOutboxContent(
            state = state,
            onRetry = controlsViewModel::retry,
            onDiscard = controlsViewModel::discard,
            onLoadLatestCategories = controlsViewModel::loadLatestForCategories,
            onRefreshLatest = controlsViewModel::refreshLatest,
        )

        state.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        state.actionError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        if (state.showReclassifyConfirmation) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "자동 분류 다시 적용",
                        fontWeight = FontWeight.Bold,
                    )
                    Text("직접 만든 분류는 유지하고, 기본 분류를 다시 적용합니다.")
                    Button(
                        onClick = controlsViewModel::confirmReclassify,
                        enabled = state.item.version != null &&
                            !state.hasUnresolvedControlRequest &&
                            !state.commandQueuing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("확인")
                    }
                    OutlinedButton(
                        onClick = controlsViewModel::cancelReclassify,
                        enabled = !state.commandQueuing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("취소")
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = controlsViewModel::requestReclassify,
                enabled = state.item.version != null &&
                    !state.hasUnresolvedControlRequest &&
                    !state.commandQueuing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("자동 분류 다시 적용")
            }
        }

        if (state.item.needsCuePrompt()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "나중에 어떤 말로 찾을까요?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Button(
                        onClick = onEditNote,
                        enabled = !state.hasUnresolvedControlRequest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("메모 수정")
                    }
                    TextButton(
                        onClick = controlsViewModel::dismissCue,
                        enabled = state.item.version != null &&
                            state.item.textRevision != null &&
                            !state.hasUnresolvedControlRequest &&
                            !state.commandQueuing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("나중에")
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentCategoryReasons(
    item: LibraryItemDetail,
    expandedCategoryId: String?,
    onCategoryClick: (String) -> Unit,
) {
    if (item.categoryRefs.isEmpty()) {
        Text("현재 적용된 분류가 없어요.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("현재 적용된 분류", fontWeight = FontWeight.SemiBold)
        item.categoryRefs.forEachIndexed { index, category ->
            val key = category.id ?: "category-$index"
            TextButton(
                onClick = { onCategoryClick(key) },
                modifier = Modifier.testTag("current-category-$key"),
            ) {
                Text(category.name?.takeUnless(String::isBlank) ?: "이름 없는 분류")
            }
            if (expandedCategoryId == key) {
                val explanations = item.currentClassificationExplanations(category)
                if (explanations.isEmpty()) {
                    Text(
                        text = "현재 자료에서 확인된 자동 분류 근거가 없어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    explanations.forEach { explanation ->
                        Text(
                            text = "표현: ${explanation.expression} · 필드: ${explanation.field}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlOutboxContent(
    state: ClassificationControlsUiState,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onLoadLatestCategories: (String) -> Unit,
    onRefreshLatest: () -> Unit,
) {
    if (state.entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("이 링크의 분류 요청", fontWeight = FontWeight.Bold)
        state.entries.forEach { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(entry.controlLabel(), fontWeight = FontWeight.SemiBold)
                    Text(entry.state.controlQueueMessage())
                    if (entry.state == OutboxState.FAILED) {
                        entry.errorMessage?.takeUnless(String::isBlank)?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = { onRetry(entry.requestId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("다시 시도")
                        }
                    }
                    if (entry.state == OutboxState.CONFLICT || entry.state == OutboxState.EXPIRED) {
                        Button(
                            onClick = {
                                if (entry.controlKind() == ControlKind.CATEGORIES) {
                                    onLoadLatestCategories(entry.requestId)
                                } else {
                                    onRefreshLatest()
                                }
                            },
                            enabled = !state.loadingLatest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("최신 내용 확인")
                        }
                    }
                    if (
                        entry.state == OutboxState.FAILED ||
                        entry.state == OutboxState.CONFLICT ||
                        entry.state == OutboxState.EXPIRED
                    ) {
                        OutlinedButton(
                            onClick = { onDiscard(entry.requestId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("요청 버리기")
                        }
                    }
                }
            }
        }
    }
}

private data class ClassificationCategory(
    val id: String,
    val name: String,
    val kind: String?,
    val systemCode: String?,
)

private data class ClassificationControlsUiState(
    val item: LibraryItemDetail,
    val categoryIntent: LibraryCategoryIntentState,
    val sessionValid: Boolean = true,
    val categories: List<ClassificationCategory> = emptyList(),
    val hasCategorySnapshot: Boolean = false,
    val categoriesLoading: Boolean = true,
    val categoriesFromCache: Boolean = false,
    val categoriesFetchedAt: Long? = null,
    val categoriesError: String? = null,
    val selectionError: String? = null,
    val entries: List<OutboxEntry> = emptyList(),
    val categoryQueuing: Boolean = false,
    val commandQueuing: Boolean = false,
    val loadingLatest: Boolean = false,
    val readyToReconfirmRequestId: String? = null,
    val showReclassifyConfirmation: Boolean = false,
    val expandedCategoryId: String? = null,
    val message: String? = null,
    val actionError: String? = null,
    val refreshGeneration: Long = 0L,
) {
    val selectedCategoryIds: List<String>
        get() = categoryIntent.selectedCategoryIds

    val missingSelectedCategoryIds: List<String>
        get() = if (!hasCategorySnapshot) {
            emptyList()
        } else {
            selectedCategoryIds.filterNot { selected -> categories.any { it.id == selected } }
        }

    val hasUnresolvedControlRequest: Boolean
        get() = entries.isNotEmpty() || categoryQueuing || commandQueuing

    val categorySelectionLocked: Boolean
        get() = categoryQueuing || entries.any { entry ->
            entry.controlKind() == ControlKind.CATEGORIES &&
                entry.state != OutboxState.CONFLICT &&
                entry.state != OutboxState.EXPIRED
        }
}

private class ClassificationControlsViewModel(
    private val client: AccountClient,
    private val outbox: OutboxRepository,
    private val ownerId: String?,
    private val sessionGeneration: Long,
    initialItem: LibraryItemDetail,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableUiState = MutableStateFlow(
        ClassificationControlsUiState(
            item = initialItem,
            categoryIntent = LibraryCategoryIntentState.pristine(
                version = initialItem.version,
                categoryIds = initialItem.categoryRefs.mapNotNull { it.id },
            ),
        ),
    )
    val uiState: StateFlow<ClassificationControlsUiState> = mutableUiState.asStateFlow()

    private val itemId = initialItem.id
    private val preparedReceipts = mutableSetOf<String>()
    private val restoredBlockedRequests = mutableSetOf<String>()
    private val replacedRequestIds = mutableSetOf<String>()
    private var categoriesJob: Job? = null
    private var actionJob: Job? = null
    private var outboxJob: Job? = null
    private var pollingJob: Job? = null
    private var visible = false
    private var deliveredRefreshGeneration = 0L

    init {
        if (ownerId.isNullOrBlank() || !identityMatches()) {
            invalidateSession()
        } else {
            loadCategories(ownerId)
        }
        observeSession()
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (!isVisible) {
            pollingJob?.cancel()
            pollingJob = null
            outboxJob?.cancel()
            outboxJob = null
        } else if (identityMatches() && mutableUiState.value.sessionValid) {
            ownerId?.let(::observeOutbox)
            if (mutableUiState.value.item.classificationState == "pending") startPolling()
        }
    }

    fun takeRefreshCallback(generation: Long): Boolean {
        if (!mutableUiState.value.sessionValid ||
            !identityMatches() ||
            generation <= deliveredRefreshGeneration
        ) {
            return false
        }
        deliveredRefreshGeneration = generation
        return true
    }

    fun updateItem(item: LibraryItemDetail) {
        if (item.id != itemId || !identityMatches()) return
        val current = mutableUiState.value.item
        val currentVersion = current.version
        val incomingVersion = item.version
        if (currentVersion != null && incomingVersion != null && incomingVersion < currentVersion) return
        val currentState = mutableUiState.value
        mutableUiState.value = currentState.copy(
            item = item,
            categoryIntent = currentState.categoryIntent.applyDisplayedSnapshot(
                version = item.version,
                categoryIds = item.categoryRefs.mapNotNull { it.id },
            ),
        )
        if (item.classificationState == "pending" && visible) startPolling()
    }

    fun toggleReason(categoryId: String) {
        if (!identityMatches()) return
        mutableUiState.value = mutableUiState.value.copy(
            expandedCategoryId = categoryId.takeUnless {
                it == mutableUiState.value.expandedCategoryId
            },
        )
    }

    fun toggleCategory(categoryId: String) {
        if (!identityMatches() || mutableUiState.value.categorySelectionLocked) return
        val currentState = mutableUiState.value
        val selected = currentState.selectedCategoryIds
        if (categoryId in selected) {
            mutableUiState.value = currentState.copy(
                categoryIntent = currentState.categoryIntent.toggle(categoryId),
                selectionError = null,
                message = null,
            )
            return
        }
        if (selected.size >= LIBRARY_CATEGORY_MAX_SELECTION) {
            mutableUiState.value = mutableUiState.value.copy(
                selectionError = "분류는 최대 ${LIBRARY_CATEGORY_MAX_SELECTION}개까지 선택할 수 있어요.",
            )
            return
        }
        mutableUiState.value = currentState.copy(
            categoryIntent = currentState.categoryIntent.toggle(categoryId),
            selectionError = null,
            message = null,
        )
    }

    fun reloadCategories() {
        if (!identityMatches()) return
        val boundOwner = ownerId ?: return
        loadCategories(boundOwner)
    }

    fun submitCategories() {
        if (!identityMatches()) return
        val version = mutableUiState.value.categoryIntent.submitVersion ?: return
        enqueueCategories(
            expectedVersion = version,
            replacesRequestId = null,
        )
    }

    fun loadLatestForCategories(requestId: String) {
        if (!identityMatches() || actionJob?.isActive == true) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.entries.firstOrNull {
            it.requestId == requestId && it.controlKind() == ControlKind.CATEGORIES &&
                (it.state == OutboxState.CONFLICT || it.state == OutboxState.EXPIRED)
        } ?: return
        mutableUiState.value = mutableUiState.value.copy(
            loadingLatest = true,
            actionError = null,
        )
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                val detailResponse = client.libraryRequest(
                    expectedOwnerId = boundOwner,
                    path = "/items/$itemId",
                )
                val categoryResponse = client.libraryRequest(
                    expectedOwnerId = boundOwner,
                    path = "/categories",
                )
                val latest = parseLibraryDetailResponse(detailResponse)
                val categories = parseClassificationCategories(categoryResponse)
                if (!identityMatches() ||
                    latest.id != itemId ||
                    latest.version == null ||
                    !isCurrentVersion(latest)
                ) {
                    throw IllegalStateException("The latest item no longer belongs to this screen.")
                }
                outbox.cacheDetail(boundOwner, detailResponse)
                outbox.cacheCategories(boundOwner, categoryResponse)
                if (!identityMatches() || !isCurrentVersion(latest)) {
                    throw IllegalStateException("A newer item was loaded while reviewing categories.")
                }
                val currentState = mutableUiState.value
                mutableUiState.value = currentState.copy(
                    item = latest,
                    categoryIntent = currentState.categoryIntent.reviewLatest(latest.version),
                    categories = categories,
                    hasCategorySnapshot = true,
                    categoriesLoading = false,
                    categoriesFromCache = false,
                    categoriesFetchedAt = System.currentTimeMillis(),
                    categoriesError = null,
                    loadingLatest = false,
                    readyToReconfirmRequestId = entry.requestId,
                    actionError = null,
                    refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateSession()
            } catch (error: Exception) {
                showActionError(error.classificationMessage("최신 링크와 분류 목록을 불러오지 못했어요."))
            }
        }
    }

    fun confirmCategoriesAgain() {
        if (!identityMatches()) return
        val requestId = mutableUiState.value.readyToReconfirmRequestId ?: return
        val version = mutableUiState.value.categoryIntent.reviewedVersion ?: return
        enqueueCategories(
            expectedVersion = version,
            replacesRequestId = requestId,
        )
    }

    fun requestReclassify() {
        if (!identityMatches()) return
        mutableUiState.value = mutableUiState.value.copy(
            showReclassifyConfirmation = true,
            actionError = null,
        )
    }

    fun cancelReclassify() {
        if (mutableUiState.value.commandQueuing) return
        mutableUiState.value = mutableUiState.value.copy(showReclassifyConfirmation = false)
    }

    fun confirmReclassify() {
        if (!identityMatches()) return
        val boundOwner = ownerId ?: return
        val version = mutableUiState.value.item.version ?: return
        enqueueCommand(
            owner = boundOwner,
            path = "/items/$itemId/reclassify",
            body = buildJsonObject { put("expected_version", version) }.toString(),
        )
    }

    fun dismissCue() {
        if (!identityMatches()) return
        val boundOwner = ownerId ?: return
        val item = mutableUiState.value.item
        val version = item.version ?: return
        val textRevision = item.textRevision ?: return
        enqueueCommand(
            owner = boundOwner,
            path = "/items/$itemId/cue-dismiss",
            body = buildJsonObject {
                put("expected_version", version)
                put("text_revision", textRevision)
            }.toString(),
        )
    }

    fun retry(requestId: String) {
        if (!identityMatches()) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.entries.firstOrNull {
            it.requestId == requestId && it.state == OutboxState.FAILED
        } ?: return
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                outbox.retry(boundOwner, entry.requestId)
                mutableUiState.value = mutableUiState.value.copy(actionError = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showActionError(error.classificationMessage("요청을 다시 대기열에 넣지 못했어요."))
            }
        }
    }

    fun discard(requestId: String) {
        if (!identityMatches()) return
        val boundOwner = ownerId ?: return
        val entry = mutableUiState.value.entries.firstOrNull {
            it.requestId == requestId &&
                (it.state == OutboxState.FAILED ||
                    it.state == OutboxState.CONFLICT ||
                    it.state == OutboxState.EXPIRED)
        } ?: return
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                outbox.discard(boundOwner, entry.requestId)
                val currentState = mutableUiState.value
                val categoryIntent = if (entry.controlKind() == ControlKind.CATEGORIES) {
                    currentState.categoryIntent.discardAt(currentState.item.version)
                } else {
                    currentState.categoryIntent
                }
                mutableUiState.value = currentState.copy(
                    categoryIntent = categoryIntent,
                    readyToReconfirmRequestId = currentState.readyToReconfirmRequestId
                        ?.takeUnless { it == entry.requestId },
                    actionError = null,
                    message = "요청을 버렸어요. 선택 내용은 이 화면에 남겨 두었어요.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showActionError(error.classificationMessage("요청을 버리지 못했어요."))
            }
        }
    }

    fun refreshLatest() {
        if (!identityMatches()) return
        val boundOwner = ownerId ?: return
        if (actionJob?.isActive == true) return
        mutableUiState.value = mutableUiState.value.copy(loadingLatest = true, actionError = null)
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                val response = client.libraryRequest(
                    expectedOwnerId = boundOwner,
                    path = "/items/$itemId",
                )
                val latest = parseLibraryDetailResponse(response)
                if (!identityMatches() || latest.id != itemId || !isCurrentVersion(latest)) {
                    return@launch
                }
                outbox.cacheDetail(boundOwner, response)
                if (!identityMatches() || !isCurrentVersion(latest)) {
                    mutableUiState.value = mutableUiState.value.copy(loadingLatest = false)
                    return@launch
                }
                val currentState = mutableUiState.value
                mutableUiState.value = currentState.copy(
                    item = latest,
                    categoryIntent = currentState.categoryIntent.applyDisplayedSnapshot(
                        version = latest.version,
                        categoryIds = latest.categoryRefs.mapNotNull { it.id },
                    ),
                    loadingLatest = false,
                    refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateSession()
            } catch (error: Exception) {
                showActionError(error.classificationMessage("최신 링크를 불러오지 못했어요."))
            }
        }
    }

    private fun enqueueCategories(expectedVersion: Long, replacesRequestId: String?) {
        if (!identityMatches() ||
            actionJob?.isActive == true ||
            mutableUiState.value.missingSelectedCategoryIds.isNotEmpty()
        ) {
            return
        }
        val unresolvedOtherRequest = mutableUiState.value.entries.any { entry ->
            entry.requestId != replacesRequestId
        }
        if (unresolvedOtherRequest) return
        val boundOwner = ownerId ?: return
        val preparation = prepareLibraryCategoryEdit(
            ownerId = boundOwner,
            itemId = itemId,
            requestId = UUID.randomUUID().toString(),
            expectedVersion = expectedVersion,
            categoryIds = mutableUiState.value.selectedCategoryIds,
        )
        if (preparation is LibraryCategoryEditPreparation.Invalid) {
            mutableUiState.value = mutableUiState.value.copy(
                selectionError = preparation.messages.joinToString("\n"),
            )
            return
        }
        val operation = (preparation as LibraryCategoryEditPreparation.Valid).operation
        mutableUiState.value = mutableUiState.value.copy(
            categoryQueuing = true,
            selectionError = null,
            actionError = null,
            message = null,
        )
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                outbox.enqueue(
                    ownerId = operation.ownerId,
                    requestId = operation.requestId,
                    method = "PATCH",
                    path = "/items/${operation.itemId}",
                    payloadJson = operation.body,
                )
                if (replacesRequestId != null) {
                    replacedRequestIds += replacesRequestId
                    outbox.discard(boundOwner, replacesRequestId)
                }
                mutableUiState.value = mutableUiState.value.copy(
                    categoryQueuing = false,
                    readyToReconfirmRequestId = null,
                    message = "분류 요청을 이 기기에 보관했어요.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (replacesRequestId != null) replacedRequestIds -= replacesRequestId
                mutableUiState.value = mutableUiState.value.copy(
                    categoryQueuing = false,
                    actionError = error.classificationMessage("분류 요청을 기기에 보관하지 못했어요."),
                )
            }
        }
    }

    private fun enqueueCommand(owner: String, path: String, body: String) {
        if (!identityMatches() ||
            mutableUiState.value.hasUnresolvedControlRequest
        ) {
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            commandQueuing = true,
            actionError = null,
            message = null,
        )
        actionJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                outbox.enqueue(
                    ownerId = owner,
                    requestId = UUID.randomUUID().toString(),
                    method = "POST",
                    path = path,
                    payloadJson = body,
                )
                mutableUiState.value = mutableUiState.value.copy(
                    commandQueuing = false,
                    showReclassifyConfirmation = false,
                    message = "요청을 이 기기에 보관했어요.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    commandQueuing = false,
                    actionError = error.classificationMessage("요청을 기기에 보관하지 못했어요."),
                )
            }
        }
    }

    private fun observeOutbox(boundOwner: String) {
        if (!visible || outboxJob?.isActive == true) return
        outboxJob = viewModelScope.launch {
            outbox.observeOutbox(boundOwner).collect { entries ->
                if (!visible || !identityMatches()) return@collect
                val relevant = entries
                    .filterNot { it.requestId in replacedRequestIds }
                    .filter { it.isControlOperationFor(itemId) }
                mutableUiState.value = mutableUiState.value.copy(entries = relevant)
                restoreBlockedCategoryDraft(relevant)
                relevant.filter { it.state == OutboxState.SAVED }.forEach { entry ->
                    consumeSavedReceipt(boundOwner, entry)
                }
            }
        }
    }

    private fun restoreBlockedCategoryDraft(entries: List<OutboxEntry>) {
        val entry = entries
            .filter { it.controlKind() == ControlKind.CATEGORIES }
            .filter { it.state == OutboxState.CONFLICT || it.state == OutboxState.EXPIRED }
            .maxByOrNull { it.createdAt }
            ?: return
        if (!restoredBlockedRequests.add(entry.requestId)) return
        val patch = runCatching { parseLibraryCategoryPatch(entry.payloadJson) }.getOrNull() ?: return
        val currentState = mutableUiState.value
        mutableUiState.value = currentState.copy(
            categoryIntent = currentState.categoryIntent.restoreBlockedPatch(
                expectedVersion = patch.expectedVersion,
                categoryIds = patch.categoryIds,
            ),
            readyToReconfirmRequestId = null,
            message = null,
        )
    }

    private suspend fun consumeSavedReceipt(boundOwner: String, entry: OutboxEntry) {
        if (!preparedReceipts.add(entry.requestId)) return
        val resultJson = entry.resultJson
        if (resultJson == null) {
            preparedReceipts -= entry.requestId
            return
        }
        try {
            val response = json.parseToJsonElement(resultJson).jsonObject
            when (entry.controlKind()) {
                ControlKind.CATEGORIES -> applyDetailReceipt(boundOwner, entry, response, isCue = false)
                ControlKind.CUE_DISMISS -> applyDetailReceipt(boundOwner, entry, response, isCue = true)
                ControlKind.RECLASSIFY -> applyReclassifyReceipt(response)
                null -> return
            }
            outbox.acknowledge(boundOwner, entry.requestId)
        } catch (error: CancellationException) {
            preparedReceipts -= entry.requestId
            throw error
        } catch (_: AccountAuthenticationRequiredException) {
            preparedReceipts -= entry.requestId
            invalidateSession()
        } catch (error: Exception) {
            preparedReceipts -= entry.requestId
            showActionError(error.classificationMessage("서버 완료 응답을 적용하지 못했어요."))
        }
    }

    private suspend fun applyDetailReceipt(
        boundOwner: String,
        entry: OutboxEntry,
        response: JsonObject,
        isCue: Boolean,
    ) {
        val detail = parseLibraryDetailResponse(response)
        if (!identityMatches() || detail.id != itemId) {
            throw IllegalStateException("The saved item no longer belongs to this screen.")
        }
        if (isCue) {
            val requestedRevision = runCatching {
                json.parseToJsonElement(entry.payloadJson).jsonObject["text_revision"]
                    ?.jsonPrimitive?.longOrNull
            }.getOrNull()
            val currentRevision = mutableUiState.value.item.textRevision
            if (detail.textRevision == requestedRevision &&
                currentRevision == requestedRevision &&
                isCurrentVersion(detail)
            ) {
                outbox.cacheDetail(boundOwner, response)
                val currentState = mutableUiState.value
                if (identityMatches() &&
                    detail.textRevision == currentState.item.textRevision &&
                    isCurrentVersion(detail)
                ) {
                    mutableUiState.value = currentState.copy(
                        item = detail,
                        categoryIntent = currentState.categoryIntent.applyDisplayedSnapshot(
                            version = detail.version,
                            categoryIds = detail.categoryRefs.mapNotNull { it.id },
                        ),
                    )
                }
            }
        } else {
            if (isCurrentVersion(detail)) {
                outbox.cacheDetail(boundOwner, response)
            }
            val currentState = mutableUiState.value
            val acceptedDetail = detail.takeIf {
                identityMatches() && isAtLeastVersion(it, currentState.item)
            } ?: currentState.item
            mutableUiState.value = currentState.copy(
                item = acceptedDetail,
                categoryIntent = currentState.categoryIntent.acknowledgeSaved(
                    version = acceptedDetail.version,
                    categoryIds = acceptedDetail.categoryRefs.mapNotNull { it.id },
                ),
                readyToReconfirmRequestId = null,
            )
        }
        mutableUiState.value = mutableUiState.value.copy(
            message = if (isCue) "현재 내용의 찾기 질문을 나중으로 미뤘어요." else "분류를 저장했어요.",
            actionError = null,
            refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
        )
    }

    private fun applyReclassifyReceipt(response: JsonObject) {
        val receiptItemId = (response["item_id"] as? JsonPrimitive)?.contentOrNull
        val jobId = (response["job_id"] as? JsonPrimitive)?.contentOrNull
        if (receiptItemId != itemId || jobId.isNullOrBlank() || !identityMatches()) {
            throw IllegalArgumentException("Invalid reclassification receipt.")
        }
        mutableUiState.value = mutableUiState.value.copy(
            message = "자동 분류 요청을 서버가 접수했어요. 처리 결과를 확인하고 있어요.",
            actionError = null,
            refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
        )
        startPolling()
    }

    private fun loadCategories(boundOwner: String) {
        categoriesJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            categoriesLoading = true,
            categoriesError = null,
        )
        categoriesJob = viewModelScope.launch {
            try {
                if (!identityMatches()) return@launch
                val response = client.libraryRequest(
                    expectedOwnerId = boundOwner,
                    path = "/categories",
                )
                val categories = parseClassificationCategories(response)
                outbox.cacheCategories(boundOwner, response)
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    categories = categories,
                    hasCategorySnapshot = true,
                    categoriesLoading = false,
                    categoriesFromCache = false,
                    categoriesFetchedAt = System.currentTimeMillis(),
                    categoriesError = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountAuthenticationRequiredException) {
                invalidateSession()
            } catch (error: Exception) {
                val cached = runCatching { outbox.readCachedCategories(boundOwner) }.getOrNull()
                val categories = cached?.let { row ->
                    runCatching {
                        parseClassificationCategories(json.parseToJsonElement(row.responseJson).jsonObject)
                    }.getOrNull()
                }
                if (!identityMatches()) return@launch
                mutableUiState.value = mutableUiState.value.copy(
                    categories = categories.orEmpty(),
                    hasCategorySnapshot = categories != null,
                    categoriesLoading = false,
                    categoriesFromCache = categories != null,
                    categoriesFetchedAt = cached?.fetchedAt,
                    categoriesError = error.classificationMessage(
                        if (categories == null) {
                            "분류 목록을 불러오지 못했어요."
                        } else {
                            "서버의 최신 분류 목록을 불러오지 못했어요."
                        },
                    ),
                )
            }
        }
    }

    private fun startPolling() {
        if (!visible || pollingJob?.isActive == true) return
        val boundOwner = ownerId ?: return
        pollingJob = viewModelScope.launch {
            val deadlineNanos = System.nanoTime() +
                CLASSIFICATION_POLL_DURATION_MILLIS * NANOS_PER_MILLISECOND
            while (true) {
                val beforeDelayMillis =
                    (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND
                if (beforeDelayMillis <= 0L) break
                delay(CLASSIFICATION_POLL_INTERVAL_MILLIS.coerceAtMost(beforeDelayMillis))
                if (!visible || !identityMatches()) return@launch
                try {
                    val requestBudgetMillis =
                        (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND
                    if (requestBudgetMillis <= 0L) break
                    val response = withTimeoutOrNull(requestBudgetMillis) {
                        client.libraryRequest(
                            expectedOwnerId = boundOwner,
                            path = "/items/$itemId",
                        )
                    } ?: break
                    val latest = parseLibraryDetailResponse(response)
                    if (!visible ||
                        !identityMatches() ||
                        latest.id != itemId ||
                        !isCurrentVersion(latest)
                    ) {
                        return@launch
                    }
                    outbox.cacheDetail(boundOwner, response)
                    if (!visible || !identityMatches() || !isCurrentVersion(latest)) {
                        return@launch
                    }
                    val currentState = mutableUiState.value
                    mutableUiState.value = currentState.copy(
                        item = latest,
                        categoryIntent = currentState.categoryIntent.applyDisplayedSnapshot(
                            version = latest.version,
                            categoryIds = latest.categoryRefs.mapNotNull { it.id },
                        ),
                        refreshGeneration = mutableUiState.value.refreshGeneration + 1L,
                    )
                    if (latest.classificationState != "pending") return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (_: AccountAuthenticationRequiredException) {
                    invalidateSession()
                    return@launch
                } catch (_: Exception) {
                    // Keep the bounded poll alive through transient failures.
                }
            }
            if (visible && mutableUiState.value.item.classificationState == "pending") {
                mutableUiState.value = mutableUiState.value.copy(
                    message = "분류 처리가 계속되고 있어요. 상세를 다시 열면 상태를 다시 확인합니다.",
                )
            }
        }
    }

    private fun showActionError(message: String) {
        mutableUiState.value = mutableUiState.value.copy(
            loadingLatest = false,
            categoryQueuing = false,
            commandQueuing = false,
            actionError = message,
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
        categoriesJob?.cancel()
        categoriesJob = null
        actionJob?.cancel()
        actionJob = null
        outboxJob?.cancel()
        outboxJob = null
        pollingJob?.cancel()
        pollingJob = null
        val currentState = mutableUiState.value
        mutableUiState.value = currentState.copy(
            item = LibraryItemDetail(id = itemId, url = ""),
            categoryIntent = LibraryCategoryIntentState.pristine(null, emptyList()),
            sessionValid = false,
            categories = emptyList(),
            hasCategorySnapshot = false,
            categoriesLoading = false,
            categoriesFromCache = false,
            categoriesFetchedAt = null,
            categoriesError = null,
            selectionError = null,
            entries = emptyList(),
            categoryQueuing = false,
            commandQueuing = false,
            loadingLatest = false,
            readyToReconfirmRequestId = null,
            showReclassifyConfirmation = false,
            expandedCategoryId = null,
            message = null,
            actionError = null,
        )
    }

    private fun identityMatches(): Boolean {
        val session = client.sessionState.value
        return !ownerId.isNullOrBlank() &&
            session.ownerId == ownerId &&
            session.generation == sessionGeneration
    }

    private fun isCurrentVersion(candidate: LibraryItemDetail): Boolean {
        return isAtLeastVersion(candidate, mutableUiState.value.item)
    }

    private fun isAtLeastVersion(
        candidate: LibraryItemDetail,
        current: LibraryItemDetail,
    ): Boolean {
        val currentVersion = current.version ?: return true
        val candidateVersion = candidate.version ?: return false
        return candidateVersion >= currentVersion
    }

    companion object {
        private const val CLASSIFICATION_POLL_INTERVAL_MILLIS = 5_000L
        private const val CLASSIFICATION_POLL_DURATION_MILLIS = 60_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun factory(
            client: AccountClient,
            outbox: OutboxRepository,
            ownerId: String?,
            sessionGeneration: Long,
            initialItem: LibraryItemDetail,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ClassificationControlsViewModel::class.java))
                return ClassificationControlsViewModel(
                    client,
                    outbox,
                    ownerId,
                    sessionGeneration,
                    initialItem,
                ) as T
            }
        }
    }
}

private enum class ControlKind {
    CATEGORIES,
    CUE_DISMISS,
    RECLASSIFY,
}

private fun OutboxEntry.isControlOperationFor(itemId: String): Boolean {
    val itemPath = "/items/$itemId"
    return when {
        method == "PATCH" && path == itemPath -> runCatching {
            Json.parseToJsonElement(payloadJson).jsonObject.containsKey("category_ids")
        }.getOrDefault(false)

        method == "POST" && path == "$itemPath/cue-dismiss" -> true
        method == "POST" && path == "$itemPath/reclassify" -> true
        else -> false
    }
}

private fun OutboxEntry.controlKind(): ControlKind? = when {
    method == "PATCH" && runCatching {
        Json.parseToJsonElement(payloadJson).jsonObject.containsKey("category_ids")
    }.getOrDefault(false) -> ControlKind.CATEGORIES

    method == "POST" && path.endsWith("/cue-dismiss") -> ControlKind.CUE_DISMISS
    method == "POST" && path.endsWith("/reclassify") -> ControlKind.RECLASSIFY
    else -> null
}

private fun OutboxEntry.controlLabel(): String = when (controlKind()) {
    ControlKind.CATEGORIES -> "직접 분류 저장"
    ControlKind.CUE_DISMISS -> "찾기 질문 나중에 보기"
    ControlKind.RECLASSIFY -> "자동 분류 다시 적용"
    null -> "분류 요청"
}

private fun OutboxState.controlQueueMessage(): String = when (this) {
    OutboxState.PENDING -> "전송 대기 중"
    OutboxState.RUNNING -> "서버에 전송 중"
    OutboxState.RETRY -> "연결되면 다시 전송"
    OutboxState.WAITING_LOGIN -> "로그인 후 전송"
    OutboxState.FAILED -> "서버 요청 실패"
    OutboxState.CONFLICT -> "최신 버전과 충돌"
    OutboxState.EXPIRED -> "요청 만료 · 최신 내용 확인 필요"
    OutboxState.SAVED -> "서버 응답 적용 중"
}

private fun LibraryItemDetail.needsCuePrompt(): Boolean =
    cuePromptDismissed != true && cueState in setOf("limited", "missing")

private fun parseClassificationCategories(response: JsonObject): List<ClassificationCategory> =
    response["categories"]?.jsonArray?.map { element ->
        val category = element.jsonObject
        ClassificationCategory(
            id = category.getValue("id").jsonPrimitive.content,
            name = category.getValue("name").jsonPrimitive.content,
            kind = category["kind"]?.jsonPrimitive?.contentOrNull,
            systemCode = category["system_code"]?.jsonPrimitive?.contentOrNull,
        )
    } ?: throw IllegalArgumentException("Category response is missing categories.")

private fun Throwable.classificationMessage(fallback: String): String = when (this) {
    is AccountClientException -> when (code) {
        "VERSION_CONFLICT" -> "다른 변경이 먼저 저장됐어요. 최신 내용을 확인해 주세요."
        "ITEM_NOT_FOUND" -> "보관한 링크를 찾지 못했어요."
        "ITEM_DELETED" -> "이미 삭제된 링크예요."
        "RATE_LIMITED" -> "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
        "DEPENDENCY_UNAVAILABLE" -> "서버가 일시적으로 요청을 처리할 수 없어요."
        else -> fallback
    }

    else -> fallback
}

private fun formatClassificationCacheTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
