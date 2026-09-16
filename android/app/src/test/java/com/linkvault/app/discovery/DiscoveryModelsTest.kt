package com.linkvault.app.discovery

import com.linkvault.app.library.LibraryItemSummary
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiscoveryModelsTest {
    @Test
    fun deletionFence_removesPrivateResultsAndDisclosureWithoutChangingServerPagination() {
        val deleted = LibraryItemSummary("deleted", url = "https://example.test/deleted")
        val retained = LibraryItemSummary("retained", url = "https://example.test/retained")
        val state = DiscoveryUiState(
            availability = DiscoveryAvailability.Ready,
            displayZoneId = "UTC",
            items = listOf(deleted, retained),
            nextOffset = 20,
            hasMore = true,
            aliasDisclosure = DiscoveryAliasDisclosure.Loaded(
                DiscoveryAliasDetail(itemId = deleted.id, explanations = emptyList()),
            ),
        )
        val filtered = state.withoutDeletedItems(setOf(deleted.id))
        assertEquals(listOf(retained), filtered.items)
        assertEquals(DiscoveryAliasDisclosure.None, filtered.aliasDisclosure)
        assertEquals(20, filtered.nextOffset)
        assertTrue(filtered.hasMore)

        val otherDisclosure = state.copy(
            aliasDisclosure = DiscoveryAliasDisclosure.Loading(retained.id),
        ).withoutDeletedItems(setOf(deleted.id))
        assertEquals(DiscoveryAliasDisclosure.Loading(retained.id), otherDisclosure.aliasDisclosure)
    }

    @Test
    fun queryEncoding_preservesLiteralPercentAndUnderscoreWithoutPatternEscaping() {
        val snapshot = validSnapshot(
            DiscoveryFilterInput(query = "100%_ café"),
            ZoneId.of("UTC"),
        )

        assertEquals(
            "/items?q=100%25_%20caf%C3%A9&aliases=true&needs_cues=false&limit=20&offset=0",
            snapshot.itemsPath(),
        )
        assertEquals(
            "q=100%25_%20caf%C3%A9&aliases=true&needs_cues=false",
            snapshot.queryKey,
        )
    }

    @Test
    fun dates_useLocalStartOfDayAndExclusiveDayAfterUpperBound() {
        val snapshot = validSnapshot(
            DiscoveryFilterInput(
                dateFrom = "2026-03-08",
                dateTo = "2026-03-08",
            ),
            ZoneId.of("America/New_York"),
        )

        assertEquals("2026-03-08T05:00:00Z", snapshot.dateFromUtc)
        assertEquals("2026-03-09T04:00:00Z", snapshot.dateToExclusiveUtc)
        assertTrue(snapshot.itemsPath().contains("date_from=2026-03-08T05%3A00%3A00Z"))
        assertTrue(snapshot.itemsPath().contains("date_to=2026-03-09T04%3A00%3A00Z"))
    }

    @Test
    fun limits_andConflictingFilters_areRejectedBeforeRequestConstruction() {
        assertTrue(
            prepareDiscoveryQuery(
                DiscoveryFilterInput(query = "𐐷".repeat(DISCOVERY_QUERY_MAX_CODE_POINTS)),
            ) is DiscoveryQueryPreparation.Valid,
        )
        assertIssue(
            input = DiscoveryFilterInput(query = "𐐷".repeat(DISCOVERY_QUERY_MAX_CODE_POINTS + 1)),
            field = DiscoveryFilterField.QUERY,
        )
        assertTrue(
            prepareDiscoveryQuery(
                DiscoveryFilterInput(query = (1..DISCOVERY_QUERY_MAX_WORDS).joinToString(" ") { "w$it" }),
            ) is DiscoveryQueryPreparation.Valid,
        )
        assertIssue(
            input = DiscoveryFilterInput(
                query = (1..(DISCOVERY_QUERY_MAX_WORDS + 1)).joinToString(" ") { "w$it" },
            ),
            field = DiscoveryFilterField.QUERY,
        )
        assertIssue(
            input = DiscoveryFilterInput(categoryId = "category-id", unclassified = true),
            field = DiscoveryFilterField.CATEGORY,
        )
        assertIssue(
            input = DiscoveryFilterInput(dateFrom = "2026-09-14", dateTo = "2026-09-13"),
            field = DiscoveryFilterField.DATE_RANGE,
        )

        val snapshot = validSnapshot(DiscoveryFilterInput(), ZoneId.of("UTC"))
        assertIllegalArgument { snapshot.itemsPath(limit = 0) }
        assertIllegalArgument { snapshot.itemsPath(limit = DISCOVERY_PAGE_LIMIT_MAX + 1) }
        assertIllegalArgument { snapshot.itemsPath(offset = -1) }
    }

    @Test
    fun stableQueryKey_excludesPaginationAndRejectsStaleGenerationOrOwner() {
        val first = validSnapshot(
            DiscoveryFilterInput(
                query = "여행 숙소",
                source = DiscoverySource.NAVER_BLOG,
                aliases = false,
                needsCues = true,
            ),
            ZoneId.of("Asia/Seoul"),
        )
        val sameIntent = validSnapshot(
            DiscoveryFilterInput(
                query = "여행 숙소",
                source = DiscoverySource.NAVER_BLOG,
                aliases = false,
                needsCues = true,
            ),
            ZoneId.of("Asia/Seoul"),
        )
        val changedIntent = validSnapshot(
            DiscoveryFilterInput(
                query = "여행 숙소",
                source = DiscoverySource.THREADS,
                aliases = false,
                needsCues = true,
            ),
            ZoneId.of("Asia/Seoul"),
        )

        assertEquals(first.queryKey, sameIntent.queryKey)
        assertNotEquals(first.itemsPath(offset = 0), first.itemsPath(offset = 20))
        assertFalse(first.queryKey.contains("offset="))
        assertNotEquals(first.queryKey, changedIntent.queryKey)

        val token = DiscoverySearchToken(
            ownerId = "owner-a",
            generation = 7,
            queryKey = first.queryKey,
            offset = 20,
        )
        assertTrue(isCurrentDiscoveryResponse(token, "owner-a", 7, first.queryKey))
        assertFalse(isCurrentDiscoveryResponse(token, "owner-a", 8, first.queryKey))
        assertFalse(isCurrentDiscoveryResponse(token, "owner-b", 7, first.queryKey))
        assertFalse(isCurrentDiscoveryResponse(token, "owner-a", 7, changedIntent.queryKey))
    }

    @Test
    fun visibilityFence_rejectsHiddenAndPreviousEntryResponses() {
        val token = DiscoveryRequestToken(
            ownerId = "owner-a",
            sessionGeneration = 4,
            visibilityGeneration = 9,
        )

        assertTrue(isCurrentDiscoveryRequest(token, "owner-a", 4, 9, isVisible = true))
        assertFalse(isCurrentDiscoveryRequest(token, "owner-a", 4, 9, isVisible = false))
        assertFalse(isCurrentDiscoveryRequest(token, "owner-a", 4, 10, isVisible = true))
        assertFalse(isCurrentDiscoveryRequest(token, "owner-a", 5, 9, isVisible = true))
        assertFalse(isCurrentDiscoveryRequest(token, "owner-b", 4, 9, isVisible = true))
    }

    @Test
    fun categoryFallback_preservesRoomCacheProvenance() {
        val cached = categorySnapshot("cached", fetchedAt = 100)
        val inMemory = categorySnapshot("memory", fetchedAt = 200)

        assertEquals(
            DiscoveryCategoryFallback(
                snapshot = cached,
                provenance = DiscoveryCategorySnapshotProvenance.ROOM_CACHE,
            ),
            resolveDiscoveryCategoryFallback(cached, inMemory),
        )
    }

    @Test
    fun categoryFallback_missingRoomCacheUsesDatedMemorySnapshot() {
        val inMemory = categorySnapshot("memory", fetchedAt = 200)

        assertEquals(
            DiscoveryCategoryFallback(
                snapshot = inMemory,
                provenance = DiscoveryCategorySnapshotProvenance.IN_MEMORY,
            ),
            resolveDiscoveryCategoryFallback(cached = null, inMemory = inMemory),
        )
    }

    @Test
    fun categoryFallback_missingCacheAndMemoryRequiresClearedUnavailableData() {
        assertEquals(null, resolveDiscoveryCategoryFallback(cached = null, inMemory = null))
    }

    private fun categorySnapshot(id: String, fetchedAt: Long) = DiscoveryCategorySnapshot(
        categoryList = DiscoveryCategoryList(
            categories = listOf(
                DiscoveryCategory(
                    id = id,
                    name = id,
                    kind = "custom",
                    itemCount = 1,
                ),
            ),
            count = 1,
            unclassifiedCount = 2,
        ),
        fetchedAt = fetchedAt,
    )

    private fun validSnapshot(
        input: DiscoveryFilterInput,
        zoneId: ZoneId,
    ): DiscoveryQuerySnapshot = when (val result = prepareDiscoveryQuery(input, zoneId)) {
        is DiscoveryQueryPreparation.Valid -> result.snapshot
        is DiscoveryQueryPreparation.Invalid -> throw AssertionError(
            "Expected valid query but got ${result.issues}",
        )
    }

    private fun assertIssue(input: DiscoveryFilterInput, field: DiscoveryFilterField) {
        val result = prepareDiscoveryQuery(input, ZoneId.of("UTC"))
        assertTrue(result is DiscoveryQueryPreparation.Invalid)
        val invalid = result as DiscoveryQueryPreparation.Invalid
        assertTrue(invalid.issues.any { it.field == field })
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
