package com.linkvault.app.privacy

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linkvault.app.R

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().testTag("privacy-screen")) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("privacy-back"),
            ) {
                Text(stringResource(R.string.privacy_back))
            }
            Text(
                text = stringResource(R.string.privacy_title),
                modifier = Modifier.semantics { heading() }.testTag("privacy-title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrivacySection(R.string.privacy_operator_title) {
                PrivacyText(R.string.privacy_operator)
            }
            PrivacySection(R.string.privacy_data_title) {
                PrivacyText(R.string.privacy_data)
                PrivacyText(R.string.privacy_data_warning)
            }
            PrivacySection(R.string.privacy_providers_title) {
                PrivacyText(R.string.privacy_providers)
            }
            PrivacySection(R.string.privacy_retention_title) {
                PrivacyText(R.string.privacy_deletion)
                PrivacyText(R.string.privacy_retention_targets)
                PrivacyText(R.string.privacy_retention_records)
                PrivacyText(R.string.privacy_retention_backups)
            }
            PrivacySection(R.string.privacy_requests_title) {
                PrivacyText(R.string.privacy_requests)
            }
            PrivacySection(R.string.privacy_unresolved_title) {
                PrivacyText(R.string.privacy_unresolved)
            }
            HorizontalDivider()
            TextButton(
                onClick = { onOpenExternalUrl("https://linkfilebox.com/privacy/") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("privacy-web-link"),
            ) {
                Text(stringResource(R.string.privacy_web_link))
            }
            TextButton(
                onClick = { onOpenExternalUrl("https://linkfilebox.com/account-deletion/") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("privacy-deletion-link"),
            ) {
                Text(stringResource(R.string.privacy_deletion_link))
            }
        }
    }
}

@Composable
private fun PrivacySection(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun PrivacyText(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
    )
}
