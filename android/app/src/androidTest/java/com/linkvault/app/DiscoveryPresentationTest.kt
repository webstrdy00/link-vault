package com.linkvault.app

import android.graphics.Bitmap
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
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.discovery.DiscoveryAvailability
import com.linkvault.app.discovery.DiscoveryCategory
import com.linkvault.app.discovery.DiscoveryFilterInput
import com.linkvault.app.discovery.DiscoveryQueryPreparation
import com.linkvault.app.discovery.DiscoverySource
import com.linkvault.app.discovery.DiscoveryUiState
import com.linkvault.app.discovery.SearchFilterSheet
import com.linkvault.app.discovery.prepareDiscoveryQuery
import com.linkvault.app.ui.VaultTheme
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
