package com.linkvault.app.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.linkvault.app.attachment.AttachmentDao
import com.linkvault.app.attachment.AttachmentOcrState
import com.linkvault.app.attachment.AttachmentStage
import com.linkvault.app.attachment.PendingAttachment
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["owner_id", "state", "next_attempt_at"]),
        Index(value = ["owner_id", "expires_at"]),
    ],
)
data class OutboxEntry(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    val method: String,
    val path: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    val state: OutboxState,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    @ColumnInfo(name = "result_json")
    val resultJson: String? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "lease_until")
    val leaseUntil: Long? = null,
)

enum class OutboxState {
    PENDING,
    RUNNING,
    RETRY,
    WAITING_LOGIN,
    FAILED,
    CONFLICT,
    EXPIRED,
    SAVED,
}

@Entity(
    tableName = "cached_items",
    primaryKeys = ["owner_id", "item_id", "is_detail"],
    indices = [Index(value = ["owner_id", "is_detail", "server_created_at", "item_id"])],
)
data class CachedItem(
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "response_json")
    val responseJson: String,
    @ColumnInfo(name = "server_version")
    val serverVersion: Long,
    @ColumnInfo(name = "server_created_at")
    val serverCreatedAt: String,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
    @ColumnInfo(name = "is_detail")
    val isDetail: Boolean,
)

@Entity(
    tableName = "item_deletion_tombstones",
    primaryKeys = ["owner_id", "item_id"],
)
data class ItemDeletionTombstone(
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "observed_deleted_at")
    val observedDeletedAt: Long,
)

@Entity(tableName = "cached_categories")
data class CachedCategories(
    @PrimaryKey
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "response_json")
    val responseJson: String,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)

@Entity(tableName = "pending_inputs")
data class PendingInput(
    @PrimaryKey
    @ColumnInfo(name = "local_id")
    val localId: String,
    val text: String,
    @ColumnInfo(name = "selected_url")
    val selectedUrl: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
)

class RequestIdConflictException(requestId: String) : IllegalStateException(
    "Request ID $requestId is already bound to a different request.",
)

