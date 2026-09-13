package com.linkvault.app.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountViewModel internal constructor(
    private val client: AccountClient,
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

    fun logout() {
        if (operationJob?.isActive == true) return
        mutableUiState.value = AccountUiState.Loading("로그아웃 중이에요.")
        launchOperation {
            try {
                client.signOut()
                mutableUiState.value = AccountUiState.Login()
            } catch (error: AccountClientException) {
                mutableUiState.value = AccountUiState.Login(error.message)
            }
        }
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
                mutableUiState.value = AccountUiState.Login("로그인이 만료됐어요. 다시 로그인해 주세요.")
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

    private fun showAccess(access: AccountAccess) {
        mutableUiState.value = when (access) {
            is AccountAccess.Active -> AccountUiState.Active(access.summary)
            AccountAccess.PendingApproval -> AccountUiState.PendingApproval
            AccountAccess.Deleting -> AccountUiState.Deleting
        }
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
        fun factory(client: AccountClient): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AccountViewModel::class.java))
                    return AccountViewModel(client) as T
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
    data object Deleting : AccountUiState
    data class Error(val message: String, val canRetry: Boolean) : AccountUiState
    data class Unconfigured(val message: String) : AccountUiState
}
