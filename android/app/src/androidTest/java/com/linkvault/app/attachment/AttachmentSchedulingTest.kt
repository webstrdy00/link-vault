package com.linkvault.app.attachment

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.linkvault.app.auth.AccountAccess
import com.linkvault.app.auth.AccountClientException
import com.linkvault.app.auth.AccountDeletionReceipt
import com.linkvault.app.auth.AccountSummary
import com.linkvault.app.storage.FailureAction
import com.linkvault.app.storage.OutboxPolicy
import com.linkvault.app.storage.VaultDatabase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real WorkManager chains, TestDriver delays/constraints and isolated in-memory Room claims/CAS.
 * The factory injects simulated account/remote-upload boundaries into the production worker;
 * ControlledDependencies uses the real DAO's nextWakeAt, retry, expiry and lease transitions.
 * These are scheduler regressions, not HTTP uploads, OS alarm delivery or physical-device tests.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSchedulingTest {
    private lateinit var context: Context
    private lateinit var database: VaultDatabase
    private lateinit var dependencies: ControlledDependencies
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private val uploadGates = mutableListOf<CompletableDeferred<Unit>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Never resolve LinkVaultApplication repositories or VaultDatabase.create here.
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        dependencies = ControlledDependencies(database.attachmentDao())
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = if (workerClassName == AttachmentWorker::class.java.name) {
                AttachmentWorker(appContext, workerParameters, dependencies)
            } else {
                // In particular, the wake worker is constructed by WorkManager, unmodified.
                null
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        manager = WorkManager.getInstance(context)
        driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) {
            manager.cancelAllWork().result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            uploadGates.forEach { it.complete(Unit) }
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun conflictExpiryDoesNotPrecedeFreshEnqueue() = runBlocking {
        assertCleanupDoesNotBlockFresh(AttachmentStage.CONFLICT, discard = false)
    }

    @Test
    fun failedExpiryDoesNotPrecedeFreshEnqueue() = runBlocking {
        assertCleanupDoesNotBlockFresh(AttachmentStage.FAILED, discard = false)
    }

    @Test
    fun discardedConflictTimerDoesNotBlockFreshEnqueue() = runBlocking {
        assertCleanupDoesNotBlockFresh(AttachmentStage.CONFLICT, discard = true)
    }

    @Test
    fun discardedFailedTimerDoesNotBlockFreshEnqueue() = runBlocking {
        assertCleanupDoesNotBlockFresh(AttachmentStage.FAILED, discard = true)
    }

    @Test
    fun conflictExpiryDoesNotPrecedeExplicitRetryOfAnotherFailedRow() = runBlocking {
        assertCleanupDoesNotBlockRetry(AttachmentStage.CONFLICT)
    }

    @Test
    fun failedExpiryDoesNotPrecedeExplicitRetryOfTheSameRow() = runBlocking {
        assertCleanupDoesNotBlockRetry(AttachmentStage.FAILED)
    }

    @Test
    fun newEnqueueAndTimerReplacementNeverCancelRunningUpload() = runBlocking {
        val cleanup = seed(AttachmentStage.CONFLICT)
        runReady(enqueueReady())
        val oldTimer = activeWake()
        val first = seed(AttachmentStage.UPLOAD)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also(uploadGates::add)
        dependencies.upload = { entry ->
            if (entry.operationId == first.operationId) {
                entered.complete(Unit)
                release.await()
            }
        }
        val running = enqueueReady()
        driver.setAllConstraintsMet(running.id)
        awaitCondition("first upload entered") { entered.isCompleted }
        awaitState(running.id, WorkInfo.State.RUNNING)
        val lease = database.attachmentDao().find(first.operationId)!!
        assertTrue(lease.leaseToken != null)

        val second = seed(AttachmentStage.UPLOAD)
        val appended = enqueueReady()
        driver.setAllConstraintsMet(appended.id)
        assertEquals(WorkInfo.State.BLOCKED, info(appended.id).state)
        AttachmentWorker.enqueue(context, OWNER_A, delayMillis = HOUR_MILLIS)
            .result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitRemoved(oldTimer.id)
        assertEquals(WorkInfo.State.RUNNING, info(running.id).state)
        assertEquals(lease.leaseToken, database.attachmentDao().find(first.operationId)?.leaseToken)
        assertEquals(listOf(first.operationId), dependencies.uploadCalls.toList())

        // A new request cannot make an existing lease writable under another owner/generation/token.
        assertEquals(0, completeUpload(lease, ownerId = OWNER_B))
        assertEquals(0, completeUpload(lease, generation = GENERATION + 1L))
        assertEquals(0, completeUpload(lease, leaseToken = UUID.randomUUID().toString()))
        release.complete(Unit)
        awaitState(running.id, WorkInfo.State.SUCCEEDED)
        awaitState(appended.id, WorkInfo.State.SUCCEEDED)
        assertEquals(listOf(first.operationId, second.operationId), dependencies.uploaded.toList())
        assertEquals(AttachmentStage.CONFLICT, database.attachmentDao().find(cleanup.operationId)?.stage)
        assertEquals(AttachmentStage.SAVED, database.attachmentDao().find(first.operationId)?.stage)
        assertEquals(AttachmentStage.SAVED, database.attachmentDao().find(second.operationId)?.stage)
    }

    @Test
    fun transientBatchRetryIsNotABackoffPredecessorAndRetryAfterRemainsBinding() = runBlocking {
        val retrying = seed(AttachmentStage.UPLOAD)
        val attempts = AtomicInteger()
        dependencies.upload = { entry ->
            if (entry.operationId == retrying.operationId && attempts.getAndIncrement() == 0) {
                throw AccountClientException("Simulated rate limit", true, "RATE_LIMITED", 120)
            }
        }
        val original = enqueueReady()
        runReady(original)
        // Persisted WorkInfo counts the first run; WorkerParameters starts at zero.
        assertEquals(1, info(original.id).runAttemptCount)
        val originalTimer = activeWake()
        val retryAt = database.attachmentDao().find(retrying.operationId)!!.nextRetryAt
        assertEquals(dependencies.now.get() + 120_000L, retryAt)

        val fresh = seed(AttachmentStage.UPLOAD)
        runReady(enqueueReady())
        awaitRemoved(originalTimer.id)
        val retryTimer = activeWake()
        assertTrue(retryTimer.initialDelayMillis > AttachmentPolicy.MIN_RETRY_MILLIS)
        assertEquals(listOf(fresh.operationId), dependencies.uploaded.toList())
        assertEquals(AttachmentStage.RETRY, database.attachmentDao().find(retrying.operationId)?.stage)
        assertEquals(retryAt, database.attachmentDao().find(retrying.operationId)?.nextRetryAt)

        // Manual retry and an artificially early TestDriver wake cannot bypass the DB timestamp.
        database.attachmentDao().retry(OWNER_A, retrying.operationId, GENERATION, dependencies.now.get())
        runReady(fireWake(retryTimer))
        assertEquals(1, attempts.get())
        assertEquals(retryAt, database.attachmentDao().find(retrying.operationId)?.nextRetryAt)
        dependencies.now.set(retryAt)
        runReady(fireWake(activeWake()))
        assertEquals(2, attempts.get())
        assertEquals(AttachmentStage.SAVED, database.attachmentDao().find(retrying.operationId)?.stage)
        assertEquals(listOf(fresh.operationId, retrying.operationId), dependencies.uploaded.toList())
        assertEquals(WorkInfo.State.SUCCEEDED, info(original.id).state)
    }

    @Test
    fun transientAuthenticationRetriesUseExponentialWakeDelaysWithoutBlockingNewWork() = runBlocking {
        val fresh = seed(AttachmentStage.UPLOAD)
        val failures = AtomicInteger(3)
        dependencies.restore = {
            if (failures.getAndDecrement() > 0) throw AccountClientException("Simulated refresh failure", true)
            activeAccount()
        }
        var immediate = enqueueReady()
        for (delay in listOf(30_000L, 60_000L, 120_000L)) {
            runReady(immediate)
            assertEquals(1, info(immediate.id).runAttemptCount)
            val timer = activeWake()
            assertEquals(delay, timer.initialDelayMillis)
            if (delay != 120_000L) immediate = fireWake(timer)
        }
        val retainedTimer = activeWake()
        assertTrue(dependencies.uploadCalls.isEmpty())
        runReady(enqueueReady())
        assertEquals(listOf(fresh.operationId), dependencies.uploaded.toList())
        assertEquals(WorkInfo.State.ENQUEUED, info(retainedTimer.id).state)
        assertTrue(immediateWork().all { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun deletionCleanupRetryDoesNotBlockTheNextCleanupAndNeverUploads() = runBlocking {
        seed(AttachmentStage.UPLOAD)
        dependencies.restore = {
            AccountAccess.Deleting(AccountDeletionReceipt(OWNER_A, GENERATION, GENERATION, false))
        }
        val cleanupAttempts = AtomicInteger()
        dependencies.cleanup = { cleanupAttempts.incrementAndGet() > 1 }
        val first = enqueueReady()
        runReady(first)
        val timer = activeWake()
        runReady(enqueueReady())
        assertEquals(2, cleanupAttempts.get())
        assertEquals(listOf(OWNER_A, OWNER_A), dependencies.cleanupOwners.toList())
        assertEquals(WorkInfo.State.SUCCEEDED, info(first.id).state)
        assertEquals(WorkInfo.State.ENQUEUED, info(timer.id).state)
        assertTrue(dependencies.processedOwners.isEmpty())
        assertTrue(dependencies.uploadCalls.isEmpty())
    }

    @Test
    fun ownerCancellationCancelsBothNamesWithoutTouchingAnotherOwner() = runBlocking {
        seed(AttachmentStage.CONFLICT, OWNER_A)
        val other = seed(AttachmentStage.FAILED, OWNER_B)
        runReady(enqueueReady(OWNER_A))
        val wakeA = activeWake(OWNER_A)
        dependencies.ownerId = OWNER_B
        runReady(enqueueReady(OWNER_B))
        val wakeB = activeWake(OWNER_B)
        val pendingA = enqueueReady(OWNER_A)
        val pendingB = enqueueReady(OWNER_B)
        assertNotEquals(AttachmentWorker.uniqueWorkName(OWNER_A), AttachmentWorker.uniqueWorkName(OWNER_B))
        assertNotEquals(AttachmentWorker.wakeWorkName(OWNER_A), AttachmentWorker.wakeWorkName(OWNER_B))

        AttachmentWorker.cancel(context, OWNER_A)
        awaitState(pendingA.id, WorkInfo.State.CANCELLED)
        awaitState(wakeA.id, WorkInfo.State.CANCELLED)
        assertEquals(WorkInfo.State.ENQUEUED, info(pendingB.id).state)
        assertEquals(WorkInfo.State.ENQUEUED, info(wakeB.id).state)
        assertEquals(AttachmentStage.FAILED, database.attachmentDao().find(other.operationId)?.stage)
        runReady(pendingB)
    }

    @Test
    fun differentActiveOwnerOrDeletingOwnerCannotProcessQueuedUpload() = runBlocking {
        val original = seed(AttachmentStage.UPLOAD)
        dependencies.ownerId = OWNER_B
        runReady(enqueueReady(OWNER_A))
        dependencies.restore = {
            AccountAccess.Deleting(AccountDeletionReceipt(OWNER_B, GENERATION, GENERATION, false))
        }
        runReady(enqueueReady(OWNER_A))
        assertTrue(dependencies.processedOwners.isEmpty())
        assertTrue(dependencies.cleanupOwners.isEmpty())
        assertTrue(dependencies.uploadCalls.isEmpty())
        assertEquals(original, database.attachmentDao().find(original.operationId))
    }

    @Test
    fun activeLeaseCannotBlockAnotherReadyRowOrBeClaimedTwice() = runBlocking {
        val leased = seed(AttachmentStage.UPLOAD)
        val held = database.attachmentDao().claimNext(
            OWNER_A, GENERATION, dependencies.now.get(), dependencies.now.get() + HOUR_MILLIS,
        )!!
        runReady(enqueueReady())
        val leaseTimer = activeWake()
        val fresh = seed(AttachmentStage.UPLOAD)
        runReady(enqueueReady())
        awaitRemoved(leaseTimer.id)
        assertEquals(listOf(fresh.operationId), dependencies.uploaded.toList())
        assertEquals(held.leaseToken, database.attachmentDao().find(leased.operationId)?.leaseToken)
        assertEquals(held.leaseUntil, database.attachmentDao().find(leased.operationId)?.leaseUntil)
        assertFalse(dependencies.uploadCalls.contains(leased.operationId))
    }

    @Test
    fun expiryTimerDurablyEnqueuesImmediateWorkWithoutAuthenticatingOrUploading() = runBlocking {
        val expiring = seed(AttachmentStage.CONFLICT)
        runReady(enqueueReady())
        val timer = activeWake()
        val authCalls = dependencies.restoreCalls.get()
        val batchCalls = dependencies.processedOwners.size
        dependencies.now.set(expiring.expiresAt)
        val immediate = fireWake(timer)
        assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
        assertEquals(0L, immediate.initialDelayMillis)
        assertEquals(WorkInfo.State.SUCCEEDED, info(timer.id).state)
        assertEquals(authCalls, dependencies.restoreCalls.get())
        assertEquals(batchCalls, dependencies.processedOwners.size)
        assertTrue(dependencies.uploadCalls.isEmpty())

        runReady(immediate)
        val expired = database.attachmentDao().find(expiring.operationId)!!
        assertEquals(AttachmentStage.EXPIRED, expired.stage)
        assertEquals("ATTACHMENT_EXPIRED", expired.errorCode)
        assertNull(expired.leaseToken)
        assertTrue(dependencies.uploadCalls.isEmpty())
    }

    private suspend fun assertCleanupDoesNotBlockFresh(stage: AttachmentStage, discard: Boolean) {
        val old = seed(stage)
        val cleanup = enqueueReady()
        runReady(cleanup)
        val timer = activeWake()
        assertTrue(timer.initialDelayMillis > HOUR_MILLIS)
        if (discard) {
            assertEquals(1, database.attachmentDao().deleteOperation(OWNER_A, old.operationId, GENERATION))
            assertNull(database.attachmentDao().find(old.operationId))
        }
        val fresh = seed(AttachmentStage.UPLOAD)
        val immediate = enqueueReady()
        assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
        assertEquals(0L, immediate.initialDelayMillis)
        runReady(immediate)
        assertEquals(listOf(fresh.operationId), dependencies.uploaded.toList())
        assertEquals(AttachmentStage.SAVED, database.attachmentDao().find(fresh.operationId)?.stage)
        assertEquals(WorkInfo.State.SUCCEEDED, info(cleanup.id).state)
        assertFalse(infoOrNull(timer.id)?.state == WorkInfo.State.SUCCEEDED)
        if (!discard) assertEquals(stage, database.attachmentDao().find(old.operationId)?.stage)
    }

    private suspend fun assertCleanupDoesNotBlockRetry(stage: AttachmentStage) {
        val old = seed(stage)
        runReady(enqueueReady())
        val timer = activeWake()
        val retrying = if (stage == AttachmentStage.FAILED) old else seed(AttachmentStage.FAILED)
        database.attachmentDao().retry(OWNER_A, retrying.operationId, GENERATION, dependencies.now.get())
        val immediate = enqueueReady()
        assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
        runReady(immediate)
        assertEquals(listOf(retrying.operationId), dependencies.uploaded.toList())
        assertEquals(AttachmentStage.SAVED, database.attachmentDao().find(retrying.operationId)?.stage)
        assertFalse(infoOrNull(timer.id)?.state == WorkInfo.State.SUCCEEDED)
        if (stage == AttachmentStage.CONFLICT) {
            assertEquals(AttachmentStage.CONFLICT, database.attachmentDao().find(old.operationId)?.stage)
        }
    }

    private suspend fun seed(stage: AttachmentStage, ownerId: String = OWNER_A): PendingAttachment {
        val now = dependencies.now.get()
        val operation = UUID.randomUUID().toString()
        val item = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString()
        val entry = PendingAttachment(
            operationId = operation,
            ownerId = ownerId,
            itemId = item,
            baseExpectedVersion = 3L,
            sessionGeneration = GENERATION,
            localFileName = "$ownerId/$operation.asset",
            mimeType = "image/jpeg",
            ocrState = AttachmentOcrState.NOT_REQUESTED,
            ocrText = null,
            ocrTruncated = false,
            createdAt = now,
            expiresAt = AttachmentPolicy.expiresAt(now),
            stage = stage,
            resumeStage = if (stage == AttachmentStage.FAILED) AttachmentStage.UPLOAD else null,
            reserveRequestId = UUID.randomUUID().toString(),
            reserveBodyJson = "{\"expected_version\":3,\"mime_type\":\"image/jpeg\"}",
            completeRequestId = UUID.randomUUID().toString(),
            completeBodyJson = "{\"expected_version\":3,\"ocr_state\":\"not_requested\"}",
            serverAssetId = asset,
            serverObjectPath = "$ownerId/$item/$asset",
            reservationExpiresAt = now + AttachmentPolicy.RESERVATION_LIFETIME_MILLIS,
            reservationReceivedAt = now,
            nextRetryAt = now,
        )
        database.attachmentDao().insertImmutable(entry)
        return entry
    }

    private suspend fun completeUpload(
        entry: PendingAttachment,
        ownerId: String = entry.ownerId,
        generation: Long = entry.sessionGeneration,
        leaseToken: String = checkNotNull(entry.leaseToken),
    ): Int = database.attachmentDao().completeUpload(
        ownerId, entry.operationId, generation, leaseToken, dependencies.now.get(),
    )

    private fun enqueueReady(ownerId: String = OWNER_A): WorkInfo {
        val before = immediateWork(ownerId).map { it.id }.toSet()
        AttachmentWorker.enqueue(context, ownerId).result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return immediateWork(ownerId).single { it.id !in before }
    }

    private fun fireWake(timer: WorkInfo): WorkInfo {
        val owner = if (wakeWork(OWNER_A).any { it.id == timer.id }) OWNER_A else OWNER_B
        val before = immediateWork(owner).map { it.id }.toSet()
        driver.setInitialDelayMet(timer.id)
        awaitState(timer.id, WorkInfo.State.SUCCEEDED)
        // Success must mean the zero-delay request was persisted, not just launched in a coroutine.
        return immediateWork(owner).single { it.id !in before }
    }

    private fun runReady(work: WorkInfo) {
        driver.setAllConstraintsMet(work.id)
        awaitState(work.id, WorkInfo.State.SUCCEEDED)
    }

    private fun activeWake(ownerId: String = OWNER_A): WorkInfo =
        wakeWork(ownerId).single { it.state == WorkInfo.State.ENQUEUED }

    private fun immediateWork(ownerId: String = OWNER_A): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(AttachmentWorker.uniqueWorkName(ownerId))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun wakeWork(ownerId: String): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(AttachmentWorker.wakeWorkName(ownerId))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun infoOrNull(id: UUID): WorkInfo? =
        manager.getWorkInfoById(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun info(id: UUID): WorkInfo = checkNotNull(infoOrNull(id))

    private fun awaitRemoved(id: UUID) {
        // WorkManager 2.9.1 REPLACE cancels and deletes the old unique-work row.
        awaitCondition("$id removed by replacement") { infoOrNull(id) == null }
    }

    private fun awaitState(id: UUID, state: WorkInfo.State) {
        awaitCondition("$id reached $state; current=${info(id)}") { info(id).state == state }
    }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(10L)
        }
    }

    private class ControlledDependencies(private val dao: AttachmentDao) : AttachmentWorkerDependencies {
        val now = AtomicLong(System.currentTimeMillis())
        val restoreCalls = AtomicInteger()
        val processedOwners = CopyOnWriteArrayList<String>()
        val cleanupOwners = CopyOnWriteArrayList<String>()
        val uploadCalls = CopyOnWriteArrayList<String>()
        val uploaded = CopyOnWriteArrayList<String>()
        @Volatile var ownerId: String? = OWNER_A
        @Volatile var generation = GENERATION
        var restore: suspend () -> AccountAccess? = { activeAccount() }
        var cleanup: suspend (String) -> Boolean = { true }
        var upload: suspend (PendingAttachment) -> Unit = {}

        override suspend fun restoreAccount(): AccountAccess? {
            restoreCalls.incrementAndGet()
            return restore()
        }

        override fun sessionUserId(): String? = ownerId

        override suspend fun clearAcceptedDeletionLocalData(ownerId: String): Boolean {
            cleanupOwners += ownerId
            return cleanup(ownerId)
        }

        override suspend fun processBatch(ownerId: String): AttachmentBatchResult {
            processedOwners += ownerId
            val boundGeneration = generation
            dao.bindOwnerSession(ownerId, boundGeneration, now.get())
            dao.expireOwnerDue(ownerId, now.get())
            repeat(AttachmentPolicy.MAX_BATCH_SIZE) {
                val claimed = dao.claimNext(ownerId, boundGeneration, now.get(), AttachmentPolicy.leaseUntil(now.get()))
                    ?: return nextBatch(ownerId, boundGeneration)
                val token = checkNotNull(claimed.leaseToken)
                when (claimed.stage) {
                    AttachmentStage.UPLOAD -> {
                        uploadCalls += claimed.operationId
                        try {
                            upload(claimed)
                        } catch (error: AccountClientException) {
                            val retry = OutboxPolicy.classifyFailure(
                                now.get(), claimed.expiresAt, claimed.attemptCount,
                                error.retryable, error.code, error.retryAfterSeconds,
                            ) as FailureAction.Retry
                            if (this.ownerId != ownerId || generation != boundGeneration) {
                                return AttachmentBatchResult.Complete
                            }
                            check(dao.completeFailure(
                                ownerId, claimed.operationId, boundGeneration, token, now.get(),
                                claimed.stage, AttachmentStage.RETRY, claimed.stage, retry.nextAttemptAt,
                                error.code, error.message,
                            ) == 1)
                            return AttachmentBatchResult.Retry
                        }
                        if (this.ownerId != ownerId || generation != boundGeneration) {
                            return AttachmentBatchResult.Complete
                        }
                        uploaded += claimed.operationId
                        check(dao.completeUpload(ownerId, claimed.operationId, boundGeneration, token, now.get()) == 1)
                    }
                    AttachmentStage.COMPLETE -> {
                        if (this.ownerId != ownerId || generation != boundGeneration) {
                            return AttachmentBatchResult.Complete
                        }
                        check(dao.completeSaved(ownerId, claimed.operationId, boundGeneration, token, now.get()) == 1)
                    }
                    else -> error("Only simulated reserved uploads are seeded in scheduling tests.")
                }
            }
            return nextBatch(ownerId, boundGeneration)
        }

        private suspend fun nextBatch(ownerId: String, generation: Long): AttachmentBatchResult =
            dao.nextWakeAt(ownerId, generation, now.get())?.let { AttachmentBatchResult.ScheduleAt(it) }
                ?: AttachmentBatchResult.Complete
    }

    private companion object {
        const val OWNER_A = "10000000-0000-4000-8000-000000000001"
        const val OWNER_B = "10000000-0000-4000-8000-000000000002"
        const val GENERATION = 7L
        const val HOUR_MILLIS = 60L * 60L * 1_000L
        const val TIMEOUT_SECONDS = 10L

        fun activeAccount() = AccountAccess.Active(AccountSummary(OWNER_A, null, null))
    }
}