@Dao
abstract class OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(entry: OutboxEntry): Long

    @Query("SELECT * FROM outbox WHERE request_id = :requestId")
    abstract suspend fun findByRequestId(requestId: String): OutboxEntry?

    @Transaction
    open suspend fun insertImmutable(entry: OutboxEntry) {
        if (insertIfAbsent(entry) != -1L) return
        val existing = findByRequestId(entry.requestId)
            ?: throw IllegalStateException("The outbox row changed while it was being inserted.")
        if (
            existing.ownerId != entry.ownerId ||
            existing.method != entry.method ||
            existing.path != entry.path ||
            existing.payloadJson != entry.payloadJson
        ) {
            throw RequestIdConflictException(entry.requestId)
        }
    }

    @Query(
        """
        SELECT * FROM outbox
        WHERE owner_id = :ownerId
        ORDER BY created_at ASC, request_id ASC
        """,
    )
    abstract fun observe(ownerId: String): Flow<List<OutboxEntry>>

    @Query(
        """
        SELECT * FROM outbox
        WHERE owner_id = :ownerId
          AND expires_at > :now
          AND (
            (state IN ('pending', 'retry') AND next_attempt_at <= :now)
            OR (state = 'running' AND lease_until IS NOT NULL AND lease_until <= :now)
          )
        ORDER BY created_at ASC, request_id ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun nextClaimCandidate(ownerId: String, now: Long): OutboxEntry?

    @Query(
        """
        UPDATE outbox
        SET state = 'running',
            attempt_count = attempt_count + 1,
            lease_until = :leaseUntil,
            error_code = NULL,
            error_message = NULL
        WHERE request_id = :requestId
          AND owner_id = :ownerId
          AND expires_at > :now
          AND (
            (state IN ('pending', 'retry') AND next_attempt_at <= :now)
            OR (state = 'running' AND lease_until IS NOT NULL AND lease_until <= :now)
          )
        """,
    )
    protected abstract suspend fun claimCandidate(
        ownerId: String,
        requestId: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Transaction
    open suspend fun claimNext(ownerId: String, now: Long, leaseUntil: Long): OutboxEntry? {
        val candidate = nextClaimCandidate(ownerId, now) ?: return null
        if (claimCandidate(ownerId, candidate.requestId, now, leaseUntil) != 1) return null
        return findByRequestId(candidate.requestId)
    }

    @Query(
        """
        UPDATE outbox
        SET state = 'expired', lease_until = NULL,
            error_code = 'REQUEST_EXPIRED',
            error_message = 'Automatic retry expired. User confirmation is required.'
        WHERE owner_id = :ownerId
          AND expires_at <= :now
          AND state IN ('pending', 'running', 'retry', 'waiting_login')
          AND (state != 'running' OR lease_until IS NULL OR lease_until <= :now)
        """,
    )
    abstract suspend fun expireAutomaticRequests(ownerId: String, now: Long): Int

    @Query(
        """
        UPDATE outbox
        SET state = 'saved', result_json = :resultJson,
            error_code = NULL, error_message = NULL, lease_until = NULL
        WHERE owner_id = :ownerId
          AND request_id = :requestId
          AND state = 'running'
          AND lease_until = :leaseUntil
        """,
    )
    abstract suspend fun completeSaved(
        ownerId: String,
        requestId: String,
        leaseUntil: Long,
        resultJson: String,
    ): Int

    @Query(
        """
        UPDATE outbox
        SET state = :state, next_attempt_at = :nextAttemptAt,
            error_code = :errorCode, error_message = :errorMessage,
            lease_until = NULL
        WHERE owner_id = :ownerId
          AND request_id = :requestId
          AND state = 'running'
          AND lease_until = :leaseUntil
        """,
    )
    abstract suspend fun completeFailure(
        ownerId: String,
        requestId: String,
        leaseUntil: Long,
        state: OutboxState,
        nextAttemptAt: Long,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    @Query(
        """
        DELETE FROM outbox
        WHERE owner_id = :ownerId
          AND request_id = :requestId
          AND state = 'saved'
        """,
    )
    abstract suspend fun acknowledge(ownerId: String, requestId: String): Int

    @Query("DELETE FROM outbox WHERE owner_id = :ownerId AND request_id = :requestId")
    abstract suspend fun discard(ownerId: String, requestId: String): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM outbox
            WHERE owner_id = :ownerId
              AND request_id = :requestId
              AND state = 'running'
              AND lease_until = :leaseUntil
        )
        """,
    )
    abstract suspend fun isClaimed(
        ownerId: String,
        requestId: String,
        leaseUntil: Long,
    ): Boolean

    @Query(
        """
        DELETE FROM outbox
        WHERE owner_id = :ownerId
          AND request_id = :requestId
          AND state = 'running'
          AND lease_until = :leaseUntil
        """,
    )
    abstract suspend fun discardClaimed(
        ownerId: String,
        requestId: String,
        leaseUntil: Long,
    ): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM outbox
            WHERE owner_id = :ownerId
              AND request_id = :requestId
              AND method = 'DELETE'
              AND path = '/items/' || :itemId
              AND state IN ('running', 'saved')
        )
        """,
    )
    abstract suspend fun isItemDeleteReceipt(
        ownerId: String,
        itemId: String,
        requestId: String,
    ): Boolean

    @Query(
        """
        DELETE FROM outbox
        WHERE owner_id = :ownerId
          AND (path = :exactItemPath OR path LIKE :nestedItemPathPattern)
          AND (:preservedRequestId IS NULL OR request_id != :preservedRequestId)
        """,
    )
    abstract suspend fun deleteItemRequests(
        ownerId: String,
        exactItemPath: String,
        nestedItemPathPattern: String,
        preservedRequestId: String?,
    ): Int

    @Query(
        """
        UPDATE outbox
        SET state = 'expired', lease_until = NULL,
            error_code = 'REQUEST_EXPIRED',
            error_message = 'Automatic retry expired. User confirmation is required.'
        WHERE owner_id = :ownerId
          AND request_id = :requestId
          AND state IN ('retry', 'failed')
          AND expires_at <= :now
        """,
    )
    protected abstract suspend fun expireManualRetry(
        ownerId: String,
        requestId: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE outbox
        SET state = CASE WHEN state = 'failed' THEN 'pending' ELSE 'retry' END,
            next_attempt_at = CASE WHEN state = 'failed' THEN :now ELSE next_attempt_at END,
            error_code = NULL, error_message = NULL, lease_until = NULL
        WHERE owner_id = :ownerId
          AND request_id = :requestId
          AND state IN ('retry', 'failed')
          AND expires_at > :now
        """,
    )
    protected abstract suspend fun retryManual(
        ownerId: String,
        requestId: String,
        now: Long,
    ): Int

    @Transaction
    open suspend fun retry(ownerId: String, requestId: String, now: Long) {
        if (retryManual(ownerId, requestId, now) == 1) return
        if (expireManualRetry(ownerId, requestId, now) == 1) return
        val existing = findByRequestId(requestId)
        if (existing != null && existing.ownerId == ownerId) {
            throw IllegalStateException("Only retryable or failed requests can be retried.")
        }
    }

    @Query(
        """
        SELECT COUNT(*) FROM outbox
        WHERE owner_id = :ownerId
          AND state IN ('pending', 'running', 'retry', 'waiting_login')
        """,
    )
    abstract suspend fun pendingCount(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state != 'saved'")
    abstract suspend fun retainedCount(): Int

    @Query(
        """
        UPDATE outbox
        SET state = 'pending', next_attempt_at = :now,
            error_code = NULL, error_message = NULL, lease_until = NULL
        WHERE owner_id = :ownerId
          AND state = 'waiting_login'
          AND expires_at > :now
        """,
    )
    abstract suspend fun resumeWaitingLogin(ownerId: String, now: Long): Int

    @Query(
        """
        SELECT MIN(
            CASE
                WHEN state = 'running' THEN COALESCE(lease_until, expires_at)
                ELSE MIN(next_attempt_at, expires_at)
            END
        )
        FROM outbox
        WHERE owner_id = :ownerId
          AND state IN ('pending', 'running', 'retry')
        """,
    )
    abstract suspend fun nextWakeAt(ownerId: String): Long?

    @Query("SELECT DISTINCT owner_id FROM outbox")
    abstract suspend fun ownerIds(): List<String>

    @Query("DELETE FROM outbox WHERE owner_id = :ownerId")
    abstract suspend fun deleteOwner(ownerId: String): Int

    @Query("DELETE FROM outbox")
    abstract suspend fun deleteAll(): Int
}

