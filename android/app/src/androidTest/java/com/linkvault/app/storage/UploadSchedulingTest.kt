package com.linkvault.app.storage

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
 * Real WorkManager chains and in-memory Room claims with injected account/upload boundaries.
 * ControlledDependencies models batch processing using the real DAO and retry policy; these
 * scheduler regressions do not exercise HTTP, OutboxRepository or OS alarm delivery.
 */
@RunWith(AndroidJUnit4::class)
class UploadSchedulingTest {
    private lateinit var context: Context
    private lateinit var database: VaultDatabase
    private lateinit var dependencies: ControlledDependencies
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private val uploadGates = mutableListOf<CompletableDeferred<Unit>>()
    private val seedSequence = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Never resolve application repositories or the persistent VaultDatabase here.
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        dependencies = ControlledDependencies(database.outboxDao())
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = if (workerClassName == UploadWorker::class.java.name) {
                UploadWorker(appContext, workerParameters, dependencies)
            } else {
                // The production wake worker is constructed by WorkManager, unmodified.
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
    fun delayedRowDoesNotBlockNewLinkAndEarlyWakeCannotBypassItsDeadline() = runBlocking {
        val delayed = seed(nextAttemptAt = dependencies.now.get() + HOUR_MILLIS)
        val original = enqueueReady()
        runReady(original)
        val oldTimer = activeWake()
        assertTrue(oldTimer.initialDelayMillis > OutboxPolicy.MIN_BACKOFF_MILLIS)

        val fresh = seed()
        val immediate = enqueueReady()
        assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
        assertEquals(0L, immediate.initialDelayMillis)
        runReady(immediate)
        awaitRemoved(oldTimer.id)
        assertEquals(listOf(fresh.requestId), dependencies.uploaded.toList())
        assertEquals(delayed, database.outboxDao().findByRequestId(delayed.requestId))

        val timer = activeWake()
        val authCalls = dependencies.restoreCalls.get()
        val batchCalls = dependencies.processedOwners.size
        val woken = fireWake(timer)
        assertEquals(WorkInfo.State.ENQUEUED, woken.state)
        assertEquals(0L, woken.initialDelayMillis)
        assertEquals(authCalls, dependencies.restoreCalls.get())
        assertEquals(batchCalls, dependencies.processedOwners.size)
        runReady(woken)
        assertEquals(listOf(fresh.requestId), dependencies.uploaded.toList())
        assertEquals(delayed.nextAttemptAt, database.outboxDao().findByRequestId(delayed.requestId)?.nextAttemptAt)

        dependencies.now.set(delayed.nextAttemptAt)
        runReady(fireWake(activeWake()))
        assertEquals(listOf(fresh.requestId, delayed.requestId), dependencies.uploaded.toList())
        assertEquals(WorkInfo.State.SUCCEEDED, info(original.id).state)
    }

    @Test
    fun entryRetryContinuesReadyRowsAndDoesNotBecomeNewLinksBackoffPrerequisite() = runBlocking {
        val retrying = seed()
        val alreadyReady = seed()
        val attempts = AtomicInteger()
        dependencies.upload = { entry ->
            if (entry.requestId == retrying.requestId && attempts.getAndIncrement() == 0) {
                throw AccountClientException("Simulated rate limit", true, "RATE_LIMITED", 120)
            }
        }
        val original = enqueueReady()
        runReady(original)
        assertEquals(listOf(alreadyReady.requestId), dependencies.uploaded.toList())
        assertEquals(1, info(original.id).runAttemptCount)
        val oldTimer = activeWake()
        val retryAt = database.outboxDao().findByRequestId(retrying.requestId)!!.nextAttemptAt
        assertEquals(dependencies.now.get() + 120_000L, retryAt)

        val fresh = seed()
        runReady(enqueueReady())
        awaitRemoved(oldTimer.id)
        assertEquals(listOf(alreadyReady.requestId, fresh.requestId), dependencies.uploaded.toList())
        assertEquals(OutboxState.RETRY, database.outboxDao().findByRequestId(retrying.requestId)?.state)
        assertEquals(retryAt, database.outboxDao().findByRequestId(retrying.requestId)?.nextAttemptAt)
        assertTrue(activeWake().initialDelayMillis > OutboxPolicy.MIN_BACKOFF_MILLIS)

        database.outboxDao().retry(OWNER_A, retrying.requestId, dependencies.now.get())
        runReady(fireWake(activeWake()))
        assertEquals(1, attempts.get())
        assertEquals(retryAt, database.outboxDao().findByRequestId(retrying.requestId)?.nextAttemptAt)
        dependencies.now.set(retryAt)
        runReady(fireWake(activeWake()))
        assertEquals(2, attempts.get())
        assertEquals(OutboxState.SAVED, database.outboxDao().findByRequestId(retrying.requestId)?.state)
        assertEquals(WorkInfo.State.SUCCEEDED, info(original.id).state)
    }

    @Test
    fun newEnqueueAndWakeReplacementNeverCancelRunningUpload() = runBlocking {
        val delayed = seed(nextAttemptAt = dependencies.now.get() + HOUR_MILLIS)
        runReady(enqueueReady())
        val oldTimer = activeWake()
        val first = seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also(uploadGates::add)
        dependencies.upload = { entry ->
            if (entry.requestId == first.requestId) {
                entered.complete(Unit)
                release.await()
            }
        }
        val running = enqueueReady()
        driver.setAllConstraintsMet(running.id)
        awaitCondition("first upload entered") { entered.isCompleted }
        awaitState(running.id, WorkInfo.State.RUNNING)
        val lease = database.outboxDao().findByRequestId(first.requestId)!!
        assertTrue(lease.leaseUntil != null)

        val second = seed()
        val appended = enqueueReady()
        driver.setAllConstraintsMet(appended.id)
        assertEquals(WorkInfo.State.BLOCKED, info(appended.id).state)
        UploadWorker.enqueue(context, OWNER_A, delayMillis = HOUR_MILLIS)
            .result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitRemoved(oldTimer.id)
        assertEquals(WorkInfo.State.RUNNING, info(running.id).state)
        assertEquals(lease, database.outboxDao().findByRequestId(first.requestId))
        assertEquals(listOf(first.requestId), dependencies.uploadCalls.toList())
        assertEquals(
            0,
            database.outboxDao().completeSaved(OWNER_B, first.requestId, checkNotNull(lease.leaseUntil), "{}"),
        )

        release.complete(Unit)
        awaitState(running.id, WorkInfo.State.SUCCEEDED)
        awaitState(appended.id, WorkInfo.State.SUCCEEDED)
        assertEquals(listOf(first.requestId, second.requestId), dependencies.uploaded.toList())
        assertEquals(OutboxState.SAVED, database.outboxDao().findByRequestId(first.requestId)?.state)
        assertEquals(OutboxState.SAVED, database.outboxDao().findByRequestId(second.requestId)?.state)
        assertEquals(delayed, database.outboxDao().findByRequestId(delayed.requestId))
    }

    @Test
    fun transientAuthenticationUsesExponentialWakeDelaysAndHonorsRetryAfter() = runBlocking {
        val fresh = seed()
        val attempts = AtomicInteger()
        dependencies.restore = {
            when (attempts.getAndIncrement()) {
                0 -> throw AccountClientException("Simulated refresh failure", true)
                1 -> throw AccountClientException("Simulated rate limit", true, "RATE_LIMITED", 90)
                2 -> throw AccountClientException("Simulated refresh failure", true)
                else -> activeAccount()
            }
        }
        var immediate = enqueueReady()
        for (delay in listOf(30_000L, 90_000L, 120_000L)) {
            runReady(immediate)
            assertEquals(1, info(immediate.id).runAttemptCount)
            val timer = activeWake()
            assertEquals(delay, timer.initialDelayMillis)
            if (delay != 120_000L) immediate = fireWake(timer)
        }
        val retainedTimer = activeWake()
        assertTrue(dependencies.uploadCalls.isEmpty())
        runReady(enqueueReady())
        assertEquals(listOf(fresh.requestId), dependencies.uploaded.toList())
        assertEquals(WorkInfo.State.ENQUEUED, info(retainedTimer.id).state)
        assertTrue(immediateWork().all { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun sessionRefreshFailureStopsBatchButItsRetryDoesNotBlockNewReadyWork() = runBlocking {
        val retrying = seed()
        val otherReady = seed()
        dependencies.upload = {
            if (it.requestId == retrying.requestId) {
                throw AccountClientException("Simulated refresh failure", true, "SESSION_REFRESH_UNAVAILABLE")
            }
        }
        runReady(enqueueReady())
        val timer = activeWake()
        assertEquals(listOf(retrying.requestId), dependencies.uploadCalls.toList())
        assertEquals(OutboxState.PENDING, database.outboxDao().findByRequestId(otherReady.requestId)?.state)
        dependencies.upload = {}
        runReady(enqueueReady())
        awaitRemoved(timer.id)
        assertEquals(listOf(otherReady.requestId), dependencies.uploaded.toList())
        assertEquals(OutboxState.RETRY, database.outboxDao().findByRequestId(retrying.requestId)?.state)
    }

    @Test
    fun ownerCancellationCancelsBothNamesWithoutTouchingAnotherOwner() = runBlocking {
        seed(nextAttemptAt = dependencies.now.get() + HOUR_MILLIS)
        val other = seed(ownerId = OWNER_B, nextAttemptAt = dependencies.now.get() + HOUR_MILLIS)
        runReady(enqueueReady())
        val wakeA = activeWake()
        dependencies.ownerId = OWNER_B
        runReady(enqueueReady(OWNER_B))
        val wakeB = activeWake(OWNER_B)
        val pendingA = enqueueReady()
        val pendingB = enqueueReady(OWNER_B)
        assertNotEquals(UploadWorker.uniqueWorkName(OWNER_A), UploadWorker.uniqueWorkName(OWNER_B))
        assertNotEquals(UploadWorker.wakeWorkName(OWNER_A), UploadWorker.wakeWorkName(OWNER_B))

        UploadWorker.cancel(context, OWNER_A)
        awaitState(pendingA.id, WorkInfo.State.CANCELLED)
        awaitState(wakeA.id, WorkInfo.State.CANCELLED)
        assertEquals(WorkInfo.State.ENQUEUED, info(pendingB.id).state)
        assertEquals(WorkInfo.State.ENQUEUED, info(wakeB.id).state)
        assertEquals(other, database.outboxDao().findByRequestId(other.requestId))
        runReady(pendingB)
    }

    @Test
    fun differentActiveOwnerOrDeletingOwnerCannotProcessQueuedUpload() = runBlocking {
        val original = seed()
        dependencies.ownerId = OWNER_B
        runReady(enqueueReady())
        dependencies.restore = {
            AccountAccess.Deleting(AccountDeletionReceipt(OWNER_B, GENERATION, GENERATION, false))
        }
        runReady(enqueueReady())
        assertTrue(dependencies.processedOwners.isEmpty())
        assertTrue(dependencies.cleanupOwners.isEmpty())
        assertTrue(dependencies.uploadCalls.isEmpty())
        assertEquals(original, database.outboxDao().findByRequestId(original.requestId))
    }

    @Test
    fun deletionCleanupRetryDoesNotBlockNextCleanupAndNeverUploads() = runBlocking {
        seed()
        dependencies.restore = {
            AccountAccess.Deleting(AccountDeletionReceipt(OWNER_A, GENERATION, GENERATION, false))
        }
        val attempts = AtomicInteger()
        dependencies.cleanup = { attempts.incrementAndGet() > 1 }
        val first = enqueueReady()
        runReady(first)
        val timer = activeWake()
        runReady(enqueueReady())
        assertEquals(2, attempts.get())
        assertEquals(listOf(OWNER_A, OWNER_A), dependencies.cleanupOwners.toList())
        assertEquals(WorkInfo.State.SUCCEEDED, info(first.id).state)
        assertEquals(WorkInfo.State.ENQUEUED, info(timer.id).state)
        assertTrue(dependencies.processedOwners.isEmpty())
        assertTrue(dependencies.uploadCalls.isEmpty())
    }

    @Test
    fun expiryBeforeFutureRetryIsMaintainedAndNeverUploaded() = runBlocking {
        val expiring = seed(
            nextAttemptAt = dependencies.now.get() + 2L * HOUR_MILLIS,
            expiresAt = dependencies.now.get() + HOUR_MILLIS,
        )
        runReady(enqueueReady())
        val timer = activeWake()
        assertTrue(timer.initialDelayMillis > OutboxPolicy.MIN_BACKOFF_MILLIS)
        assertTrue(timer.initialDelayMillis <= HOUR_MILLIS)
        dependencies.now.set(expiring.expiresAt)
        runReady(fireWake(timer))
        val expired = database.outboxDao().findByRequestId(expiring.requestId)!!
        assertEquals(OutboxState.EXPIRED, expired.state)
        assertEquals("REQUEST_EXPIRED", expired.errorCode)
        assertNull(expired.leaseUntil)
        assertTrue(dependencies.uploadCalls.isEmpty())
        assertFalse(wakeWork(OWNER_A).any { it.state == WorkInfo.State.ENQUEUED })
    }

    private suspend fun seed(
        ownerId: String = OWNER_A,
        nextAttemptAt: Long = dependencies.now.get(),
        expiresAt: Long = OutboxPolicy.expiresAt(dependencies.now.get()),
    ): OutboxEntry {
        val entry = OutboxEntry(
            requestId = UUID.randomUUID().toString(),
            ownerId = ownerId,
            method = "POST",
            path = "/items",
            payloadJson = "{\"url\":\"https://example.com/${UUID.randomUUID()}\"}",
            state = OutboxState.PENDING,
            attemptCount = 0,
            createdAt = dependencies.now.get() - 1_000L + seedSequence.getAndIncrement(),
            expiresAt = expiresAt,
            nextAttemptAt = nextAttemptAt,
        )
        database.outboxDao().insertImmutable(entry)
        return entry
    }

    private fun enqueueReady(ownerId: String = OWNER_A): WorkInfo {
        val before = immediateWork(ownerId).map { it.id }.toSet()
        UploadWorker.enqueue(context, ownerId).result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return immediateWork(ownerId).single { it.id !in before }
    }

    private fun fireWake(timer: WorkInfo): WorkInfo {
        val owner = if (wakeWork(OWNER_A).any { it.id == timer.id }) OWNER_A else OWNER_B
        val before = immediateWork(owner).map { it.id }.toSet()
        driver.setInitialDelayMet(timer.id)
        awaitState(timer.id, WorkInfo.State.SUCCEEDED)
        // Success means the zero-delay request was durably enqueued, not launched asynchronously.
        return immediateWork(owner).single { it.id !in before }
    }

    private fun runReady(work: WorkInfo) {
        driver.setAllConstraintsMet(work.id)
        awaitState(work.id, WorkInfo.State.SUCCEEDED)
    }

    private fun activeWake(ownerId: String = OWNER_A): WorkInfo =
        wakeWork(ownerId).single { it.state == WorkInfo.State.ENQUEUED }

    private fun immediateWork(ownerId: String = OWNER_A): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(UploadWorker.uniqueWorkName(ownerId))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun wakeWork(ownerId: String): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(UploadWorker.wakeWorkName(ownerId))
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

    private class ControlledDependencies(private val dao: OutboxDao) : UploadWorkerDependencies {
        val now = AtomicLong(System.currentTimeMillis())
        val restoreCalls = AtomicInteger()
        val processedOwners = CopyOnWriteArrayList<String>()
        val cleanupOwners = CopyOnWriteArrayList<String>()
        val uploadCalls = CopyOnWriteArrayList<String>()
        val uploaded = CopyOnWriteArrayList<String>()
        @Volatile var ownerId: String? = OWNER_A
        var restore: suspend () -> AccountAccess? = { activeAccount() }
        var cleanup: suspend (String) -> Boolean = { true }
        var upload: suspend (OutboxEntry) -> Unit = {}

        override suspend fun restoreAccount(): AccountAccess? {
            restoreCalls.incrementAndGet()
            return restore()
        }

        override fun sessionUserId(): String? = ownerId

        override suspend fun clearAcceptedDeletionLocalData(ownerId: String): Boolean {
            cleanupOwners += ownerId
            return cleanup(ownerId)
        }

        override suspend fun processBatch(ownerId: String): BatchResult {
            processedOwners += ownerId
            dao.expireAutomaticRequests(ownerId, now.get())
            dao.resumeWaitingLogin(ownerId, now.get())
            repeat(OutboxPolicy.MAX_BATCH_SIZE) {
                val claimed = dao.claimNext(ownerId, now.get(), OutboxPolicy.leaseUntil(now.get()))
                    ?: return nextBatch(ownerId)
                val lease = checkNotNull(claimed.leaseUntil)
                uploadCalls += claimed.requestId
                try {
                    upload(claimed)
                } catch (error: AccountClientException) {
                    val retry = OutboxPolicy.classifyFailure(
                        now.get(), claimed.expiresAt, claimed.attemptCount,
                        error.retryable, error.code, error.retryAfterSeconds,
                    ) as FailureAction.Retry
                    if (this.ownerId != ownerId) return BatchResult.Complete
                    check(dao.completeFailure(
                        ownerId, claimed.requestId, lease, OutboxState.RETRY,
                        retry.nextAttemptAt, error.code, error.message,
                    ) == 1)
                    if (error.code == "SESSION_REFRESH_UNAVAILABLE") return BatchResult.Retry
                    return@repeat
                }
                if (this.ownerId != ownerId) return BatchResult.Complete
                check(dao.completeSaved(ownerId, claimed.requestId, lease, "{}") == 1)
                uploaded += claimed.requestId
            }
            return nextBatch(ownerId)
        }

        private suspend fun nextBatch(ownerId: String): BatchResult =
            dao.nextWakeAt(ownerId)?.let { BatchResult.ScheduleAt(it) } ?: BatchResult.Complete
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
