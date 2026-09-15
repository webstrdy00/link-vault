package com.linkvault.app.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultDatabaseTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: VaultDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun persistedRequestReopensWithOriginalBodyAndIdenticalEnqueueDoesNotResetIt() = runBlocking {
        val original = outboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = "owner-a",
            payload = "{\"url\":\"https://example.com/one\"}",
            createdAt = 1_000L,
        )
        database.outboxDao().insertImmutable(original)
        database.close()
        database = openDatabase()

        database.outboxDao().insertImmutable(
            original.copy(
                createdAt = 9_000L,
                expiresAt = 99_000L,
                nextAttemptAt = 9_000L,
            ),
        )

        assertEquals(original, database.outboxDao().findByRequestId(original.requestId))
    }

    @Test
    fun cacheQueriesAreIsolatedByOwnerAndKeepListSeparateFromDetail() = runBlocking {
        val cache = database.cachedItemDao()
        cache.cacheList(
            ownerId = "owner-a",
            items = listOf(cachedItem("owner-a", "item-1", false, "{\"id\":\"item-1\",\"a\":1}")),
            replace = true,
        )
        cache.cacheList(
            ownerId = "owner-b",
            items = listOf(cachedItem("owner-b", "item-2", false, "{\"id\":\"item-2\",\"b\":2}")),
            replace = true,
        )
        cache.upsert(cachedItem("owner-a", "item-1", true, "{\"id\":\"item-1\",\"detail\":true}"))

        assertEquals(listOf("item-1"), cache.observeList("owner-a").first().map(CachedItem::itemId))
        assertEquals(listOf("item-2"), cache.observeList("owner-b").first().map(CachedItem::itemId))
        assertEquals(
            "{\"id\":\"item-1\",\"detail\":true}",
            cache.readDetail("owner-a", "item-1")?.responseJson,
        )
        assertNull(cache.readDetail("owner-b", "item-1"))
    }

    @Test
    fun categoryCacheIsIsolatedByOwnerAndReplacesOnlyTheOwnedEnvelope() = runBlocking {
        val cache = database.cachedCategoriesDao()
        cache.upsert(CachedCategories("owner-a", "{\"count\":1}", 1_000L))
        cache.upsert(CachedCategories("owner-b", "{\"count\":2}", 2_000L))
        cache.upsert(CachedCategories("owner-a", "{\"count\":3}", 3_000L))

        assertEquals("{\"count\":3}", cache.read("owner-a")?.responseJson)
        assertEquals(3_000L, cache.read("owner-a")?.fetchedAt)
        assertEquals("{\"count\":2}", cache.read("owner-b")?.responseJson)
        assertEquals(setOf("owner-a", "owner-b"), cache.ownerIds().toSet())
        assertNull(cache.read("owner-c"))
    }

    @Test
    fun lateVersionCannotReplaceNewerListOrDetailAndReplaceOnlyPrunesOwnedRows() = runBlocking {
        val cache = database.cachedItemDao()
        cache.cacheList(
            ownerId = "owner-a",
            items = listOf(
                cachedItem(
                    ownerId = "owner-a",
                    itemId = "item-1",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-1\",\"version\":2}",
                    serverVersion = 2L,
                ),
                cachedItem("owner-a", "removed", false, "{\"id\":\"removed\",\"version\":1}"),
            ),
            replace = true,
        )
        cache.upsert(
            cachedItem(
                ownerId = "owner-a",
                itemId = "item-1",
                isDetail = true,
                responseJson = "{\"id\":\"item-1\",\"version\":2,\"note\":\"new\"}",
                serverVersion = 2L,
            ),
        )
        cache.cacheList(
            ownerId = "owner-b",
            items = listOf(
                cachedItem(
                    ownerId = "owner-b",
                    itemId = "item-b",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-b\",\"version\":3}",
                    serverVersion = 3L,
                ),
            ),
            replace = true,
        )

        cache.cacheList(
            ownerId = "owner-a",
            items = listOf(
                cachedItem(
                    ownerId = "owner-a",
                    itemId = "item-1",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-1\",\"version\":1}",
                    serverVersion = 1L,
                ),
                cachedItem("owner-a", "item-2", false, "{\"id\":\"item-2\",\"version\":1}"),
            ),
            replace = true,
        )
        cache.upsert(
            cachedItem(
                ownerId = "owner-a",
                itemId = "item-1",
                isDetail = true,
                responseJson = "{\"id\":\"item-1\",\"version\":1,\"note\":\"old\"}",
                serverVersion = 1L,
            ),
        )

        val ownerA = cache.observeList("owner-a").first().associateBy(CachedItem::itemId)
        assertEquals(2L, ownerA.getValue("item-1").serverVersion)
        assertEquals("{\"id\":\"item-1\",\"version\":2}", ownerA.getValue("item-1").responseJson)
        assertEquals(setOf("item-1", "item-2"), ownerA.keys)
        assertEquals(
            "{\"id\":\"item-1\",\"version\":2,\"note\":\"new\"}",
            cache.readDetail("owner-a", "item-1")?.responseJson,
        )
        assertEquals(3L, cache.observeList("owner-b").first().single().serverVersion)
    }

    @Test
    fun listOrderUsesServerCreationTimeAndDescendingIdNotPageFetchTime() = runBlocking {
        val cache = database.cachedItemDao()
        cache.cacheList(
            ownerId = "owner-a",
            items = listOf(
                cachedItem(
                    ownerId = "owner-a",
                    itemId = "item-z",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-z\"}",
                    serverCreatedAt = "2026-09-13T03:00:00.000000000Z",
                    fetchedAt = 1_000L,
                ),
                cachedItem(
                    ownerId = "owner-a",
                    itemId = "item-y",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-y\"}",
                    serverCreatedAt = "2026-09-13T02:00:00.000000000Z",
                    fetchedAt = 1_000L,
                ),
            ),
            replace = true,
        )
        cache.cacheList(
            ownerId = "owner-a",
            items = listOf(
                cachedItem(
                    ownerId = "owner-a",
                    itemId = "item-b",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-b\"}",
                    serverCreatedAt = "2026-09-13T01:00:00.000000000Z",
                    fetchedAt = 3_000L,
                ),
                cachedItem(
                    ownerId = "owner-a",
                    itemId = "item-a",
                    isDetail = false,
                    responseJson = "{\"id\":\"item-a\"}",
                    serverCreatedAt = "2026-09-13T01:00:00.000000000Z",
                    fetchedAt = 4_000L,
                ),
            ),
            replace = false,
        )

        assertEquals(
            listOf("item-z", "item-y", "item-b", "item-a"),
            cache.observeList("owner-a").first().map(CachedItem::itemId),
        )
    }

    @Test
    fun requestIdCannotBeReboundToAnotherBodyOrOwner() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val original = outboxEntry(requestId, "owner-a", "{\"url\":\"https://example.com/one\"}")
        database.outboxDao().insertImmutable(original)

        assertRequestConflict {
            database.outboxDao().insertImmutable(
                original.copy(payloadJson = "{\"url\":\"https://example.com/two\"}"),
            )
        }
        assertRequestConflict {
            database.outboxDao().insertImmutable(original.copy(ownerId = "owner-b"))
        }
        assertRequestConflict {
            database.outboxDao().insertImmutable(original.copy(method = "PATCH"))
        }
        assertRequestConflict {
            database.outboxDao().insertImmutable(original.copy(path = "/items/item-1"))
        }
        assertEquals(original, database.outboxDao().findByRequestId(requestId))
    }

    @Test
    fun outboxRouteAllowlistRequiresExactMethodAndCanonicalResourceUuid() {
        val itemId = "10000000-0000-4000-8000-000000000001"
        val categoryId = "20000000-0000-4000-8000-000000000002"
        listOf(
            "POST" to "/items",
            "PATCH" to "/items/$itemId",
            "POST" to "/categories",
            "PATCH" to "/categories/$categoryId",
            "DELETE" to "/categories/$categoryId",
            "POST" to "/items/$itemId/cue-dismiss",
            "POST" to "/items/$itemId/reclassify",
        ).forEach { (method, path) ->
            requireValidOutboxRoute(method, path)
        }

        listOf(
            "DELETE" to "/items/$itemId",
            "POST" to "/items/$itemId",
            "DELETE" to "/categories",
            "POST" to "/categories/$categoryId",
            "PATCH" to "/items/not-a-uuid",
            "PATCH" to "/items/$itemId/extra",
            "POST" to "/items/$itemId/reclassify/extra",
            "POST" to "/items/$itemId?unexpected=true",
            "GET" to "/items",
        ).forEach { (method, path) ->
            assertRejected { requireValidOutboxRoute(method, path) }
        }
    }

    @Test
    fun outboxResponsesCacheOnlyRealItemDetailsAndInvalidateCategoryCounts() {
        val itemId = "10000000-0000-4000-8000-000000000001"
        val categoryId = "20000000-0000-4000-8000-000000000002"
        val jobId = "30000000-0000-4000-8000-000000000003"
        val item = jsonObject(
            """
            {
              "id":"$itemId",
              "version":2,
              "created_at":"2026-09-13T01:02:03Z",
              "display_title":"Saved"
            }
            """,
        )

        val created = mapOutboxResponse(
            responseEntry("POST", "/items", "{\"url\":\"https://example.com\"}"),
            jsonObject("""{"duplicate":false,"item":$item}"""),
            fetchedAt = 10_000L,
        )
        assertEquals(listOf(false, true), created.cachedItems.map(CachedItem::isDetail))
        assertEquals(listOf(itemId, itemId), created.cachedItems.map(CachedItem::itemId))
        assertFalse(created.invalidatesCategories)

        val categoryEdit = mapOutboxResponse(
            responseEntry("PATCH", "/items/$itemId", """{"category_ids":["$categoryId"]}"""),
            item,
            fetchedAt = 11_000L,
        )
        assertEquals(2, categoryEdit.cachedItems.size)
        assertTrue(categoryEdit.invalidatesCategories)

        val titleEdit = mapOutboxResponse(
            responseEntry("PATCH", "/items/$itemId", """{"title":"Renamed"}"""),
            item,
            fetchedAt = 12_000L,
        )
        assertEquals(2, titleEdit.cachedItems.size)
        assertFalse(titleEdit.invalidatesCategories)

        val dismissed = mapOutboxResponse(
            responseEntry("POST", "/items/$itemId/cue-dismiss", "{}"),
            item,
            fetchedAt = 13_000L,
        )
        assertEquals(2, dismissed.cachedItems.size)
        assertFalse(dismissed.invalidatesCategories)

        val createdCategory = mapOutboxResponse(
            responseEntry("POST", "/categories", """{"name":"Travel"}"""),
            jsonObject("""{"id":"$categoryId","name":"Travel"}"""),
            fetchedAt = 14_000L,
        )
        assertTrue(createdCategory.cachedItems.isEmpty())
        assertTrue(createdCategory.invalidatesCategories)

        val editedCategory = mapOutboxResponse(
            responseEntry("PATCH", "/categories/$categoryId", """{"name":"Trips"}"""),
            jsonObject("""{"id":"$categoryId","name":"Trips"}"""),
            fetchedAt = 15_000L,
        )
        assertTrue(editedCategory.cachedItems.isEmpty())
        assertTrue(editedCategory.invalidatesCategories)

        val deletedCategory = mapOutboxResponse(
            responseEntry("DELETE", "/categories/$categoryId", "{}"),
            jsonObject("{}"),
            fetchedAt = 16_000L,
        )
        assertTrue(deletedCategory.cachedItems.isEmpty())
        assertTrue(deletedCategory.invalidatesCategories)

        val reclassified = mapOutboxResponse(
            responseEntry("POST", "/items/$itemId/reclassify", """{"expected_version":2}"""),
            jsonObject("""{"job_id":"$jobId","item_id":"$itemId"}"""),
            fetchedAt = 17_000L,
        )
        assertTrue(reclassified.cachedItems.isEmpty())
        assertTrue(reclassified.invalidatesCategories)
    }

    @Test
    fun outboxResponseValidationRejectsWrongShapesAndMismatchedIds() {
        val itemId = "10000000-0000-4000-8000-000000000001"
        val otherItemId = "10000000-0000-4000-8000-000000000009"
        val categoryId = "20000000-0000-4000-8000-000000000002"
        val item = jsonObject(
            """{"id":"$otherItemId","version":1,"created_at":"2026-09-13T01:02:03Z"}""",
        )

        assertRejected {
            mapOutboxResponse(
                responseEntry("POST", "/items", "{}"),
                jsonObject("""{"item":$item}"""),
                1L,
            )
        }
        assertRejected {
            mapOutboxResponse(
                responseEntry("PATCH", "/items/$itemId", "{}"),
                item,
                1L,
            )
        }
        assertRejected {
            mapOutboxResponse(
                responseEntry("PATCH", "/categories/$categoryId", "{}"),
                jsonObject("""{"id":"20000000-0000-4000-8000-000000000009"}"""),
                1L,
            )
        }
        assertRejected {
            mapOutboxResponse(
                responseEntry("DELETE", "/categories/$categoryId", "{}"),
                jsonObject("""{"deleted":true}"""),
                1L,
            )
        }
        assertRejected {
            mapOutboxResponse(
                responseEntry("POST", "/items/$itemId/reclassify", "{}"),
                jsonObject(
                    """
                    {
                      "job_id":"30000000-0000-4000-8000-000000000003",
                      "item_id":"$otherItemId"
                    }
                    """,
                ),
                1L,
            )
        }
    }

    @Test
    fun categoryEnvelopeValidationChecksCountsKindsAndCategoryRows() {
        validateCategoriesResponse(
            jsonObject(
                """
                {
                  "categories":[
                    {
                      "id":"20000000-0000-4000-8000-000000000001",
                      "name":"Travel",
                      "kind":"system",
                      "system_code":"travel",
                      "item_count":2
                    },
                    {
                      "id":"20000000-0000-4000-8000-000000000002",
                      "name":"Ideas",
                      "kind":"custom",
                      "system_code":null,
                      "item_count":0
                    }
                  ],
                  "count":2,
                  "unclassified_count":1
                }
                """,
            ),
        )

        assertRejected {
            validateCategoriesResponse(
                jsonObject(
                    """
                    {
                      "categories":[{
                        "id":"20000000-0000-4000-8000-000000000001",
                        "name":"Travel",
                        "kind":"unknown",
                        "item_count":-1
                      }],
                      "count":2,
                      "unclassified_count":0
                    }
                    """,
                ),
            )
        }
    }

    @Test
    fun activeLeasePreventsDoubleClaimAndExpiredLeaseCanBeRecovered() = runBlocking {
        val now = 10_000L
        val entry = outboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = "owner-a",
            payload = "{}",
            createdAt = now,
        )
        database.outboxDao().insertImmutable(entry)

        val first = database.outboxDao().claimNext("owner-a", now, now + 1_000L)
        val duplicate = database.outboxDao().claimNext("owner-a", now, now + 2_000L)
        val recovered = database.outboxDao().claimNext("owner-a", now + 1_000L, now + 3_000L)

        assertNotNull(first)
        assertNull(duplicate)
        assertEquals(1, first?.attemptCount)
        assertEquals(2, recovered?.attemptCount)
        assertEquals(now + 3_000L, recovered?.leaseUntil)
    }

    @Test
    fun lateResultCannotOverwriteReclaimedLeaseAndSavedReceiptSurvivesReopen() = runBlocking {
        val now = 15_000L
        val entry = outboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = "owner-a",
            payload = "{}",
            createdAt = now,
        )
        val outbox = database.outboxDao()
        outbox.insertImmutable(entry)
        outbox.claimNext("owner-a", now, now + 1_000L)
        outbox.claimNext("owner-a", now + 1_000L, now + 2_000L)

        assertEquals(
            0,
            outbox.completeSaved("owner-a", entry.requestId, now + 1_000L, "{\"late\":true}"),
        )
        assertEquals(
            1,
            outbox.completeSaved("owner-a", entry.requestId, now + 2_000L, "{\"item\":{}}"),
        )
        database.close()
        database = openDatabase()

        val reopened = database.outboxDao().findByRequestId(entry.requestId)
        assertEquals(OutboxState.SAVED, reopened?.state)
        assertEquals("{\"item\":{}}", reopened?.resultJson)
        assertEquals(0, database.outboxDao().pendingCount("owner-a"))
        assertEquals(0, database.outboxDao().retainedCount())
    }

    @Test
    fun manualRetryCannotBypassRetryAfterDeadline() = runBlocking {
        val now = 20_000L
        val retryAt = 80_000L
        val entry = outboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = "owner-a",
            payload = "{}",
            createdAt = 1_000L,
            expiresAt = 100_000L,
        ).copy(
            state = OutboxState.RETRY,
            attemptCount = 1,
            nextAttemptAt = retryAt,
            errorCode = "RATE_LIMITED",
        )
        database.outboxDao().insertImmutable(entry)

        database.outboxDao().retry("owner-a", entry.requestId, now)

        val retained = database.outboxDao().findByRequestId(entry.requestId)
        assertEquals(OutboxState.RETRY, retained?.state)
        assertEquals(retryAt, retained?.nextAttemptAt)
        assertNull(database.outboxDao().claimNext("owner-a", now, now + 1_000L))
    }

    @Test
    fun authenticatedBatchResumesWaitingLoginWrittenAfterEarlierResume() = runBlocking {
        val now = 30_000L
        val leaseUntil = now + 1_000L
        val entry = outboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = "owner-a",
            payload = "{}",
            createdAt = now,
        )
        val outbox = database.outboxDao()
        outbox.insertImmutable(entry)
        assertNotNull(outbox.claimNext("owner-a", now, leaseUntil))

        assertEquals(0, outbox.resumeWaitingLogin("owner-a", now))
        assertEquals(
            1,
            outbox.completeFailure(
                ownerId = "owner-a",
                requestId = entry.requestId,
                leaseUntil = leaseUntil,
                state = OutboxState.WAITING_LOGIN,
                nextAttemptAt = now,
                errorCode = "UNAUTHENTICATED",
                errorMessage = "Login required",
            ),
        )
        assertEquals(
            OutboxState.WAITING_LOGIN,
            outbox.findByRequestId(entry.requestId)?.state,
        )

        database.withTransaction {
            outbox.expireAutomaticRequests("owner-a", now)
            outbox.resumeWaitingLogin("owner-a", now)
        }
        val reclaimed = outbox.claimNext("owner-a", now, now + 2_000L)

        assertNotNull(reclaimed)
        assertEquals(OutboxState.RUNNING, reclaimed?.state)
        assertEquals(2, reclaimed?.attemptCount)
    }

    @Test
    fun expiredRequestIsRetainedButNeverClaimed() = runBlocking {
        val now = 20_000L
        val entry = outboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = "owner-a",
            payload = "{}",
            createdAt = 1L,
            expiresAt = now,
        )
        database.outboxDao().insertImmutable(entry)

        database.outboxDao().expireAutomaticRequests("owner-a", now)

        assertNull(database.outboxDao().claimNext("owner-a", now, now + 1_000L))
        assertEquals(
            OutboxState.EXPIRED,
            database.outboxDao().findByRequestId(entry.requestId)?.state,
        )
        assertEquals(entry.payloadJson, database.outboxDao().findByRequestId(entry.requestId)?.payloadJson)
    }

    @Test
    fun expiredPendingInputIsDeletedAndDraftEditGetsANewLifetime() = runBlocking {
        val pending = database.pendingInputDao()
        pending.saveDraft(PendingInput("expired", "old", null, 1L, 2L))
        pending.saveDraft(PendingInput("active", "first", null, 10L, 1_000L))
        pending.saveDraft(PendingInput("active", "edited", "https://example.com", 20L, 2_000L))

        pending.deleteExpired(2L)

        assertNull(pending.read("expired"))
        val remaining = pending.read("active")
        assertEquals("edited", remaining?.text)
        assertEquals(20L, remaining?.createdAt)
        assertEquals(2_000L, remaining?.expiresAt)
    }

    @Test
    fun clearOwnerDeletesOnlyThatOwnersOutboxAndCacheButClearsUnboundDrafts() = runBlocking {
        val ownerARequest = outboxEntry(UUID.randomUUID().toString(), "owner-a", "{}")
        val ownerBRequest = outboxEntry(UUID.randomUUID().toString(), "owner-b", "{}")
        database.outboxDao().insertImmutable(ownerARequest)
        database.outboxDao().insertImmutable(ownerBRequest)
        database.cachedItemDao().upsert(cachedItem("owner-a", "item-a", false, "{\"id\":\"item-a\"}"))
        database.cachedItemDao().upsert(cachedItem("owner-b", "item-b", false, "{\"id\":\"item-b\"}"))
        database.cachedCategoriesDao().upsert(CachedCategories("owner-a", "{\"count\":1}", 1L))
        database.cachedCategoriesDao().upsert(CachedCategories("owner-b", "{\"count\":2}", 2L))
        database.pendingInputDao().saveDraft(PendingInput("draft", "private", null, 1L, 100L))

        database.clearOwner("owner-a")

        assertNull(database.outboxDao().findByRequestId(ownerARequest.requestId))
        assertNotNull(database.outboxDao().findByRequestId(ownerBRequest.requestId))
        assertTrue(database.cachedItemDao().observeList("owner-a").first().isEmpty())
        assertEquals("item-b", database.cachedItemDao().observeList("owner-b").first().single().itemId)
        assertNull(database.cachedCategoriesDao().read("owner-a"))
        assertEquals("{\"count\":2}", database.cachedCategoriesDao().read("owner-b")?.responseJson)
        assertNull(database.pendingInputDao().read("draft"))
    }

    @Test
    fun clearAllOwnersDeletesEveryOwnerAndRetainedCountExcludesSavedReceipts() = runBlocking {
        val pending = outboxEntry(UUID.randomUUID().toString(), "owner-a", "{}")
        val saved = outboxEntry(UUID.randomUUID().toString(), "owner-b", "{}").copy(
            state = OutboxState.SAVED,
            resultJson = "{\"item\":{}}",
        )
        database.outboxDao().insertImmutable(pending)
        database.outboxDao().insertImmutable(saved)
        database.cachedItemDao().upsert(cachedItem("owner-a", "item-a", false, "{\"id\":\"item-a\"}"))
        database.cachedItemDao().upsert(cachedItem("owner-b", "item-b", false, "{\"id\":\"item-b\"}"))
        database.cachedCategoriesDao().upsert(CachedCategories("owner-a", "{\"count\":1}", 1L))
        database.cachedCategoriesDao().upsert(CachedCategories("owner-b", "{\"count\":2}", 2L))
        database.pendingInputDao().saveDraft(PendingInput("draft", "private", null, 1L, 100L))

        assertEquals(1, database.outboxDao().retainedCount())
        assertEquals(setOf("owner-a", "owner-b"), database.outboxDao().ownerIds().toSet())

        database.clearAllOwners()

        assertNull(database.outboxDao().findByRequestId(pending.requestId))
        assertNull(database.outboxDao().findByRequestId(saved.requestId))
        assertTrue(database.cachedItemDao().observeList("owner-a").first().isEmpty())
        assertTrue(database.cachedItemDao().observeList("owner-b").first().isEmpty())
        assertNull(database.cachedCategoriesDao().read("owner-a"))
        assertNull(database.cachedCategoriesDao().read("owner-b"))
        assertNull(database.pendingInputDao().read("draft"))
        assertEquals(0, database.outboxDao().retainedCount())
    }

    @Test
    fun retryPolicyHonorsConflictExpiryBackoffAndRetryAfter() {
        assertEquals(
            FailureAction.Conflict,
            OutboxPolicy.classifyFailure(
                now = 1_000L,
                expiresAt = 100_000L,
                attemptCount = 1,
                retryable = false,
                errorCode = "VERSION_CONFLICT",
                retryAfterSeconds = null,
            ),
        )
        assertEquals(
            FailureAction.Expire,
            OutboxPolicy.classifyFailure(
                now = 100_000L,
                expiresAt = 100_000L,
                attemptCount = 1,
                retryable = true,
                errorCode = null,
                retryAfterSeconds = null,
            ),
        )
        assertEquals(30_000L, OutboxPolicy.retryDelayMillis(1, null))
        assertEquals(90_000L, OutboxPolicy.retryDelayMillis(1, 90))
        assertEquals(
            FailureAction.Retry(91_000L),
            OutboxPolicy.classifyFailure(
                now = 1_000L,
                expiresAt = 200_000L,
                attemptCount = 1,
                retryable = false,
                errorCode = "RATE_LIMITED",
                retryAfterSeconds = 90,
            ),
        )
    }

    private fun openDatabase(): VaultDatabase = Room.databaseBuilder(
        context,
        VaultDatabase::class.java,
        databaseName,
    ).build()

    private fun outboxEntry(
        requestId: String,
        ownerId: String,
        payload: String,
        createdAt: Long = 1_000L,
        expiresAt: Long = createdAt + OutboxPolicy.REQUEST_LIFETIME_MILLIS,
    ) = OutboxEntry(
        requestId = requestId,
        ownerId = ownerId,
        method = "POST",
        path = "/items",
        payloadJson = payload,
        state = OutboxState.PENDING,
        attemptCount = 0,
        createdAt = createdAt,
        expiresAt = expiresAt,
        nextAttemptAt = createdAt,
    )

    private fun cachedItem(
        ownerId: String,
        itemId: String,
        isDetail: Boolean,
        responseJson: String,
        serverVersion: Long = 1L,
        serverCreatedAt: String = "2026-09-13T00:00:00.000000000Z",
        fetchedAt: Long = 1_000L,
    ) = CachedItem(
        ownerId = ownerId,
        itemId = itemId,
        responseJson = responseJson,
        serverVersion = serverVersion,
        serverCreatedAt = serverCreatedAt,
        fetchedAt = fetchedAt,
        isDetail = isDetail,
    )

    private fun responseEntry(method: String, path: String, payload: String): OutboxEntry =
        outboxEntry(UUID.randomUUID().toString(), "owner-a", payload).copy(
            method = method,
            path = path,
        )

    private fun jsonObject(raw: String): JsonObject =
        Json.parseToJsonElement(raw).jsonObject

    private fun assertRejected(block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertNotNull(thrown)
    }

    private suspend fun assertRequestConflict(block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(thrown is RequestIdConflictException)
    }
}
