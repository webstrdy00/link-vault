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
        assertEquals(null, effect.deletedItemId)
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

    @Test
    fun acceptedItemDeletionHasAnExactDeletingReceiptAndDoesNotCollideWithImageDeletion() {
        requireValidOutboxRoute("DELETE", "/items/$item")
        val effect = mapOutboxResponse(
            entry("DELETE", "/items/$item"),
            json("""{"item_id":"$item","state":"deleting"}"""),
            2,
        )

        assertEquals(item, effect.deletedItemId)
        assertEquals(listOf(item), effect.invalidatesItemIds)
        assertTrue(effect.invalidatesCategories)
        assertTrue(effect.cachedItems.isEmpty())

        val imageEffect = mapOutboxResponse(
            entry("DELETE", "/items/$item/assets/$asset"),
            json("""{"asset_id":"$asset"}"""),
            2,
        )
        assertEquals(null, imageEffect.deletedItemId)
    }

    @Test
    fun itemDeletionRejectsWrongIdentityStateAndEnvelope() {
        for (response in listOf(
            """{"item_id":"$asset","state":"deleting"}""",
            """{"item_id":"$item","state":"deleted"}""",
            """{"item":{"id":"$item"},"state":"deleting"}""",
            """{"item_id":"$item","state":"deleting","deleted":true}""",
        )) {
            assertTrue(
                runCatching {
                    mapOutboxResponse(
                        entry("DELETE", "/items/$item"),
                        json(response),
                        2,
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun itemDeletionIntentRequiresOnlyAFrozenPositiveVersion() {
        assertEquals(7L, requireItemDeleteExpectedVersion(json("""{"expected_version":7}""")))
        for (payload in listOf(
            "{}",
            """{"expected_version":0}""",
            """{"expected_version":"7"}""",
            """{"expected_version":7,"item_id":"$item"}""",
        )) {
            assertTrue(runCatching { requireItemDeleteExpectedVersion(json(payload)) }.isFailure)
        }
    }

    @Test
    fun knownOwnedMissingItemUsesAnExplicitLocalResolutionReceipt() {
        assertEquals(
            json("""{"item_id":"$item","state":"already_deleted"}"""),
            knownMissingItemDeleteReceipt(item),
        )
        assertTrue(
            runCatching {
                knownMissingItemDeleteReceipt("not-an-item-id")
            }.isFailure,
        )
    }

    @Test
    fun quotaRejectedSaveRequiresReconfirmationInsteadOfSameRequestRetry() {
        val rejected = entry("POST", "/items").copy(
            state = OutboxState.FAILED,
            errorCode = "ITEM_LIMIT_REACHED",
        )
        assertEquals(SaveRecoveryAction.RECONFIRM, OutboxPolicy.saveRecoveryAction(rejected, owner))
        assertEquals(
            SaveRecoveryAction.EDIT_OR_REMOVE,
            OutboxPolicy.saveRecoveryAction(rejected.copy(state = OutboxState.RETRY), owner),
        )
    }

    @Test
    fun ambiguousSaveFailuresKeepSameRequestRecovery() {
        for (state in listOf(OutboxState.FAILED, OutboxState.RETRY)) {
            for (code in listOf(null, "NETWORK_ERROR", "DEPENDENCY_UNAVAILABLE", "RATE_LIMITED")) {
                val ambiguous = entry("POST", "/items").copy(state = state, errorCode = code)
                assertEquals(
                    SaveRecoveryAction.RETRY_SAME_REQUEST,
                    OutboxPolicy.saveRecoveryAction(ambiguous, owner),
                )
            }
        }
    }

    @Test
    fun committedSaveFailuresRequireEditingOrRemoval() {
        for (code in listOf(
            "URL_HASH_COLLISION", "IDEMPOTENCY_MISMATCH", "VERSION_CONFLICT",
            "INVALID_BODY", "INVALID_REQUEST_BODY", "ITEM_DELETED", "ITEM_NOT_FOUND",
            "ACCOUNT_DELETING", "BETA_ACCESS_REQUIRED", "STORAGE_LIMIT_REACHED",
        )) {
            for (state in listOf(OutboxState.FAILED, OutboxState.RETRY, OutboxState.CONFLICT)) {
                assertEquals(
                    SaveRecoveryAction.EDIT_OR_REMOVE,
                    OutboxPolicy.saveRecoveryAction(
                        entry("POST", "/items").copy(state = state, errorCode = code),
                        owner,
                    ),
                )
            }
        }
        for (code in listOf(null, "NETWORK_ERROR", "ITEM_LIMIT_REACHED")) {
            assertEquals(
                SaveRecoveryAction.EDIT_OR_REMOVE,
                OutboxPolicy.saveRecoveryAction(
                    entry("POST", "/items").copy(state = OutboxState.CONFLICT, errorCode = code),
                    owner,
                ),
            )
        }
    }

    @Test
    fun saveRecoveryRequiresExactPostItemsRoute() {
        for ((method, path) in listOf(
            "GET" to "/items", "PATCH" to "/items", "post" to "/items",
            "POST" to "/items/", "POST" to "/items?retry=1", "POST" to "/items#save",
            "POST" to "/items/$item/retry-metadata", "POST" to "/categories",
        )) {
            for (state in listOf(OutboxState.FAILED, OutboxState.RETRY, OutboxState.EXPIRED)) {
                assertEquals(
                    null,
                    OutboxPolicy.saveRecoveryAction(
                        entry(method, path).copy(state = state, errorCode = "ITEM_LIMIT_REACHED"),
                        owner,
                    ),
                )
            }
        }
    }

    @Test
    fun saveRecoveryRequiresSameNonblankOwner() {
        for (otherOwner in listOf(null, "", " ", item)) {
            for (state in listOf(OutboxState.FAILED, OutboxState.RETRY, OutboxState.EXPIRED)) {
                assertEquals(
                    null,
                    OutboxPolicy.saveRecoveryAction(
                        entry("POST", "/items").copy(state = state, errorCode = "ITEM_LIMIT_REACHED"),
                        otherOwner,
                    ),
                )
            }
        }
    }

    @Test
    fun expiredSaveRequiresReconfirmationUnlessItsIdentityConflicted() {
        for (code in listOf(null, "REQUEST_EXPIRED", "NETWORK_ERROR", "ITEM_LIMIT_REACHED")) {
            assertEquals(
                SaveRecoveryAction.RECONFIRM,
                OutboxPolicy.saveRecoveryAction(
                    entry("POST", "/items").copy(state = OutboxState.EXPIRED, errorCode = code),
                    owner,
                ),
            )
        }
        for (code in listOf("URL_HASH_COLLISION", "IDEMPOTENCY_MISMATCH")) {
            assertEquals(
                SaveRecoveryAction.EDIT_OR_REMOVE,
                OutboxPolicy.saveRecoveryAction(
                    entry("POST", "/items").copy(state = OutboxState.EXPIRED, errorCode = code),
                    owner,
                ),
            )
        }
    }

    @Test
    fun activeAndSavedRequestsDoNotOfferTerminalSaveRecovery() {
        for (state in listOf(
            OutboxState.PENDING, OutboxState.RUNNING, OutboxState.WAITING_LOGIN, OutboxState.SAVED,
        )) {
            for (code in listOf(null, "ITEM_LIMIT_REACHED", "URL_HASH_COLLISION")) {
                assertEquals(
                    null,
                    OutboxPolicy.saveRecoveryAction(
                        entry("POST", "/items").copy(state = state, errorCode = code),
                        owner,
                    ),
                )
            }
        }
    }

    @Test
    fun urlHashCollisionRemainsTerminalEvenWhenMarkedRetryable() {
        assertEquals(
            FailureAction.Fail,
            OutboxPolicy.classifyFailure(
                now = 1,
                expiresAt = 100_000,
                attemptCount = 1,
                retryable = true,
                errorCode = "URL_HASH_COLLISION",
                retryAfterSeconds = 1,
            ),
        )
    }
}
