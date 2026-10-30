package com.linkvault.app

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.discovery.DiscoveryAvailability
import com.linkvault.app.discovery.DiscoveryCategory
import com.linkvault.app.discovery.DiscoveryCategoryReconfirmation
import com.linkvault.app.discovery.DiscoveryFilterInput
import com.linkvault.app.discovery.DiscoveryQueryPreparation
import com.linkvault.app.discovery.DiscoverySource
import com.linkvault.app.discovery.DiscoveryUiState
import com.linkvault.app.discovery.DiscoveryViewModel
import com.linkvault.app.discovery.SearchFilterSheet
import com.linkvault.app.discovery.SearchResultCard
import com.linkvault.app.discovery.prepareDiscoveryQuery
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxState
import com.linkvault.app.storage.VaultDatabase
import com.linkvault.app.ui.VaultTheme
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DiscoveryPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filterFooterStaysVisibleAndOnlyValidApplyDismisses() {
        var applyCount = 0
        var dismissCount = 0

        compose.setContent {
            var state by remember { mutableStateOf(filterTestState()) }
            VaultTheme {
                SearchFilterSheet(
                    state = state,
                    onDismiss = { dismissCount += 1 },
                    onSubmit = {
                        applyCount += 1
                        val issues = when (
                            val preparation = prepareDiscoveryQuery(
                                state.filters,
                                ZoneId.of(state.displayZoneId),
                            )
                        ) {
                            is DiscoveryQueryPreparation.Invalid -> preparation.issues
                            is DiscoveryQueryPreparation.Valid -> emptyList()
                        }
                        state = state.copy(filterIssues = issues)
                    },
                    onCategorySelected = { categoryId ->
                        state = state.copy(
                            filters = state.filters.copy(
                                categoryId = categoryId,
                                unclassified = false,
                            ),
                            filterIssues = emptyList(),
                        )
                    },
                    onUnclassifiedChange = { enabled ->
                        state = state.copy(
                            filters = state.filters.copy(
                                categoryId = if (enabled) null else state.filters.categoryId,
                                unclassified = enabled,
                            ),
                            filterIssues = emptyList(),
                        )
                    },
                    onSourceSelected = { source ->
                        state = state.copy(
                            filters = state.filters.copy(source = source),
                            filterIssues = emptyList(),
                        )
                    },
                    onDateFromChange = { value ->
                        state = state.copy(
                            filters = state.filters.copy(dateFrom = value),
                            filterIssues = emptyList(),
                        )
                    },
                    onDateToChange = { value ->
                        state = state.copy(
                            filters = state.filters.copy(dateTo = value),
                            filterIssues = emptyList(),
                        )
                    },
                    onAliasesChange = { checked ->
                        state = state.copy(filters = state.filters.copy(aliases = checked))
                    },
                    onNeedsCuesChange = { checked ->
                        state = state.copy(filters = state.filters.copy(needsCues = checked))
                    },
                    onClearFilters = {
                        state = state.copy(
                            filters = DiscoveryFilterInput(query = state.filters.query),
                            filterIssues = emptyList(),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("search-filter-apply", useUnmergedTree = true)
            .assertIsDisplayed()
        captureFilterSheet()

        compose.onNodeWithTag("search-date-from", useUnmergedTree = true)
            .performScrollTo()
            .performTextReplacement("invalid-date")
        compose.onNodeWithTag("search-filter-apply", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("날짜는 YYYY-MM-DD 형식으로 입력해 주세요.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, applyCount)
            assertEquals(0, dismissCount)
        }

        compose.onNodeWithTag("search-date-from", useUnmergedTree = true)
            .performScrollTo()
            .performTextReplacement("2026-09-01")
        compose.onNodeWithTag("search-filter-apply", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(2, applyCount)
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun categoryDiscardRefusalKeepsRequestReconfirmationAndUnrelatedDraft() = runBlocking {
        for (state in listOf(OutboxState.RUNNING, OutboxState.SAVED)) {
            for (edit in listOf(false, true)) {
                withCategoryRequest(state) { viewModel, entry, database ->
                    val reconfirmation = viewModel.uiState.value.categoryReconfirmation
                    withContext(Dispatchers.Main) {
                        if (edit) {
                            viewModel.discardAndEditCategoryRequest(entry.requestId)
                        } else {
                            viewModel.discardCategoryRequest(entry.requestId)
                        }
                    }
                    val result = withTimeout(5_000L) {
                        viewModel.uiState.first { it.categoryMutationError != null }
                    }

                    assertEquals(entry, database.outboxDao().findByRequestId(entry.requestId))
                    assertEquals(reconfirmation, result.categoryReconfirmation)
                    assertEquals("작성 중인 다른 이름", result.createCategoryName)
                    assertEquals(listOf(entry.requestId), result.categoryOutboxEntries.map { it.requestId })
                    assertNull(result.categoryMutationNotice)
                    assertTrue(result.categoryMutationError!!.contains("버리지 못했어요"))
                }
            }
        }
    }

    @Test
    fun categoryDiscardSuccessExplainsThatServerChangesAreNotUndone() = runBlocking {
        withCategoryRequest(OutboxState.FAILED) { viewModel, entry, database ->
            withContext(Dispatchers.Main) { viewModel.discardCategoryRequest(entry.requestId) }
            val result = withTimeout(5_000L) {
                viewModel.uiState.first { it.categoryMutationNotice != null }
            }

            assertNull(database.outboxDao().findByRequestId(entry.requestId))
            assertNull(result.categoryMutationError)
            assertEquals("작성 중인 다른 이름", result.createCategoryName)
            assertTrue(result.categoryMutationNotice!!.contains("서버에 완료된 변경은 되돌리지 않았어요"))
        }
    }

    private suspend fun withCategoryRequest(
        state: OutboxState,
        block: suspend (DiscoveryViewModel, OutboxEntry, VaultDatabase) -> Unit,
    ) {
        val fixture = CaptureCompletionFixture()
        val owner = fixture.ownerId
        val database = fixture.database
        val client = fixture.client
        val store = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val entry = OutboxEntry(
                requestId = UUID.randomUUID().toString(),
                ownerId = owner,
                method = "POST",
                path = "/categories",
                payloadJson = "{\"name\":\"이전 요청 이름\"}",
                state = state,
                attemptCount = 1,
                createdAt = 1_000L,
                expiresAt = 90_000L,
                nextAttemptAt = 1_000L,
                resultJson = if (state == OutboxState.SAVED) "{}" else null,
                leaseUntil = if (state == OutboxState.RUNNING) 30_000L else null,
            )
            database.outboxDao().insertImmutable(entry)
            val viewModel = withContext(Dispatchers.Main) {
                val model = ViewModelProvider(
                    store,
                    DiscoveryViewModel.factory(
                        client,
                        fixture.outbox,
                        owner,
                        client.sessionState.value.generation,
                        true,
                    ),
                )[DiscoveryViewModel::class.java]
                // Freeze the rendered queue before a worker's running/saved transition, without
                // starting live category/search requests or exposing production test-only APIs.
                DiscoveryViewModel::class.java.getDeclaredField("isVisible").apply {
                    isAccessible = true
                    setBoolean(model, true)
                }
                DiscoveryViewModel::class.java.getDeclaredField("screenScope").apply {
                    isAccessible = true
                    set(model, scope)
                }
                @Suppress("UNCHECKED_CAST")
                val uiState = DiscoveryViewModel::class.java.getDeclaredField("mutableUiState").let { field ->
                    field.isAccessible = true
                    field.get(model) as MutableStateFlow<DiscoveryUiState>
                }
                uiState.value = model.uiState.value.copy(
                    createCategoryName = "작성 중인 다른 이름",
                    categoryOutboxEntries = listOf(entry.copy(state = OutboxState.FAILED)),
                    categoryReconfirmation = DiscoveryCategoryReconfirmation(
                        entry.requestId,
                        entry.method,
                        entry.path,
                        entry.payloadJson,
                        "이전 요청 이름",
                    ),
                )
                model
            }
            block(viewModel, entry, database)
        } finally {
            withContext(Dispatchers.Main) {
                scope.cancel()
                store.clear()
            }
            fixture.close()
        }
    }

    private fun captureFilterSheet() {
        val bitmap = compose.onNodeWithTag("search-filter-sheet", useUnmergedTree = true)
            .captureToImage()
            .asAndroidBitmap()
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-v2-filters.png",
        )
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun filterTestState(): DiscoveryUiState = DiscoveryUiState(
        availability = DiscoveryAvailability.Ready,
        displayZoneId = "Asia/Seoul",
        filters = DiscoveryFilterInput(source = DiscoverySource.ALL),
        categories = (1..18).map { index ->
            DiscoveryCategory(
                id = "test-category-$index",
                name = "테스트 분류 $index",
                kind = "custom",
                itemCount = index,
            )
        },
        categoryCount = 18,
        unclassifiedCount = 7,
        isSearchLoading = false,
        isCategoriesLoading = false,
    )
}

class SearchCardQualityTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        title: String? = "공유한 제주 여행 글",
        note: String? = null,
        content: String? = null,
        created: String? = "2026-09-01T12:00:00Z",
        updated: String? = "2026-10-28T12:00:00Z",
    ) {
        compose.setContent {
            VaultTheme {
                Box(Modifier.width(280.dp)) {
                    SearchResultCard(
                        item = LibraryItemSummary(
                            id = "quality",
                            version = 1,
                            url = "https://example.com/post",
                            displayTitle = title,
                            source = "instagram",
                            noteExcerpt = note,
                            contentExcerpt = content,
                            createdAt = created,
                            updatedAt = updated,
                        ),
                        onOpen = {},
                        onOpenAliasDisclosure = {},
                    )
                }
            }
        }
    }

    @Test
    fun collectedContentAppearsWithoutWritingAMemo() {
        show(content = "가을에 제주여행 가고 싶은 사람, 여행 주간 안내")
        compose.onNodeWithText("가을에 제주여행 가고 싶은 사람, 여행 주간 안내")
            .assertIsDisplayed()
    }

    @Test
    fun manualMemoTakesPrecedenceOverCollectedPreview() {
        show(note = "다음 주에 예약 확인", content = "수집한 설명")
        compose.onNodeWithText("다음 주에 예약 확인").assertIsDisplayed()
        compose.onNodeWithText("수집한 설명").assertDoesNotExist()
    }

    @Test
    fun blankMemoUsesContentAndLongPreviewStaysWithinTwoLines() {
        show(note = "   ", content = "제주 여행에서 다시 볼 숙소와 맛집 안내 ".repeat(12))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("search-item-preview-quality", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertTrue(layouts.single().lineCount <= 2)
        assertTrue(layouts.single().isLineEllipsized(layouts.single().lineCount - 1))
    }

    @Test
    fun longTitleUsesSameCompactTwoLinePresentationAsLibrary() {
        val title = "제주도에서 찾아본 조용한 숙소와 여행 코스 ".repeat(10)
        show(title = title)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("search-item-title-quality", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertTrue(layouts.single().lineCount <= 2)
        assertTrue(layouts.single().layoutInput.text.text.length <= 60)
    }

    @Test
    fun savedDateDoesNotChangeToMetadataUpdateDate() {
        show()
        compose.onNodeWithText("저장 · 2026.09.01").assertIsDisplayed()
        compose.onNodeWithText("저장 · 2026.10.28").assertDoesNotExist()
    }

    @Test
    fun absentContentAndDateDoNotInventAPreviewOrDate() {
        show(title = null, note = "", content = null, created = null)
        compose.onNodeWithTag("search-item-preview-quality", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("저장 · 2026.10.28").assertDoesNotExist()
        compose.onNodeWithText("https://example.com/post").assertIsDisplayed()
    }
}
