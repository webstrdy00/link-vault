package com.linkvault.app.discovery

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.library.codePointLength
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

@Composable
fun DiscoveryScreen(
    client: AccountClient,
    outbox: OutboxRepository,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val sessionState by client.sessionState.collectAsState()
    LaunchedEffect(client, client.isConfigured, sessionState.initialized) {
        if (client.isConfigured && !sessionState.initialized) {
            try {
                client.restoreAccount()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // AccountClient publishes the completed restoration state.
            }
        }
    }
    val viewModelKey = remember(
        client,
        sessionState.ownerId,
        sessionState.generation,
        sessionState.initialized,
    ) {
        "discovery:${System.identityHashCode(client)}:" +
            "${sessionState.ownerId ?: "signed-out"}:${sessionState.generation}:" +
            sessionState.initialized
    }
    val factory = remember(
        client,
        outbox,
        sessionState.ownerId,
        sessionState.generation,
        sessionState.initialized,
    ) {
        DiscoveryViewModel.factory(
            client = client,
            outbox = outbox,
            ownerId = sessionState.ownerId,
            sessionGeneration = sessionState.generation,
            sessionInitialized = sessionState.initialized,
        )
    }
    val discoveryViewModel: DiscoveryViewModel = viewModel(
        key = viewModelKey,
        factory = factory,
    )
    val state by discoveryViewModel.uiState.collectAsState()
    LaunchedEffect(discoveryViewModel, sessionState) {
        discoveryViewModel.syncSessionState(sessionState)
    }
    DisposableEffect(discoveryViewModel) {
        discoveryViewModel.setVisible(true)
        onDispose {
            discoveryViewModel.setVisible(false)
        }
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
            TextButton(onClick = onBack) { Text("뒤로") }
            Text(
                text = "검색과 분류",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = discoveryViewModel::refreshCategories,
                enabled = state.availability is DiscoveryAvailability.Ready &&
                    !state.isCategoriesLoading,
            ) {
                Text("분류 새로고침")
            }
        }
        HorizontalDivider()

        when (val availability = state.availability) {
            DiscoveryAvailability.Checking -> DiscoveryLoading("로그인 계정을 확인하고 있어요.")
            is DiscoveryAvailability.SignInRequired -> Text(
                text = availability.message,
                color = MaterialTheme.colorScheme.error,
            )
            DiscoveryAvailability.Ready -> {
                state.sessionRecoveryMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SearchControls(
                    state = state,
                    onQueryChange = discoveryViewModel::updateQuery,
                    onSubmit = discoveryViewModel::submitSearch,
                    onCategorySelected = discoveryViewModel::selectCategory,
                    onUnclassifiedChange = discoveryViewModel::setUnclassified,
                    onSourceSelected = discoveryViewModel::selectSource,
                    onDateFromChange = discoveryViewModel::updateDateFrom,
                    onDateToChange = discoveryViewModel::updateDateTo,
                    onAliasesChange = discoveryViewModel::setAliases,
                    onNeedsCuesChange = discoveryViewModel::setNeedsCues,
                    onClearFilters = discoveryViewModel::clearFiltersKeepingQuery,
                    onShowNeedsCues = discoveryViewModel::showItemsNeedingCues,
                )
                HorizontalDivider()
                SearchResults(
                    state = state,
                    onOpenItem = onOpenItem,
                    onOpenAliasDisclosure = discoveryViewModel::openAliasDisclosure,
                    onRetry = discoveryViewModel::retrySearch,
                    onLoadMore = discoveryViewModel::loadMore,
                    onClearFilters = discoveryViewModel::clearFiltersKeepingQuery,
                    onShowNeedsCues = discoveryViewModel::showItemsNeedingCues,
                )
                HorizontalDivider()
                CategoryManagement(
                    state = state,
                    onCreateNameChange = discoveryViewModel::updateCreateCategoryName,
                    onCreate = discoveryViewModel::createCategory,
                    onRename = discoveryViewModel::beginRenameCategory,
                    onDelete = discoveryViewModel::requestDeleteCategory,
                    onRefresh = discoveryViewModel::refreshCategories,
                    onRetryRequest = discoveryViewModel::retryCategoryRequest,
                    onDiscardRequest = discoveryViewModel::discardCategoryRequest,
                    onDiscardAndEditRequest = discoveryViewModel::discardAndEditCategoryRequest,
                    onReviewRequest = discoveryViewModel::reviewCategoryRequest,
                )
            }
        }
    }

    RenameCategoryDialog(
        state = state,
        onNameChange = discoveryViewModel::updateRenameCategoryName,
        onConfirm = discoveryViewModel::renameCategory,
        onDismiss = discoveryViewModel::cancelRenameCategory,
    )
    DeleteCategoryDialog(
        state = state,
        onConfirm = discoveryViewModel::confirmDeleteCategory,
        onDismiss = discoveryViewModel::cancelDeleteCategory,
    )
    CategoryReconfirmationDialog(
        state = state,
        onConfirm = discoveryViewModel::confirmCategoryReconfirmation,
        onDismiss = discoveryViewModel::cancelCategoryReconfirmation,
    )
    AliasDisclosureDialog(
        disclosure = state.aliasDisclosure,
        onRetry = discoveryViewModel::openAliasDisclosure,
        onDismiss = discoveryViewModel::closeAliasDisclosure,
    )
}

