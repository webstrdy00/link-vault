package com.linkvault.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.linkvault.app.auth.AccountScreen
import com.linkvault.app.attachment.IncomingImageCaptureException
import com.linkvault.app.attachment.IncomingImageCaptureFailure
import com.linkvault.app.capture.CaptureInput
import com.linkvault.app.capture.InvalidUrlReason
import com.linkvault.app.discovery.DiscoveryScreen
import com.linkvault.app.library.LibraryScreen
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var capture by mutableStateOf(IncomingCapture())
    private var nextSequence by mutableIntStateOf(0)
    private var imageCaptureError by mutableStateOf<String?>(null)
    private var imageCaptureJob: Job? = null
    private var imageCaptureRevision = 0L
    private var draftTouched = false
    private var recoveryError by mutableStateOf<String?>(null)
    private var handledLogoutGeneration = 0L
    private val app get() = application as LinkVaultApplication
    private val privateSessionStoreViewModel: PrivateSessionStoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val currentLogout = app.logoutGeneration.value
        val staleState = savedInstanceState != null &&
            savedInstanceState.getLong("capture_logout_generation", 0L) != currentLogout
        super.onCreate(if (staleState) null else savedInstanceState)
        handledLogoutGeneration = currentLogout
        if (staleState) setIntent(Intent(this, MainActivity::class.java))
        capture = readIncomingCapture(intent, sequence = 0)
        val isShare = intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (fromHistory && savedInstanceState == null) capture = IncomingCapture()
        if (isShare && !staleState && savedInstanceState == null && !fromHistory) {
            draftTouched = true
            app.persistCapture(capture.text, null)
            receiveIncomingImages(capture)
        } else if (savedInstanceState == null || staleState) {
            lifecycleScope.launch {
                try {
                    val pending = app.outboxRepository.readDraft(LinkVaultApplication.CAPTURE_DRAFT_ID)
                    if (!draftTouched && capture.sequence == 0 && pending != null) {
                        nextSequence += 1
                        capture = IncomingCapture(
                            text = pending.text,
                            selectedUrl = pending.selectedUrl,
                            sequence = nextSequence,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    recoveryError = "기기에 보관한 입력을 불러오지 못했어요."
                }
            }
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("capture_logout_generation", handledLogoutGeneration)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        draftTouched = true
        nextSequence += 1
        capture = readIncomingCapture(intent, sequence = nextSequence)
        app.persistCapture(capture.text, null)
        receiveIncomingImages(capture)
    }

    @Composable
    private fun CaptureScreen(
        capture: IncomingCapture,
        onOpenOriginal: (String) -> String?,
    ) {
        var showAccount by rememberSaveable { mutableStateOf(false) }
        var showLibrary by rememberSaveable { mutableStateOf(false) }
        var showDiscovery by rememberSaveable { mutableStateOf(false) }
        var discoveryItemId by rememberSaveable { mutableStateOf<String?>(null) }
        var discoveryEntryId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
        var libraryEntryId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
        var libraryUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var librarySharedText by rememberSaveable { mutableStateOf("") }
        var input by rememberSaveable { mutableStateOf(capture.text) }
        var selectedUrl by rememberSaveable { mutableStateOf(capture.selectedUrl) }
        var inputLimitExceeded by rememberSaveable {
            mutableStateOf(capture.text.length > CaptureInput.MAX_INPUT_LENGTH)
        }
        var selectedSharedImageIndex by rememberSaveable {
            mutableStateOf(if (capture.imageUris.size == 1) 0 else null)
        }
        var openError by rememberSaveable { mutableStateOf<String?>(null) }
        val logoutGeneration by app.logoutGeneration.collectAsState()
        val draftError by app.draftError.collectAsState()
        val incomingImageDraft by app.incomingImageStore.draft.collectAsState()
        val accountSessionState by app.accountClient.sessionState.collectAsState()
        val privateSessionIdentity = PrivateSessionIdentity(
            generation = accountSessionState.generation,
            ownerId = accountSessionState.ownerId,
        )
        val privateViewModelStoreOwner =
            privateSessionStoreViewModel.storeOwnerFor(privateSessionIdentity)
        var routedSessionIdentity by remember {
            mutableStateOf<PrivateSessionIdentity?>(null)
        }

        fun closePrivateRoutes() {
            showLibrary = false
            showDiscovery = false
            discoveryItemId = null
            libraryUrl = null
            librarySharedText = ""
        }

        LaunchedEffect(privateSessionIdentity, accountSessionState.initialized) {
            if (!accountSessionState.initialized && privateSessionIdentity.ownerId == null) {
                return@LaunchedEffect
            }
            val previousIdentity = routedSessionIdentity
            val boundaryChanged = if (previousIdentity == null) {
                accountSessionState.initialized && privateSessionIdentity.ownerId == null
            } else {
                previousIdentity.invalidatesPrivateContent(privateSessionIdentity)
            }
            routedSessionIdentity = privateSessionIdentity
            if (boundaryChanged) closePrivateRoutes()
        }

        LaunchedEffect(logoutGeneration) {
            if (logoutGeneration != handledLogoutGeneration) {
                input = ""
                selectedUrl = null
                closePrivateRoutes()
                inputLimitExceeded = false
                openError = null
                imageCaptureError = null
                imageCaptureRevision += 1
                imageCaptureJob?.cancel()
                imageCaptureJob = null
                selectedSharedImageIndex = null
                draftTouched = true
                setIntent(Intent(this@MainActivity, MainActivity::class.java))
                handledLogoutGeneration = logoutGeneration
            }
        }

        LaunchedEffect(capture.sequence) {
            if (capture.sequence > 0) {
                showAccount = false
                closePrivateRoutes()
                input = capture.text
                selectedUrl = capture.selectedUrl
                inputLimitExceeded = capture.text.length > CaptureInput.MAX_INPUT_LENGTH
                openError = null
                selectedSharedImageIndex = if (capture.imageUris.size == 1) 0 else null
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
                if (capture.imageUris.isEmpty()) {
                    Text(
                        text = "이미지 공유 요청을 받았습니다. 사용할 수 있는 콘텐츠 URI가 없습니다.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "이미지 ${capture.imageUris.size}개를 받았습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (capture.imageUris.size > 1) {
                    Text(
                        text = "가져올 이미지 하나를 선택하세요. 선택하기 전에는 이미지를 복사하지 않습니다.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    capture.imageUris.indices.forEach { index ->
                        val selected = selectedSharedImageIndex == index
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSharedImageIndex = index
                                    imageCaptureError = null
                                },
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
                                RadioButton(selected = selected, onClick = null)
                                Text("이미지 ${index + 1}", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    Button(
                        onClick = {
                            selectedSharedImageIndex?.let { selected ->
                                capture.imageUris.getOrNull(selected)?.let(::copyIncomingImage)
                            }
                        },
                        enabled = selectedSharedImageIndex != null,
                    ) {
                        Text("선택한 이미지 가져오기")
                    }
                }
            }

            imageCaptureError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            if (incomingImageDraft != null) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "공유 이미지를 임시로 가져왔습니다.",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("원본은 변경하지 않았습니다. 이 임시 복사본은 24시간 동안 보관됩니다.")
                        Button(onClick = {
                            libraryEntryId = UUID.randomUUID().toString()
                            libraryUrl = targetUrl
                            librarySharedText = input
                            showLibrary = true
                        }) {
                            Text("공유 이미지를 첨부할 항목 선택")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    draftTouched = true
                    input = newValue
                    selectedUrl = null
                    inputLimitExceeded = newValue.length > CaptureInput.MAX_INPUT_LENGTH
                    openError = null
                    app.persistCapture(newValue, null)
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

            (draftError ?: recoveryError)?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = {
                draftTouched = true
                input = ""
                selectedUrl = null
                inputLimitExceeded = false
                openError = null
                app.persistCapture("", null)
            }) { Text("입력 지우기") }

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
                                    draftTouched = true
                                    app.persistCapture(input, url)
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
        val openFromLibrary: (String) -> Unit = { url ->
            openError = onOpenOriginal(url)
            openError?.let { message ->
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
        val consumeIncomingImage: () -> Unit = {
            incomingImageDraft?.id?.let { draftId ->
                lifecycleScope.launch {
                    app.incomingImageStore.consume(draftId)
                }
            }
        }
        val privateRoutesReady =
            !app.accountClient.isConfigured ||
                accountSessionState.initialized || accountSessionState.ownerId != null
        if (!privateRoutesReady && (showLibrary || showDiscovery) && !showAccount) {
            Dialog(onDismissRequest = { closePrivateRoutes() }) {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("로그인 상태를 확인하고 있어요.")
                        Button(onClick = { closePrivateRoutes() }) { Text("뒤로") }
                    }
                }
            }
        }
        if (privateRoutesReady && showLibrary && !showAccount && !showDiscovery) {
            Dialog(
                onDismissRequest = { showLibrary = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides privateViewModelStoreOwner,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        LibraryScreen(
                            client = app.accountClient,
                            outbox = app.outboxRepository,
                            attachments = app.attachmentRepository,
                            entryId = libraryEntryId,
                            initialUrl = libraryUrl,
                            sharedText = librarySharedText,
                            incomingImageUri = incomingImageDraft?.uri,
                            onIncomingImageConsumed = consumeIncomingImage,
                            onBack = { showLibrary = false },
                            onSignIn = { showAccount = true },
                            onDiscover = { showDiscovery = true },
                            onOpenOriginal = openFromLibrary,
                        )
                    }
                }
            }
        }
        if (privateRoutesReady && showDiscovery && !showAccount) {
            val closeDiscovery: () -> Unit = {
                if (discoveryItemId != null) discoveryItemId = null else showDiscovery = false
            }
            Dialog(
                onDismissRequest = closeDiscovery,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides privateViewModelStoreOwner,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val itemId = discoveryItemId
                        if (itemId == null) {
                            DiscoveryScreen(
                                client = app.accountClient,
                                outbox = app.outboxRepository,
                                onBack = { showDiscovery = false },
                                onOpenItem = { selectedItemId ->
                                    discoveryEntryId = UUID.randomUUID().toString()
                                    discoveryItemId = selectedItemId
                                },
                            )
                        } else {
                            LibraryScreen(
                                client = app.accountClient,
                                outbox = app.outboxRepository,
                                attachments = app.attachmentRepository,
                                entryId = discoveryEntryId,
                                initialUrl = null,
                                sharedText = "",
                                initialItemId = itemId,
                                incomingImageUri = incomingImageDraft?.uri,
                                onIncomingImageConsumed = consumeIncomingImage,
                                onBack = closeDiscovery,
                                onSignIn = { showAccount = true },
                                onDiscover = { discoveryItemId = null },
                                onOpenOriginal = openFromLibrary,
                            )
                        }
                    }
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
                        outbox = app.outboxRepository,
                        onBack = { showAccount = false },
                    )
                }
            }
        }
    }

    private fun receiveIncomingImages(incoming: IncomingCapture) {
        imageCaptureRevision += 1
        imageCaptureJob?.cancel()
        imageCaptureJob = null
        imageCaptureError = null
        if (incoming.isImageShare && incoming.imageUris.size == 1) {
            copyIncomingImage(incoming.imageUris.single())
        }
    }

    private fun copyIncomingImage(uri: Uri) {
        imageCaptureRevision += 1
        val revision = imageCaptureRevision
        imageCaptureJob?.cancel()
        imageCaptureError = null
        val expectedGeneration = app.incomingImageStore.generation()
        imageCaptureJob = lifecycleScope.launch {
            try {
                app.incomingImageStore.capture(uri, expectedGeneration)
                if (revision == imageCaptureRevision) imageCaptureError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: IncomingImageCaptureException) {
                if (revision == imageCaptureRevision &&
                    error.reason != IncomingImageCaptureFailure.STALE_CAPTURE
                ) {
                    imageCaptureError = incomingImageErrorMessage(error.reason)
                }
            } catch (_: Exception) {
                if (revision == imageCaptureRevision) {
                    imageCaptureError = "공유 이미지를 앱의 임시 보관소에 저장하지 못했어요."
                }
            }
        }
    }

    private fun incomingImageErrorMessage(reason: IncomingImageCaptureFailure): String =
        when (reason) {
            IncomingImageCaptureFailure.INVALID_URI ->
                "공유 이미지 주소를 사용할 수 없어요. content URI로 다시 공유해 주세요."
            IncomingImageCaptureFailure.UNSUPPORTED_MIME_TYPE ->
                "PNG, JPEG 또는 WebP 이미지만 가져올 수 있어요."
            IncomingImageCaptureFailure.SOURCE_UNAVAILABLE ->
                "공유 이미지를 읽을 수 없어요. 원본 앱에서 다시 공유해 주세요."
            IncomingImageCaptureFailure.SOURCE_TOO_LARGE ->
                "공유 이미지는 10MB 이하만 가져올 수 있어요."
            IncomingImageCaptureFailure.STORAGE_UNAVAILABLE ->
                "공유 이미지를 앱의 임시 보관소에 저장하지 못했어요."
            IncomingImageCaptureFailure.STALE_CAPTURE -> ""
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
        val isTextShare =
            action == Intent.ACTION_SEND && mimeType.equals("text/plain", ignoreCase = true)
        val isImageShare =
            (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) &&
                mimeType.startsWith("image/", ignoreCase = true)
        if (!isTextShare && !isImageShare) {
            return IncomingCapture(sequence = sequence)
        }

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        return IncomingCapture(
            text = sharedText,
            imageUris = if (isImageShare) readSharedImageUris(intent) else emptyList(),
            isImageShare = isImageShare,
            sequence = sequence,
        )
    }

    @Suppress("DEPRECATION")
    private fun readSharedImageUris(intent: Intent): List<Uri> {
        val uris = buildList {
            intent.clipData?.let { clipData ->
                repeat(clipData.itemCount) { index ->
                    clipData.getItemAt(index).uri?.let { uri -> add(uri) }
                }
            }
            when (intent.action) {
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                        addAll(uris)
                    }
                Intent.ACTION_SEND ->
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri -> add(uri) }
                else -> Unit
            }
        }
        return uris.distinctBy { uri -> uri.toString() }
    }
}

private data class IncomingCapture(
    val text: String = "",
    val selectedUrl: String? = null,
    val imageUris: List<Uri> = emptyList(),
    val isImageShare: Boolean = false,
    val sequence: Int = 0,
)

internal data class PrivateSessionIdentity(
    val generation: Long,
    val ownerId: String?,
) {
    fun invalidatesPrivateContent(next: PrivateSessionIdentity): Boolean =
        ownerId != null && (ownerId != next.ownerId || generation != next.generation)
}
