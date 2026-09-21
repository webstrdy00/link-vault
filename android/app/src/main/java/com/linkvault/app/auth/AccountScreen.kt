package com.linkvault.app.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkvault.app.storage.OutboxRepository

@Composable
fun AccountScreen(
    client: AccountClient,
    outbox: OutboxRepository,
    onBack: () -> Unit,
) {
    val factory = remember(client, outbox) { AccountViewModel.factory(client, outbox) }
    val accountViewModel: AccountViewModel = viewModel(factory = factory)
    val state by accountViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()
    var fontLicenseText by remember { mutableStateOf<String?>(null) }
    val confirmationVisible =
        state is AccountUiState.ConfirmLogout || state is AccountUiState.ConfirmAccountDeletion

    BackHandler(enabled = confirmationVisible) {
        when (state) {
            is AccountUiState.ConfirmLogout -> accountViewModel.cancelLogout()
            is AccountUiState.ConfirmAccountDeletion -> accountViewModel.cancelAccountDeletion()
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = when (state) {
                        is AccountUiState.ConfirmLogout -> accountViewModel::cancelLogout
                        is AccountUiState.ConfirmAccountDeletion ->
                            accountViewModel::cancelAccountDeletion
                        else -> onBack
                    },
                    shape = EditorialShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("뒤로")
                }
                Text(
                    text = "계정",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(
                onClick = {
                    fontLicenseText = context.assets
                        .open("licenses/pretendard-OFL.txt")
                        .bufferedReader()
                        .use { it.readText() }
                },
                shape = EditorialShape,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("font-license"),
            ) {
                Text(
                    text = "글꼴 라이선스",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        HorizontalDivider()

        when (val currentState = state) {
            is AccountUiState.Loading -> LoadingContent(
                state = currentState,
                onCancel = accountViewModel::cancelLogin,
            )

            is AccountUiState.Login -> LoginContent(
                message = currentState.message,
                onLogin = {
                    if (activity == null) {
                        accountViewModel.reportLoginUnavailable()
                    } else {
                        accountViewModel.login(activity)
                    }
                },
                onClearDeviceData = accountViewModel::requestLogout,
            )

            AccountUiState.PendingApproval -> PendingApprovalContent(
                onRetry = {
                    if (activity == null) {
                        accountViewModel.reportLoginUnavailable()
                    } else {
                        accountViewModel.retry(activity)
                    }
                },
                onLogout = accountViewModel::requestLogout,
                onDeleteAccount = accountViewModel::requestAccountDeletion,
            )

            is AccountUiState.Active -> ActiveContent(
                summary = currentState.summary,
                onLogout = accountViewModel::requestLogout,
                onDeleteAccount = accountViewModel::requestAccountDeletion,
            )

            is AccountUiState.Error -> ErrorContent(
                state = currentState,
                onRetry = {
                    if (activity == null) {
                        accountViewModel.reportLoginUnavailable()
                    } else {
                        accountViewModel.retry(activity)
                    }
                },
                onClearDeviceData = accountViewModel::requestLogout,
                hasSession = client.hasSession(),
            )

            is AccountUiState.Unconfigured -> UnconfiguredContent(currentState.message)

            is AccountUiState.ConfirmLogout -> ConfirmLogoutContent(
                state = currentState,
                onConfirm = accountViewModel::confirmLogout,
                onRetryCheck = accountViewModel::retryLogoutConfirmation,
                onCancel = accountViewModel::cancelLogout,
            )

            is AccountUiState.ConfirmAccountDeletion -> ConfirmAccountDeletionContent(
                state = currentState,
                onConfirm = {
                    if (activity == null) {
                        accountViewModel.reportDeletionReauthenticationUnavailable()
                    } else {
                        accountViewModel.confirmAccountDeletion(activity)
                    }
                },
                onCancel = accountViewModel::cancelAccountDeletion,
            )

            is AccountUiState.DeletionStatusUnknown -> DeletionStatusUnknownContent(
                state = currentState,
                onCheck = accountViewModel::checkAccountDeletionStatus,
            )

            is AccountUiState.DeletionAccepted -> DeletionAcceptedContent(
                state = currentState,
                onRetryLocalCleanup = accountViewModel::retryAcceptedDeletionLocalCleanup,
            )
        }
    }

    fontLicenseText?.let { licenseText ->
        FontLicenseDialog(
            text = licenseText,
            onDismiss = { fontLicenseText = null },
        )
    }
}

@Composable
private fun FontLicenseDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("font-license-dialog"),
        title = {
            Text(
                text = "글꼴 라이선스",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = text,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = EditorialShape,
            ) {
                Text("닫기")
            }
        },
    )
}

@Composable
private fun LoadingContent(
    state: AccountUiState.Loading,
    onCancel: () -> Unit,
) {
    SettingsGroup(title = "계정 상태") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            Text(state.message)
            if (state.canCancel) {
                TextButton(
                    onClick = onCancel,
                    shape = EditorialShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("취소")
                }
            }
        }
    }
}

