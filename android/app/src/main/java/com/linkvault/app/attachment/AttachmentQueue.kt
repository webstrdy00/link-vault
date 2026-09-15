package com.linkvault.app.attachment

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.linkvault.app.storage.OutboxPolicy
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "pending_attachments",
    indices = [
        Index(value = ["owner_id", "item_id"]),
        Index(value = ["owner_id", "stage", "next_retry_at"]),
        Index(value = ["owner_id", "expires_at"]),
    ],
)
data class PendingAttachment(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "base_expected_version")
    val baseExpectedVersion: Long,
    @ColumnInfo(name = "session_generation")
    val sessionGeneration: Long,
    @ColumnInfo(name = "local_file_name")
    val localFileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "ocr_state")
    val ocrState: AttachmentOcrState,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String?,
    @ColumnInfo(name = "ocr_truncated")
    val ocrTruncated: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    val stage: AttachmentStage,
    @ColumnInfo(name = "resume_stage")
    val resumeStage: AttachmentStage? = null,
    @ColumnInfo(name = "reserve_request_id")
    val reserveRequestId: String,
    @ColumnInfo(name = "reserve_body_json")
    val reserveBodyJson: String,
    @ColumnInfo(name = "complete_request_id")
    val completeRequestId: String? = null,
    @ColumnInfo(name = "complete_body_json")
    val completeBodyJson: String,
    @ColumnInfo(name = "server_asset_id")
    val serverAssetId: String? = null,
    @ColumnInfo(name = "server_object_path")
    val serverObjectPath: String? = null,
    @ColumnInfo(name = "reservation_expires_at")
    val reservationExpiresAt: Long? = null,
    @ColumnInfo(name = "reservation_received_at")
    val reservationReceivedAt: Long? = null,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long,
    @ColumnInfo(name = "lease_token")
    val leaseToken: String? = null,
    @ColumnInfo(name = "lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
)

enum class AttachmentStage {
    RESERVE,
    UPLOAD,
    COMPLETE,
    SAVED,
    RETRY,
    WAITING_LOGIN,
    CONFLICT,
    EXPIRED,
    FAILED,
}

enum class AttachmentOcrState(val wireValue: String) {
    NOT_REQUESTED("not_requested"),
    READY("ready"),
    FAILED("failed"),
}

class AttachmentRequestConflictException(operationId: String) : IllegalStateException(
    "Attachment operation $operationId is already bound to different immutable input.",
)

