package com.linkvault.app.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AccountScreen(
    client: AccountClient,
    onBack: () -> Unit,
) {
    val factory = remember(client) { AccountViewModel.factory(client) }
    val accountViewModel: AccountViewModel = viewModel(factory = factory)
    val state by accountViewModel.uiState.collectAsState()
    val activity = LocalContext.current.findActivity()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("뒤로")
            }
            Text(
                text = "계정",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
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
            )

            AccountUiState.PendingApproval -> PendingApprovalContent(
                onRetry = {
                    if (activity == null) {
                        accountViewModel.reportLoginUnavailable()
                    } else {
                        accountViewModel.retry(activity)
                    }
                },
                onLogout = accountViewModel::logout,
            )

            is AccountUiState.Active -> ActiveContent(
                summary = currentState.summary,
                onLogout = accountViewModel::logout,
            )

            AccountUiState.Deleting -> DeletingContent(
                onLogout = accountViewModel::logout,
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
            )

            is AccountUiState.Unconfigured -> UnconfiguredContent(currentState.message)
        }
    }
}

@Composable
private fun LoadingContent(
    state: AccountUiState.Loading,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(state.message)
        if (state.canCancel) {
            TextButton(onClick = onCancel) {
                Text("취소")
            }
        }
    }
}

@Composable
private fun LoginContent(
    message: String?,
    onLogin: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Google 계정으로 로그인",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("서비스를 이용하려면 Google 로그인이 필요해요.")
        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Google로 로그인")
        }
    }
}

@Composable
private fun PendingApprovalContent(
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "베타 이용 승인 대기 중이에요",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("로그인은 완료됐지만 아직 베타 이용 승인이 필요해요.")
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("승인 상태 다시 확인")
        }
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("로그아웃")
        }
    }
}

@Composable
private fun ActiveContent(
    summary: AccountSummary,
    onLogout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "로그인했어요",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        val limits = summary.limits
        val usage = summary.usage
        if (limits != null && usage != null) {
            Text("사용 중인 항목 ${usage.activeItemCount}개 / ${limits.items}개")
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("로그아웃")
        }
    }
}

@Composable
private fun DeletingContent(onLogout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "계정 삭제를 처리 중이에요",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("삭제가 끝날 때까지 이 계정은 이용할 수 없어요.")
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("로그아웃")
        }
    }
}

@Composable
private fun ErrorContent(
    state: AccountUiState.Error,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "계정 요청에 실패했어요",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = state.message,
            color = MaterialTheme.colorScheme.error,
        )
        if (state.canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("다시 시도")
            }
        }
    }
}

@Composable
private fun UnconfiguredContent(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "로그인 설정이 필요해요",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(message, color = MaterialTheme.colorScheme.error)
        Text("앱 빌드에 Supabase와 Google OAuth 설정을 추가해야 로그인할 수 있어요.")
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
