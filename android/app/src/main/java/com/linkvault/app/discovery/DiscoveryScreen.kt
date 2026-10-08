package com.linkvault.app.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.library.codePointLength
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxState
import java.text.DateFormat
import java.time.ZoneId
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
    var showCategoryManagement by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "검색",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { showCategoryManagement = true },
                enabled = state.availability is DiscoveryAvailability.Ready,
            ) {
                Text("분류 관리")
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
                )
                HorizontalDivider()
                SearchResults(
                    state = state,
                    onOpenItem = onOpenItem,
                    onOpenAliasDisclosure = discoveryViewModel::openAliasDisclosure,
                    onRetry = discoveryViewModel::retrySearch,
                    onLoadMore = discoveryViewModel::loadMore,
                )
            }
        }
    }

    if (showCategoryManagement && state.availability is DiscoveryAvailability.Ready) {
        CategoryManagementSheet(
            state = state,
            onDismiss = { showCategoryManagement = false },
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
) {
    val filters = state.filters
    val queryIssues = state.filterIssues.filter { it.field == DiscoveryFilterField.QUERY }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val searchShape = RoundedCornerShape(9.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(
                    width = 1.dp,
                    color = if (queryIssues.isEmpty()) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    shape = searchShape,
                ),
            shape = searchShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = filters.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 4.dp)
                        .testTag("search-query")
                        .semantics {
                            contentDescription = "검색어"
                            if (queryIssues.isNotEmpty()) {
                                error(queryIssues.joinToString { it.message })
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSubmit()
                            keyboardController?.hide()
                        },
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (filters.query.isBlank()) {
                                Text(
                                    text = "제목, URL, 메모",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                IconButton(
                    onClick = {
                        onSubmit()
                        keyboardController?.hide()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("search-submit"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = if (filters.query.isBlank()) {
                            "최신 항목 보기"
                        } else {
                            "검색"
                        },
                    )
                }
            }
        }
        queryIssues.forEach { issue ->
            Text(
                text = issue.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (
            queryIssues.isEmpty() &&
            filters.query.codePointLength() > DISCOVERY_QUERY_MAX_CODE_POINTS * 3 / 4
        ) {
            Text(
                text = "${filters.query.codePointLength()} / $DISCOVERY_QUERY_MAX_CODE_POINTS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { showFilters = true },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                val count = filters.activeFilterCount()
                Text(
                    text = if (count == 0) "필터" else "필터 $count",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        SelectedFilters(
            state = state,
            onCategorySelected = onCategorySelected,
            onUnclassifiedChange = onUnclassifiedChange,
            onSourceSelected = onSourceSelected,
            onDateFromChange = onDateFromChange,
            onDateToChange = onDateToChange,
            onAliasesChange = onAliasesChange,
            onNeedsCuesChange = onNeedsCuesChange,
        )
        if (filters.activeFilterCount() > 0) {
            TextButton(onClick = onClearFilters) {
                Text("필터 지우기 · 검색어 유지")
            }
        }
    }

    if (showFilters) {
        SearchFilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onSubmit = onSubmit,
            onCategorySelected = onCategorySelected,
            onUnclassifiedChange = onUnclassifiedChange,
            onSourceSelected = onSourceSelected,
            onDateFromChange = onDateFromChange,
            onDateToChange = onDateToChange,
            onAliasesChange = onAliasesChange,
            onNeedsCuesChange = onNeedsCuesChange,
            onClearFilters = {
                onClearFilters()
                showFilters = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterSheet(
    state: DiscoveryUiState,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    onUnclassifiedChange: (Boolean) -> Unit,
    onSourceSelected: (DiscoverySource) -> Unit,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit,
    onAliasesChange: (Boolean) -> Unit,
    onNeedsCuesChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
) {
    val filters = state.filters
    val dateIssues = state.filterIssues.filter { issue ->
        issue.field == DiscoveryFilterField.DATE_FROM ||
            issue.field == DiscoveryFilterField.DATE_TO ||
            issue.field == DiscoveryFilterField.DATE_RANGE
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .testTag("search-filter-sheet"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "검색 필터",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "선택한 조건은 ‘필터 적용’을 누른 뒤 결과에 반영됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("분류", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChoice(
                        selected = filters.categoryId == null && !filters.unclassified,
                        label = "전체 분류",
                        onClick = { onCategorySelected(null) },
                    )
                    FilterChoice(
                        selected = filters.unclassified,
                        label = "미분류 (${state.unclassifiedCount})",
                        onClick = { onUnclassifiedChange(true) },
                    )
                    state.categories.forEach { category ->
                        FilterChoice(
                            selected = filters.categoryId == category.id,
                            label = "${category.name} (${category.itemCount})",
                            onClick = { onCategorySelected(category.id) },
                        )
                    }
                }

                HorizontalDivider()
                Text("출처", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DiscoverySource.entries.forEach { source ->
                        FilterChoice(
                            selected = filters.source == source,
                            label = source.displayName,
                            onClick = { onSourceSelected(source) },
                        )
                    }
                }

                HorizontalDivider()
                Text("저장 날짜", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "기기 시간대: ${state.displayZoneId} · 마지막 날짜 전체를 포함합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = filters.dateFrom,
                        onValueChange = onDateFromChange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search-date-from"),
                        label = { Text("시작") },
                        placeholder = { Text("YYYY-MM-DD") },
                        isError = state.filterIssues.any {
                            it.field == DiscoveryFilterField.DATE_FROM ||
                                it.field == DiscoveryFilterField.DATE_RANGE
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = filters.dateTo,
                        onValueChange = onDateToChange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search-date-to"),
                        label = { Text("마지막") },
                        placeholder = { Text("YYYY-MM-DD") },
                        isError = state.filterIssues.any {
                            it.field == DiscoveryFilterField.DATE_TO ||
                                it.field == DiscoveryFilterField.DATE_RANGE
                        },
                        singleLine = true,
                    )
                }
                dateIssues.forEach { issue ->
                    Text(
                        text = issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()
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

                state.filterIssues.filterNot(dateIssues::contains).forEach { issue ->
                    Text(
                        text = issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClearFilters,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("필터 지우기")
                }
                Button(
                    onClick = {
                        onSubmit()
                        if (state.filtersAreValid()) onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("search-filter-apply"),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("필터 적용")
                }
            }
        }
    }
}

@Composable
private fun SelectedFilters(
    state: DiscoveryUiState,
    onCategorySelected: (String?) -> Unit,
    onUnclassifiedChange: (Boolean) -> Unit,
    onSourceSelected: (DiscoverySource) -> Unit,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit,
    onAliasesChange: (Boolean) -> Unit,
    onNeedsCuesChange: (Boolean) -> Unit,
) {
    val filters = state.filters
    if (filters.activeFilterCount() == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "선택한 필터",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                filters.unclassified -> RemovableFilterChip(
                    label = "분류 · 미분류",
                    onRemove = { onUnclassifiedChange(false) },
                )
                filters.categoryId != null -> RemovableFilterChip(
                    label = "분류 · ${
                        state.categories.firstOrNull { it.id == filters.categoryId }?.name
                            ?: "선택한 분류"
                    }",
                    onRemove = { onCategorySelected(null) },
                )
            }
            if (filters.source != DiscoverySource.ALL) {
                RemovableFilterChip(
                    label = "출처 · ${filters.source.displayName}",
                    onRemove = { onSourceSelected(DiscoverySource.ALL) },
                )
            }
            if (filters.dateFrom.isNotBlank()) {
                RemovableFilterChip(
                    label = "시작 · ${filters.dateFrom}",
                    onRemove = { onDateFromChange("") },
                )
            }
            if (filters.dateTo.isNotBlank()) {
                RemovableFilterChip(
                    label = "마지막 · ${filters.dateTo}",
                    onRemove = { onDateToChange("") },
                )
            }
            if (!filters.aliases) {
                RemovableFilterChip(
                    label = "별칭 제외",
                    onRemove = { onAliasesChange(true) },
                )
            }
            if (filters.needsCues) {
                RemovableFilterChip(
                    label = "단서 보완 필요",
                    onRemove = { onNeedsCuesChange(false) },
                )
            }
        }
    }
}

@Composable
private fun RemovableFilterChip(
    label: String,
    onRemove: () -> Unit,
) {
    AssistChip(
        onClick = onRemove,
        label = { Text(label) },
        modifier = Modifier.semantics {
            contentDescription = "$label 지우기"
        },
        shape = RoundedCornerShape(7.dp),
    )
}

@Composable
private fun FilterChoice(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(7.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun SearchResults(
    state: DiscoveryUiState,
    onOpenItem: (String) -> Unit,
    onOpenAliasDisclosure: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag("search-results"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (state.appliedQuery.isLatestItemsQuery()) "최신 저장 항목" else "검색 결과",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.hasUnappliedSearchChanges()) {
            Text(
                text = "지금 보이는 결과에는 위 변경사항이 아직 적용되지 않았어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            state.isSearchLoading && state.items.isEmpty() -> DiscoveryLoading("항목을 찾고 있어요.")
            state.searchError != null && state.items.isEmpty() -> SearchFailure(state.searchError, onRetry)
            state.items.isEmpty() -> {
                Text("조건에 맞는 항목이 없어요.")
                Text(
                    text = if (state.appliedQuery.isLatestItemsQuery()) {
                        "링크를 보관하면 제목과 메모로 다시 찾을 수 있어요."
                    } else {
                        "검색어를 바꾸거나 선택한 필터를 확인해 보세요."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Column {
                    state.items.forEachIndexed { index, item ->
                        SearchResultCard(
                            item = item,
                            onOpen = { onOpenItem(item.id) },
                            onOpenAliasDisclosure = { onOpenAliasDisclosure(item.id) },
                        )
                        if (index < state.items.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search-item-${item.id}")
            .clickable(onClick = onOpen)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "출처 · ${sourceDisplayName(item.source)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = item.displayTitle?.takeUnless(String::isBlank) ?: item.url,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        item.noteExcerpt?.takeUnless(String::isBlank)?.let { excerpt ->
            Text(
                text = excerpt,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.categoryRefs.isEmpty()) {
            Text(
                text = "미분류",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = item.categoryRefs.mapNotNull { it.name }.joinToString(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.cueLabel()?.let { label ->
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (item.cueState == "limited" || item.cueState == "missing") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (item.matchType == "alias") {
            Text(
                text = "별칭으로 찾음",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(
                onClick = onOpenAliasDisclosure,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = "일치 표현 확인",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SearchFailure(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Text(
            text = "오류가 난 결과를 기기에 보관된 전체 항목으로 바꾸어 표시하지 않았어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("검색 다시 시도")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManagementSheet(
    state: DiscoveryUiState,
    onDismiss: () -> Unit,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "분류 관리",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 32.dp),
            ) {
                CategoryManagement(
                    state = state,
                    onCreateNameChange = onCreateNameChange,
                    onCreate = onCreate,
                    onRename = onRename,
                    onDelete = onDelete,
                    onRefresh = onRefresh,
                    onRetryRequest = onRetryRequest,
                    onDiscardRequest = onDiscardRequest,
                    onDiscardAndEditRequest = onDiscardAndEditRequest,
                    onReviewRequest = onReviewRequest,
                )
            }
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
        Text("직접 만든 분류는 최대 ${DISCOVERY_CUSTOM_CATEGORY_LIMIT}개까지 사용할 수 있어요.")
        OutlinedButton(
            onClick = onRefresh,
            enabled = !state.isCategoriesLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isCategoriesLoading) "분류 불러오는 중" else "분류 새로고침")
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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

private fun DiscoveryFilterInput.activeFilterCount(): Int =
    listOf(
        categoryId != null || unclassified,
        source != DiscoverySource.ALL,
        dateFrom.isNotBlank(),
        dateTo.isNotBlank(),
        !aliases,
        needsCues,
    ).count { it }

private fun DiscoveryUiState.filtersAreValid(): Boolean = runCatching {
    prepareDiscoveryQuery(filters, ZoneId.of(displayZoneId)) is DiscoveryQueryPreparation.Valid
}.getOrDefault(false)

private fun DiscoveryUiState.hasUnappliedSearchChanges(): Boolean {
    val applied = appliedQuery ?: return false
    val prepared = runCatching {
        prepareDiscoveryQuery(filters, ZoneId.of(displayZoneId))
    }.getOrNull()
    return prepared !is DiscoveryQueryPreparation.Valid ||
        prepared.snapshot.queryKey != applied.queryKey
}

private fun DiscoveryQuerySnapshot?.isLatestItemsQuery(): Boolean = this == null ||
    (
        query == null &&
            categoryId == null &&
            !unclassified &&
            source == DiscoverySource.ALL &&
            dateFromUtc == null &&
            dateToExclusiveUtc == null &&
            aliases &&
            !needsCues
        )