@Dao
abstract class AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(entry: PendingAttachment): Long

    @Query("SELECT * FROM pending_attachments WHERE operation_id = :operationId")
    abstract suspend fun find(operationId: String): PendingAttachment?

    @Transaction
    open suspend fun insertImmutable(entry: PendingAttachment) {
        if (insertIfAbsent(entry) != -1L) return
        val existing = find(entry.operationId)
            ?: throw IllegalStateException("The attachment row changed while it was inserted.")
        if (
            existing.ownerId != entry.ownerId ||
            existing.itemId != entry.itemId ||
            existing.baseExpectedVersion != entry.baseExpectedVersion ||
            existing.sessionGeneration != entry.sessionGeneration ||
            existing.localFileName != entry.localFileName ||
            existing.mimeType != entry.mimeType ||
            existing.ocrState != entry.ocrState ||
            existing.ocrText != entry.ocrText ||
            existing.ocrTruncated != entry.ocrTruncated ||
            existing.reserveRequestId != entry.reserveRequestId ||
            existing.reserveBodyJson != entry.reserveBodyJson ||
            existing.completeBodyJson != entry.completeBodyJson
        ) {
            throw AttachmentRequestConflictException(entry.operationId)
        }
    }

    @Query(
        """
        SELECT * FROM pending_attachments
        WHERE owner_id = :ownerId
          AND (:itemId IS NULL OR item_id = :itemId)
        ORDER BY created_at ASC, operation_id ASC
        """,
    )
    abstract fun observe(ownerId: String, itemId: String?): Flow<List<PendingAttachment>>

    @Query(
        """
        SELECT * FROM pending_attachments
        WHERE owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND expires_at > :now
          AND stage IN ('reserve', 'upload', 'complete', 'retry')
          AND (stage != 'retry' OR next_retry_at <= :now)
          AND (lease_token IS NULL OR lease_until IS NULL OR lease_until <= :now)
        ORDER BY created_at ASC, operation_id ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun nextClaimCandidate(
        ownerId: String,
        sessionGeneration: Long,
        now: Long,
    ): PendingAttachment?

    @Query(
        """
        UPDATE pending_attachments
        SET stage = CASE WHEN stage = 'retry' THEN resume_stage ELSE stage END,
            resume_stage = NULL,
            attempt_count = attempt_count + 1,
            lease_token = :leaseToken,
            lease_until = :leaseUntil,
            error_code = NULL,
            error_message = NULL
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND expires_at > :now
          AND stage IN ('reserve', 'upload', 'complete', 'retry')
          AND (stage != 'retry' OR (resume_stage IS NOT NULL AND next_retry_at <= :now))
          AND (lease_token IS NULL OR lease_until IS NULL OR lease_until <= :now)
        """,
    )
    protected abstract suspend fun claimCandidate(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        now: Long,
        leaseToken: String,
        leaseUntil: Long,
    ): Int

    @Transaction
    open suspend fun claimNext(
        ownerId: String,
        sessionGeneration: Long,
        now: Long,
        leaseUntil: Long,
        leaseToken: String = UUID.randomUUID().toString(),
    ): PendingAttachment? {
        val candidate = nextClaimCandidate(ownerId, sessionGeneration, now) ?: return null
        if (
            claimCandidate(
                ownerId = ownerId,
                operationId = candidate.operationId,
                sessionGeneration = sessionGeneration,
                now = now,
                leaseToken = leaseToken,
                leaseUntil = leaseUntil,
            ) != 1
        ) return null
        return find(candidate.operationId)
    }

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'upload',
            complete_request_id = :completeRequestId,
            server_asset_id = :assetId,
            server_object_path = :objectPath,
            reservation_expires_at = :reservationExpiresAt,
            reservation_received_at = :now,
            lease_token = NULL,
            lease_until = NULL
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage = 'reserve'
          AND lease_token = :leaseToken
          AND lease_until IS NOT NULL
          AND lease_until > :now
          AND expires_at > :now
        """,
    )
    abstract suspend fun completeReservation(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        leaseToken: String,
        now: Long,
        completeRequestId: String,
        assetId: String,
        objectPath: String,
        reservationExpiresAt: Long,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'complete', lease_token = NULL, lease_until = NULL
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage = 'upload'
          AND lease_token = :leaseToken
          AND lease_until IS NOT NULL
          AND lease_until > :now
          AND expires_at > :now
        """,
    )
    abstract suspend fun completeUpload(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        leaseToken: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'reserve',
            reserve_request_id = :reserveRequestId,
            complete_request_id = NULL,
            server_asset_id = NULL,
            server_object_path = NULL,
            reservation_expires_at = NULL,
            reservation_received_at = NULL,
            next_retry_at = :now,
            lease_token = NULL,
            lease_until = NULL,
            error_code = NULL,
            error_message = NULL
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND lease_token = :leaseToken
          AND lease_until IS NOT NULL
          AND lease_until > :now
          AND reservation_expires_at IS NOT NULL
          AND expires_at > :now
          AND stage IN ('upload', 'complete')
        """,
    )
    abstract suspend fun rotateExpiredReservation(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        leaseToken: String,
        reserveRequestId: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET lease_until = :leaseUntil
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage = 'complete'
          AND lease_token = :leaseToken
          AND lease_until IS NOT NULL
          AND lease_until > :now
          AND expires_at > :now
        """,
    )
    abstract suspend fun renewCompleteLease(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        leaseToken: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'saved',
            resume_stage = NULL,
            lease_token = NULL,
            lease_until = NULL,
            error_code = NULL,
            error_message = NULL
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage = 'complete'
          AND lease_token = :leaseToken
          AND lease_until IS NOT NULL
          AND lease_until > :now
          AND expires_at > :now
        """,
    )
    abstract suspend fun completeSaved(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        leaseToken: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = :failureStage,
            resume_stage = :resumeStage,
            next_retry_at = :nextRetryAt,
            lease_token = NULL,
            lease_until = NULL,
            error_code = :errorCode,
            error_message = :errorMessage
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage = :claimedStage
          AND lease_token = :leaseToken
          AND lease_until IS NOT NULL
          AND lease_until > :now
        """,
    )
    abstract suspend fun completeFailure(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        leaseToken: String,
        now: Long,
        claimedStage: AttachmentStage,
        failureStage: AttachmentStage,
        resumeStage: AttachmentStage?,
        nextRetryAt: Long,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'expired',
            resume_stage = NULL,
            lease_token = NULL,
            lease_until = NULL,
            error_code = 'ATTACHMENT_EXPIRED',
            error_message = 'The local attachment expired before it was saved.'
        WHERE expires_at <= :now
          AND stage NOT IN ('saved', 'expired')
        """,
    )
    abstract suspend fun expireDue(now: Long): Int

    @Query(
        """
        UPDATE pending_attachments
        SET stage = 'expired',
            resume_stage = NULL,
            lease_token = NULL,
            lease_until = NULL,
            error_code = 'ATTACHMENT_EXPIRED',
            error_message = 'The local attachment expired before it was saved.'
        WHERE owner_id = :ownerId
          AND expires_at <= :now
          AND stage NOT IN ('saved', 'expired')
        """,
    )
    abstract suspend fun expireOwnerDue(
        ownerId: String,
        now: Long,
    ): Int

    @Query(
        """
        SELECT * FROM pending_attachments
        WHERE (stage = 'saved' OR (stage = 'expired' AND expires_at <= :now))
          AND (:ownerId IS NULL OR owner_id = :ownerId)
        """,
    )
    abstract suspend fun filesReadyForDeletion(
        ownerId: String?,
        now: Long,
    ): List<PendingAttachment>

    @Query("SELECT local_file_name FROM pending_attachments")
    abstract suspend fun retainedFileNames(): List<String>

    @Query(
        """
        UPDATE pending_attachments
        SET stage = CASE WHEN stage = 'failed' THEN 'retry' ELSE stage END,
            next_retry_at = CASE WHEN stage = 'failed' THEN :now ELSE next_retry_at END,
            error_code = NULL,
            error_message = NULL
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage IN ('retry', 'failed')
          AND expires_at > :now
        """,
    )
    protected abstract suspend fun retryActive(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        now: Long,
    ): Int

    @Transaction
    open suspend fun retry(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
        now: Long,
    ) {
        if (retryActive(ownerId, operationId, sessionGeneration, now) == 1) return
        val existing = find(operationId)
        if (existing != null && existing.ownerId == ownerId) {
            check(existing.expiresAt > now) { "Expired attachments require a new explicit operation." }
            error("Only retryable or failed attachments can be retried.")
        }
    }

    @Query(
        """
        UPDATE pending_attachments
        SET session_generation = :sessionGeneration,
            stage = CASE
                WHEN stage = 'waiting_login' AND resume_stage IS NOT NULL THEN resume_stage
                ELSE stage
            END,
            resume_stage = CASE WHEN stage = 'waiting_login' THEN NULL ELSE resume_stage END,
            next_retry_at = CASE WHEN stage = 'waiting_login' THEN :now ELSE next_retry_at END,
            error_code = CASE WHEN stage = 'waiting_login' THEN NULL ELSE error_code END,
            error_message = CASE WHEN stage = 'waiting_login' THEN NULL ELSE error_message END,
            lease_token = NULL,
            lease_until = NULL
        WHERE owner_id = :ownerId
          AND (
            session_generation != :sessionGeneration
            OR (stage = 'waiting_login' AND resume_stage IS NOT NULL)
          )
        """,
    )
    abstract suspend fun bindOwnerSession(
        ownerId: String,
        sessionGeneration: Long,
        now: Long,
    ): Int

    @Query(
        """
        SELECT MIN(
            CASE
                WHEN lease_token IS NOT NULL AND lease_until IS NOT NULL THEN
                    MIN(lease_until, expires_at)
                WHEN stage = 'retry' THEN MIN(next_retry_at, expires_at)
                WHEN stage IN ('conflict', 'failed', 'expired') THEN expires_at
                ELSE 0
            END
        )
        FROM pending_attachments
        WHERE owner_id = :ownerId
          AND session_generation = :sessionGeneration
          AND stage IN (
            'reserve', 'upload', 'complete', 'retry', 'conflict', 'failed', 'expired'
          )
          AND (stage != 'expired' OR expires_at > :now)
        """,
    )
    abstract suspend fun nextWakeAt(
        ownerId: String,
        sessionGeneration: Long,
        now: Long,
    ): Long?

    @Query("SELECT DISTINCT owner_id FROM pending_attachments")
    abstract suspend fun ownerIds(): List<String>

    @Query(
        """
        DELETE FROM pending_attachments
        WHERE operation_id = :operationId
          AND owner_id = :ownerId
          AND session_generation = :sessionGeneration
        """,
    )
    abstract suspend fun deleteOperation(
        ownerId: String,
        operationId: String,
        sessionGeneration: Long,
    ): Int

    @Query("DELETE FROM pending_attachments WHERE owner_id = :ownerId")
    abstract suspend fun deleteOwner(ownerId: String): Int

    @Query("DELETE FROM pending_attachments")
    abstract suspend fun deleteAll(): Int
}

