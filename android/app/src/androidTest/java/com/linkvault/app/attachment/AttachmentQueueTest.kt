package com.linkvault.app.attachment

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.storage.MIGRATION_2_3
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.VaultDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentQueueTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: VaultDatabase

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "attachment-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, VaultDatabase::class.java, databaseName).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration2To3PreservesOutboxCachesAndDraftAndCreatesEmptyAttachmentQueue() {
        val migrationName = "attachment-migration-${UUID.randomUUID()}.db"
        migrationHelper.createDatabase(migrationName, 2).apply {
            execSQL(
                """
                INSERT INTO outbox (
                    request_id, owner_id, method, path, payload_json, state,
                    attempt_count, created_at, expires_at, next_attempt_at,
                    result_json, error_code, error_message, lease_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    REQUEST_ID,
                    OWNER_A,
                    "POST",
                    "/items",
                    "{\"url\":\"https://example.com\"}",
                    "retry",
                    2,
                    1_000L,
                    90_000L,
                    30_000L,
                    null,
                    "RATE_LIMITED",
                    "Later",
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
                    ITEM_A,
                    "{\"id\":\"$ITEM_A\"}",
                    3L,
                    "2026-09-15T00:00:00.000000000Z",
                    2_000L,
                    1,
                ),
            )
            execSQL(
                """
                INSERT INTO cached_categories (owner_id, response_json, fetched_at)
                VALUES (?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(OWNER_A, "{\"categories\":[],\"count\":0}", 3_000L),
            )
            execSQL(
                """
                INSERT INTO pending_inputs (local_id, text, selected_url, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>("capture", "private draft", null, 4_000L, 90_000L),
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            migrationName,
            3,
            true,
            MIGRATION_2_3,
        ).apply {
            query("SELECT payload_json, state, attempt_count FROM outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"url\":\"https://example.com\"}", cursor.getString(0))
                assertEquals("retry", cursor.getString(1))
                assertEquals(2, cursor.getInt(2))
            }
            query("SELECT response_json, server_version FROM cached_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"id\":\"$ITEM_A\"}", cursor.getString(0))
                assertEquals(3L, cursor.getLong(1))
            }
            query("SELECT response_json FROM cached_categories").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"categories\":[],\"count\":0}", cursor.getString(0))
            }
            query("SELECT text, expires_at FROM pending_inputs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("private draft", cursor.getString(0))
                assertEquals(90_000L, cursor.getLong(1))
            }
            query("SELECT COUNT(*) FROM pending_attachments").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
        context.deleteDatabase(migrationName)
    }

    @Test
    fun immutableRequestBodiesCannotBeReboundAndSurviveLeaseTransitions() = runBlocking {
        val original = attachment(createdAt = 1_000L)
        val dao = database.attachmentDao()
        dao.insertImmutable(original)

        assertAttachmentConflict {
            dao.insertImmutable(original.copy(reserveBodyJson = "{\"expected_version\":9}"))
        }
        assertAttachmentConflict {
            dao.insertImmutable(original.copy(completeBodyJson = "{\"ocr_state\":\"failed\"}"))
        }

        val claimed = dao.claimNext(
            OWNER_A,
            original.sessionGeneration,
            1_000L,
            2_000L,
            LEASE_A,
        )
        assertNotNull(claimed)
        assertEquals(original.reserveRequestId, claimed?.reserveRequestId)
        assertEquals(original.reserveBodyJson, claimed?.reserveBodyJson)
        assertEquals(original.completeBodyJson, claimed?.completeBodyJson)
    }

    @Test
    fun observationsAndClaimsAreOwnerAndGenerationIsolated() = runBlocking {
        val ownerA = attachment(createdAt = 1_000L)
        val ownerB = attachment(
            operationId = OPERATION_B,
            ownerId = OWNER_B,
            itemId = ITEM_B,
            createdAt = 2_000L,
        )
        database.attachmentDao().insertImmutable(ownerA)
        database.attachmentDao().insertImmutable(ownerB)

        assertEquals(
            listOf(ownerA.operationId),
            database.attachmentDao().observe(OWNER_A, null).first().map { it.operationId },
        )
        assertEquals(
            listOf(ownerB.operationId),
            database.attachmentDao().observe(OWNER_B, ITEM_B).first().map { it.operationId },
        )
        assertNull(
            database.attachmentDao().claimNext(
                OWNER_A,
                ownerA.sessionGeneration + 1L,
                2_000L,
                3_000L,
                LEASE_A,
            ),
        )
        assertEquals(
            ownerA.operationId,
            database.attachmentDao().claimNext(
                OWNER_A,
                ownerA.sessionGeneration,
                2_000L,
                3_000L,
                LEASE_A,
            )?.operationId,
        )
        assertEquals(AttachmentStage.RESERVE, database.attachmentDao().find(ownerB.operationId)?.stage)
    }

    @Test
    fun ownerPurgeCannotBeReversedByBindingTheSameOwnerToANewGeneration() = runBlocking {
        val ownerA = attachment(createdAt = 1_000L)
        val ownerB = attachment(
            operationId = OPERATION_B,
            ownerId = OWNER_B,
            itemId = ITEM_B,
            createdAt = 2_000L,
        )
        val dao = database.attachmentDao()
        dao.insertImmutable(ownerA)
        dao.insertImmutable(ownerB)

        database.clearOwner(OWNER_A)
        dao.bindOwnerSession(OWNER_A, ownerA.sessionGeneration + 1L, 3_000L)

        assertNull(dao.find(ownerA.operationId))
        assertNotNull(dao.find(ownerB.operationId))
    }

    @Test
    fun leaseTokenCasRejectsDuplicateWorkerAndStaleCompletion() = runBlocking {
        val entry = attachment(createdAt = 10_000L)
        val dao = database.attachmentDao()
        dao.insertImmutable(entry)

        assertNotNull(dao.claimNext(OWNER_A, 7L, 10_000L, 11_000L, LEASE_A))
        assertNull(dao.claimNext(OWNER_A, 7L, 10_500L, 12_000L, LEASE_B))
        assertNotNull(dao.claimNext(OWNER_A, 7L, 11_000L, 13_000L, LEASE_B))

        assertEquals(
            0,
            dao.completeReservation(
                OWNER_A,
                entry.operationId,
                7L,
                LEASE_A,
                11_000L,
                COMPLETE_REQUEST_A,
                ASSET_A,
                "$OWNER_A/$ITEM_A/$ASSET_A",
                900_000L,
            ),
        )
        assertEquals(
            1,
            dao.completeReservation(
                OWNER_A,
                entry.operationId,
                7L,
                LEASE_B,
                11_000L,
                COMPLETE_REQUEST_A,
                ASSET_A,
                "$OWNER_A/$ITEM_A/$ASSET_A",
                900_000L,
            ),
        )
        val saved = dao.find(entry.operationId)
        assertEquals(AttachmentStage.UPLOAD, saved?.stage)
        assertEquals(2, saved?.attemptCount)
        assertEquals(COMPLETE_REQUEST_A, saved?.completeRequestId)
    }

    @Test
    fun itemDeletionCancelsLeasesAndCleanupDeletesOnlyMatchingQueueFiles() = runBlocking {
        val now = System.currentTimeMillis()
        val ownerDirectory = File(context.noBackupFilesDir, "attachment_queue/$OWNER_A")
        assertTrue(ownerDirectory.mkdirs() || ownerDirectory.isDirectory)
        val deletedItemFile = File(ownerDirectory, "$OPERATION_A.asset").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val otherItemFile = File(ownerDirectory, "$OPERATION_B.asset").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val externalPrivateFile = File(context.filesDir, "attachment-source-${UUID.randomUUID()}").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val dao = database.attachmentDao()
        val deletedItem = attachment(createdAt = now)
        val otherItem = attachment(
            operationId = OPERATION_B,
            itemId = ITEM_B,
            createdAt = now + 1L,
        )
        dao.insertImmutable(deletedItem)
        dao.insertImmutable(otherItem)
        assertNotNull(
            dao.claimNext(
                OWNER_A,
                deletedItem.sessionGeneration,
                now,
                now + 10_000L,
                LEASE_A,
            ),
        )

        assertFalse(
            database.markItemDeleted(
                ownerId = OWNER_A,
                itemId = ITEM_A,
                observedDeletedAt = now,
                preservedRequestId = UUID.randomUUID().toString(),
            ),
        )

        val cancelled = dao.find(OPERATION_A)
        assertEquals(AttachmentStage.EXPIRED, cancelled?.stage)
        assertNull(cancelled?.leaseToken)
        assertNull(cancelled?.leaseUntil)
        assertEquals("ITEM_DELETED", cancelled?.errorCode)
        assertEquals(
            0,
            dao.completeReservation(
                OWNER_A,
                OPERATION_A,
                deletedItem.sessionGeneration,
                LEASE_A,
                now + 1L,
                COMPLETE_REQUEST_A,
                ASSET_A,
                "$OWNER_A/$ITEM_A/$ASSET_A",
                now + FIFTEEN_MINUTES,
            ),
        )
        assertEquals(AttachmentStage.RESERVE, dao.find(OPERATION_B)?.stage)

        val client = AccountClient(
            context = context,
            url = "",
            key = "",
            googleWebClientId = "",
            debug = true,
            localDataOwner = { OWNER_A },
            beforeSignOut = {},
            onSessionOwner = {},
        )
        val repository = AttachmentRepository(
            context,
            client,
            OutboxRepository(context, client, database),
            database,
        )
        repository.cleanup()

        assertFalse(deletedItemFile.exists())
        assertTrue(otherItemFile.exists())
        assertTrue(externalPrivateFile.exists())
        assertEquals(1, dao.deleteItem(OWNER_A, ITEM_A))
        assertTrue(otherItemFile.delete())
        assertTrue(externalPrivateFile.delete())
    }

    @Test
    fun expiredFifteenMinuteReservationGetsNewRequestWithoutChangingBaseOrDayExpiry() = runBlocking {
        val createdAt = 100_000L
        val entry = attachment(createdAt = createdAt)
        val dao = database.attachmentDao()
        dao.insertImmutable(entry)
        dao.claimNext(OWNER_A, 7L, createdAt, createdAt + 1_000L, LEASE_A)
        dao.completeReservation(
            OWNER_A,
            entry.operationId,
            7L,
            LEASE_A,
            createdAt,
            COMPLETE_REQUEST_A,
            ASSET_A,
            "$OWNER_A/$ITEM_A/$ASSET_A",
            createdAt + FIFTEEN_MINUTES,
        )

        val afterReservation = dao.find(entry.operationId)!!
        assertEquals(createdAt, afterReservation.reservationReceivedAt)
        dao.claimNext(
            OWNER_A,
            7L,
            createdAt + FIFTEEN_MINUTES,
            createdAt + FIFTEEN_MINUTES + 1_000L,
            LEASE_B,
        )
        val newReserveRequest = UUID.randomUUID().toString()
        assertEquals(
            1,
            dao.rotateExpiredReservation(
                OWNER_A,
                entry.operationId,
                7L,
                LEASE_B,
                newReserveRequest,
                createdAt + FIFTEEN_MINUTES,
            ),
        )

        val rotated = dao.find(entry.operationId)!!
        assertEquals(AttachmentStage.RESERVE, rotated.stage)
        assertNotEquals(afterReservation.reserveRequestId, rotated.reserveRequestId)
        assertEquals(newReserveRequest, rotated.reserveRequestId)
        assertEquals(entry.reserveBodyJson, rotated.reserveBodyJson)
        assertEquals(entry.completeBodyJson, rotated.completeBodyJson)
        assertEquals(entry.baseExpectedVersion, rotated.baseExpectedVersion)
        assertEquals(createdAt + AttachmentPolicy.LIFETIME_MILLIS, rotated.expiresAt)
        assertNull(rotated.serverAssetId)
        assertNull(rotated.completeRequestId)
    }

    @Test
    fun manualRetryDoesNotBypassRetryAfter() = runBlocking {
        val retryAt = 90_000L
        val entry = attachment(createdAt = 1_000L).copy(
            stage = AttachmentStage.RETRY,
            resumeStage = AttachmentStage.RESERVE,
            nextRetryAt = retryAt,
            errorCode = "RATE_LIMITED",
        )
        val dao = database.attachmentDao()
        dao.insertImmutable(entry)

        dao.retry(OWNER_A, entry.operationId, entry.sessionGeneration, 10_000L)

        val retained = dao.find(entry.operationId)!!
        assertEquals(AttachmentStage.RETRY, retained.stage)
        assertEquals(retryAt, retained.nextRetryAt)
        assertNull(
            dao.claimNext(
                OWNER_A,
                entry.sessionGeneration,
                retryAt - 1L,
                retryAt + 1_000L,
                LEASE_A,
            ),
        )
        assertNotNull(
            dao.claimNext(
                OWNER_A,
                entry.sessionGeneration,
                retryAt,
                retryAt + 1_000L,
                LEASE_A,
            ),
        )
    }

    @Test
    fun conflictCannotBeClaimedRetriedOrAutomaticallyRebased() = runBlocking {
        val conflicted = attachment(createdAt = 1_000L).copy(
            stage = AttachmentStage.CONFLICT,
            errorCode = "VERSION_CONFLICT",
        )
        val dao = database.attachmentDao()
        dao.insertImmutable(conflicted)

        assertNull(dao.claimNext(OWNER_A, 7L, 2_000L, 3_000L, LEASE_A))
        assertFails { dao.retry(OWNER_A, conflicted.operationId, 7L, 2_000L) }
        val retained = dao.find(conflicted.operationId)!!
        assertEquals(AttachmentStage.CONFLICT, retained.stage)
        assertEquals(3L, retained.baseExpectedVersion)
        assertEquals(conflicted.operationId, retained.operationId)
    }

    @Test
    fun expiryAndOrphanCleanupDeleteOnlyQueueFilesAndNeverAnExternalPrivateSource() = runBlocking {
        val ownerDirectory = File(context.noBackupFilesDir, "attachment_queue/$OWNER_A")
        assertTrue(ownerDirectory.mkdirs() || ownerDirectory.isDirectory)
        val expiredQueueFile = File(ownerDirectory, "$OPERATION_A.asset").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(setLastModified(1L))
        }
        val orphan = File(ownerDirectory, "$OPERATION_B.asset").apply {
            writeBytes(byteArrayOf(7, 8, 9))
            assertTrue(setLastModified(1L))
        }
        val userSource = File(context.filesDir, "user-source-${UUID.randomUUID()}.jpg").apply {
            writeBytes(byteArrayOf(4, 5, 6))
            assertTrue(setLastModified(1L))
        }
        val client = AccountClient(
            context = context,
            url = "",
            key = "",
            googleWebClientId = "",
            debug = true,
            localDataOwner = { OWNER_A },
            beforeSignOut = {},
            onSessionOwner = {},
        )
        val outbox = OutboxRepository(context, client, database)
        val repository = AttachmentRepository(context, client, outbox, database)
        database.attachmentDao().insertImmutable(attachment(createdAt = 1L))

        repository.cleanup()

        assertFalse(expiredQueueFile.exists())
        assertFalse(orphan.exists())
        assertEquals(
            AttachmentStage.EXPIRED,
            database.attachmentDao().find(OPERATION_A)?.stage,
        )
        assertTrue(userSource.exists())
        assertTrue(userSource.readBytes().contentEquals(byteArrayOf(4, 5, 6)))
        assertTrue(userSource.delete())
    }

    private fun attachment(
        operationId: String = OPERATION_A,
        ownerId: String = OWNER_A,
        itemId: String = ITEM_A,
        createdAt: Long,
    ) = PendingAttachment(
        operationId = operationId,
        ownerId = ownerId,
        itemId = itemId,
        baseExpectedVersion = 3L,
        sessionGeneration = 7L,
        localFileName = "$ownerId/$operationId.asset",
        mimeType = "image/jpeg",
        ocrState = AttachmentOcrState.READY,
        ocrText = "recognized",
        ocrTruncated = true,
        createdAt = createdAt,
        expiresAt = createdAt + AttachmentPolicy.LIFETIME_MILLIS,
        stage = AttachmentStage.RESERVE,
        reserveRequestId = REQUEST_ID,
        reserveBodyJson = "{\"expected_version\":3,\"mime_type\":\"image/jpeg\"}",
        completeBodyJson =
            "{\"expected_version\":3,\"ocr_state\":\"ready\",\"ocr_text\":\"recognized\",\"ocr_truncated\":true}",
        nextRetryAt = createdAt,
    )

    private suspend fun assertAttachmentConflict(block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(thrown is AttachmentRequestConflictException)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertNotNull(thrown)
    }

    private companion object {
        const val OWNER_A = "10000000-0000-4000-8000-000000000001"
        const val OWNER_B = "10000000-0000-4000-8000-000000000002"
        const val ITEM_A = "20000000-0000-4000-8000-000000000001"
        const val ITEM_B = "20000000-0000-4000-8000-000000000002"
        const val OPERATION_A = "30000000-0000-4000-8000-000000000001"
        const val OPERATION_B = "30000000-0000-4000-8000-000000000002"
        const val REQUEST_ID = "40000000-0000-4000-8000-000000000001"
        const val COMPLETE_REQUEST_A = "50000000-0000-4000-8000-000000000001"
        const val ASSET_A = "60000000-0000-4000-8000-000000000001"
        const val LEASE_A = "70000000-0000-4000-8000-000000000001"
        const val LEASE_B = "70000000-0000-4000-8000-000000000002"
        const val FIFTEEN_MINUTES = 15L * 60L * 1_000L
    }
}