@Composable
private fun LoginContent(
    message: String?,
    onLogin: () -> Unit,
    onClearDeviceData: () -> Unit,
) {
    SettingsGroup(title = "로그인 상태") {
        Text(
            text = "Google 계정으로 로그인",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("서비스를 이용하려면 Google 로그인이 필요해요.")
        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onLogin,
            shape = EditorialShape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("Google로 로그인")
        }
    }
    SettingsGroup(title = "이 기기의 자료") {
        Text(
            text = "계정을 바꾸면 이전 입력·대기 요청·기기 캐시가 삭제돼요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClearDeviceData,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("기기 대기 자료 정리")
        }
    }
}

@Composable
private fun PendingApprovalContent(
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    SettingsGroup(title = "로그인 상태") {
        Text(
            text = "베타 이용 승인 대기 중이에요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("로그인은 완료됐지만 베타 이용 승인이 없거나 이용 권한이 회수됐어요.")
        Button(
            onClick = onRetry,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("승인 상태 다시 확인")
        }
    }
    SettingsGroup(title = "이 기기의 자료") {
        Text(
            "로그아웃하면 서버 계정과 서버 자료는 유지되고, 이 기기의 계정 자료만 정리해요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onLogout,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("로그아웃")
        }
    }
    AccountDeletionGroup(onDeleteAccount)
}

@Composable
private fun ActiveContent(
    summary: AccountSummary,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    SettingsGroup(title = "로그인 상태") {
        Text(
            text = "로그인했어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Google 계정으로 보관함을 사용하고 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsGroup(title = "사용량") {
        val limits = summary.limits
        val usage = summary.usage
        if (limits != null && usage != null) {
            Text("사용 중인 항목 ${usage.activeItemCount}개 / ${limits.items}개")
        } else {
            Text(
                "사용량 정보를 확인할 수 없어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SettingsGroup(title = "이 기기의 자료") {
        Text(
            "로그아웃하면 서버 계정과 서버 자료는 유지되고, 이 기기의 계정 자료만 정리해요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onLogout,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("로그아웃")
        }
    }
    AccountDeletionGroup(onDeleteAccount)
}

@Composable
private fun ErrorContent(
    state: AccountUiState.Error,
    onRetry: () -> Unit,
    onClearDeviceData: () -> Unit,
    hasSession: Boolean,
) {
    SettingsGroup(title = "계정 상태") {
        Text(
            text = "계정 요청에 실패했어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.message,
            color = MaterialTheme.colorScheme.error,
        )
        if (state.canRetry) {
            Button(
                onClick = onRetry,
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("다시 시도")
            }
        }
    }
    SettingsGroup(title = "이 기기의 자료") {
        Text(
            text = if (hasSession) {
                "로그아웃을 계속하면 서버 자료는 유지되고 이 기기의 계정 자료만 정리해요."
            } else {
                "로그인하지 않은 상태로 남은 입력·대기 요청·기기 캐시를 정리할 수 있어요."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClearDeviceData,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(if (hasSession) "로그아웃" else "기기 대기 자료 정리")
        }
    }
}

@Composable
private fun UnconfiguredContent(message: String) {
    SettingsGroup(title = "로그인 상태") {
        Text(
            text = "로그인 설정이 필요해요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(message, color = MaterialTheme.colorScheme.error)
        Text("앱 빌드에 Supabase와 Google OAuth 설정을 추가해야 로그인할 수 있어요.")
    }
}

@Composable
private fun ConfirmLogoutContent(
    state: AccountUiState.ConfirmLogout,
    onConfirm: () -> Unit,
    onRetryCheck: () -> Unit,
    onCancel: () -> Unit,
) {
    SettingsGroup(title = "이 기기의 자료") {
        Text(
            text = if (state.expectedSessionOwner == null) {
                "기기 대기 자료를 정리할까요?"
            } else {
                "로그아웃할까요?"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.canConfirm) {
            Text(
                "서버 저장을 마치지 않은 요청 ${state.pendingCount}개를 포함해 기기에 남은 자료가 삭제됩니다.",
            )
        } else {
            Text("대기 중인 저장 요청 수를 확인해야 로그아웃할 수 있어요.")
        }
        Text(
            "임시 공유 입력, 동기화 캐시, 수정 충돌 자료, 아직 서버에 저장되지 않은 요청은 삭제 후 복구할 수 없어요.",
            color = MaterialTheme.colorScheme.error,
        )
        if (state.expectedSessionOwner != null) {
            Text("로그아웃은 서버 계정과 서버에 보관된 자료를 삭제하지 않아요.")
        }
        state.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        if (state.canConfirm) {
            Button(
                onClick = onConfirm,
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    if (state.expectedSessionOwner == null) {
                        "기기 대기 자료 정리"
                    } else {
                        "로그아웃 및 기기 자료 삭제"
                    },
                )
            }
        } else {
            Button(
                onClick = onRetryCheck,
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("대기 요청 다시 확인")
            }
        }
        OutlinedButton(
            onClick = onCancel,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("취소")
        }
    }
}

@Composable
private fun ConfirmAccountDeletionContent(
    state: AccountUiState.ConfirmAccountDeletion,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DangerSettingsGroup(title = "계정 삭제") {
        Text(
            text = "계정과 서버 자료를 삭제할까요?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "로그아웃과 달리 계정 삭제를 접수하면 서버의 보관 항목, 첨부, 계정은 복구할 수 없어요.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "Google 계정을 다시 선택해 현재 계정 본인인지 확인한 뒤에만 삭제를 접수해요.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "운영 자료 정리는 72시간 이내를 목표로 하지만 완료 시간을 보장하지 않아요.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "운영 백업은 최대 30일 순환을 목표로 하며, 복원 시 삭제 요청을 다시 적용해요.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        state.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (state.canConfirm) {
            Button(
                onClick = onConfirm,
                shape = EditorialShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Google 재인증 후 되돌릴 수 없는 삭제")
            }
        }
        OutlinedButton(
            onClick = onCancel,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("계정 유지")
        }
    }
}

@Composable
private fun DeletionStatusUnknownContent(
    state: AccountUiState.DeletionStatusUnknown,
    onCheck: () -> Unit,
) {
    DangerSettingsGroup(title = "계정 삭제") {
        Text(
            text = "삭제 접수 결과를 확인해야 해요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            state.message,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "확인 전에는 새 삭제 요청을 보내지 않아요.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(
            onClick = onCheck,
            shape = EditorialShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("계정 상태 확인")
        }
    }
}

@Composable
private fun DeletionAcceptedContent(
    state: AccountUiState.DeletionAccepted,
    onRetryLocalCleanup: () -> Unit,
) {
    SettingsGroup(title = "계정 삭제") {
        Text(
            text = "계정 삭제가 접수됐어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("이 계정은 더 이상 보관함을 이용할 수 없어요.")
        Text("운영 자료 정리는 72시간 이내를 목표로 하지만 완료 시간을 보장하지 않아요.")
        if (!state.localDataCleared) {
            Text(
                "LOCAL_DATA_CLEAR_FAILED: 서버 삭제는 접수됐지만 이 기기의 대기 자료를 지우지 못했어요.",
                color = MaterialTheme.colorScheme.error,
            )
            state.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onRetryLocalCleanup,
                shape = EditorialShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("기기 자료 정리 다시 시도")
            }
        } else {
            Text("이 기기의 계정 자료와 로그인 정보도 정리했어요.")
            state.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AccountDeletionGroup(onDeleteAccount: () -> Unit) {
    DangerSettingsGroup(title = "계정 삭제") {
        Text(
            "계정과 서버에 보관된 모든 자료를 되돌릴 수 없게 삭제해요.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton(
            onClick = onDeleteAccount,
            shape = EditorialShape,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("계정과 서버 자료 삭제")
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    content()
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DangerSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = EditorialShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            ProvideTextStyle(
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                content()
            }
        }
    }
}

private val EditorialShape = RoundedCornerShape(8.dp)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