@Composable
private fun SearchControls(
    state: DiscoveryUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    onUnclassifiedChange: (Boolean) -> Unit,
    onSourceSelected: (DiscoverySource) -> Unit,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit,
    onAliasesChange: (Boolean) -> Unit,
    onNeedsCuesChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onShowNeedsCues: () -> Unit,
) {
    val filters = state.filters
    val queryIssues = state.filterIssues.filter { it.field == DiscoveryFilterField.QUERY }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "서버에서 찾기",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("검색과 필터는 서버에서 함께 적용하며 서버가 보낸 순서 그대로 표시합니다.")
        OutlinedTextField(
            value = filters.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search-query"),
            label = { Text("검색어") },
            supportingText = {
                Text("${filters.query.codePointLength()} / $DISCOVERY_QUERY_MAX_CODE_POINTS · 최대 ${DISCOVERY_QUERY_MAX_WORDS}개 단어")
            },
            isError = queryIssues.isNotEmpty(),
            singleLine = true,
        )

        Text("분류", fontWeight = FontWeight.SemiBold)
        RadioChoice(
            selected = filters.categoryId == null && !filters.unclassified,
            label = "전체 분류",
            onClick = { onCategorySelected(null) },
        )
        RadioChoice(
            selected = filters.unclassified,
            label = "미분류 (${state.unclassifiedCount})",
            onClick = { onUnclassifiedChange(true) },
        )
        state.categories.forEach { category ->
            RadioChoice(
                selected = filters.categoryId == category.id,
                label = "${category.name} (${category.itemCount})",
                onClick = { onCategorySelected(category.id) },
            )
        }

        Text("출처", fontWeight = FontWeight.SemiBold)
        DiscoverySource.entries.forEach { source ->
            RadioChoice(
                selected = filters.source == source,
                label = source.displayName,
                onClick = { onSourceSelected(source) },
            )
        }

        Text("저장 날짜", fontWeight = FontWeight.SemiBold)
        Text(
            text = "기기 시간대: ${state.displayZoneId} · 입력 날짜의 시작부터 마지막 날짜 전체를 검색합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = filters.dateFrom,
            onValueChange = onDateFromChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search-date-from"),
            label = { Text("시작 날짜 YYYY-MM-DD") },
            isError = state.filterIssues.any {
                it.field == DiscoveryFilterField.DATE_FROM || it.field == DiscoveryFilterField.DATE_RANGE
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = filters.dateTo,
            onValueChange = onDateToChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search-date-to"),
            label = { Text("마지막 날짜 YYYY-MM-DD") },
            isError = state.filterIssues.any {
                it.field == DiscoveryFilterField.DATE_TO || it.field == DiscoveryFilterField.DATE_RANGE
            },
            singleLine = true,
        )

        ToggleRow(
            label = "검토된 별칭도 검색",
            checked = filters.aliases,
            onCheckedChange = onAliasesChange,
        )
        ToggleRow(
            label = "단서 보완이 필요한 항목만",
            checked = filters.needsCues,
            onCheckedChange = onNeedsCuesChange,
        )

        state.filterIssues.forEach { issue ->
            Text(issue.message, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search-submit"),
        ) {
            Text(if (filters.query.isBlank()) "최신 항목 보기" else "검색")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onClearFilters,
                modifier = Modifier.weight(1f),
            ) {
                Text("필터 지우기 · 검색어 유지")
            }
            OutlinedButton(
                onClick = onShowNeedsCues,
                modifier = Modifier.weight(1f),
            ) {
                Text("단서 보완")
            }
        }
    }
}

