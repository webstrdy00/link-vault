package com.linkvault.app

import android.app.Application
import com.linkvault.app.attachment.AttachmentRepository
import com.linkvault.app.attachment.IncomingImageStore
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountAccess
import com.linkvault.app.storage.OutboxRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LinkVaultApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draftMutex = Mutex()
    private val draftRevision = AtomicLong()
    private val localState by lazy { getSharedPreferences("local_state", MODE_PRIVATE) }
    private val mutableLogoutGeneration by lazy {
        MutableStateFlow(localState.getLong("logout_generation", 0L))
    }
    val logoutGeneration get() = mutableLogoutGeneration.asStateFlow()
    private val mutableDraftError = MutableStateFlow<String?>(null)
    val draftError = mutableDraftError.asStateFlow()

    val accountClient: AccountClient by lazy {
        AccountClient(
            context = this,
            url = BuildConfig.SUPABASE_URL,
            key = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            debug = BuildConfig.DEBUG,
            localDataOwner = { localState.getString("data_owner", null) },
            beforeSignOut = { clearLocalBoundary(nextOwner = null) },
            onSessionOwner = ::bindLocalOwner,
        )
    }

    val outboxRepository: OutboxRepository by lazy { OutboxRepository(this, accountClient) }
    val attachmentRepository: AttachmentRepository by lazy {
        AttachmentRepository(this, accountClient, outboxRepository)
    }
    val incomingImageStore: IncomingImageStore by lazy { IncomingImageStore(this) }

    private suspend fun bindLocalOwner(ownerId: String): Unit = withContext(Dispatchers.IO) {
        val previous = localState.getString("data_owner", null)
        if (previous != null && previous != ownerId) {
            clearLocalBoundary(nextOwner = ownerId)
        } else if (previous == null) {
            check(localState.edit().putString("data_owner", ownerId).commit()) {
                "Could not persist local data ownership"
            }
        }
    }

    private suspend fun clearLocalBoundary(nextOwner: String?): Unit = withContext(Dispatchers.IO) {
        draftRevision.incrementAndGet()
        draftMutex.withLock {
            incomingImageStore.clear()
            attachmentRepository.clearAll()
            outboxRepository.clearAllOwners()
            val generation = mutableLogoutGeneration.value + 1
            check(
                localState.edit().putLong("logout_generation", generation)
                    .putString("data_owner", nextOwner).commit(),
            ) { "Could not persist local logout boundary" }
            mutableLogoutGeneration.value = generation
            mutableDraftError.value = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                outboxRepository.cleanupDrafts()
                incomingImageStore.cleanupExpired()
                attachmentRepository.cleanup()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableDraftError.value = "만료된 임시 입력을 정리하지 못했어요."
            }
            if (!accountClient.isConfigured) return@launch
            var restoredAccess: AccountAccess? = null
            try {
                restoredAccess = accountClient.restoreAccount()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A transient refresh failure can preserve the known session owner.
                // The worker, not this startup path, records transport errors.
            }
            try {
                if (restoredAccess is AccountAccess.Deleting) {
                    val acceptance = accountClient.clearAcceptedDeletionLocalData(
                        restoredAccess.receipt.ownerId,
                    )
                    if (!acceptance.localDataCleared) {
                        mutableDraftError.value = "탈퇴는 접수됐지만 기기의 개인 자료를 정리하지 못했어요."
                    }
                    return@launch
                }
                if (restoredAccess !is AccountAccess.Active) return@launch
                accountClient.sessionUserId()?.let { owner ->
                    outboxRepository.resumeOwner(owner)
                    attachmentRepository.resumeOwner(owner)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableDraftError.value = "전송 대기 자료를 복구하지 못했어요. 보관함에서 다시 시도해 주세요."
            }
        }
    }

    fun persistCapture(text: String, selectedUrl: String?) {
        val revision = draftRevision.incrementAndGet()
        applicationScope.launch {
            draftMutex.withLock {
                if (revision != draftRevision.get()) return@withLock
                try {
                    if (text.isBlank()) {
                        outboxRepository.deleteDraft(CAPTURE_DRAFT_ID)
                    } else {
                        outboxRepository.saveDraft(CAPTURE_DRAFT_ID, text, selectedUrl)
                    }
                    mutableDraftError.value = null
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableDraftError.value = "입력을 기기에 임시 보관하지 못했어요."
                }
            }
        }
    }

    companion object {
        const val CAPTURE_DRAFT_ID = "capture"
    }
}
