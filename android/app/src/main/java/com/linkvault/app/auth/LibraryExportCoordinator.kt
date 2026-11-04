package com.linkvault.app.auth

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class LibraryExportStatus {
    IDLE,
    SELECTING_DESTINATION,
    EXPORTING,
    CANCELLING,
    SUCCESS,
    ERROR,
    CANCELLED,
}

internal data class LibraryExportUiState(
    val status: LibraryExportStatus = LibraryExportStatus.IDLE,
    val pickerPending: Boolean = false,
    val cleaningUp: Boolean = false,
    val message: String? = null,
    val cleanupFailed: Boolean = false,
    val exportedBytes: Long? = null,
) {
    val isBusy: Boolean
        get() = pickerPending || cleaningUp || status == LibraryExportStatus.EXPORTING ||
            status == LibraryExportStatus.CANCELLING
}

// Activity scoped, deliberately without SavedStateHandle: a recreated process cannot resume an export.
internal class LibraryExportCoordinator internal constructor(
    private val session: StateFlow<AccountSessionState>,
    private val export: suspend (LibraryExportRequest, Uri, (Uri) -> Unit) -> Long,
    private val discard: suspend (Uri) -> Boolean,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LibraryExportUiState())
    val uiState: StateFlow<LibraryExportUiState> = mutableUiState.asStateFlow()

    private var currentRequest: LibraryExportRequest? = null
    private var pendingPicker: PickerSlot? = null
    private var transfer: Transfer? = null
    private var currentDestination: Uri? = null
    private var pendingCleanupCount = 0

    init {
        viewModelScope.launch {
            session.collect { live ->
                currentRequest?.let { request ->
                    if (!request.matches(live)) {
                        invalidate("로그인 계정이 변경되어 내보내기를 중단했어요.")
                        mutableUiState.value = mutableUiState.value.copy(cleanupFailed = false)
                    }
                }
            }
        }
    }

    fun launchPicker(request: LibraryExportRequest, launch: (String) -> Unit) {
        if (uiState.value.isBusy || pendingPicker != null || transfer != null) return
        if (!request.matches(session.value)) {
            reportUnavailable()
            return
        }
        currentRequest = request
        currentDestination = null
        pendingPicker = PickerSlot(request)
        mutableUiState.value = LibraryExportUiState(
            status = LibraryExportStatus.SELECTING_DESTINATION,
            pickerPending = true,
        )
        try {
            launch(request.fileName)
        } catch (error: Exception) {
            pendingPicker = null
            mutableUiState.value = LibraryExportUiState(
                status = if (error is CancellationException) LibraryExportStatus.CANCELLED else LibraryExportStatus.ERROR,
                message = if (error is CancellationException) "내보내기를 취소했어요."
                    else "파일 저장 위치 선택기를 열지 못했어요. 다시 시도해 주세요.",
            )
            if (error is CancellationException) throw error
        }
    }

    fun reportUnavailable() {
        if (uiState.value.isBusy) return
        mutableUiState.value = LibraryExportUiState(
            status = LibraryExportStatus.ERROR,
            message = "현재 로그인 계정 상태를 다시 확인한 뒤 내보내 주세요.",
        )
    }

    // The persistent ActivityResult registration delivers once per launch. Reserving invalidated
    // slots prevents a late first result from binding to another request. URI identity is only a
    // guard for duplicate results of this operation; providers may reuse it on a fresh launch.
    // An explicitly identified stale result is ignored, including synthetic duplicate callbacks.
    fun onDestinationResult(
        destination: Uri?,
        requestId: String? = pendingPicker?.request?.requestId,
    ) {
        if (requestId != pendingPicker?.request?.requestId) return
        if (destination != null && destination == currentDestination) return
        val slot = pendingPicker
        pendingPicker = null
        if (slot == null) {
            // ActivityResultRegistry can deliver a saved result after process death, with no request.
            if (transfer == null) currentDestination = destination
            destination?.let { discardUnclaimed(it, request = null) }
            return
        }
        currentDestination = destination
        if (destination == null) {
            mutableUiState.value = mutableUiState.value.copy(
                status = LibraryExportStatus.CANCELLED,
                pickerPending = false,
                message = "내보내기를 취소했어요.",
            )
            return
        }
        if (!slot.valid || !isCurrent(slot.request)) {
            mutableUiState.value = mutableUiState.value.copy(
                status = LibraryExportStatus.CANCELLED,
                pickerPending = false,
            )
            discardUnclaimed(destination, slot.request)
            return
        }
        startTransfer(slot.request, destination)
    }

    fun cancel() {
        if (uiState.value.isBusy) invalidate("내보내기를 취소했어요.")
    }

    fun leaveAccount() {
        cancel()
    }

    private fun invalidate(message: String) {
        pendingPicker?.valid = false
        val running = transfer
        running?.valid = false
        mutableUiState.value = mutableUiState.value.copy(
            status = if (running != null) LibraryExportStatus.CANCELLING else LibraryExportStatus.CANCELLED,
            pickerPending = pendingPicker != null,
            message = message,
            exportedBytes = null,
        )
        running?.job?.cancel()
    }

    private fun startTransfer(request: LibraryExportRequest, destination: Uri) {
        val running = Transfer()
        transfer = running
        mutableUiState.value = LibraryExportUiState(status = LibraryExportStatus.EXPORTING)
        running.job = viewModelScope.launch {
            var actionCompleted = false
            try {
                currentCoroutineContext().ensureActive()
                if (!running.valid || !isCurrent(request)) throw CancellationException()
                val bytes = export(request, destination) { reportCleanupFailure(request) }
                actionCompleted = true
                currentCoroutineContext().ensureActive()
                if (!running.valid || !isCurrent(request)) throw CancellationException()
                mutableUiState.value = mutableUiState.value.copy(
                    status = LibraryExportStatus.SUCCESS,
                    message = "JSON 파일로 내보냈어요.",
                    exportedBytes = bytes,
                )
            } catch (error: CancellationException) {
                // The action cleans failures itself. Only a completed action can need cleanup here:
                // cancellation/session change may race its final return to the coordinator.
                if (actionCompleted) discardCompleted(request, destination)
                if (transfer === running) {
                    mutableUiState.value = mutableUiState.value.copy(
                        status = LibraryExportStatus.CANCELLED,
                        message = mutableUiState.value.message ?: "내보내기를 취소했어요.",
                        exportedBytes = null,
                    )
                }
                throw error
            } catch (error: Exception) {
                if (running.valid && isCurrent(request)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        status = LibraryExportStatus.ERROR,
                        message = when (error) {
                            is AccountClientException -> error.message
                            is LibraryExportDestinationException ->
                                "선택한 파일에 저장하지 못했어요. 저장 공간과 파일 제공자를 확인해 주세요."
                            else -> "내보내지 못했어요. 연결과 저장 공간을 확인한 뒤 다시 시도해 주세요."
                        },
                    )
                }
            } finally {
                if (transfer === running) {
                    transfer = null
                    if (mutableUiState.value.status == LibraryExportStatus.CANCELLING) {
                        mutableUiState.value = mutableUiState.value.copy(status = LibraryExportStatus.CANCELLED)
                    }
                }
            }
        }
    }

    private fun discardUnclaimed(destination: Uri, request: LibraryExportRequest?) {
        val identity = session.value
        val expectedRequestId = currentRequest?.requestId
        pendingCleanupCount += 1
        mutableUiState.value = mutableUiState.value.copy(cleaningUp = true)
        viewModelScope.launch {
            try {
                val deleted = deleteDocument(destination)
                if (
                    !deleted && currentRequest?.requestId == expectedRequestId &&
                    identity.ownerId == session.value.ownerId && identity.generation == session.value.generation &&
                    (request == null || isCurrent(request))
                ) {
                    mutableUiState.value = mutableUiState.value.copy(cleanupFailed = true)
                }
            } finally {
                pendingCleanupCount -= 1
                mutableUiState.value = mutableUiState.value.copy(cleaningUp = pendingCleanupCount > 0)
            }
        }
    }

    private suspend fun discardCompleted(request: LibraryExportRequest, destination: Uri) {
        withContext(NonCancellable) {
            if (!deleteDocument(destination)) reportCleanupFailure(request)
        }
    }

    private suspend fun deleteDocument(destination: Uri): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                discard(destination)
            } catch (_: Exception) {
                false
            }
        }

    private fun reportCleanupFailure(request: LibraryExportRequest) {
        viewModelScope.launch {
            if (isCurrent(request)) {
                mutableUiState.value = mutableUiState.value.copy(cleanupFailed = true)
            }
        }
    }

    private fun isCurrent(request: LibraryExportRequest): Boolean =
        currentRequest == request && request.matches(session.value)

    private class PickerSlot(val request: LibraryExportRequest, var valid: Boolean = true)

    private class Transfer(var valid: Boolean = true, var job: Job? = null)

    companion object {
        fun factory(context: Context, client: AccountClient): ViewModelProvider.Factory {
            val action = LibraryExportAction.create(context.applicationContext, client)
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LibraryExportCoordinator::class.java))
                    return LibraryExportCoordinator(client.sessionState, action::export, action::discard) as T
                }
            }
        }
    }
}

private fun LibraryExportRequest.matches(session: AccountSessionState): Boolean =
    ownerId.isNotBlank() && ownerId == session.ownerId && generation == session.generation
