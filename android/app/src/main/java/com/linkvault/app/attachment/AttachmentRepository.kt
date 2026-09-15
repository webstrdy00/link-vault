package com.linkvault.app.attachment

import android.content.Context
import androidx.room.withTransaction
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.storage.FailureAction
import com.linkvault.app.storage.OutboxRepository
import com.linkvault.app.storage.OutboxPolicy
import com.linkvault.app.storage.VaultDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class AttachmentRepository(
    appContext: Context,
    private val client: AccountClient,
    private val outboxRepository: OutboxRepository,
    private val database: VaultDatabase = VaultDatabase.create(appContext),
) {
    private val applicationContext = appContext.applicationContext
    private val attachments = database.attachmentDao()
    private val queueRoot = File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY)

    fun beginPreparation(ownerId: String): AttachmentPreparation {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val session = currentSession(owner)
        val operationId = UUID.randomUUID().toString()
        val destination = queueFile(owner, operationId)
        check(!destination.exists()) { "The attachment preparation target already exists." }
        val parent = checkNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) {
            "Could not create the private attachment directory."
        }
        return AttachmentPreparation(
            operationId = operationId,
            sessionGeneration = session.generation,
            file = destination,
        )
    }

    fun observe(ownerId: String, itemId: String? = null): Flow<List<PendingAttachment>> {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        check(client.sessionState.value.ownerId == owner) {
            "Attachments can only be observed by their current owner."
        }
        return attachments.observe(owner, itemId?.requireCanonicalUuid("Item ID"))
    }

    suspend fun enqueuePrepared(
        ownerId: String,
        itemId: String,
        expectedVersion: Long,
        file: File,
        mimeType: String,
        ocrState: AttachmentOcrState,
        ocrText: String?,
        ocrTruncated: Boolean,
        operationId: String,
        expectedSessionGeneration: Long,
    ): PendingAttachment {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val item = itemId.requireCanonicalUuid("Item ID")
        val operation = operationId.requireCanonicalUuid("Attachment operation ID")
        require(expectedVersion > 0L) { "Expected version must be positive." }
        require(mimeType in ALLOWED_MIME_TYPES) { "Unsupported attachment MIME type." }
        validateOcr(ocrState, ocrText, ocrTruncated)
        val initialSession = currentSession(owner, expectedSessionGeneration)
        val source = requireOwnedPrivateFile(file)
        require(source.length() in 1L..AttachmentPolicy.MAX_BYTES) {
            "Prepared attachment must contain at most 2 MB."
        }

        val destination = queueFile(owner, operation)
        val claimedInPlace = source == destination.canonicalFile
        if (!claimedInPlace) {
            withContext(Dispatchers.IO) {
                copyOwnedFile(source, destination)
            }
        }

        val now = System.currentTimeMillis()
        val entry = PendingAttachment(
            operationId = operation,
            ownerId = owner,
            itemId = item,
            baseExpectedVersion = expectedVersion,
            sessionGeneration = initialSession.generation,
            localFileName = queueRelativeName(owner, operation),
            mimeType = mimeType,
            ocrState = ocrState,
            ocrText = ocrText,
            ocrTruncated = ocrTruncated,
            createdAt = now,
            expiresAt = AttachmentPolicy.expiresAt(now),
            stage = AttachmentStage.RESERVE,
            reserveRequestId = UUID.randomUUID().toString(),
            reserveBodyJson = reserveBody(expectedVersion, mimeType),
            completeBodyJson = completeBody(
                expectedVersion = expectedVersion,
                ocrState = ocrState,
                ocrText = ocrText,
                ocrTruncated = ocrTruncated,
            ),
            nextRetryAt = now,
        )

        var preserveExistingFile = false
        val queued = try {
            withBoundSession(initialSession) {
                require(destination.length() in 1L..AttachmentPolicy.MAX_BYTES) {
                    "Prepared attachment changed before it was queued."
                }
                val existing = attachments.find(operation)
                if (existing != null) {
                    preserveExistingFile = existing.matchesPreparedInput(entry)
                    check(preserveExistingFile) {
                        "The attachment operation is already bound to different input."
                    }
                    existing
                } else {
                    attachments.insertImmutable(entry)
                    entry
                }
            }
        } catch (error: Exception) {
            if (!preserveExistingFile) destination.delete()
            throw error
        }
        AttachmentWorker.enqueue(applicationContext, owner)
        return queued
    }

    suspend fun retry(ownerId: String, operationId: String) {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val operation = operationId.requireCanonicalUuid("Attachment operation ID")
        val session = currentSession(owner)
        withBoundSession(session) {
            attachments.retry(owner, operation, session.generation, System.currentTimeMillis())
        }
        AttachmentWorker.enqueue(applicationContext, owner)
    }

    suspend fun discard(ownerId: String, operationId: String) {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val operation = operationId.requireCanonicalUuid("Attachment operation ID")
        val session = currentSession(owner)
        var removed: PendingAttachment? = null
        withBoundSession(session) {
            database.withTransaction {
                val existing = attachments.find(operation)
                if (
                    existing?.ownerId == owner &&
                    existing.sessionGeneration == session.generation &&
                    attachments.deleteOperation(owner, operation, session.generation) == 1
                ) {
                    removed = existing
                }
            }
        }
        removed?.let(::deleteQueueFile)
    }

    suspend fun confirmLatest(
        ownerId: String,
        oldOperationId: String,
        newExpectedVersion: Long,
    ): PendingAttachment {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val oldOperation = oldOperationId.requireCanonicalUuid("Attachment operation ID")
        require(newExpectedVersion > 0L) { "Expected version must be positive." }
        val session = currentSession(owner)
        val old = withBoundSession(session) {
            attachments.find(oldOperation)?.takeIf {
                it.ownerId == owner &&
                    it.sessionGeneration == session.generation &&
                    it.stage == AttachmentStage.CONFLICT &&
                    it.expiresAt > System.currentTimeMillis()
            } ?: error("Only an unexpired attachment conflict can be explicitly confirmed.")
        }
        require(newExpectedVersion > old.baseExpectedVersion) {
            "A confirmed attachment must use a newer reviewed item version."
        }
        val source = resolveQueueFile(old.localFileName)
        check(source.isFile) { "The attachment file is no longer available." }

        val operation = UUID.randomUUID().toString()
        val destination = queueFile(owner, operation)
        withContext(Dispatchers.IO) {
            copyOwnedFile(source, destination)
        }
        val now = System.currentTimeMillis()
        val replacement = old.copy(
            operationId = operation,
            baseExpectedVersion = newExpectedVersion,
            sessionGeneration = session.generation,
            localFileName = queueRelativeName(owner, operation),
            createdAt = now,
            expiresAt = AttachmentPolicy.expiresAt(now),
            stage = AttachmentStage.RESERVE,
            resumeStage = null,
            reserveRequestId = UUID.randomUUID().toString(),
            reserveBodyJson = reserveBody(newExpectedVersion, old.mimeType),
            completeRequestId = null,
            completeBodyJson = completeBody(
                expectedVersion = newExpectedVersion,
                ocrState = old.ocrState,
                ocrText = old.ocrText,
                ocrTruncated = old.ocrTruncated,
            ),
            serverAssetId = null,
            serverObjectPath = null,
            reservationExpiresAt = null,
            reservationReceivedAt = null,
            attemptCount = 0,
            nextRetryAt = now,
            leaseToken = null,
            leaseUntil = null,
            errorCode = null,
            errorMessage = null,
        )

        try {
            withBoundSession(session) {
                database.withTransaction {
                    val latestOld = attachments.find(oldOperation)
                    check(
                        latestOld?.ownerId == owner &&
                            latestOld.sessionGeneration == session.generation &&
                            latestOld.stage == AttachmentStage.CONFLICT &&
                            latestOld.expiresAt > now
                    ) { "The attachment conflict changed before it was confirmed." }
                    attachments.insertImmutable(replacement)
                    check(attachments.deleteOperation(owner, oldOperation, session.generation) == 1) {
                        "The original attachment conflict changed before replacement."
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        source.delete()
        AttachmentWorker.enqueue(applicationContext, owner)
        return replacement
    }

    suspend fun processBatch(ownerId: String): AttachmentBatchResult {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val session = try {
            currentSession(owner)
        } catch (_: IllegalStateException) {
            return AttachmentBatchResult.Complete
        }

        try {
            withBoundSession(session) {
                val now = System.currentTimeMillis()
                database.withTransaction {
                    attachments.bindOwnerSession(owner, session.generation, now)
                    attachments.expireOwnerDue(owner, now)
                }
            }
        } catch (error: AccountClientException) {
            return if (error.retryable) {
                AttachmentBatchResult.Retry
            } else {
                AttachmentBatchResult.Complete
            }
        }
        deleteTerminalFiles(owner)

        repeat(AttachmentPolicy.MAX_BATCH_SIZE) {
            val now = System.currentTimeMillis()
            val claimed = try {
                withBoundSession(session) {
                    attachments.claimNext(
                        ownerId = owner,
                        sessionGeneration = session.generation,
                        now = now,
                        leaseUntil = AttachmentPolicy.leaseUntil(now),
                    )
                }
            } catch (error: AccountClientException) {
                return if (error.retryable) {
                    AttachmentBatchResult.Retry
                } else {
                    AttachmentBatchResult.Complete
                }
            }
            if (claimed == null) return nextBatchResult(session)

            when (processClaim(session, claimed)) {
                AttachmentOutcome.Continue -> Unit
                AttachmentOutcome.Retry -> return AttachmentBatchResult.Retry
                AttachmentOutcome.Pause -> return nextBatchResult(session)
            }
        }
        return nextBatchResult(session)
    }

    suspend fun resumeOwner(ownerId: String) {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        val session = currentSession(owner)
        withBoundSession(session) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                attachments.bindOwnerSession(owner, session.generation, now)
                attachments.expireOwnerDue(owner, now)
            }
        }
        deleteTerminalFiles(owner)
        AttachmentWorker.enqueue(applicationContext, owner)
    }

    suspend fun clearOwner(ownerId: String) {
        val owner = ownerId.requireCanonicalUuid("Owner ID")
        AttachmentWorker.cancel(applicationContext, owner)
        attachments.deleteOwner(owner)
        check(deleteOwnerDirectory(owner)) {
            "Could not remove the owner's private attachment files."
        }
    }

    suspend fun clearAll() {
        attachments.ownerIds().forEach { owner ->
            AttachmentWorker.cancel(applicationContext, owner)
        }
        attachments.deleteAll()
        check(deleteRecursivelyInsideQueue(queueRoot)) {
            "Could not remove private attachment files."
        }
    }

    suspend fun cleanup() {
        attachments.expireDue(System.currentTimeMillis())
        deleteTerminalFiles()
        val retained = attachments.retainedFileNames().toHashSet()
        val cutoff = System.currentTimeMillis() - AttachmentPolicy.LIFETIME_MILLIS
        queueRoot.listFiles().orEmpty().forEach { ownerDirectory ->
            ownerDirectory.listFiles().orEmpty().forEach { candidate ->
                val relative = "${ownerDirectory.name}/${candidate.name}"
                if (relative !in retained && candidate.lastModified() <= cutoff) {
                    deleteRecursivelyInsideQueue(candidate)
                }
            }
            if (ownerDirectory.listFiles().isNullOrEmpty()) ownerDirectory.delete()
        }
    }

    private suspend fun processClaim(
        session: AttachmentSession,
        entry: PendingAttachment,
    ): AttachmentOutcome {
        val leaseToken = entry.leaseToken ?: return AttachmentOutcome.Pause
        val now = System.currentTimeMillis()
        if (now >= entry.expiresAt) {
            return persistFailure(
                session = session,
                entry = entry,
                leaseToken = leaseToken,
                failure = AttachmentFailure.Expired,
            )
        }
        if (AttachmentPolicy.reservationExpiredBeforeUpload(entry.stage, entry.reservationReceivedAt, now)) {
            return rotateReservation(session, entry, leaseToken, now)
        }
        return try {
            when (entry.stage) {
                AttachmentStage.RESERVE -> reserve(session, entry, leaseToken)
                AttachmentStage.UPLOAD -> upload(session, entry, leaseToken)
                AttachmentStage.COMPLETE -> complete(session, entry, leaseToken)
                else -> AttachmentOutcome.Pause
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AccountClientException) {
            if (
                error.code == RESERVATION_EXPIRED &&
                entry.reservationExpiresAt != null &&
                (entry.stage == AttachmentStage.UPLOAD || entry.stage == AttachmentStage.COMPLETE)
            ) {
                val failureNow = System.currentTimeMillis()
                if (failureNow >= entry.expiresAt) {
                    return persistFailure(
                        session,
                        entry,
                        leaseToken,
                        AttachmentFailure.Expired,
                    )
                }
                return rotateReservation(
                    session,
                    entry,
                    leaseToken,
                    failureNow,
                )
            }
            persistFailure(
                session,
                entry,
                leaseToken,
                AttachmentFailure.Remote(
                    retryable = error.retryable,
                    code = error.code,
                    message = error.message,
                    retryAfterSeconds = error.retryAfterSeconds,
                ),
            )
        } catch (error: AttachmentResponseException) {
            persistFailure(
                session,
                entry,
                leaseToken,
                AttachmentFailure.Remote(
                    retryable = true,
                    code = "INVALID_RESPONSE",
                    message = error.message,
                    retryAfterSeconds = null,
                ),
            )
        } catch (error: IOException) {
            persistFailure(
                session,
                entry,
                leaseToken,
                AttachmentFailure.Remote(
                    retryable = false,
                    code = "LOCAL_FILE_UNAVAILABLE",
                    message = "The prepared attachment file is unavailable.",
                    retryAfterSeconds = null,
                ),
            )
        } catch (error: Exception) {
            persistFailure(
                session,
                entry,
                leaseToken,
                AttachmentFailure.Remote(
                    retryable = true,
                    code = "INVALID_RESPONSE",
                    message = "The attachment response could not be stored.",
                    retryAfterSeconds = null,
                ),
            )
        }
    }

    private suspend fun rotateReservation(
        session: AttachmentSession,
        entry: PendingAttachment,
        leaseToken: String,
        now: Long,
    ): AttachmentOutcome = try {
        val rotated = persistBound(session) {
            attachments.rotateExpiredReservation(
                ownerId = entry.ownerId,
                operationId = entry.operationId,
                sessionGeneration = session.generation,
                leaseToken = leaseToken,
                reserveRequestId = UUID.randomUUID().toString(),
                now = now,
            )
        }
        if (rotated == 1) AttachmentOutcome.Continue else AttachmentOutcome.Pause
    } catch (_: AccountClientException) {
        AttachmentOutcome.Pause
    }

    private suspend fun reserve(
        session: AttachmentSession,
        entry: PendingAttachment,
        leaseToken: String,
    ): AttachmentOutcome {
        val response = client.libraryRequest(
            expectedOwnerId = entry.ownerId,
            path = "/items/${entry.itemId}/assets/reserve",
            method = "POST",
            body = entry.reserveBodyJson,
            requestId = entry.reserveRequestId,
        )
        val assetId = response.requireUuid("asset_id")
        val objectPath = response.requireString("object_path")
            .takeIf { it == "${entry.ownerId}/${entry.itemId}/$assetId" }
            ?: throw AttachmentResponseException("The reservation has an invalid object path.")
        val expiresAt = response.requireInstantMillis("expires_at")
        val maxBytes = response.requireLong("max_bytes")
        if (maxBytes != AttachmentPolicy.MAX_BYTES || expiresAt <= 0L) {
            throw AttachmentResponseException("The reservation limits are invalid.")
        }
        val changed = persistBound(session) {
            attachments.completeReservation(
                ownerId = entry.ownerId,
                operationId = entry.operationId,
                sessionGeneration = session.generation,
                leaseToken = leaseToken,
                now = System.currentTimeMillis(),
                completeRequestId = UUID.randomUUID().toString(),
                assetId = assetId,
                objectPath = objectPath,
                reservationExpiresAt = expiresAt,
            )
        }
        return if (changed == 1) AttachmentOutcome.Continue else AttachmentOutcome.Pause
    }

    private suspend fun upload(
        session: AttachmentSession,
        entry: PendingAttachment,
        leaseToken: String,
    ): AttachmentOutcome {
        val assetId = entry.serverAssetId
            ?: throw AttachmentResponseException("The upload is missing its asset ID.")
        val bytes = readQueueBytes(entry)
        try {
            client.uploadReservedAsset(
                expectedOwnerId = entry.ownerId,
                itemId = entry.itemId,
                assetId = assetId,
                mimeType = entry.mimeType,
                bytes = bytes,
            )
        } catch (error: AccountClientException) {
            if (error.code != ASSET_OBJECT_EXISTS) throw error
        }
        val changed = persistBound(session) {
            attachments.completeUpload(
                ownerId = entry.ownerId,
                operationId = entry.operationId,
                sessionGeneration = session.generation,
                leaseToken = leaseToken,
                now = System.currentTimeMillis(),
            )
        }
        return if (changed == 1) AttachmentOutcome.Continue else AttachmentOutcome.Pause
    }

    private suspend fun complete(
        session: AttachmentSession,
        entry: PendingAttachment,
        leaseToken: String,
    ): AttachmentOutcome {
        val assetId = entry.serverAssetId
            ?: throw AttachmentResponseException("The completion is missing its asset ID.")
        val requestId = entry.completeRequestId
            ?: throw AttachmentResponseException("The completion is missing its request ID.")
        val response = client.libraryRequest(
            expectedOwnerId = entry.ownerId,
            path = "/items/${entry.itemId}/assets/$assetId/complete",
            method = "POST",
            body = entry.completeBodyJson,
            requestId = requestId,
        )
        val item = response
        if (!item.requireUuid("id").equals(entry.itemId, ignoreCase = true)) {
            throw AttachmentResponseException("The completion response has a different item ID.")
        }

        val beforeCache = System.currentTimeMillis()
        val renewed = persistBound(session) {
            attachments.renewCompleteLease(
                ownerId = entry.ownerId,
                operationId = entry.operationId,
                sessionGeneration = session.generation,
                leaseToken = leaseToken,
                now = beforeCache,
                leaseUntil = AttachmentPolicy.leaseUntil(beforeCache),
            )
        }
        if (renewed != 1) return AttachmentOutcome.Pause
        outboxRepository.cacheList(entry.ownerId, listOf(item), replace = false)
        requireStillCurrent(session)
        outboxRepository.cacheDetail(entry.ownerId, item)
        requireStillCurrent(session)
        val changed = persistBound(session) {
            attachments.completeSaved(
                ownerId = entry.ownerId,
                operationId = entry.operationId,
                sessionGeneration = session.generation,
                leaseToken = leaseToken,
                now = System.currentTimeMillis(),
            )
        }
        if (changed != 1) return AttachmentOutcome.Pause
        deleteQueueFile(entry)
        return AttachmentOutcome.Continue
    }

    private suspend fun persistFailure(
        session: AttachmentSession,
        entry: PendingAttachment,
        leaseToken: String,
        failure: AttachmentFailure,
    ): AttachmentOutcome {
        val now = System.currentTimeMillis()
        val decision = failureDecision(entry, failure, now)
        val write: suspend () -> Int = {
            attachments.completeFailure(
                ownerId = entry.ownerId,
                operationId = entry.operationId,
                sessionGeneration = session.generation,
                leaseToken = leaseToken,
                now = now,
                claimedStage = entry.stage,
                failureStage = decision.stage,
                resumeStage = decision.resumeStage,
                nextRetryAt = decision.nextRetryAt,
                errorCode = decision.errorCode,
                errorMessage = decision.errorMessage,
            )
        }
        val changed = if (
            decision.stage == AttachmentStage.WAITING_LOGIN &&
            client.sessionState.value.let {
                it.ownerId == null && it.generation == session.generation + 1L
            }
        ) {
            write()
        } else {
            try {
                persistBound(session, write)
            } catch (_: AccountClientException) {
                return AttachmentOutcome.Pause
            }
        }
        if (changed != 1) return AttachmentOutcome.Pause
        if (
            decision.stage == AttachmentStage.EXPIRED &&
            now >= entry.expiresAt
        ) deleteQueueFile(entry)
        return when (decision.stage) {
            AttachmentStage.RETRY -> AttachmentOutcome.Retry
            AttachmentStage.WAITING_LOGIN -> AttachmentOutcome.Pause
            else -> AttachmentOutcome.Continue
        }
    }

    private fun failureDecision(
        entry: PendingAttachment,
        failure: AttachmentFailure,
        now: Long,
    ): AttachmentFailureDecision {
        if (failure is AttachmentFailure.Expired || now >= entry.expiresAt) {
            return AttachmentFailureDecision(
                stage = AttachmentStage.EXPIRED,
                resumeStage = null,
                nextRetryAt = entry.expiresAt,
                errorCode = "ATTACHMENT_EXPIRED",
                errorMessage = "The local attachment expired before it was saved.",
            )
        }
        failure as AttachmentFailure.Remote
        return when (
            val action = OutboxPolicy.classifyFailure(
                now = now,
                expiresAt = entry.expiresAt,
                attemptCount = entry.attemptCount,
                retryable = failure.retryable,
                errorCode = failure.code,
                retryAfterSeconds = failure.retryAfterSeconds,
            )
        ) {
            FailureAction.WaitForLogin -> AttachmentFailureDecision(
                AttachmentStage.WAITING_LOGIN,
                entry.stage,
                now,
                failure.code,
                failure.message,
            )
            FailureAction.Conflict -> AttachmentFailureDecision(
                AttachmentStage.CONFLICT,
                null,
                now,
                failure.code,
                failure.message,
            )
            is FailureAction.Retry -> AttachmentFailureDecision(
                AttachmentStage.RETRY,
                entry.stage,
                action.nextAttemptAt,
                failure.code,
                failure.message,
            )
            FailureAction.Fail -> AttachmentFailureDecision(
                AttachmentStage.FAILED,
                entry.stage,
                now,
                failure.code,
                failure.message,
            )
            FailureAction.Expire -> AttachmentFailureDecision(
                AttachmentStage.EXPIRED,
                null,
                entry.expiresAt,
                "ATTACHMENT_EXPIRED",
                "The attachment retry window expired.",
            )
        }
    }

    private suspend fun nextBatchResult(session: AttachmentSession): AttachmentBatchResult {
        val wakeAt = attachments.nextWakeAt(
            session.ownerId,
            session.generation,
            System.currentTimeMillis(),
        )
            ?: return AttachmentBatchResult.Complete
        return AttachmentBatchResult.ScheduleAt(wakeAt)
    }

    private fun currentSession(
        ownerId: String,
        expectedGeneration: Long? = null,
    ): AttachmentSession {
        val state = client.sessionState.value
        check(state.ownerId == ownerId) { "The attachment owner is not the current session owner." }
        if (expectedGeneration != null) {
            check(state.generation == expectedGeneration) {
                "The attachment preprocessing session is no longer current."
            }
        }
        return AttachmentSession(ownerId, state.generation)
    }

    private suspend fun <T> withBoundSession(
        session: AttachmentSession,
        block: suspend () -> T,
    ): T = client.withSessionOwner(session.ownerId) {
        requireStillCurrent(session)
        block()
    }

    private suspend fun <T> persistBound(
        session: AttachmentSession,
        block: suspend () -> T,
    ): T = withBoundSession(session, block)

    private fun requireStillCurrent(session: AttachmentSession) {
        val state = client.sessionState.value
        if (state.ownerId != session.ownerId || state.generation != session.generation) {
            throw AccountClientException(
                message = "The attachment session changed before local state could be updated.",
                retryable = false,
                code = "SESSION_CHANGED",
            )
        }
    }

    private fun validateOcr(
        state: AttachmentOcrState,
        text: String?,
        truncated: Boolean,
    ) {
        when (state) {
            AttachmentOcrState.READY -> {
                require(text != null) { "Ready OCR must include text." }
                require(text.codePointCount(0, text.length) <= AttachmentPolicy.MAX_OCR_CODE_POINTS) {
                    "OCR text exceeds 20,000 Unicode code points."
                }
            }
            AttachmentOcrState.FAILED,
            AttachmentOcrState.NOT_REQUESTED,
            -> {
                require(text == null) { "OCR text is only allowed when OCR is ready." }
                require(!truncated) { "Only ready OCR can be truncated." }
            }
        }
    }

    private fun requireOwnedPrivateFile(file: File): File {
        val candidate = try {
            file.canonicalFile
        } catch (error: IOException) {
            throw IllegalArgumentException("Prepared attachment path is invalid.", error)
        }
        val roots = listOf(
            applicationContext.filesDir,
            applicationContext.cacheDir,
            applicationContext.noBackupFilesDir,
            applicationContext.codeCacheDir,
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        require(roots.any { candidate.isStrictDescendantOf(it) } && candidate.isFile) {
            "Prepared attachment must be an app-owned private file."
        }
        return candidate
    }

    private fun copyOwnedFile(source: File, destination: File) {
        val safeDestination = destination.canonicalFile
        require(safeDestination.isStrictDescendantOf(queueRoot.canonicalFile)) {
            "Attachment destination must stay inside the private queue."
        }
        check(!safeDestination.exists()) { "Attachment destination already exists." }
        val parent = checkNotNull(safeDestination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) {
            "Could not create the private attachment directory."
        }
        try {
            source.inputStream().use { input ->
                FileOutputStream(safeDestination, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total.toLong() > AttachmentPolicy.MAX_BYTES) {
                            throw IllegalArgumentException("Prepared attachment exceeds 2 MB.")
                        }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0L) { "Prepared attachment must not be empty." }
                }
            }
        } catch (error: Exception) {
            safeDestination.delete()
            throw error
        }
    }

    private suspend fun readQueueBytes(entry: PendingAttachment): ByteArray =
        withContext(Dispatchers.IO) {
            val file = resolveQueueFile(entry.localFileName)
            if (!file.isFile || file.length() !in 1L..AttachmentPolicy.MAX_BYTES) {
                throw IOException("Prepared attachment is unavailable.")
            }
            file.inputStream().use { input ->
                ByteArrayOutputStream(file.length().toInt()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total.toLong() > AttachmentPolicy.MAX_BYTES) {
                            throw IOException("Prepared attachment has an invalid size.")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (total == 0) {
                        throw IOException("Prepared attachment has an invalid size.")
                    }
                    output.toByteArray()
                }
            }
        }

    private suspend fun deleteTerminalFiles(ownerId: String? = null) {
        attachments.filesReadyForDeletion(
            ownerId,
            System.currentTimeMillis(),
        ).forEach(::deleteQueueFile)
    }

    private fun deleteQueueFile(entry: PendingAttachment) {
        runCatching { resolveQueueFile(entry.localFileName).delete() }
    }

    private fun queueFile(ownerId: String, operationId: String): File =
        resolveQueueFile(queueRelativeName(ownerId, operationId))

    private fun queueRelativeName(ownerId: String, operationId: String): String =
        "$ownerId/$operationId.asset"

    private fun resolveQueueFile(relativeName: String): File {
        require(!File(relativeName).isAbsolute) { "Attachment filename must be relative." }
        val candidate = File(queueRoot, relativeName).canonicalFile
        require(candidate.isStrictDescendantOf(queueRoot.canonicalFile)) {
            "Attachment filename escapes the private queue."
        }
        return candidate
    }

    private fun deleteOwnerDirectory(ownerId: String): Boolean =
        deleteRecursivelyInsideQueue(File(queueRoot, ownerId))

    private fun deleteRecursivelyInsideQueue(file: File): Boolean {
        val root = runCatching { queueRoot.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (candidate != root && !candidate.isStrictDescendantOf(root)) return false
        if (!candidate.exists()) return true
        candidate.listFiles().orEmpty().forEach(::deleteRecursivelyInsideQueue)
        return candidate.delete() || !candidate.exists()
    }

    private fun File.isStrictDescendantOf(directory: File): Boolean =
        path.startsWith(directory.path + File.separator)

    private companion object {
        const val QUEUE_DIRECTORY = "attachment_queue"
        const val ASSET_OBJECT_EXISTS = "ASSET_OBJECT_EXISTS"
        const val RESERVATION_EXPIRED = "RESERVATION_EXPIRED"
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

data class AttachmentPreparation(
    val operationId: String,
    val sessionGeneration: Long,
    val file: File,
)

sealed interface AttachmentBatchResult {
    data object Complete : AttachmentBatchResult
    data object Retry : AttachmentBatchResult
    data class ScheduleAt(val timestamp: Long) : AttachmentBatchResult
}

private data class AttachmentSession(
    val ownerId: String,
    val generation: Long,
)

private enum class AttachmentOutcome {
    Continue,
    Retry,
    Pause,
}

private sealed interface AttachmentFailure {
    data object Expired : AttachmentFailure

    data class Remote(
        val retryable: Boolean,
        val code: String?,
        val message: String?,
        val retryAfterSeconds: Int?,
    ) : AttachmentFailure
}

private data class AttachmentFailureDecision(
    val stage: AttachmentStage,
    val resumeStage: AttachmentStage?,
    val nextRetryAt: Long,
    val errorCode: String?,
    val errorMessage: String?,
)

private class AttachmentResponseException(message: String) : Exception(message)

private fun reserveBody(expectedVersion: Long, mimeType: String): String = buildJsonObject {
    put("expected_version", expectedVersion)
    put("mime_type", mimeType)
}.toString()

private fun completeBody(
    expectedVersion: Long,
    ocrState: AttachmentOcrState,
    ocrText: String?,
    ocrTruncated: Boolean,
): String = buildJsonObject {
    put("expected_version", expectedVersion)
    put("ocr_state", ocrState.wireValue)
    if (ocrText != null) put("ocr_text", ocrText)
    put("ocr_truncated", ocrTruncated)
}.toString()

private fun JsonObject.requireString(field: String): String {
    val primitive = this[field] as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw AttachmentResponseException("The attachment response has an invalid $field.")
    }
    return primitive.contentOrNull
        ?: throw AttachmentResponseException("The attachment response has an invalid $field.")
}

private fun JsonObject.requireUuid(field: String): String =
    requireString(field).takeIf { value ->
        runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
            .getOrDefault(false)
    } ?: throw AttachmentResponseException("The attachment response has an invalid $field.")

private fun JsonObject.requireLong(field: String): Long {
    val primitive = this[field] as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.booleanOrNull != null) {
        throw AttachmentResponseException("The attachment response has an invalid $field.")
    }
    return primitive.longOrNull
        ?: throw AttachmentResponseException("The attachment response has an invalid $field.")
}

private fun JsonObject.requireInstantMillis(field: String): Long = try {
    Instant.parse(requireString(field)).toEpochMilli()
} catch (error: AttachmentResponseException) {
    throw error
} catch (_: Exception) {
    throw AttachmentResponseException("The attachment response has an invalid $field.")
}

private fun String.requireCanonicalUuid(label: String): String {
    val value = trim()
    val canonical = runCatching { UUID.fromString(value).toString() }.getOrNull()
    require(canonical?.equals(value, ignoreCase = true) == true) { "$label must be a UUID." }
    return checkNotNull(canonical)
}

private fun PendingAttachment.matchesPreparedInput(other: PendingAttachment): Boolean =
    ownerId == other.ownerId &&
        itemId == other.itemId &&
        baseExpectedVersion == other.baseExpectedVersion &&
        sessionGeneration == other.sessionGeneration &&
        localFileName == other.localFileName &&
        mimeType == other.mimeType &&
        ocrState == other.ocrState &&
        ocrText == other.ocrText &&
        ocrTruncated == other.ocrTruncated &&
        reserveBodyJson == other.reserveBodyJson &&
        completeBodyJson == other.completeBodyJson
