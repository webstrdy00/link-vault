package com.linkvault.app.storage

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, VaultDatabase::class.java)

    @Test
    fun migration1To2PreservesVersion1DataAndCreatesCategoryCache() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            assertIdentityHashSetup(V1_IDENTITY_HASH)
            execSQL(
                """
                INSERT INTO outbox (
                    request_id, owner_id, method, path, payload_json, state,
                    attempt_count, created_at, expires_at, next_attempt_at,
                    result_json, error_code, error_message, lease_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    SAVED_REQUEST_ID,
                    "owner-a",
                    "POST",
                    "/items",
                    "{\"url\":\"https://example.com/saved\"}",
                    "saved",
                    1,
                    1_000L,
                    87_401_000L,
                    1_000L,
                    "{\"item\":{\"id\":\"$ITEM_ID\"}}",
                    null,
                    null,
                    null,
                ),
            )
            execSQL(
                """
                INSERT INTO outbox (
                    request_id, owner_id, method, path, payload_json, state,
                    attempt_count, created_at, expires_at, next_attempt_at,
                    result_json, error_code, error_message, lease_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    RETRY_REQUEST_ID,
                    "owner-b",
                    "PATCH",
                    "/items/$ITEM_ID",
                    "{\"title\":\"Later\"}",
                    "retry",
                    2,
                    2_000L,
                    87_402_000L,
                    32_000L,
                    null,
                    "DEPENDENCY_UNAVAILABLE",
                    "Try later",
                    null,
                ),
            )
            execSQL(
                """
                INSERT INTO cached_items (
                    owner_id, item_id, response_json, server_version,
                    server_created_at, fetched_at, is_detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "owner-a",
                    ITEM_ID,
                    "{\"id\":\"$ITEM_ID\",\"summary\":true}",
                    4L,
                    "2026-09-13T00:00:00.000000000Z",
                    4_000L,
                    0,
                ),
            )
            execSQL(
                """
                INSERT INTO cached_items (
                    owner_id, item_id, response_json, server_version,
                    server_created_at, fetched_at, is_detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "owner-a",
                    ITEM_ID,
                    "{\"id\":\"$ITEM_ID\",\"detail\":true}",
                    4L,
                    "2026-09-13T00:00:00.000000000Z",
                    4_100L,
                    1,
                ),
            )
            execSQL(
                """
                INSERT INTO pending_inputs (
                    local_id, text, selected_url, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "draft-a",
                    "private draft",
                    "https://example.com/draft",
                    5_000L,
                    87_405_000L,
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            MIGRATION_1_2,
        ).apply {
            query("SELECT COUNT(*) FROM outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            query(
                """
                SELECT owner_id, payload_json, state, result_json
                FROM outbox WHERE request_id = '$SAVED_REQUEST_ID'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("owner-a", cursor.getString(0))
                assertEquals("{\"url\":\"https://example.com/saved\"}", cursor.getString(1))
                assertEquals("saved", cursor.getString(2))
                assertEquals("{\"item\":{\"id\":\"$ITEM_ID\"}}", cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
            query(
                """
                SELECT attempt_count, next_attempt_at, error_code, error_message
                FROM outbox WHERE request_id = '$RETRY_REQUEST_ID'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
                assertEquals(32_000L, cursor.getLong(1))
                assertEquals("DEPENDENCY_UNAVAILABLE", cursor.getString(2))
                assertEquals("Try later", cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
            query(
                """
                SELECT response_json, server_version, server_created_at, fetched_at, is_detail
                FROM cached_items
                WHERE owner_id = 'owner-a' AND item_id = '$ITEM_ID'
                ORDER BY is_detail
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"id\":\"$ITEM_ID\",\"summary\":true}", cursor.getString(0))
                assertEquals(4L, cursor.getLong(1))
                assertEquals("2026-09-13T00:00:00.000000000Z", cursor.getString(2))
                assertEquals(4_000L, cursor.getLong(3))
                assertEquals(0, cursor.getInt(4))
                assertTrue(cursor.moveToNext())
                assertEquals("{\"id\":\"$ITEM_ID\",\"detail\":true}", cursor.getString(0))
                assertEquals(4_100L, cursor.getLong(3))
                assertEquals(1, cursor.getInt(4))
                assertFalse(cursor.moveToNext())
            }
            query(
                """
                SELECT text, selected_url, created_at, expires_at
                FROM pending_inputs WHERE local_id = 'draft-a'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("private draft", cursor.getString(0))
                assertEquals("https://example.com/draft", cursor.getString(1))
                assertEquals(5_000L, cursor.getLong(2))
                assertEquals(87_405_000L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }

            execSQL(
                """
                INSERT INTO cached_categories (owner_id, response_json, fetched_at)
                VALUES (?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>("owner-a", "{\"categories\":[],\"count\":0,\"unclassified_count\":1}", 6_000L),
            )
            query(
                """
                SELECT response_json, fetched_at FROM cached_categories
                WHERE owner_id = 'owner-a'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "{\"categories\":[],\"count\":0,\"unclassified_count\":1}",
                    cursor.getString(0),
                )
                assertEquals(6_000L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            close()
        }
    }

    @Test
    fun migration3To4PersistsDeletionFenceAndBlocksOldAndNewLateCacheRows() = runBlocking {
        val databaseName = "vault-deletion-migration-${UUID.randomUUID()}.db"
        helper.createDatabase(databaseName, 3).apply {
            execSQL(
                """
                INSERT INTO outbox (
                    request_id, owner_id, method, path, payload_json, state,
                    attempt_count, created_at, expires_at, next_attempt_at,
                    result_json, error_code, error_message, lease_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    DELETE_REQUEST_ID,
                    OWNER_A,
                    "DELETE",
                    "/items/$ITEM_ID",
                    "{}",
                    "running",
                    1,
                    1_000L,
                    90_000L,
                    1_000L,
                    null,
                    null,
                    null,
                    10_000L,
                ),
            )
            execSQL(
                """
                INSERT INTO outbox (
                    request_id, owner_id, method, path, payload_json, state,
                    attempt_count, created_at, expires_at, next_attempt_at,
                    result_json, error_code, error_message, lease_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    ITEM_MUTATION_REQUEST_ID,
                    OWNER_A,
                    "POST",
                    "/items/$ITEM_ID/assets/reserve",
                    "{}",
                    "retry",
                    1,
                    1_100L,
                    90_000L,
                    2_000L,
                    null,
                    null,
                    null,
                    null,
                ),
            )
            execSQL(
                """
                INSERT INTO cached_items (
                    owner_id, item_id, response_json, server_version,
                    server_created_at, fetched_at, is_detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    OWNER_A,
                    ITEM_ID,
                    "{\"id\":\"$ITEM_ID\",\"version\":7}",
                    7L,
                    "2026-09-16T00:00:00Z",
                    2_000L,
                    1,
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            MIGRATION_3_4,
        ).apply {
            query("SELECT response_json FROM cached_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"id\":\"$ITEM_ID\",\"version\":7}", cursor.getString(0))
            }
            query("SELECT COUNT(*) FROM item_deletion_tombstones").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var reopened = Room.databaseBuilder(context, VaultDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_3_4)
            .build()
        try {
            assertTrue(
                reopened.markItemDeleted(
                    ownerId = OWNER_A,
                    itemId = ITEM_ID,
                    observedDeletedAt = 5_000L,
                    preservedRequestId = DELETE_REQUEST_ID,
                ),
            )
            assertNull(reopened.cachedItemDao().readDetail(OWNER_A, ITEM_ID))
            assertNull(reopened.outboxDao().findByRequestId(ITEM_MUTATION_REQUEST_ID))
            assertTrue(reopened.outboxDao().findByRequestId(DELETE_REQUEST_ID) != null)
        } finally {
            reopened.close()
        }

        reopened = Room.databaseBuilder(context, VaultDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_3_4)
            .build()
        try {
            assertTrue(reopened.isItemDeleted(OWNER_A, ITEM_ID))
            val cache = reopened.cachedItemDao()
            cache.upsert(cachedItem(OWNER_A, ITEM_ID, version = 1L, isDetail = true))
            cache.upsert(cachedItem(OWNER_A, ITEM_ID, version = 999L, isDetail = true))
            assertNull(cache.readDetail(OWNER_A, ITEM_ID))

            cache.cacheList(
                ownerId = OWNER_A,
                items = listOf(
                    cachedItem(OWNER_A, ITEM_ID, version = 999L, isDetail = false),
                    cachedItem(OWNER_A, OTHER_ITEM_ID, version = 1L, isDetail = false),
                ),
                replace = true,
            )
            assertEquals(
                listOf(OTHER_ITEM_ID),
                cache.observeList(OWNER_A).first().map(CachedItem::itemId),
            )
            assertTrue(reopened.isItemDeleted(OWNER_A, ITEM_ID))
            reopened.openHelper.writableDatabase.query(
                """
                SELECT COUNT(*) FROM cached_items
                WHERE owner_id = '$OWNER_A' AND item_id = '$ITEM_ID'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            cache.upsert(cachedItem(OWNER_B, ITEM_ID, version = 1L, isDetail = true))
            assertEquals(ITEM_ID, cache.readDetail(OWNER_B, ITEM_ID)?.itemId)

            assertFalse(
                reopened.markItemDeleted(
                    ownerId = OWNER_A,
                    itemId = ITEM_ID,
                    observedDeletedAt = 4_000L,
                    preservedRequestId = UUID.randomUUID().toString(),
                ),
            )
            reopened.openHelper.writableDatabase.query(
                """
                SELECT observed_deleted_at FROM item_deletion_tombstones
                WHERE owner_id = '$OWNER_A' AND item_id = '$ITEM_ID'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5_000L, cursor.getLong(0))
            }

            reopened.markItemDeleted(
                ownerId = OWNER_B,
                itemId = ITEM_ID,
                observedDeletedAt = 6_000L,
                preservedRequestId = UUID.randomUUID().toString(),
            )
            reopened.clearOwner(OWNER_A)
            assertFalse(reopened.isItemDeleted(OWNER_A, ITEM_ID))
            assertTrue(reopened.isItemDeleted(OWNER_B, ITEM_ID))
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun cachedItem(
        ownerId: String,
        itemId: String,
        version: Long,
        isDetail: Boolean,
    ) = CachedItem(
        ownerId = ownerId,
        itemId = itemId,
        responseJson = "{\"id\":\"$itemId\",\"version\":$version}",
        serverVersion = version,
        serverCreatedAt = "2026-09-16T00:00:00Z",
        fetchedAt = version,
        isDetail = isDetail,
    )

    private fun SupportSQLiteDatabase.assertIdentityHashSetup(expected: String) {
        query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private companion object {
        const val DATABASE_NAME = "vault-migration-test.db"
        const val V1_IDENTITY_HASH = "d0fd3289d4630ecc35b0b20f94efc29f"
        const val ITEM_ID = "10000000-0000-4000-8000-000000000001"
        const val SAVED_REQUEST_ID = "40000000-0000-4000-8000-000000000004"
        const val RETRY_REQUEST_ID = "50000000-0000-4000-8000-000000000005"
        const val OWNER_A = "10000000-0000-4000-8000-000000000010"
        const val OWNER_B = "10000000-0000-4000-8000-000000000011"
        const val OTHER_ITEM_ID = "10000000-0000-4000-8000-000000000002"
        const val DELETE_REQUEST_ID = "40000000-0000-4000-8000-000000000010"
        const val ITEM_MUTATION_REQUEST_ID = "40000000-0000-4000-8000-000000000011"
    }
}
