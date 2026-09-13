package com.linkvault.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linkvault.app.auth.AccountScreen
import com.linkvault.app.library.LibraryScreen
import com.linkvault.app.capture.CaptureInput
import com.linkvault.app.capture.InvalidUrlReason
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var capture by mutableStateOf(IncomingCapture())
    private var nextSequence by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capture = readIncomingCapture(intent, sequence = 0)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen(
                        capture = capture,
                        onOpenOriginal = ::openOriginal,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nextSequence += 1
        capture = readIncomingCapture(intent, sequence = nextSequence)
    }

    @Composable
    private fun CaptureScreen(
        capture: IncomingCapture,
        onOpenOriginal: (String) -> String?,
    ) {
        var showAccount by rememberSaveable { mutableStateOf(false) }
        var showLibrary by rememberSaveable { mutableStateOf(false) }
        var libraryEntryId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
        var libraryUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var librarySharedText by rememberSaveable { mutableStateOf("") }
        var input by rememberSaveable { mutableStateOf(capture.text) }
        var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var inputLimitExceeded by rememberSaveable {
            mutableStateOf(capture.text.length > CaptureInput.MAX_INPUT_LENGTH)
        }
        var openError by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(capture.sequence) {
            if (capture.sequence > 0) {
                showAccount = false
                showLibrary = false
                input = capture.text
                selectedUrl = null
                inputLimitExceeded = capture.text.length > CaptureInput.MAX_INPUT_LENGTH
                openError = null
            }
        }

        val parsed = remember(input) { CaptureInput.parse(input) }
        val savedSelection = selectedUrl
        val targetUrl = when {
            parsed.urls.size == 1 -> parsed.urls.single()
            savedSelection != null && savedSelection in parsed.urls -> savedSelection
            else -> null
        }
        val validationMessage = when {
            inputLimitExceeded || parsed.inputTooLong ->
                "입력은 최대 ${CaptureInput.MAX_INPUT_LENGTH}자까지 확인할 수 있습니다. 내용을 줄여 주세요."
            parsed.rejected.any { it.reason == InvalidUrlReason.TOO_LONG } ->
                "URL은 최대 ${CaptureInput.MAX_URL_LENGTH}자까지 사용할 수 있습니다. 주소를 줄이거나 다른 원문 URL을 입력하세요."
            input.isBlank() -> "HTTP 또는 HTTPS 원문 URL이 포함된 텍스트를 입력하거나 붙여 넣으세요."
            parsed.urls.isEmpty() && parsed.rejected.isNotEmpty() ->
                "웹 주소 형식이 올바르지 않습니다. 사용자 정보가 없는 HTTP/HTTPS URL인지 확인하세요."
            parsed.urls.isEmpty() ->
                "HTTP 또는 HTTPS 원문 URL을 찾지 못했습니다. 원문 주소를 직접 입력해 주세요."
            parsed.rejected.isNotEmpty() ->
                "유효하지 않은 주소는 제외했습니다. 아래 후보를 확인하세요."
            else -> null
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "링크 입력 확인",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = { showAccount = true }) {
                Text("회원 계정")
            }
            Button(onClick = {
                libraryEntryId = UUID.randomUUID().toString()
                libraryUrl = null
                librarySharedText = ""
                showLibrary = true
            }) {
                Text("보관함")
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "링크를 확인한 뒤 선택한 링크 보관에서 로그인하고 저장을 확인하세요. 원문 열기는 저장하지 않고 원문으로 이동합니다.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (capture.isImageShare) {
                val imageDescription = if (capture.imageCount > 0) {
                    "이미지 ${capture.imageCount}개"
                } else {
                    "이미지 공유 요청"
                }
                Text(
                    text = "${imageDescription}을 받았습니다. 이미지는 복사하거나 단독 보관하지 않습니다. 원문 URL을 아래에 입력해 주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    input = newValue
                    selectedUrl = null
                    inputLimitExceeded = newValue.length > CaptureInput.MAX_INPUT_LENGTH
                    openError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("공유 텍스트 또는 원문 URL") },
                minLines = 3,
                maxLines = 8,
                isError = inputLimitExceeded || parsed.inputTooLong ||
                    (input.isNotBlank() && parsed.urls.isEmpty()),
                supportingText = validationMessage?.let { message ->
                    { Text(message) }
                },
            )

            if (parsed.urls.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "URL 후보 ${parsed.urls.size}개",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (parsed.urls.size > 1 && targetUrl == null) {
                    Text(
                        text = "원문으로 열 URL 하나를 선택하세요.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                parsed.urls.forEach { url ->
                    val selected = targetUrl == url
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = parsed.urls.size > 1,
                                onClick = {
                                    selectedUrl = url
                                    openError = null
                                },
                            ),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (parsed.urls.size > 1) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                )
                            }
                            Text(
                                text = url,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = if (parsed.urls.size > 1) 8.dp else 0.dp),
                            )
                        }
                    }
                }
            }

            openError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    libraryEntryId = UUID.randomUUID().toString()
                    libraryUrl = targetUrl
                    librarySharedText = input
                    showLibrary = true
                },
                enabled = targetUrl != null && !inputLimitExceeded && !parsed.inputTooLong,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("선택한 링크 보관")
            }
            Button(
                onClick = {
                    targetUrl?.let { url ->
                        openError = onOpenOriginal(url)
                    }
                },
                enabled = targetUrl != null && !inputLimitExceeded && !parsed.inputTooLong,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("원문 열기")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (showLibrary && !showAccount) {
            Dialog(
                onDismissRequest = { showLibrary = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LibraryScreen(
                        client = (application as LinkVaultApplication).accountClient,
                        entryId = libraryEntryId,
                        initialUrl = libraryUrl,
                        sharedText = librarySharedText,
                        onBack = { showLibrary = false },
                        onSignIn = { showAccount = true },
                        onOpenOriginal = { url ->
                            openError = openOriginal(url)
                            openError?.let { message ->
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
        if (showAccount) {
            Dialog(
                onDismissRequest = { showAccount = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    AccountScreen(
                        client = (application as LinkVaultApplication).accountClient,
                        onBack = { showAccount = false },
                    )
                }
            }
        }
    }

    private fun openOriginal(url: String): String? = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        null
    } catch (_: ActivityNotFoundException) {
        "원문 URL을 열 수 있는 앱이 없습니다."
    } catch (_: SecurityException) {
        "보안 설정으로 원문 URL을 열 수 없습니다."
    }

    private fun readIncomingCapture(intent: Intent?, sequence: Int): IncomingCapture {
        if (intent == null) return IncomingCapture(sequence = sequence)

        val action = intent.action
        val mimeType = intent.type.orEmpty()
        val isTextShare = action == Intent.ACTION_SEND && mimeType.equals("text/plain", ignoreCase = true)
        val isImageShare =
            (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) &&
                mimeType.startsWith("image/", ignoreCase = true)
        if (!isTextShare && !isImageShare) {
            return IncomingCapture(sequence = sequence)
        }

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        return IncomingCapture(
            text = sharedText,
            imageCount = if (isImageShare) countSharedImages(intent) else 0,
            isImageShare = isImageShare,
            sequence = sequence,
        )
    }

    @Suppress("DEPRECATION")
    private fun countSharedImages(intent: Intent): Int {
        val clipDataCount = intent.clipData?.itemCount ?: 0
        val extraCount = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.size ?: 0
            Intent.ACTION_SEND ->
                if (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) != null) 1 else 0
            else -> 0
        }
        return maxOf(clipDataCount, extraCount)
    }
}

private data class IncomingCapture(
    val text: String = "",
    val imageCount: Int = 0,
    val isImageShare: Boolean = false,
    val sequence: Int = 0,
)