@Composable
private fun RadioChoice(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SearchResults(
    state: DiscoveryUiState,
    onOpenItem: (String) -> Unit,
    onOpenAliasDisclosure: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onClearFilters: () -> Unit,
    onShowNeedsCues: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag("search-results"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (state.appliedQuery?.query == null) "최신 저장 항목" else "서버 검색 결과",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        when {
            state.isSearchLoading && state.items.isEmpty() -> DiscoveryLoading("서버에서 항목을 찾고 있어요.")
            state.searchError != null && state.items.isEmpty() -> SearchFailure(state.searchError, onRetry)
            state.items.isEmpty() -> {
                Text("조건에 맞는 항목이 없어요.")
                OutlinedButton(onClick = onClearFilters, modifier = Modifier.fillMaxWidth()) {
                    Text("필터 지우기 · 검색어 유지")
                }
                OutlinedButton(onClick = onShowNeedsCues, modifier = Modifier.fillMaxWidth()) {
                    Text("단서 보완이 필요한 항목 보기")
                }
            }
            else -> {
                state.items.forEach { item ->
                    SearchResultCard(
                        item = item,
                        onOpen = { onOpenItem(item.id) },
                        onOpenAliasDisclosure = { onOpenAliasDisclosure(item.id) },
                    )
                }
                state.searchError?.let { SearchFailure(it, onRetry) }
                if (state.isSearchLoading) {
                    DiscoveryLoading("다음 결과를 불러오고 있어요.")
                } else if (state.hasMore && state.searchError == null) {
                    OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("더 불러오기")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: LibraryItemSummary,
    onOpen: () -> Unit,
    onOpenAliasDisclosure: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search-item-${item.id}")
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = item.displayTitle?.takeUnless(String::isBlank) ?: item.url,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (item.matchType == "alias") {
                Text(
                    text = "별칭으로 찾음",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item.noteExcerpt?.takeUnless(String::isBlank)?.let { excerpt ->
                Text("나중에 찾을 메모: $excerpt")
            }
            Text(
                text = "출처: ${sourceDisplayName(item.source)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.categoryRefs.isEmpty()) {
                Text("분류: 미분류", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    text = "분류: ${item.categoryRefs.mapNotNull { it.name }.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.cueLabel()?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.cueState == "limited" || item.cueState == "missing") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("항목 열기")
            }
            if (item.matchType == "alias") {
                OutlinedButton(
                    onClick = onOpenAliasDisclosure,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("일치 표현 확인")
                }
            }
        }
    }
}

@Composable
private fun SearchFailure(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Text("검색 결과 대신 기기의 전체 보관 캐시를 표시하지 않았어요.")
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("검색 다시 시도")
        }
    }
}

@Composable
private fun CategoryManagement(
    state: DiscoveryUiState,
    onCreateNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetryRequest: (String) -> Unit,
    onDiscardRequest: (String) -> Unit,
    onDiscardAndEditRequest: (String) -> Unit,
    onReviewRequest: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "분류 관리",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("직접 만든 분류는 최대 ${DISCOVERY_CUSTOM_CATEGORY_LIMIT}개까지 사용할 수 있어요.")
        if (state.isShowingCachedCategories) {
            Text(
                text = "최신이 아닐 수 있는 마지막 확인 분류 목록 · ${state.categoriesFetchedAt?.let(::formatCachedAt) ?: "동기화 시각 확인 불가"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text("오프라인 요청은 기기에만 보관되며 서버 반영으로 표시하지 않습니다.")
        }
        state.categoryError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("분류 다시 불러오기")
            }
        }

        OutlinedTextField(
            value = state.createCategoryName,
            onValueChange = onCreateNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category-name-field"),
            enabled = !state.isCategoryMutationInProgress,
            label = { Text("새 분류 이름") },
            supportingText = {
                Text("${state.createCategoryName.codePointLength()} / $DISCOVERY_CATEGORY_NAME_MAX_CODE_POINTS")
            },
            isError = state.createCategoryIssue != null,
            singleLine = true,
        )
        state.createCategoryIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onCreate,
            enabled = !state.isCategoryMutationInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category-create"),
        ) {
            Text("분류 만들기 요청 보관")
        }

        state.categoryMutationNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        state.categoryMutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.categoryOutboxEntries.isNotEmpty()) {
            Text("분류 변경 대기", fontWeight = FontWeight.Bold)
            state.categoryOutboxEntries.forEach { entry ->
                CategoryOutboxCard(
                    entry = entry,
                    isReviewLoading = state.categoryReviewRequestId == entry.requestId,
                    onRetry = { onRetryRequest(entry.requestId) },
                    onDiscard = { onDiscardRequest(entry.requestId) },
                    onDiscardAndEdit = { onDiscardAndEditRequest(entry.requestId) },
                    onReview = { onReviewRequest(entry.requestId) },
                )
            }
        }

        if (state.isCategoriesLoading && state.categories.isEmpty()) {
            DiscoveryLoading("서버에서 분류를 불러오고 있어요.")
        } else {
            Text("분류 ${state.categoryCount}개 · 미분류 항목 ${state.unclassifiedCount}개")
            state.categories.forEach { category ->
                CategoryCard(
                    category = category,
                    onRename = { onRename(category.id) },
                    onDelete = { onDelete(category.id) },
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: DiscoveryCategory,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("category-${category.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(category.name, fontWeight = FontWeight.SemiBold)
            Text("연결된 항목 ${category.itemCount}개")
            if (category.isSystem) {
                Text(
                    text = "기본 분류 · 시스템 코드 ${category.systemCode ?: "확인 중"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("기본 분류는 서버에서 보호하므로 이름 변경과 삭제를 제공하지 않습니다.")
            } else {
                Text("직접 만든 분류", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onRename,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("category-rename-${category.id}"),
                    ) {
                        Text("이름 변경")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("category-delete-${category.id}"),
                    ) {
                        Text("삭제")
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryOutboxCard(
    entry: OutboxEntry,
    isReviewLoading: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onDiscardAndEdit: () -> Unit,
    onReview: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(categoryOperationName(entry), fontWeight = FontWeight.SemiBold)
            Text(categoryQueueMessage(entry.state))
            if (entry.state == OutboxState.FAILED || entry.state == OutboxState.CONFLICT) {
                Text(entry.categoryFailureMessage(), color = MaterialTheme.colorScheme.error)
            }
            when (entry.state) {
                OutboxState.PENDING,
                OutboxState.WAITING_LOGIN,
                -> OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                    Text("대기 요청 버리기")
                }
                OutboxState.RETRY -> Text("같은 요청 ID와 내용으로 서버 재시도를 기다립니다.")
                OutboxState.RUNNING -> Unit
                OutboxState.SAVED -> Text("서버 반영 확인 중")
                OutboxState.EXPIRED -> {
                    Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                        Text("새 요청으로 다시 확인")
                    }
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                        Text("만료 요청 버리기")
                    }
                }
                OutboxState.CONFLICT -> {
                    Button(
                        onClick = onReview,
                        enabled = !isReviewLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isReviewLoading) "최신 분류 확인 중" else "최신 분류 확인")
                    }
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                        Text("충돌 요청 버리기")
                    }
                }
                OutboxState.FAILED -> {
                    if (
                        entry.errorCode == "IDEMPOTENCY_MISMATCH" ||
                        entry.errorCode == "CATEGORY_NAME_EXISTS" ||
                        entry.errorCode == "CATEGORY_LIMIT_REACHED"
                    ) {
                        Button(
                            onClick = onReview,
                            enabled = !isReviewLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (isReviewLoading) {
                                    "최신 분류 확인 중"
                                } else if (
                                    entry.errorCode == "CATEGORY_NAME_EXISTS" ||
                                    entry.errorCode == "CATEGORY_LIMIT_REACHED"
                                ) {
                                    "최신 분류 확인 후 이름 수정"
                                } else {
                                    "최신 분류 확인 후 새 요청"
                                },
                            )
                        }
                    } else if (entry.isCategoryRequestRetryableInUi()) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text("같은 요청 다시 시도")
                        }
                    }
                    if (
                        (entry.method == "POST" || entry.method == "PATCH") &&
                        entry.errorCode != "CATEGORY_NAME_EXISTS" &&
                        entry.errorCode != "CATEGORY_LIMIT_REACHED" &&
                        entry.errorCode != "IDEMPOTENCY_MISMATCH"
                    ) {
                        OutlinedButton(onClick = onDiscardAndEdit, modifier = Modifier.fillMaxWidth()) {
                            Text("요청 버리고 이름 수정")
                        }
                    } else {
                        OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                            Text("실패 요청 버리기")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameCategoryDialog(
    state: DiscoveryUiState,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val category = state.renameCategory ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("분류 이름 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("서버가 이름의 정규화와 중복 여부를 최종 확인합니다.")
                OutlinedTextField(
                    value = state.renameCategoryName,
                    onValueChange = onNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("category-rename-name-${category.id}"),
                    label = { Text("새 이름") },
                    supportingText = {
                        Text("${state.renameCategoryName.codePointLength()} / $DISCOVERY_CATEGORY_NAME_MAX_CODE_POINTS")
                    },
                    isError = state.renameCategoryIssue != null,
                    singleLine = true,
                )
                state.renameCategoryIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.isCategoryMutationInProgress,
            ) {
                Text("이름 변경 요청 보관")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun DeleteCategoryDialog(
    state: DiscoveryUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val category = state.deleteCategory ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${category.name} 분류 삭제") },
        text = {
            Text("분류와 ${category.itemCount}개 링크의 연결만 삭제합니다. 보관한 링크 자체는 삭제하지 않아요.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.isCategoryMutationInProgress,
            ) {
                Text("분류만 삭제 요청 보관")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun CategoryReconfirmationDialog(
    state: DiscoveryUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reconfirmation = state.categoryReconfirmation ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 분류 요청 확인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("확인할 작업: ${reconfirmation.description}")
                Text("기존 요청을 자동 재사용하지 않습니다. 확인하면 같은 내용을 새 요청 ID로 기기에 보관합니다.")
                if (reconfirmation.method == "DELETE") {
                    Text("분류 연결만 삭제하며 링크 자체는 삭제하지 않습니다.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.isCategoryMutationInProgress,
            ) {
                Text("확인하고 새 요청 만들기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun AliasDisclosureDialog(
    disclosure: DiscoveryAliasDisclosure,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (disclosure is DiscoveryAliasDisclosure.None) return
    val itemId = when (disclosure) {
        is DiscoveryAliasDisclosure.Loading -> disclosure.itemId
        is DiscoveryAliasDisclosure.Loaded -> disclosure.detail.itemId
        is DiscoveryAliasDisclosure.Failed -> disclosure.itemId
        DiscoveryAliasDisclosure.None -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일치 표현 확인") },
        text = {
            when (disclosure) {
                is DiscoveryAliasDisclosure.Loading -> DiscoveryLoading("서버에서 실제 일치 표현을 확인하고 있어요.")
                is DiscoveryAliasDisclosure.Failed -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(disclosure.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { onRetry(itemId) }) { Text("다시 확인") }
                }
                is DiscoveryAliasDisclosure.Loaded -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("서버가 현재 검색어와 상세 항목을 비교해 반환한 표현입니다.")
                    if (disclosure.detail.explanations.isEmpty()) {
                        Text("현재 서버 응답에는 공개할 일치 표현이 없어요.")
                    } else {
                        disclosure.detail.explanations.forEach { explanation ->
                            Text(
                                text = "${aliasFieldDisplayName(explanation.field)}: ${explanation.expression}",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                DiscoveryAliasDisclosure.None -> Unit
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun DiscoveryLoading(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp))
        Text(message)
    }
}

private fun sourceDisplayName(source: String?): String = when (source) {
    "instagram" -> "Instagram"
    "threads" -> "Threads"
    "naver_blog" -> "네이버 블로그"
    "other" -> "기타"
    else -> "확인 중"
}

private fun aliasFieldDisplayName(field: String): String = when (field) {
    "user_title" -> "사용자 제목"
    "fetched_title" -> "가져온 제목"
    "shared_text" -> "공유 텍스트"
    "description" -> "설명"
    "body_text" -> "본문"
    "note" -> "나중에 찾을 메모"
    "ocr_text" -> "이미지 글자"
    "categories" -> "분류"
    "url" -> "URL"
    else -> "일치 표현"
}

private fun categoryOperationName(entry: OutboxEntry): String = when (entry.method) {
    "POST" -> "분류 만들기"
    "PATCH" -> "분류 이름 변경"
    "DELETE" -> "분류 삭제"
    else -> "분류 변경"
}

private fun categoryQueueMessage(state: OutboxState): String = when (state) {
    OutboxState.PENDING -> "전송 대기 중 · 서버 확인 전"
    OutboxState.RUNNING -> "서버에 전송 중"
    OutboxState.RETRY -> "연결 또는 서버 안내 시각을 기다린 뒤 재전송"
    OutboxState.WAITING_LOGIN -> "로그인 후 전송"
    OutboxState.FAILED -> "서버 반영 실패"
    OutboxState.CONFLICT -> "서버 최신 상태와 충돌"
    OutboxState.EXPIRED -> "요청 만료 · 사용자 확인 필요"
    OutboxState.SAVED -> "서버 저장 완료 · 영수증 확인 중"
}

private fun formatCachedAt(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