internal object AttachmentPolicy {
    const val MAX_BYTES = ImagePreparation.MAX_OUTPUT_BYTES
    const val MAX_OCR_CODE_POINTS = OcrProcessor.MAX_TEXT_CODE_POINTS
    const val MAX_BATCH_SIZE = OutboxPolicy.MAX_BATCH_SIZE
    const val RESERVATION_LIFETIME_MILLIS = 15L * 60L * 1_000L
    const val LIFETIME_MILLIS = OutboxPolicy.REQUEST_LIFETIME_MILLIS
    const val LEASE_MILLIS = OutboxPolicy.CLAIM_LEASE_MILLIS
    const val MIN_RETRY_MILLIS = OutboxPolicy.MIN_BACKOFF_MILLIS

    // COMPLETE may already be committed: replay its frozen receipt before deciding expiry.
    fun reservationExpiredBeforeUpload(stage: AttachmentStage, receivedAt: Long?, now: Long): Boolean =
        stage == AttachmentStage.UPLOAD && receivedAt != null &&
            now >= receivedAt && now - receivedAt >= RESERVATION_LIFETIME_MILLIS

    fun expiresAt(createdAt: Long): Long = OutboxPolicy.expiresAt(createdAt)

    fun leaseUntil(now: Long): Long = OutboxPolicy.leaseUntil(now)
}