@Dao
abstract class CachedItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(item: CachedItem): Long

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM item_deletion_tombstones
            WHERE owner_id = :ownerId AND item_id = :itemId
        )
        """,
    )
    protected abstract suspend fun isDeleted(ownerId: String, itemId: String): Boolean

    @Query(
        """
        UPDATE cached_items
        SET response_json = :responseJson,
            server_version = :serverVersion,
            server_created_at = :serverCreatedAt,
            fetched_at = :fetchedAt
        WHERE owner_id = :ownerId
          AND item_id = :itemId
          AND is_detail = :isDetail
          AND server_version <= :serverVersion
          AND NOT EXISTS(
            SELECT 1 FROM item_deletion_tombstones
            WHERE owner_id = :ownerId AND item_id = :itemId
          )
        """,
    )
    protected abstract suspend fun updateIfNotOlder(
        ownerId: String,
        itemId: String,
        isDetail: Boolean,
        responseJson: String,
        serverVersion: Long,
        serverCreatedAt: String,
        fetchedAt: Long,
    ): Int

    @Transaction
    open suspend fun upsert(item: CachedItem) {
        if (isDeleted(item.ownerId, item.itemId)) return
        if (insertIfAbsent(item) != -1L) return
        updateIfNotOlder(
            ownerId = item.ownerId,
            itemId = item.itemId,
            isDetail = item.isDetail,
            responseJson = item.responseJson,
            serverVersion = item.serverVersion,
            serverCreatedAt = item.serverCreatedAt,
            fetchedAt = item.fetchedAt,
        )
    }

    @Query("DELETE FROM cached_items WHERE owner_id = :ownerId AND is_detail = 0")
    protected abstract suspend fun deleteList(ownerId: String): Int

    @Query(
        """
        DELETE FROM cached_items
        WHERE owner_id = :ownerId
          AND is_detail = 0
          AND item_id NOT IN (:retainedItemIds)
        """,
    )
    protected abstract suspend fun deleteListExcept(
        ownerId: String,
        retainedItemIds: List<String>,
    ): Int

    @Transaction
    open suspend fun cacheList(ownerId: String, items: List<CachedItem>, replace: Boolean) {
        require(items.all { it.ownerId == ownerId && !it.isDetail }) {
            "List cache rows must belong to the requested owner and must not be detail rows."
        }
        items.forEach { upsert(it) }
        if (replace) {
            val retainedItemIds = items.map(CachedItem::itemId).distinct()
            if (retainedItemIds.isEmpty()) {
                deleteList(ownerId)
            } else {
                deleteListExcept(ownerId, retainedItemIds)
            }
        }
    }

    @Query(
        """
        SELECT * FROM cached_items
        WHERE owner_id = :ownerId AND is_detail = 0
          AND NOT EXISTS(
            SELECT 1 FROM item_deletion_tombstones
            WHERE owner_id = :ownerId AND item_id = cached_items.item_id
          )
        ORDER BY server_created_at DESC, item_id DESC
        """,
    )
    abstract fun observeList(ownerId: String): Flow<List<CachedItem>>

    @Query(
        """
        SELECT * FROM cached_items
        WHERE owner_id = :ownerId AND item_id = :itemId AND is_detail = 1
          AND NOT EXISTS(
            SELECT 1 FROM item_deletion_tombstones
            WHERE owner_id = :ownerId AND item_id = :itemId
          )
        """,
    )
    abstract suspend fun readDetail(ownerId: String, itemId: String): CachedItem?

    @Query("DELETE FROM cached_items WHERE owner_id = :ownerId AND item_id = :itemId")
    abstract suspend fun deleteItem(ownerId: String, itemId: String): Int

    @Query("DELETE FROM cached_items WHERE owner_id = :ownerId")
    abstract suspend fun deleteOwner(ownerId: String): Int

    @Query("DELETE FROM cached_items")
    abstract suspend fun deleteAll(): Int
}

@Dao
abstract class ItemDeletionTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(tombstone: ItemDeletionTombstone): Long

    @Query(
        """
        UPDATE item_deletion_tombstones
        SET observed_deleted_at = :observedDeletedAt
        WHERE owner_id = :ownerId
          AND item_id = :itemId
          AND observed_deleted_at < :observedDeletedAt
        """,
    )
    protected abstract suspend fun updateObservedDeletedAt(
        ownerId: String,
        itemId: String,
        observedDeletedAt: Long,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'expired',
            resume_stage = NULL,
            expires_at = MIN(expires_at, :observedDeletedAt),
            lease_token = NULL,
            lease_until = NULL,
            error_code = 'ITEM_DELETED',
            error_message = 'The item was deleted before the attachment was saved.'
        WHERE owner_id = :ownerId AND item_id = :itemId
        """,
    )
    abstract suspend fun cancelItemAttachments(
        ownerId: String,
        itemId: String,
        observedDeletedAt: Long,
    ): Int

    @Transaction
    open suspend fun markDeleted(ownerId: String, itemId: String, observedDeletedAt: Long) {
        if (
            insertIfAbsent(
                ItemDeletionTombstone(
                    ownerId = ownerId,
                    itemId = itemId,
                    observedDeletedAt = observedDeletedAt,
                ),
            ) == -1L
        ) {
            updateObservedDeletedAt(ownerId, itemId, observedDeletedAt)
        }
        cancelItemAttachments(ownerId, itemId, observedDeletedAt)
    }

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM item_deletion_tombstones
            WHERE owner_id = :ownerId AND item_id = :itemId
        )
        """,
    )
    abstract suspend fun isDeleted(ownerId: String, itemId: String): Boolean

    @Query("SELECT item_id FROM item_deletion_tombstones WHERE owner_id = :ownerId")
    abstract fun observeItemIds(ownerId: String): Flow<List<String>>

    @Query("SELECT item_id FROM item_deletion_tombstones WHERE owner_id = :ownerId")
    abstract suspend fun readItemIds(ownerId: String): List<String>

    @Query("DELETE FROM item_deletion_tombstones WHERE owner_id = :ownerId")
    abstract suspend fun deleteOwner(ownerId: String): Int

    @Query("DELETE FROM item_deletion_tombstones")
    abstract suspend fun deleteAll(): Int
}

@Dao
abstract class CachedCategoriesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(categories: CachedCategories)

    @Query("SELECT * FROM cached_categories WHERE owner_id = :ownerId")
    abstract suspend fun read(ownerId: String): CachedCategories?

    @Query("SELECT owner_id FROM cached_categories")
    abstract suspend fun ownerIds(): List<String>

    @Query("DELETE FROM cached_categories WHERE owner_id = :ownerId")
    abstract suspend fun deleteOwner(ownerId: String): Int

    @Query("DELETE FROM cached_categories")
    abstract suspend fun deleteAll(): Int
}

@Dao
abstract class PendingInputDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(input: PendingInput): Long

    @Query(
        """
        UPDATE pending_inputs
        SET text = :text, selected_url = :selectedUrl,
            created_at = :createdAt, expires_at = :expiresAt
        WHERE local_id = :localId
        """,
    )
    protected abstract suspend fun updateDraft(
        localId: String,
        text: String,
        selectedUrl: String?,
        createdAt: Long,
        expiresAt: Long,
    ): Int

    @Transaction
    open suspend fun saveDraft(input: PendingInput) {
        if (insertIfAbsent(input) == -1L) {
            updateDraft(
                localId = input.localId,
                text = input.text,
                selectedUrl = input.selectedUrl,
                createdAt = input.createdAt,
                expiresAt = input.expiresAt,
            )
        }
    }

    @Query(
        """
        SELECT * FROM pending_inputs
        WHERE local_id = :localId AND expires_at > :now
        """,
    )
    abstract suspend fun readActive(localId: String, now: Long): PendingInput?

    @Query("DELETE FROM pending_inputs WHERE local_id = :localId")
    abstract suspend fun delete(localId: String): Int

    @Query("DELETE FROM pending_inputs WHERE expires_at <= :now")
    abstract suspend fun deleteExpired(now: Long): Int

    @Query(
        """
        DELETE FROM pending_inputs
        WHERE text = :text AND created_at <= :createdAt
        """,
    )
    abstract suspend fun deleteMatchingCapture(text: String, createdAt: Long): Int

    @Query("DELETE FROM pending_inputs")
    abstract suspend fun deleteAll(): Int

    @Query("SELECT * FROM pending_inputs WHERE local_id = :localId")
    abstract suspend fun read(localId: String): PendingInput?
}

class VaultConverters {
    @TypeConverter
    fun outboxStateToStorage(state: OutboxState): String = state.name.lowercase()

    @TypeConverter
    fun outboxStateFromStorage(value: String): OutboxState =
        OutboxState.valueOf(value.uppercase())

    @TypeConverter
    fun attachmentStageToStorage(stage: AttachmentStage?): String? = stage?.name?.lowercase()

    @TypeConverter
    fun attachmentStageFromStorage(value: String?): AttachmentStage? =
        value?.let { AttachmentStage.valueOf(it.uppercase()) }

    @TypeConverter
    fun attachmentOcrStateToStorage(state: AttachmentOcrState): String =
        state.name.lowercase()

    @TypeConverter
    fun attachmentOcrStateFromStorage(value: String): AttachmentOcrState =
        AttachmentOcrState.valueOf(value.uppercase())
}

@Database(
    entities = [
        OutboxEntry::class,
        CachedItem::class,
        CachedCategories::class,
        PendingInput::class,
        PendingAttachment::class,
        ItemDeletionTombstone::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(VaultConverters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun cachedItemDao(): CachedItemDao
    abstract fun cachedCategoriesDao(): CachedCategoriesDao
    abstract fun pendingInputDao(): PendingInputDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun deletedItems(): ItemDeletionTombstoneDao

    suspend fun markItemDeleted(
        ownerId: String,
        itemId: String,
        observedDeletedAt: Long,
        preservedRequestId: String,
    ): Boolean = withTransaction {
        val preservedReceiptEligible = outboxDao().isItemDeleteReceipt(
            ownerId = ownerId,
            itemId = itemId,
            requestId = preservedRequestId,
        )
        deletedItems().markDeleted(ownerId, itemId, observedDeletedAt)
        cachedItemDao().deleteItem(ownerId, itemId)
        val exactItemPath = "/items/$itemId"
        outboxDao().deleteItemRequests(
            ownerId = ownerId,
            exactItemPath = exactItemPath,
            nestedItemPathPattern = "$exactItemPath/%",
            preservedRequestId = preservedRequestId,
        )
        preservedReceiptEligible
    }

    suspend fun isItemDeleted(ownerId: String, itemId: String): Boolean =
        deletedItems().isDeleted(ownerId, itemId)

    suspend fun clearOwner(ownerId: String) {
        withTransaction {
            attachmentDao().deleteOwner(ownerId)
            outboxDao().deleteOwner(ownerId)
            cachedItemDao().deleteOwner(ownerId)
            cachedCategoriesDao().deleteOwner(ownerId)
            pendingInputDao().deleteAll()
            deletedItems().deleteOwner(ownerId)
        }
    }

    suspend fun clearAllOwners() {
        withTransaction {
            attachmentDao().deleteAll()
            outboxDao().deleteAll()
            cachedItemDao().deleteAll()
            cachedCategoriesDao().deleteAll()
            pendingInputDao().deleteAll()
            deletedItems().deleteAll()
        }
    }

    companion object {
        private const val DATABASE_NAME = "link_vault.db"

        fun create(context: Context): VaultDatabase = Room.databaseBuilder(
            context.applicationContext,
            VaultDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cached_categories` (
                `owner_id` TEXT NOT NULL,
                `response_json` TEXT NOT NULL,
                `fetched_at` INTEGER NOT NULL,
                PRIMARY KEY(`owner_id`)
            )
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_attachments` (
                `operation_id` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `item_id` TEXT NOT NULL,
                `base_expected_version` INTEGER NOT NULL,
                `session_generation` INTEGER NOT NULL,
                `local_file_name` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `ocr_state` TEXT NOT NULL,
                `ocr_text` TEXT,
                `ocr_truncated` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `expires_at` INTEGER NOT NULL,
                `stage` TEXT NOT NULL,
                `resume_stage` TEXT,
                `reserve_request_id` TEXT NOT NULL,
                `reserve_body_json` TEXT NOT NULL,
                `complete_request_id` TEXT,
                `complete_body_json` TEXT NOT NULL,
                `server_asset_id` TEXT,
                `server_object_path` TEXT,
                `reservation_expires_at` INTEGER,
                `reservation_received_at` INTEGER,
                `attempt_count` INTEGER NOT NULL,
                `next_retry_at` INTEGER NOT NULL,
                `lease_token` TEXT,
                `lease_until` INTEGER,
                `error_code` TEXT,
                `error_message` TEXT,
                PRIMARY KEY(`operation_id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_pending_attachments_owner_id_item_id`
            ON `pending_attachments` (`owner_id`, `item_id`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_pending_attachments_owner_id_stage_next_retry_at`
            ON `pending_attachments` (`owner_id`, `stage`, `next_retry_at`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_pending_attachments_owner_id_expires_at`
            ON `pending_attachments` (`owner_id`, `expires_at`)
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `item_deletion_tombstones` (
                `owner_id` TEXT NOT NULL,
                `item_id` TEXT NOT NULL,
                `observed_deleted_at` INTEGER NOT NULL,
                PRIMARY KEY(`owner_id`, `item_id`)
            )
            """.trimIndent(),
        )
    }
}
