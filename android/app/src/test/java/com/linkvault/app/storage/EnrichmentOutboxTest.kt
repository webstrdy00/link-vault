package com.linkvault.app.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentOutboxTest {
    private val owner = "10000000-0000-0000-0000-000000000001"
    private val item = "20000000-0000-0000-0000-000000000001"
    private val asset = "30000000-0000-0000-0000-000000000001"

    private fun entry(method: String, path: String) = OutboxEntry(
        requestId = "40000000-0000-0000-0000-000000000001",
        ownerId = owner, method = method, path = path,
        payloadJson = "{\"expected_version\":1}", state = OutboxState.RUNNING,
        attemptCount = 1, createdAt = 1, expiresAt = 100, nextAttemptAt = 1,
    )

    private fun json(value: String) = Json.decodeFromString<JsonObject>(value)

    @Test
    fun acceptedDeletionInvalidatesBothItemCachesWithoutInventingAnItem() {
        val effect = mapOutboxResponse(
            entry("DELETE", "/items/$item/assets/$asset"),
            json("{\"asset_id\":\"$asset\"}"), 2,
        )
        assertEquals(listOf(item), effect.invalidatesItemIds)
        assertTrue(effect.invalidatesCategories)
        assertTrue(effect.cachedItems.isEmpty())
    }

    @Test
    fun deletedAssetIdentityMustMatchTheFrozenRequest() {
        assertTrue(runCatching {
            mapOutboxResponse(
                entry("DELETE", "/items/$item/assets/$asset"),
                json("{\"asset_id\":\"$item\"}"), 2,
            )
        }.isFailure)
    }

    @Test
    fun metadataAcceptanceDoesNotMasqueradeAsCompletedItemData() {
        val effect = mapOutboxResponse(
            entry("POST", "/items/$item/retry-metadata"),
            json("{\"job_id\":\"$asset\"}"), 2,
        )
        assertTrue(effect.cachedItems.isEmpty())
        assertTrue(effect.invalidatesItemIds.isEmpty())
        assertFalse(effect.invalidatesCategories)
    }

    @Test
    fun ocrSuccessCachesOnlyTheMatchingVersionedItem() {
        val response = json("""{"id":"$item","version":2,"created_at":"2026-09-15T00:00:00Z"}""")
        val effect = mapOutboxResponse(entry("PATCH", "/items/$item/assets/$asset/ocr"), response, 2)
        assertEquals(setOf(false, true), effect.cachedItems.map { it.isDetail }.toSet())
        assertTrue(effect.cachedItems.all { it.ownerId == owner && it.itemId == item && it.serverVersion == 2L })
        assertTrue(effect.invalidatesCategories)
    }

    @Test
    fun nestedAssetRoutesRejectQueriesTraversalAndWrongMethods() {
        for ((method, path) in listOf(
            "POST" to "/items/$item/assets/$asset/ocr",
            "DELETE" to "/items/$item/assets/$asset?x=1",
            "PATCH" to "/items/$item/assets/../ocr",
            "DELETE" to "/items/$item/assets/$asset/extra",
            "POST" to "/items/$item/assets/reserve",
        )) assertTrue(runCatching { requireValidOutboxRoute(method, path) }.isFailure)
    }
}
