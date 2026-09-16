package com.linkvault.app.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linkvault.app.storage.OutboxRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountViewModel internal constructor(
    private val client: AccountClient,
    private val outbox: OutboxRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<AccountUiState>(AccountUiState.Loading())
    val uiState: StateFlow<AccountUiState> = mutableUiState.asStateFlow()

    private var operationJob: Job? = null
    private var retryTarget = RetryTarget.RESTORE_SESSION

    init {
        if (client.isConfigured) {
            restoreSession()
        } else {
            mutableUiState.value = AccountUiState.Unconfigured(
                client.configurationMessage ?: "로그인 설정이 필요해요.",
            )
        }
    }

    fun login(activity: Activity) {
        if (operationJob?.isActive == true) return
        mutableUiState.value = AccountUiState.Loading(
            message = "Google 로그인 중이에요.",
            canCancel = true,
        )
        retryTarget = RetryTarget.LOGIN
        launchOperation {
            try {
                showAccess(client.signIn(activity))
            } catch (_: AccountSignInCancelledException) {
                mutableUiState.value = AccountUiState.Login("로그인을 취소했어요.")
            } catch (_: AccountAuthenticationRequiredException) {
                mutableUiState.value = AccountUiState.Login("로그인이 완료되지 않았어요. 다시 시도해 주세요.")
            } catch (error: AccountClientException) {
                retryTarget = if (client.hasSession()) {
                    RetryTarget.BOOTSTRAP
                } else {
                    RetryTarget.LOGIN
                }
                mutableUiState.value = AccountUiState.Error(
                    message = error.message,
                    canRetry = error.retryable,
                )
            }
        }
    }

    fun cancelLogin() {
        val state = mutableUiState.value as? AccountUiState.Loading ?: return
        if (!state.canCancel) return
        val job = operationJob ?: return
        operationJob = null
        job.cancel()
        mutableUiState.value = if (client.hasSession()) {
            retryTarget = RetryTarget.BOOTSTRAP
            AccountUiState.Error(
                message = "로그인은 완료됐지만 계정 확인을 중단했어요.",
                canRetry = true,
            )
        } else {
            AccountUiState.Login("로그인을 취소했어요.")
        }
    }

    fun retry(activity: Activity) {
        if (operationJob?.isActive == true) return
        if (mutableUiState.value is AccountUiState.PendingApproval) {
            checkAccount()
            return
        }
        when (retryTarget) {
            RetryTarget.RESTORE_SESSION -> restoreSession()
            RetryTarget.LOGIN -> login(activity)
            RetryTarget.BOOTSTRAP -> bootstrap()
            RetryTarget.CHECK_ACCOUNT -> checkAccount()
        }
    }

    fun requestLogout() {
        if (
            operationJob?.isActive == true ||
            mutableUiState.value is AccountUiState.ConfirmLogout ||
            mutableUiState.value is AccountUiState.ConfirmAccountDeletion
        ) {
            return
        }
        val previous = mutableUiState.value
        val expectedSessionOwner = client.sessionUserId()
        mutableUiState.value = AccountUiState.Loading("정리할 기기 자료를 확인하고 있어요.")
        launchOperation {
            try {
                val pendingCount = outbox.retainedCount()
                mutableUiState.value = AccountUiState.ConfirmLogout(
                    previous = previous,
                    pendingCount = pendingCount,
                    expectedSessionOwner = expectedSessionOwner,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableUiState.value = AccountUiState.ConfirmLogout(
                    previous = previous,
                    pendingCount = 0,
                    expectedSessionOwner = expectedSessionOwner,
                    message = "기기에 남은 대기 자료 수를 확인하지 못했어요. 다시 확인하거나 취소해 주세요.",
                    canConfirm = false,
                )
            }
        }
    }

    fun confirmLogout() {
        if (operationJob?.isActive == true) return
        val confirmation = mutableUiState.value as? AccountUiState.ConfirmLogout ?: return
        if (!confirmation.canConfirm) return
        if (client.sessionUserId() != confirmation.expectedSessionOwner) {
            mutableUiState.value = confirmation.copy(
                message = "로그인 계정 상태가 변경됐어요. 기기 자료를 다시 확인해 주세요.",
                canConfirm = false,
            )
            return
        }
        mutableUiState.value = AccountUiState.Loading(
            if (confirmation.expectedSessionOwner == null) {
                "기기 대기 자료를 정리하고 있어요."
            } else {
                "로그아웃 중이에요."
            },
        )
        launchOperation {
            try {
                client.signOut()
                mutableUiState.value = AccountUiState.Login()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = (error as? AccountClientException)?.message
                    ?: if (confirmation.expectedSessionOwner == null) {
                        "기기 대기 자료를 정리하지 못했어요. 다시 시도해 주세요."
                    } else {
                        "로그아웃하지 못했어요. 다시 시도해 주세요."
                    }
                mutableUiState.value = confirmation.copy(message = message)
            }
        }
    }

    fun cancelLogout() {
        if (operationJob?.isActive == true) return
        val confirmation = mutableUiState.value as? AccountUiState.ConfirmLogout ?: return
        mutableUiState.value = confirmation.previous
    }

    fun retryLogoutConfirmation() {
        if (operationJob?.isActive == true) return
        val confirmation = mutableUiState.value as? AccountUiState.ConfirmLogout ?: return
        if (confirmation.canConfirm) return
        mutableUiState.value = confirmation.previous
        requestLogout()
    }

    fun requestAccountDeletion() {
        if (operationJob?.isActive == true) return
        val previous = mutableUiState.value
        if (previous !is AccountUiState.Active && previous !is AccountUiState.PendingApproval) return
        val session = client.sessionState.value
        val ownerId = session.ownerId
        if (ownerId == null) {
            mutableUiState.value = AccountUiState.Login("로그인이 만료됐어요. 다시 로그인해 주세요.")
            return
        }
        mutableUiState.value = AccountUiState.ConfirmAccountDeletion(
            previous = previous,
            expectedSessionOwner = ownerId,
            expectedSessionGeneration = session.generation,
        )
    }

    fun confirmAccountDeletion(activity: Activity) {
        if (operationJob?.isActive == true) return
        val confirmation =
            mutableUiState.value as? AccountUiState.ConfirmAccountDeletion ?: return
        if (!confirmation.canConfirm) return
        val currentSession = client.sessionState.value
        if (
            currentSession.ownerId != confirmation.expectedSessionOwner ||
            currentSession.generation != confirmation.expectedSessionGeneration
        ) {
            mutableUiState.value = confirmation.copy(
                message = "로그인 계정이 변경됐어요. 계정 상태를 다시 확인해 주세요.",
                canConfirm = false,
            )
            return
        }
        mutableUiState.value = AccountUiState.Loading("Google에서 계정 삭제를 다시 확인하고 있어요.")
        launchOperation {
            try {
                val acceptance = client.requestAccountDeletion(
                    activity = activity,
                    expectedOwnerId = confirmation.expectedSessionOwner,
                    expectedGeneration = confirmation.expectedSessionGeneration,
                )
                val receipt = client.currentAcceptedDeletionReceipt(
                    expectedOwnerId = confirmation.expectedSessionOwner,
                    acceptanceGeneration = confirmation.expectedSessionGeneration,
                ) ?: throw deletionReceiptChanged()
                showDeletionAcceptance(receipt, acceptance)
            } catch (_: AccountDeletionReauthenticationCancelledException) {
                mutableUiState.value = confirmation.copy(
                    message = "Google 재인증을 취소해 계정 삭제를 시작하지 않았어요.",
                )
            } catch (error: AccountDeletionStatusUnknownException) {
                mutableUiState.value = AccountUiState.DeletionStatusUnknown(
                    expectedSessionOwner = error.expectedOwnerId,
                    expectedSessionGeneration = error.expectedGeneration,
                    message = error.message,
                )
            } catch (_: AccountAuthenticationRequiredException) {
                mutableUiState.value = AccountUiState.Login(
                    "로그인이 만료되어 계정 삭제를 시작하지 않았어요.",
                )
            } catch (error: AccountClientException) {
                mutableUiState.value = if (error.code == "SESSION_CHANGED") {
                    AccountUiState.Error(
                        message = error.message,
                        canRetry = false,
                    )
                } else {
                    confirmation.copy(
                        message = error.message,
                        canConfirm = client.sessionState.value.let {
                            it.ownerId == confirmation.expectedSessionOwner &&
                                it.generation == confirmation.expectedSessionGeneration
                        },
                    )
                }
            }
        }
    }

    fun cancelAccountDeletion() {
        if (operationJob?.isActive == true) return
        val confirmation =
            mutableUiState.value as? AccountUiState.ConfirmAccountDeletion ?: return
        mutableUiState.value = confirmation.previous
    }

    fun checkAccountDeletionStatus() {
        if (operationJob?.isActive == true) return
        val unknown = mutableUiState.value as? AccountUiState.DeletionStatusUnknown ?: return
        mutableUiState.value = AccountUiState.Loading("계정 삭제 접수 상태를 확인하고 있어요.")
        launchOperation {
            try {
                when (
                    val access = client.recoverAccountDeletionState(
                        expectedOwnerId = unknown.expectedSessionOwner,
                        expectedGeneration = unknown.expectedSessionGeneration,
                    )
                ) {
                    is AccountAccess.Active -> {
                        outbox.resumeOwner(unknown.expectedSessionOwner)
                        mutableUiState.value = AccountUiState.ConfirmAccountDeletion(
                            previous = AccountUiState.Active(access.summary),
                            expectedSessionOwner = unknown.expectedSessionOwner,
                            expectedSessionGeneration = unknown.expectedSessionGeneration,
                            message = "서버에서 삭제 접수를 확인하지 못했어요. 삭제하려면 다시 확인해 주세요.",
                        )
                    }
                    AccountAccess.PendingApproval -> {
                        mutableUiState.value = AccountUiState.ConfirmAccountDeletion(
                            previous = AccountUiState.PendingApproval,
                            expectedSessionOwner = unknown.expectedSessionOwner,
                            expectedSessionGeneration = unknown.expectedSessionGeneration,
                            message = "서버에서 삭제 접수를 확인하지 못했어요. 삭제하려면 다시 확인해 주세요.",
                        )
                    }
                    is AccountAccess.Deleting -> {
                        showDeletingAccess(access)
                    }
                }
            } catch (_: AccountAuthenticationRequiredException) {
                mutableUiState.value = preserveUnknownDeletionStatus(
                    unknown = unknown,
                    message = "로그인이 만료되어 삭제 접수 여부를 확인할 수 없어요. 삭제가 시작되지 않았다고 판단할 수 없어요.",
                )
            } catch (error: AccountClientException) {
                mutableUiState.value = preserveUnknownDeletionStatus(
                    unknown = unknown,
                    message = error.message,
                )
            }
        }
    }

    fun retryAcceptedDeletionLocalCleanup() {
        if (operationJob?.isActive == true) return
        val accepted = mutableUiState.value as? AccountUiState.DeletionAccepted ?: return
        if (accepted.localDataCleared) return
        mutableUiState.value = AccountUiState.Loading("이 기기에 남은 계정 자료를 정리하고 있어요.")
        launchOperation {
            client.currentAcceptedDeletionReceipt(
                expectedOwnerId = accepted.expectedSessionOwner,
                acceptanceGeneration = accepted.acceptanceGeneration,
            ) ?: throw deletionReceiptChanged()
            val acceptance = client.clearAcceptedDeletionLocalData(
                accepted.expectedSessionOwner,
            )
            val receipt = client.currentAcceptedDeletionReceipt(
                expectedOwnerId = accepted.expectedSessionOwner,
                acceptanceGeneration = accepted.acceptanceGeneration,
            ) ?: throw deletionReceiptChanged()
            showDeletionAcceptance(receipt, acceptance)
        }
    }

    fun reportDeletionReauthenticationUnavailable() {
        val confirmation =
            mutableUiState.value as? AccountUiState.ConfirmAccountDeletion ?: return
        mutableUiState.value = confirmation.copy(
            message = "이 화면에서는 Google 재인증을 열 수 없어 계정 삭제를 시작하지 않았어요.",
            canConfirm = false,
        )
    }

    fun reportLoginUnavailable() {
        mutableUiState.value = AccountUiState.Error(
            message = "이 화면에서는 Google 로그인을 열 수 없어요.",
            canRetry = false,
        )
        retryTarget = RetryTarget.LOGIN
    }

    private fun restoreSession() {
        mutableUiState.value = AccountUiState.Loading("로그인 상태를 확인하고 있어요.")
        retryTarget = RetryTarget.RESTORE_SESSION
        launchOperation {
            try {
                val access = client.restoreAccount()
                if (access == null) {
                    mutableUiState.value = AccountUiState.Login()
                } else {
                    showAccess(access)
                }
            } catch (_: AccountAuthenticationRequiredException) {
                mutableUiState.value =
                    AccountUiState.Login("로그인이 만료됐어요. 다시 로그인해 주세요.")
            } catch (error: AccountClientException) {
                mutableUiState.value = AccountUiState.Error(
                    message = error.message,
                    canRetry = error.retryable,
                )
            }
        }
    }

    private fun bootstrap() {
        mutableUiState.value = AccountUiState.Loading("계정을 확인하고 있어요.")
        retryTarget = RetryTarget.BOOTSTRAP
        launchOperation {
            try {
                showAccess(client.bootstrapAccount())
            } catch (_: AccountAuthenticationRequiredException) {
                mutableUiState.value = AccountUiState.Login("로그인이 만료됐어요. 다시 로그인해 주세요.")
            } catch (error: AccountClientException) {
                mutableUiState.value = AccountUiState.Error(
                    message = error.message,
                    canRetry = error.retryable,
                )
            }
        }
    }

    private fun checkAccount() {
        mutableUiState.value = AccountUiState.Loading("승인 상태를 확인하고 있어요.")
        retryTarget = RetryTarget.CHECK_ACCOUNT
        launchOperation {
            try {
                // Approval may have arrived before this member has a profile.
                // Bootstrap creates it atomically; /me alone would stay pending.
                showAccess(client.bootstrapAccount())
            } catch (_: AccountAuthenticationRequiredException) {
                mutableUiState.value = AccountUiState.Login("로그인이 만료됐어요. 다시 로그인해 주세요.")
            } catch (error: AccountClientException) {
                mutableUiState.value = AccountUiState.Error(
                    message = error.message,
                    canRetry = error.retryable,
                )
            }
        }
    }

    private suspend fun showAccess(access: AccountAccess) {
        if (access is AccountAccess.Deleting) {
            showDeletingAccess(access)
            return
        }
        val ownerId = client.sessionUserId() ?: throw AccountAuthenticationRequiredException()
        when (access) {
            is AccountAccess.Active -> {
                outbox.resumeOwner(ownerId)
                mutableUiState.value = AccountUiState.Active(access.summary)
            }
            AccountAccess.PendingApproval -> {
                mutableUiState.value = AccountUiState.PendingApproval
            }
            is AccountAccess.Deleting -> error("Handled before requiring a live session")
        }
    }

    private fun showDeletionAcceptance(
        receipt: AccountDeletionReceipt,
        acceptance: AccountDeletionAcceptance,
    ) {
        mutableUiState.value = AccountUiState.DeletionAccepted(
            expectedSessionOwner = receipt.ownerId,
            acceptanceGeneration = receipt.acceptanceGeneration,
            localDataCleared = acceptance.localDataCleared,
            message = acceptance.message,
        )
    }

    private suspend fun showDeletingAccess(access: AccountAccess.Deleting) {
        val receipt = client.currentAcceptedDeletionReceipt(
            expectedOwnerId = access.receipt.ownerId,
            acceptanceGeneration = access.receipt.acceptanceGeneration,
        ) ?: throw deletionReceiptChanged()
        val acceptance = if (receipt.localDataCleared) {
            AccountDeletionAcceptance(localDataCleared = true)
        } else {
            client.clearAcceptedDeletionLocalData(receipt.ownerId)
        }
        val currentReceipt = client.currentAcceptedDeletionReceipt(
            expectedOwnerId = receipt.ownerId,
            acceptanceGeneration = receipt.acceptanceGeneration,
        ) ?: throw deletionReceiptChanged()
        showDeletionAcceptance(
            receipt = currentReceipt,
            acceptance = if (currentReceipt.localDataCleared) {
                AccountDeletionAcceptance(localDataCleared = true)
            } else {
                acceptance
            },
        )
    }

    private fun launchOperation(block: suspend () -> Unit) {
        operationJob?.cancel()
        val job = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.value = AccountUiState.Error(
                    message = "예상하지 못한 오류가 발생했어요. 다시 시도해 주세요.",
                    canRetry = true,
                )
            }
        }
        operationJob = job
        job.invokeOnCompletion {
            if (operationJob === job) operationJob = null
        }
    }

    companion object {
        fun factory(
            client: AccountClient,
            outbox: OutboxRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AccountViewModel::class.java))
                return AccountViewModel(client, outbox) as T
            }
        }
    }

    private enum class RetryTarget {
        RESTORE_SESSION,
        LOGIN,
        BOOTSTRAP,
        CHECK_ACCOUNT,
    }
}

sealed interface AccountUiState {
    data class Loading(
        val message: String = "확인하고 있어요.",
        val canCancel: Boolean = false,
    ) : AccountUiState

    data class Login(val message: String? = null) : AccountUiState
    data object PendingApproval : AccountUiState
    data class Active(val summary: AccountSummary) : AccountUiState
    data class Error(val message: String, val canRetry: Boolean) : AccountUiState
    data class Unconfigured(val message: String) : AccountUiState
    data class ConfirmLogout(
        val previous: AccountUiState,
        val pendingCount: Int,
        val expectedSessionOwner: String?,
        val message: String? = null,
        val canConfirm: Boolean = true,
    ) : AccountUiState

    data class ConfirmAccountDeletion(
        val previous: AccountUiState,
        val expectedSessionOwner: String,
        val expectedSessionGeneration: Long,
        val message: String? = null,
        val canConfirm: Boolean = true,
    ) : AccountUiState

    data class DeletionStatusUnknown(
        val expectedSessionOwner: String,
        val expectedSessionGeneration: Long,
        val message: String,
    ) : AccountUiState

    data class DeletionAccepted(
        val expectedSessionOwner: String,
        val acceptanceGeneration: Long,
        val localDataCleared: Boolean,
        val message: String? = null,
    ) : AccountUiState
}

internal fun preserveUnknownDeletionStatus(
    unknown: AccountUiState.DeletionStatusUnknown,
    message: String,
): AccountUiState.DeletionStatusUnknown = unknown.copy(message = message)

private fun deletionReceiptChanged() = AccountClientException(
    message = "로그인 계정이 변경되어 삭제 접수 화면을 닫았어요.",
    retryable = false,
    code = "SESSION_CHANGED",
)
