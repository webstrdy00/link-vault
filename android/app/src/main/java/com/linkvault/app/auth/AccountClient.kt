package com.linkvault.app.auth

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.seconds

class AccountClient(
    context: Context,
    url: String,
    key: String,
    googleWebClientId: String,
    debug: Boolean,
    private val beforeSignOut: suspend () -> Unit,
    private val localDataOwner: () -> String?,
    private val onSessionOwner: suspend (ownerId: String) -> Unit,
) {
    private val configuration = validateAccountConfig(url, key, googleWebClientId, debug)
    private val credentialManager = CredentialManager.create(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionBoundary = SessionBoundary()
    private val acceptedDeletionCleanup = AcceptedDeletionCleanupTracker()
    private val acceptedDeletionReceipts = AcceptedDeletionReceiptStore()

    private val configured: ValidatedAccountConfig?
        get() = (configuration as? AccountConfigValidation.Configured)?.config

    private val supabase: SupabaseClient? = configured?.let { config ->
        createSupabaseClient(
            supabaseUrl = config.url,
            supabaseKey = config.key,
        ) {
            defaultLogLevel = LogLevel.NONE
            requestTimeout = AUTH_REQUEST_TIMEOUT_SECONDS.seconds
            install(Auth) {
                alwaysAutoRefresh = false
                autoLoadFromStorage = false
                // All session writers share SessionBoundary, including refresh.
                enableLifecycleCallbacks = false
            }
        }
    }

    private val httpClient: HttpClient? = configured?.let {
        HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = API_REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = API_CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = API_REQUEST_TIMEOUT_MILLIS
            }
        }
    }

    val isConfigured: Boolean
        get() = configuration is AccountConfigValidation.Configured

    val configurationMessage: String?
        get() = (configuration as? AccountConfigValidation.Unconfigured)?.issue?.message

    val sessionState: StateFlow<AccountSessionState> = sessionBoundary.sessionState

    fun hasSession(): Boolean = sessionBoundary.hasSession()

    fun sessionUserId(): String? = sessionBoundary.ownerId()

    suspend fun <T> withSessionOwner(expectedOwnerId: String, block: suspend () -> T): T =
        sessionBoundary.locked {
            val origin = currentOrigin() ?: throw AccountAuthenticationRequiredException()
            if (origin.ownerId != expectedOwnerId) throw sessionChanged()
            block()
        }

    suspend fun restoreAccount(): AccountAccess? = try {
        val auth = requireSupabase().auth
        val restoredOrigin = sessionBoundary.locked {
            when (val status = auth.sessionStatus.value) {
                is SessionStatus.Authenticated -> {
                    if (status.session.expiresAt <= kotlinx.datetime.Clock.System.now()) {
                        if (!restoreStoredSession(auth)) return@locked null
                        return@locked currentOrigin()
                    }
                    val ownerId = status.session.user?.id
                        ?: throw invalidSdkSession()
                    prepareLocalOwner(ownerId)
                    val origin = currentOrigin() ?: commitIdentity(ownerId)
                    if (origin.ownerId != ownerId) {
                        auth.clearSession()
                        clear(origin)
                        throw AccountAuthenticationRequiredException()
                    }
                    confirmAuthenticated(origin, ownerId)
                    origin
                }
                is SessionStatus.RefreshFailure -> {
                    if (restoreStoredSession(auth)) currentOrigin() else null
                }
                is SessionStatus.NotAuthenticated,
                SessionStatus.Initializing,
                -> if (restoreStoredSession(auth)) currentOrigin() else null
            }
        }
        if (restoredOrigin == null) {
            acceptedDeletionAccessForCurrentPresentation()
        } else {
            try {
                getAccount(restoredOrigin.ownerId, restoredOrigin.generation)
            } catch (error: AccountClientException) {
                acceptedDeletionAccessAfterSessionRace(restoredOrigin, error) ?: throw error
            }
        }
    } catch (error: SessionBoundaryChangedException) {
        throw sessionChanged()
    } finally {
        sessionBoundary.locked { markInitialized() }
    }

    suspend fun signIn(activity: Activity): AccountAccess {
        val config = requireConfig()
        val auth = requireSupabase().auth
        val expectedGeneration = sessionBoundary.generation()
        val rawNonce = generateRawNonce()
        val hashedNonce = sha256Hex(rawNonce)
        val googleOption = GetSignInWithGoogleOption.Builder(config.googleWebClientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val credential = try {
            credentialManager.getCredential(
                context = MutableContextWrapper(activity),
                request = request,
            ).credential
        } catch (error: GetCredentialCancellationException) {
            throw AccountSignInCancelledException(error)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AccountClientException(
                message = "Google 로그인 화면을 열지 못했어요. 잠시 후 다시 시도해 주세요.",
                retryable = true,
                cause = error,
            )
        }

        if (
            credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AccountClientException(
                message = "Google에서 지원하지 않는 로그인 응답을 받았어요.",
                retryable = true,
            )
        }

        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: Exception) {
            throw AccountClientException(
                message = "Google 로그인 응답을 확인하지 못했어요. 다시 시도해 주세요.",
                retryable = true,
                cause = error,
            )
        }

        try {
            sessionBoundary.locked {
                requireGeneration(expectedGeneration)
                auth.signInWith(IDToken) {
                    provider = Google
                    this.idToken = idToken
                    nonce = rawNonce
                }
                val ownerId = auth.currentUserOrNull()?.id ?: run {
                    auth.clearSession()
                    clearCurrent()
                    throw invalidSdkSession()
                }
                prepareLocalOwner(ownerId)
                commitIdentity(ownerId)
            }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AccountClientException(
                message = "Google 로그인을 완료하지 못했어요. 네트워크와 로그인 설정을 확인해 주세요.",
                retryable = true,
                cause = error,
            )
        }

        return bootstrapAccount()
    }

    suspend fun bootstrapAccount(): AccountAccess {
        val requestId = UUID.randomUUID().toString()
        val response = authorizedRequest { accessToken ->
            val httpResponse = requireHttpClient().post(
                "${requireConfig().url}$FUNCTIONS_PATH/bootstrap",
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
                header(REQUEST_ID_HEADER, requestId)
                contentType(ContentType.Application.Json)
                setBody(EMPTY_JSON_OBJECT)
            }
            RawResponse(httpResponse.status, httpResponse.bodyAsText())
        }
        return acceptAccountAccessResponse(response, ::parseBootstrap)
    }

    suspend fun getAccount(
        expectedOwnerId: String? = null,
        expectedGeneration: Long? = null,
    ): AccountAccess {
        val requestId = UUID.randomUUID().toString()
        val response = authorizedRequest(expectedOwnerId, expectedGeneration) { accessToken ->
            val httpResponse = requireHttpClient().get(
                "${requireConfig().url}$FUNCTIONS_PATH/me",
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
                header(REQUEST_ID_HEADER, requestId)
            }
            RawResponse(httpResponse.status, httpResponse.bodyAsText())
        }
        return acceptAccountAccessResponse(response, ::parseMe)
    }

    suspend fun requestAccountDeletion(
        activity: Activity,
        expectedOwnerId: String,
        expectedGeneration: Long,
    ): AccountDeletionAcceptance {
        val challengeRequestId = UUID.randomUUID().toString()
        val challengeResponse = authorizedRequest(
            expectedOwnerId,
            expectedGeneration,
        ) { accessToken ->
            val httpResponse = requireHttpClient().post(
                "${requireConfig().url}$FUNCTIONS_PATH/account/delete-challenge",
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
                header(REQUEST_ID_HEADER, challengeRequestId)
                contentType(ContentType.Application.Json)
                setBody(EMPTY_JSON_OBJECT)
            }
            RawResponse(httpResponse.status, httpResponse.bodyAsText())
        }
        val origin = challengeResponse.origin
        val challenge = acceptResponse(challengeResponse, ::parseDeletionChallenge)
        requireUsableDeletionChallenge(origin, challenge.expiresAt)

        val googleIdToken = requestFreshDeletionProof(
            activity = activity,
            rawNonce = challenge.nonce,
        )
        requireUsableDeletionChallenge(origin, challenge.expiresAt)

        val requestId = UUID.randomUUID().toString()
        val frozenBody = json.encodeToString(
            AccountDeletionRequest(
                challengeId = challenge.challengeId,
                googleIdToken = googleIdToken,
            ),
        )
        var ambiguousFailure: AccountClientException? = null
        var deleteRequestMayHaveReachedServer = false
        repeat(ACCOUNT_DELETE_ATTEMPTS) { attempt ->
            try {
                val deleteResponse = authorizedRequest(
                    expectedOwnerId,
                    origin.generation,
                ) { accessToken ->
                    deleteRequestMayHaveReachedServer = true
                    val httpResponse = requireHttpClient().post(
                        "${requireConfig().url}$FUNCTIONS_PATH/account/delete",
                    ) {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header(API_KEY_HEADER, requireConfig().key)
                        header(REQUEST_ID_HEADER, requestId)
                        contentType(ContentType.Application.Json)
                        setBody(frozenBody)
                    }
                    RawResponse(
                        httpResponse.status,
                        httpResponse.bodyAsText(),
                        parseRetryAfter(httpResponse.headers[HttpHeaders.RetryAfter]),
                    )
                }
                acceptDeletionResponse(deleteResponse)
                return finishAcceptedDeletion(origin)
            } catch (error: AccountClientException) {
                if (
                    accountDeletionResultIsUnknown(
                        deleteRequestMayHaveReachedServer = deleteRequestMayHaveReachedServer,
                        error = error,
                    )
                ) {
                    throw AccountDeletionStatusUnknownException(
                        expectedOwnerId = origin.ownerId,
                        expectedGeneration = origin.generation,
                        cause = error,
                    )
                }
                if (!error.retryable) {
                    if (ambiguousFailure != null) {
                        when (recoverDeletionState(origin, expectedOwnerId)) {
                            true -> return finishAcceptedDeletion(origin)
                            false -> Unit
                            null -> throw AccountDeletionStatusUnknownException(
                                expectedOwnerId = origin.ownerId,
                                expectedGeneration = origin.generation,
                                cause = ambiguousFailure,
                            )
                        }
                    }
                    throw error
                }
                ambiguousFailure = error
                when (recoverDeletionState(origin, expectedOwnerId)) {
                    true -> return finishAcceptedDeletion(origin)
                    false -> Unit
                    null -> Unit
                }
                if (attempt + 1 < ACCOUNT_DELETE_ATTEMPTS) {
                    guardAmbiguousAccountDeletionRetry(
                        expectedOwnerId = origin.ownerId,
                        expectedGeneration = origin.generation,
                        ambiguousFailure = error,
                    ) {
                        requireUsableDeletionChallenge(origin, challenge.expiresAt)
                    }
                }
            }
        }
        throw AccountDeletionStatusUnknownException(
            expectedOwnerId = origin.ownerId,
            expectedGeneration = origin.generation,
            cause = ambiguousFailure,
        )
    }

    suspend fun recoverAccountDeletionState(
        expectedOwnerId: String,
        expectedGeneration: Long,
    ): AccountAccess {
        requireDeletionOrigin(expectedOwnerId, expectedGeneration)
        val access = getAccount(expectedOwnerId, expectedGeneration)
        if (access is AccountAccess.Deleting) return access
        requireDeletionOrigin(expectedOwnerId, expectedGeneration)
        return access
    }

    suspend fun currentAcceptedDeletionReceipt(
        expectedOwnerId: String,
        acceptanceGeneration: Long,
    ): AccountDeletionReceipt? = sessionBoundary.locked {
        acceptedDeletionReceipts.current(
            ownerId = expectedOwnerId,
            acceptanceGeneration = acceptanceGeneration,
            presentationGeneration = generation(),
        )
    }

    suspend fun clearAcceptedDeletionLocalData(
        expectedOwnerId: String,
    ): AccountDeletionAcceptance =
        finishAcceptedDeletion(expectedOwnerId = expectedOwnerId, expectedOrigin = null)

    suspend fun libraryRequest(
        expectedOwnerId: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        requestId: String? = null,
    ): JsonObject {
        val safePath = validateLibraryPath(path)
        val httpMethod = parseLibraryMethod(method)
        validateLibraryRouteMethod(safePath, httpMethod)
        if (safePath.endsWith("/content")) {
            throw AccountClientException(
                message = "이미지는 이미지 다운로드 요청으로 받아야 해요.",
                retryable = false,
                code = "BINARY_RESPONSE_REQUIRED",
            )
        }
        val safeRequestId = requestId?.let(::validateRequestId)
        if (httpMethod != HttpMethod.Get && safeRequestId == null) {
            throw AccountClientException(
                message = "변경 요청에는 UUID 요청 ID가 필요해요.",
                retryable = false,
                code = INVALID_REQUEST_ID,
            )
        }
        validateLibraryRequestBody(safePath, httpMethod, body)

        val response = authorizedRequest(expectedOwnerId) { accessToken ->
            val httpResponse = requireHttpClient().request(
                "${requireConfig().url}$FUNCTIONS_PATH$safePath",
            ) {
                this.method = httpMethod
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
                safeRequestId?.let { header(REQUEST_ID_HEADER, it) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            RawResponse(
                httpResponse.status,
                httpResponse.bodyAsText(),
                parseRetryAfter(httpResponse.headers[HttpHeaders.RetryAfter]),
            )
        }
        return acceptResponse(response) { rawResponse ->
            if (!rawResponse.status.isSuccess()) throw apiException(rawResponse)
            if (
                rawResponse.status == HttpStatusCode.NoContent &&
                httpMethod == HttpMethod.Delete && safePath.startsWith("/categories/")
            ) {
                return@acceptResponse JsonObject(emptyMap())
            }
            try {
                if (isItemDeleteRoute(safePath, httpMethod)) {
                    validateItemDeleteResponse(safePath, rawResponse.status, rawResponse.body)
                } else {
                    json.decodeFromString<JsonObject>(rawResponse.body)
                }
            } catch (error: Exception) {
                if (error is AccountClientException) throw error
                throw AccountClientException(
                    message = "서버 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
                    retryable = true,
                    cause = error,
                )
            }
        }
    }

    suspend fun uploadReservedAsset(
        expectedOwnerId: String,
        itemId: String,
        assetId: String,
        mimeType: String,
        bytes: ByteArray,
    ): Unit {
        val asset = validateAssetTransport(
            expectedOwnerId = expectedOwnerId,
            itemId = itemId,
            assetId = assetId,
        )
        val safeMimeType = validateAssetMimeType(mimeType)
        validateAssetByteCount(bytes.size)

        val response = authorizedRequest(asset.expectedOwnerId) { accessToken ->
            val httpResponse = requireHttpClient().post(
                "${requireConfig().url}/storage/v1/object/library-images/" +
                    "${asset.expectedOwnerId}/${asset.itemId}/${asset.assetId}",
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
                header(HttpHeaders.ContentType, safeMimeType)
                header(UPSERT_HEADER, "false")
                setBody(bytes)
            }
            if (httpResponse.status.isSuccess() || httpResponse.status == HttpStatusCode.Unauthorized) {
                httpResponse.bodyAsChannel().cancel(null)
                RawResponse(httpResponse.status)
            } else {
                httpResponse.toBoundedTextResponse(MAX_ERROR_BODY_BYTES)
            }
        }
        acceptResponse(response) { rawResponse ->
            if (isStorageObjectExists(rawResponse)) {
                throw AccountClientException(
                    message = "이미 업로드된 이미지 객체가 있어요.",
                    retryable = false,
                    code = ASSET_OBJECT_EXISTS,
                )
            }
            if (!rawResponse.status.isSuccess()) throw apiException(rawResponse)
        }
    }

    suspend fun downloadActiveAsset(
        expectedOwnerId: String,
        itemId: String,
        assetId: String,
    ): ByteArray {
        val asset = validateAssetTransport(
            expectedOwnerId = expectedOwnerId,
            itemId = itemId,
            assetId = assetId,
        )
        val safePath = validateLibraryPath(
            "/items/${asset.itemId}/assets/${asset.assetId}/content",
        )

        val response = authorizedRequest(asset.expectedOwnerId) { accessToken ->
            val httpResponse = requireHttpClient().get(
                "${requireConfig().url}$FUNCTIONS_PATH$safePath",
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
            }
            when {
                httpResponse.status == HttpStatusCode.Unauthorized -> {
                    httpResponse.bodyAsChannel().cancel(null)
                    RawResponse(httpResponse.status)
                }
                !httpResponse.status.isSuccess() -> {
                    httpResponse.toBoundedTextResponse(MAX_ERROR_BODY_BYTES)
                }
                httpResponse.headers[HttpHeaders.ContentType] !in ALLOWED_ASSET_MIME_TYPES -> {
                    httpResponse.bodyAsChannel().cancel(null)
                    RawResponse(
                        status = httpResponse.status,
                        contentType = httpResponse.headers[HttpHeaders.ContentType],
                    )
                }
                else -> {
                    val body = httpResponse.readBoundedBody(MAX_ASSET_BYTES)
                    RawResponse(
                        status = httpResponse.status,
                        bytes = body.bytes,
                        contentType = httpResponse.headers[HttpHeaders.ContentType],
                        bodyOverflow = body.overflow,
                    )
                }
            }
        }
        return acceptResponse(response) { rawResponse ->
            if (!rawResponse.status.isSuccess()) throw apiException(rawResponse)
            if (rawResponse.contentType !in ALLOWED_ASSET_MIME_TYPES) {
                throw AccountClientException(
                    message = "서버가 지원하지 않는 이미지 형식을 반환했어요.",
                    retryable = false,
                    code = ASSET_MIME_MISMATCH,
                )
            }
            if (rawResponse.bodyOverflow) {
                throw AccountClientException(
                    message = ASSET_TOO_LARGE_MESSAGE,
                    retryable = false,
                    code = ASSET_TOO_LARGE,
                )
            }
            rawResponse.bytes ?: throw AccountClientException(
                message = "서버가 이미지 데이터를 반환하지 않았어요.",
                retryable = true,
                code = "INVALID_ASSET_RESPONSE",
            )
        }
    }

    suspend fun signOut() {
        signOutBound(null)
    }

    private suspend fun signOutBound(expectedOrigin: SessionOrigin?) {
        val auth = requireSupabase().auth
        var localAuthFailure: Exception? = null
        var remoteFailure: Exception? = null
        try {
            withContext(NonCancellable) {
                sessionBoundary.locked {
                    expectedOrigin?.let(::requireCurrent)
                    try {
                        beforeSignOut()
                    } catch (error: Exception) {
                        throw AccountClientException(
                            message = "기기 대기 자료를 정리하지 못해 로그아웃하지 않았어요. 다시 시도해 주세요.",
                            retryable = true,
                            code = LOCAL_DATA_CLEAR_FAILED,
                            cause = error,
                        )
                    }
                    try {
                        auth.signOut()
                    } catch (error: Exception) {
                        remoteFailure = error
                    }
                    try {
                        auth.clearSession()
                    } catch (error: Exception) {
                        localAuthFailure = error
                    }
                    if (localAuthFailure == null) clearCurrent()
                    try {
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                    } catch (error: Exception) {
                        if (remoteFailure == null) remoteFailure = error
                    }
                }
            }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }

        localAuthFailure?.let { error ->
            throw AccountClientException(
                message = "기기 자료는 지웠지만 로그인 정보를 완전히 지우지 못했어요. 다시 시도해 주세요.",
                retryable = true,
                code = LOCAL_DATA_CLEAR_FAILED,
                cause = error,
            )
        }
        remoteFailure?.let { error ->
            if (error is CancellationException) throw error
            throw AccountClientException(
                message = "이 기기의 로그인 정보는 지웠지만 서버 또는 Google 로그인 상태를 정리하지 못했어요.",
                retryable = false,
                cause = error,
            )
        }
    }

    private suspend fun requestFreshDeletionProof(
        activity: Activity,
        rawNonce: String,
    ): String {
        val googleOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(requireConfig().googleWebClientId)
            .setAutoSelectEnabled(false)
            .setNonce(rawNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()
        val credential = try {
            credentialManager.getCredential(
                context = MutableContextWrapper(activity),
                request = request,
            ).credential
        } catch (error: GetCredentialCancellationException) {
            throw AccountDeletionReauthenticationCancelledException(error)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AccountClientException(
                message = "계정 삭제를 위한 Google 재인증 화면을 열지 못했어요.",
                retryable = true,
                code = "GOOGLE_REAUTH_UNAVAILABLE",
                cause = error,
            )
        }
        if (
            credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AccountClientException(
                message = "Google에서 지원하지 않는 재인증 응답을 받았어요.",
                retryable = false,
                code = "GOOGLE_PROOF_INVALID",
            )
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Blank Google ID token")
        } catch (error: Exception) {
            throw AccountClientException(
                message = "Google 재인증 응답을 확인하지 못했어요.",
                retryable = false,
                code = "GOOGLE_PROOF_INVALID",
                cause = error,
            )
        }
    }

    private fun parseDeletionChallenge(response: RawResponse): DeletionChallenge {
        if (response.status != HttpStatusCode.Created) throw apiException(response)
        requireExactJsonKeys(
            body = response.body,
            expectedKeys = setOf("challenge_id", "nonce", "expires_at"),
            code = "INVALID_DELETE_CHALLENGE_RESPONSE",
        )
        val body = decodeOrThrow<DeleteChallengeResponse>(response.body)
        if (!body.challengeId.isCanonicalUuid()) {
            throw invalidDeletionResponse("삭제 확인 ID 형식이 올바르지 않아요.")
        }
        if (
            body.nonce.isBlank() ||
            body.nonce.length > MAX_DELETE_NONCE_LENGTH ||
            body.nonce.any { Character.isISOControl(it.code) }
        ) {
            throw invalidDeletionResponse("삭제 재인증 값을 확인하지 못했어요.")
        }
        val expiresAt = try {
            Instant.parse(body.expiresAt)
        } catch (error: Exception) {
            throw invalidDeletionResponse("삭제 재인증 만료 시간을 확인하지 못했어요.", error)
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw AccountClientException(
                message = "삭제 재인증 시간이 만료됐어요. 다시 확인해 주세요.",
                retryable = false,
                code = DELETE_CHALLENGE_EXPIRED,
            )
        }
        return DeletionChallenge(
            challengeId = body.challengeId,
            nonce = body.nonce,
            expiresAt = expiresAt,
        )
    }

    private fun parseDeletionAcceptance(response: RawResponse) {
        if (response.status != HttpStatusCode.Accepted) throw apiException(response)
        requireExactJsonKeys(
            body = response.body,
            expectedKeys = setOf("state"),
            code = "INVALID_ACCOUNT_DELETE_RESPONSE",
        )
        val body = decodeOrThrow<DeleteAccountResponse>(response.body)
        if (body.state != PROFILE_DELETING) {
            throw invalidDeletionResponse("서버가 계정 삭제 접수 상태를 반환하지 않았어요.")
        }
    }

    private suspend fun requireUsableDeletionChallenge(
        origin: SessionOrigin,
        expiresAt: Instant,
    ) {
        try {
            sessionBoundary.locked { requireCurrent(origin) }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw AccountClientException(
                message = "삭제 재인증 시간이 만료됐어요. 다시 확인해 주세요.",
                retryable = false,
                code = DELETE_CHALLENGE_EXPIRED,
            )
        }
    }

    private suspend fun recoverDeletionState(
        origin: SessionOrigin,
        expectedOwnerId: String,
    ): Boolean? = try {
        requireUsableDeletionOrigin(origin)
        val access = getAccount(expectedOwnerId, origin.generation)
        requireUsableDeletionOrigin(origin)
        access is AccountAccess.Deleting
    } catch (error: AccountClientException) {
        if (error is AccountAuthenticationRequiredException || error.code == SESSION_CHANGED) {
            null
        } else {
            if (!error.retryable) throw error
            null
        }
    }

    private suspend fun requireUsableDeletionOrigin(origin: SessionOrigin) {
        try {
            sessionBoundary.locked { requireCurrent(origin) }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }
    }

    private suspend fun requireDeletionOrigin(
        expectedOwnerId: String,
        expectedGeneration: Long,
    ) {
        try {
            sessionBoundary.locked {
                val current = currentOrigin() ?: throw SessionBoundaryChangedException()
                if (
                    current.ownerId != expectedOwnerId ||
                    current.generation != expectedGeneration
                ) {
                    throw SessionBoundaryChangedException()
                }
            }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }
    }

    private suspend fun finishAcceptedDeletion(
        origin: SessionOrigin,
    ): AccountDeletionAcceptance = finishAcceptedDeletion(
        expectedOwnerId = origin.ownerId,
        expectedOrigin = origin,
    )

    private suspend fun finishAcceptedDeletion(
        expectedOwnerId: String,
        expectedOrigin: SessionOrigin?,
    ): AccountDeletionAcceptance {
        val auth = requireSupabase().auth
        var localAuthFailure: Exception? = null
        var remoteFailure: Exception? = null
        var refusalMessage: String? = null
        return try {
            val localDataCleared = withContext(NonCancellable) {
                sessionBoundary.locked {
                    expectedOrigin?.let(::requireCurrent)
                    val acceptedReceipt = expectedOrigin?.let {
                        acceptedDeletionReceipts.current(
                            ownerId = it.ownerId,
                            acceptanceGeneration = it.generation,
                            presentationGeneration = generation(),
                        )
                    } ?: acceptedDeletionReceipts.currentForPresentation(
                        ownerId = expectedOwnerId,
                        presentationGeneration = generation(),
                    )
                    if (acceptedReceipt == null) {
                        refusalMessage =
                            "이 계정의 삭제 접수 증거를 확인하지 못해 기기 자료를 지우지 않았어요."
                        return@locked false
                    }
                    val currentOwnerId = currentOrigin()?.ownerId
                    val persistedOwnerId = try {
                        localDataOwner()
                    } catch (error: Exception) {
                        if (acceptedDeletionCleanup.wasSuccessfullyPurged(expectedOwnerId)) {
                            clearAuthenticationForAcceptedDeletion(
                                auth = auth,
                                expectedOwnerId = expectedOwnerId,
                                currentOwnerId = currentOwnerId,
                                onLocalFailure = { localAuthFailure = it },
                                onRemoteFailure = { remoteFailure = it },
                            )
                            acceptedDeletionReceipts.recordLocalCleanup(
                                expectedOwnerId,
                                generation(),
                                localAuthenticationCleared = localAuthFailure == null,
                            )
                            return@locked localAuthFailure == null
                        }
                        refusalMessage = "기기 자료의 소유 계정을 확인하지 못했어요. 다른 계정 자료는 지우지 않았어요."
                        return@locked false
                    }
                    when (
                        acceptedDeletionCleanup.action(
                            expectedOwnerId = expectedOwnerId,
                            sessionOwnerId = currentOwnerId,
                            localDataOwnerId = persistedOwnerId,
                        )
                    ) {
                        AcceptedDeletionCleanupAction.PURGE_EXPECTED_OWNER -> {
                            try {
                                acceptedDeletionCleanup.purge(expectedOwnerId) {
                                    beforeSignOut()
                                }
                            } catch (error: Exception) {
                                refusalMessage =
                                    "기기 대기 자료를 정리하지 못했어요. 다시 시도해 주세요."
                                return@locked false
                            }
                            clearAuthenticationForAcceptedDeletion(
                                auth = auth,
                                expectedOwnerId = expectedOwnerId,
                                currentOwnerId = currentOwnerId,
                                onLocalFailure = { localAuthFailure = it },
                                onRemoteFailure = { remoteFailure = it },
                            )
                            acceptedDeletionReceipts.recordLocalCleanup(
                                expectedOwnerId,
                                generation(),
                                localAuthenticationCleared = localAuthFailure == null,
                            )
                            true
                        }
                        AcceptedDeletionCleanupAction.CLEAR_AUTH_ONLY -> {
                            clearAuthenticationForAcceptedDeletion(
                                auth = auth,
                                expectedOwnerId = expectedOwnerId,
                                currentOwnerId = currentOwnerId,
                                onLocalFailure = { localAuthFailure = it },
                                onRemoteFailure = { remoteFailure = it },
                            )
                            acceptedDeletionReceipts.recordLocalCleanup(
                                expectedOwnerId,
                                generation(),
                                localAuthenticationCleared = localAuthFailure == null,
                            )
                            localAuthFailure == null
                        }
                        AcceptedDeletionCleanupAction.ALREADY_ABSENT -> {
                            acceptedDeletionReceipts.recordLocalCleanup(
                                expectedOwnerId,
                                generation(),
                                localAuthenticationCleared = true,
                            )
                            true
                        }
                        AcceptedDeletionCleanupAction.REFUSE_OWNER_MISMATCH -> {
                            refusalMessage =
                                "기기 자료의 소유 계정이 달라 다른 계정 자료는 지우지 않았어요."
                            false
                        }
                    }
                }
            }
            if (!localDataCleared) {
                return AccountDeletionAcceptance(
                    localDataCleared = false,
                    message = refusalMessage,
                )
            }
            localAuthFailure?.let { error ->
                return AccountDeletionAcceptance(
                    localDataCleared = false,
                    message = "기기 자료는 지웠지만 로그인 정보를 완전히 지우지 못했어요. 다시 시도해 주세요.",
                )
            }
            AccountDeletionAcceptance(
                localDataCleared = true,
                message = remoteFailure?.let {
                    "기기 자료와 로그인 정보는 지웠지만 서버 또는 Google 로그아웃을 확인하지 못했어요."
                },
            )
        } catch (error: SessionBoundaryChangedException) {
            AccountDeletionAcceptance(
                localDataCleared = false,
                message = "로그인 계정이 변경되어 다른 계정 자료는 지우지 않았어요.",
            )
        }
    }

    private suspend fun SessionBoundary.clearAuthenticationForAcceptedDeletion(
        auth: Auth,
        expectedOwnerId: String,
        currentOwnerId: String?,
        onLocalFailure: (Exception) -> Unit,
        onRemoteFailure: (Exception) -> Unit,
    ) {
        if (currentOwnerId != null && currentOwnerId != expectedOwnerId) return
        try {
            auth.signOut()
        } catch (error: Exception) {
            onRemoteFailure(error)
        }
        var localFailure: Exception? = null
        try {
            auth.clearSession()
        } catch (error: Exception) {
            localFailure = error
            onLocalFailure(error)
        }
        if (localFailure == null && currentOrigin()?.ownerId == expectedOwnerId) {
            clearCurrent()
        }
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (error: Exception) {
            onRemoteFailure(error)
        }
    }

    private suspend fun authorizedRequest(
        expectedOwnerId: String? = null,
        expectedGeneration: Long? = null,
        request: suspend (accessToken: String) -> RawResponse,
    ): BoundResponse {
        val auth = requireSupabase().auth
        val initialCredential = captureCredential(auth, expectedOwnerId, expectedGeneration)
        var response = performRequest(initialCredential, request)
        if (response.status != HttpStatusCode.Unauthorized) {
            return BoundResponse(initialCredential.origin, response)
        }

        val retryCredential = refreshOrUseRotatedCredential(
            auth = auth,
            rejectedCredential = initialCredential,
        )
        response = performRequest(retryCredential, request)
        return try {
            sessionBoundary.locked {
                requireCurrent(retryCredential.origin)
                if (response.status == HttpStatusCode.Unauthorized) {
                    val status = auth.sessionStatus.value
                    if (status is SessionStatus.RefreshFailure) {
                        markRecoverable(retryCredential.origin)
                        throw refreshUnavailable()
                    }
                    if (status !is SessionStatus.Authenticated) {
                        auth.clearSession()
                        clear(retryCredential.origin)
                        throw AccountAuthenticationRequiredException()
                    }
                    val currentOwnerId = status.session.user?.id ?: throw invalidSdkSession()
                    if (currentOwnerId != retryCredential.origin.ownerId) {
                        commitIdentity(currentOwnerId)
                        throw sessionChanged()
                    }
                    if (status.session.accessToken != retryCredential.accessToken) {
                        throw AccountClientException(
                            message = "요청 중 로그인 세션이 갱신됐어요. 다시 시도해 주세요.",
                            retryable = true,
                            code = "SESSION_ROTATED",
                        )
                    }
                    auth.clearSession()
                    clear(retryCredential.origin)
                    throw AccountAuthenticationRequiredException()
                }
                BoundResponse(retryCredential.origin, response)
            }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }
    }

    private suspend fun <T> acceptResponse(
        response: BoundResponse,
        accept: (RawResponse) -> T,
    ): T = try {
        sessionBoundary.locked {
            requireCurrent(response.origin)
            accept(response.raw)
        }
    } catch (error: SessionBoundaryChangedException) {
        throw sessionChanged()
    }

    private suspend fun acceptAccountAccessResponse(
        response: BoundResponse,
        accept: (RawResponse, SessionOrigin) -> AccountAccess,
    ): AccountAccess = try {
        sessionBoundary.locked {
            requireCurrent(response.origin)
            accept(response.raw, response.origin)
        }
    } catch (error: SessionBoundaryChangedException) {
        throw sessionChanged()
    }

    private suspend fun acceptDeletionResponse(response: BoundResponse) {
        try {
            sessionBoundary.locked {
                requireCurrent(response.origin)
                parseDeletionAcceptance(response.raw)
                acceptedDeletionReceipts.recordServerAcceptance(response.origin)
            }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }
    }

    private suspend fun acceptedDeletionAccessAfterSessionRace(
        expectedOrigin: SessionOrigin,
        error: AccountClientException,
    ): AccountAccess? = sessionBoundary.locked {
        resolveAcceptedDeletionSessionRace(
            expectedOrigin = expectedOrigin,
            error = error,
            receipt = acceptedDeletionReceipts.current(
                ownerId = expectedOrigin.ownerId,
                acceptanceGeneration = expectedOrigin.generation,
                presentationGeneration = generation(),
            ),
        )
    }

    private suspend fun acceptedDeletionAccessForCurrentPresentation(): AccountAccess.Deleting? =
        sessionBoundary.locked {
            acceptedDeletionReceipts.currentForPresentation(generation())
                ?.let { AccountAccess.Deleting(it) }
        }

    private suspend fun performRequest(
        credential: BoundCredential,
        request: suspend (accessToken: String) -> RawResponse,
    ): RawResponse = try {
        val response = request(credential.accessToken)
        sessionBoundary.locked {
            requireCurrent(credential.origin)
            response
        }
    } catch (error: SessionBoundaryChangedException) {
        throw sessionChanged()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        sessionBoundary.locked {
            try {
                requireCurrent(credential.origin)
            } catch (_: SessionBoundaryChangedException) {
                throw sessionChanged()
            }
        }
        throw AccountClientException(
            message = "서버에 연결하지 못했어요. 네트워크 연결을 확인하고 다시 시도해 주세요.",
            retryable = true,
            cause = error,
        )
    }

    private suspend fun captureCredential(
        auth: Auth,
        expectedOwnerId: String?,
        expectedGeneration: Long?,
    ): BoundCredential = try {
        sessionBoundary.locked {
            val origin = currentOrigin() ?: throw AccountAuthenticationRequiredException()
            if (expectedOwnerId != null && origin.ownerId != expectedOwnerId) throw sessionChanged()
            if (expectedGeneration != null && origin.generation != expectedGeneration) {
                throw sessionChanged()
            }
            when (val status = auth.sessionStatus.value) {
                is SessionStatus.Authenticated -> {
                    val ownerId = status.session.user?.id ?: throw invalidSdkSession()
                    requireCurrent(origin)
                    if (ownerId != origin.ownerId) throw SessionBoundaryChangedException()
                    BoundCredential(
                        origin = origin,
                        accessToken = status.session.accessToken,
                    )
                }
                is SessionStatus.RefreshFailure -> {
                    markRecoverable(origin)
                    throw refreshUnavailable()
                }
                is SessionStatus.NotAuthenticated,
                SessionStatus.Initializing,
                -> {
                    clear(origin)
                    throw AccountAuthenticationRequiredException()
                }
            }
        }
    } catch (error: SessionBoundaryChangedException) {
        throw sessionChanged()
    }

    private suspend fun refreshOrUseRotatedCredential(
        auth: Auth,
        rejectedCredential: BoundCredential,
    ): BoundCredential = try {
        sessionBoundary.locked {
            requireCurrent(rejectedCredential.origin)
            when (val status = auth.sessionStatus.value) {
                is SessionStatus.RefreshFailure -> {
                    markRecoverable(rejectedCredential.origin)
                    throw refreshUnavailable()
                }
                is SessionStatus.NotAuthenticated,
                SessionStatus.Initializing,
                -> {
                    clear(rejectedCredential.origin)
                    throw AccountAuthenticationRequiredException()
                }
                is SessionStatus.Authenticated -> {
                    val ownerId = status.session.user?.id ?: throw invalidSdkSession()
                    if (ownerId != rejectedCredential.origin.ownerId) {
                        throw SessionBoundaryChangedException()
                    }
                    if (status.session.accessToken != rejectedCredential.accessToken) {
                        return@locked BoundCredential(
                            origin = rejectedCredential.origin,
                            accessToken = status.session.accessToken,
                        )
                    }
                    if (status.session.refreshToken.isBlank()) {
                        auth.clearSession()
                        clear(rejectedCredential.origin)
                        throw AccountAuthenticationRequiredException()
                    }
                }
            }

            try {
                auth.refreshCurrentSession()
            } catch (error: RestException) {
                if (error.isDefinitiveRefreshFailure()) {
                    requireCurrent(rejectedCredential.origin)
                    auth.clearSession()
                    clear(rejectedCredential.origin)
                    throw AccountAuthenticationRequiredException(error)
                }
                markRecoverable(rejectedCredential.origin)
                throw refreshUnavailable(error)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                markRecoverable(rejectedCredential.origin)
                throw refreshUnavailable(error)
            }

            requireCurrent(rejectedCredential.origin)
            val refreshedSession = auth.currentSessionOrNull() ?: run {
                auth.clearSession()
                clear(rejectedCredential.origin)
                throw AccountAuthenticationRequiredException()
            }
            val refreshedOwnerId = refreshedSession.user?.id ?: throw invalidSdkSession()
            if (refreshedOwnerId != rejectedCredential.origin.ownerId) {
                auth.clearSession()
                clear(rejectedCredential.origin)
                throw AccountAuthenticationRequiredException()
            }
            confirmAuthenticated(rejectedCredential.origin, refreshedOwnerId)
            BoundCredential(
                origin = rejectedCredential.origin,
                accessToken = refreshedSession.accessToken,
            )
        }
    } catch (error: SessionBoundaryChangedException) {
        throw sessionChanged()
    }

    private fun SessionBoundary.commitIdentity(ownerId: String): SessionOrigin {
        val origin = commitLogin(ownerId)
        acceptedDeletionReceipts.invalidateForNewIdentity(origin)
        return origin
    }

    private fun SessionBoundary.beginRestoreIdentity(ownerId: String): SessionOrigin {
        val previous = currentOrigin()
        val origin = beginRestore(ownerId)
        if (previous != origin) {
            acceptedDeletionReceipts.invalidateForNewIdentity(origin)
        }
        return origin
    }

    private suspend fun SessionBoundary.prepareLocalOwner(ownerId: String) {
        acceptedDeletionReceipts.invalidateForOwner(ownerId)
        if (currentOrigin()?.ownerId?.let { it != ownerId } == true) {
            clearCurrent()
        }
        try {
            onSessionOwner(ownerId)
        } catch (error: CancellationException) {
            clearCurrent()
            throw error
        } catch (error: Exception) {
            clearCurrent()
            throw AccountClientException(
                message = "이 기기의 계정 자료를 준비하지 못했어요. 다시 시도해 주세요.",
                retryable = true,
                code = "LOCAL_ACCOUNT_STORAGE_UNAVAILABLE",
                cause = error,
            )
        }
    }

    private suspend fun SessionBoundary.restoreStoredSession(auth: Auth): Boolean {
        val storedSession = try {
            auth.sessionManager.loadSession()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw refreshUnavailable(error)
        }
        if (storedSession == null) {
            if (currentOrigin() != null) clearCurrent()
            return false
        }

        val storedOwnerId = storedSession.user?.id ?: run {
            auth.clearSession()
            if (currentOrigin() != null) clearCurrent()
            throw invalidSdkSession()
        }
        prepareLocalOwner(storedOwnerId)
        val origin = beginRestoreIdentity(storedOwnerId)
        try {
            withTimeout(AUTH_SESSION_OPERATION_TIMEOUT_MILLIS) {
                auth.importSession(storedSession, autoRefresh = false)
                if (storedSession.expiresAt <= kotlinx.datetime.Clock.System.now()) {
                    auth.refreshCurrentSession()
                }
            }
        } catch (error: TimeoutCancellationException) {
            markRecoverable(origin)
            throw refreshUnavailable(error)
        } catch (error: RestException) {
            if (error.isDefinitiveRefreshFailure()) {
                auth.clearSession()
                clear(origin)
                return false
            }
            markRecoverable(origin)
            throw refreshUnavailable(error)
        } catch (error: CancellationException) {
            markRecoverable(origin)
            throw error
        } catch (error: Exception) {
            markRecoverable(origin)
            throw refreshUnavailable(error)
        }

        return when (val status = auth.sessionStatus.value) {
            is SessionStatus.Authenticated -> {
                val ownerId = status.session.user?.id ?: throw invalidSdkSession()
                if (ownerId != origin.ownerId) {
                    auth.clearSession()
                    clear(origin)
                    throw AccountAuthenticationRequiredException()
                }
                confirmAuthenticated(origin, ownerId)
                true
            }
            is SessionStatus.RefreshFailure -> {
                markRecoverable(origin)
                throw refreshUnavailable()
            }
            is SessionStatus.NotAuthenticated,
            SessionStatus.Initializing,
            -> {
                clear(origin)
                false
            }
        }
    }

    private fun parseBootstrap(response: RawResponse, origin: SessionOrigin): AccountAccess {
        if (!response.status.isSuccess()) return parseApiError(response, origin)
        val body = decodeOrThrow<BootstrapResponse>(response.body)
        return accountAccess(
            state = body.profile.state,
            summary = AccountSummary(
                profileId = body.profile.id,
                limits = body.limits.toAccountLimits(),
                usage = body.usage.toAccountUsage(),
            ),
            origin = origin,
        )
    }

    private fun parseMe(response: RawResponse, origin: SessionOrigin): AccountAccess {
        if (!response.status.isSuccess()) return parseApiError(response, origin)
        val body = decodeOrThrow<MeResponse>(response.body)
        return accountAccess(
            state = body.state,
            summary = if (body.id != null || body.limits != null || body.usage != null) {
                AccountSummary(
                    profileId = body.id,
                    limits = body.limits?.toAccountLimits(),
                    usage = body.usage?.toAccountUsage(),
                )
            } else {
                null
            },
            origin = origin,
        )
    }

    private fun parseApiError(response: RawResponse, origin: SessionOrigin): AccountAccess {
        val envelope = decodeApiError(response.body)
        return when {
            response.status == HttpStatusCode.Forbidden &&
                envelope?.error?.code == BETA_ACCESS_REQUIRED -> AccountAccess.PendingApproval
            response.status == HttpStatusCode.Forbidden &&
                envelope?.error?.code == ACCOUNT_DELETING -> deletingAccess(origin)
            else -> throw apiException(response, envelope)
        }
    }

    private fun apiException(
        response: RawResponse,
        envelope: ApiErrorEnvelope? = decodeApiError(response.body),
    ) = AccountClientException(
        message = envelope?.error?.message
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_ERROR_MESSAGE_LENGTH)
            ?: "요청을 처리하지 못했어요. (HTTP ${response.status.value})",
        retryable = envelope?.error?.retryable ?: (response.status.value >= 500),
        code = envelope?.error?.code,
        requestId = envelope?.requestId,
        retryAfterSeconds = response.retryAfterSeconds,
    )

    private fun decodeApiError(body: String): ApiErrorEnvelope? = try {
        json.decodeFromString(body)
    } catch (_: Exception) {
        null
    }

    private fun isStorageObjectExists(response: RawResponse): Boolean {
        if (response.status == HttpStatusCode.Conflict) return true
        if (response.status != HttpStatusCode.BadRequest) return false
        val error = try {
            json.decodeFromString<StorageErrorResponse>(response.body)
        } catch (_: Exception) {
            return false
        }
        return error.code == "ResourceAlreadyExists" ||
            error.code == "KeyAlreadyExists" ||
            (error.statusCode == "409" && error.error == "Duplicate")
    }

    private fun requireExactJsonKeys(
        body: String,
        expectedKeys: Set<String>,
        code: String,
    ) {
        val objectBody = try {
            json.decodeFromString<JsonObject>(body)
        } catch (error: Exception) {
            throw AccountClientException(
                message = "서버 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
                retryable = true,
                code = code,
                cause = error,
            )
        }
        if (objectBody.keys != expectedKeys) {
            throw AccountClientException(
                message = "서버 응답 형식이 올바르지 않아요. 잠시 후 다시 시도해 주세요.",
                retryable = true,
                code = code,
            )
        }
    }

    private fun accountAccess(
        state: String,
        summary: AccountSummary?,
        origin: SessionOrigin,
    ): AccountAccess = when (state) {
        PROFILE_ACTIVE -> AccountAccess.Active(
            summary ?: throw AccountClientException(
                message = "계정 응답에 사용량 정보가 없어요.",
                retryable = true,
            ),
        )
        PROFILE_PENDING -> AccountAccess.PendingApproval
        PROFILE_DELETING -> deletingAccess(origin)
        else -> throw AccountClientException(
            message = "서버에서 알 수 없는 계정 상태를 받았어요.",
            retryable = false,
        )
    }

    private fun deletingAccess(origin: SessionOrigin): AccountAccess.Deleting {
        acceptedDeletionReceipts.recordServerAcceptance(origin)
        val receipt = requireNotNull(
            acceptedDeletionReceipts.current(
                ownerId = origin.ownerId,
                acceptanceGeneration = origin.generation,
                presentationGeneration = sessionBoundary.generation(),
            ),
        )
        return AccountAccess.Deleting(receipt)
    }

    private inline fun <reified T> decodeOrThrow(body: String): T = try {
        json.decodeFromString(body)
    } catch (error: Exception) {
        throw AccountClientException(
            message = "서버 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
            retryable = true,
            cause = error,
        )
    }

    private fun requireConfig(): ValidatedAccountConfig = configured
        ?: throw AccountClientException(
            message = configurationMessage ?: "로그인 설정이 필요해요.",
            retryable = false,
        )

    private fun requireSupabase(): SupabaseClient = supabase
        ?: throw AccountClientException(
            message = configurationMessage ?: "로그인 설정이 필요해요.",
            retryable = false,
        )

    private fun requireHttpClient(): HttpClient = httpClient
        ?: throw AccountClientException(
            message = configurationMessage ?: "로그인 설정이 필요해요.",
            retryable = false,
        )

    private companion object {
        const val FUNCTIONS_PATH = "/functions/v1/library-api/v1"
        const val API_KEY_HEADER = "apikey"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val UPSERT_HEADER = "x-upsert"
        const val EMPTY_JSON_OBJECT = "{}"
        const val BETA_ACCESS_REQUIRED = "BETA_ACCESS_REQUIRED"
        const val ACCOUNT_DELETING = "ACCOUNT_DELETING"
        const val INVALID_REQUEST_ID = "INVALID_REQUEST_ID"
        const val DELETE_CHALLENGE_EXPIRED = "DELETE_CHALLENGE_EXPIRED"
        const val LOCAL_DATA_CLEAR_FAILED = "LOCAL_DATA_CLEAR_FAILED"
        const val SESSION_CHANGED = "SESSION_CHANGED"
        const val ASSET_OBJECT_EXISTS = "ASSET_OBJECT_EXISTS"
        const val ASSET_MIME_MISMATCH = "ASSET_MIME_MISMATCH"
        const val PROFILE_ACTIVE = "active"
        const val PROFILE_PENDING = "pending_approval"
        const val PROFILE_DELETING = "deleting"
        const val MAX_ERROR_MESSAGE_LENGTH = 300
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val AUTH_REQUEST_TIMEOUT_SECONDS = 15
        const val AUTH_SESSION_OPERATION_TIMEOUT_MILLIS = 16_000L
        const val API_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val API_CONNECT_TIMEOUT_MILLIS = 10_000L
        const val ACCOUNT_DELETE_ATTEMPTS = 2
        const val MAX_DELETE_NONCE_LENGTH = 1024
    }
}

sealed interface AccountAccess {
    data class Active(val summary: AccountSummary) : AccountAccess
    data object PendingApproval : AccountAccess
    data class Deleting(val receipt: AccountDeletionReceipt) : AccountAccess
}

data class AccountDeletionAcceptance(
    val localDataCleared: Boolean,
    val message: String? = null,
)

data class AccountDeletionReceipt(
    val ownerId: String,
    val acceptanceGeneration: Long,
    val presentationGeneration: Long,
    val localDataCleared: Boolean,
)

internal class AcceptedDeletionReceiptStore {
    private var receipt: AccountDeletionReceipt? = null

    fun recordServerAcceptance(origin: SessionOrigin) {
        val current = receipt
        receipt = if (
            current?.ownerId == origin.ownerId &&
            current.acceptanceGeneration == origin.generation
        ) {
            current.copy(presentationGeneration = origin.generation)
        } else {
            AccountDeletionReceipt(
                ownerId = origin.ownerId,
                acceptanceGeneration = origin.generation,
                presentationGeneration = origin.generation,
                localDataCleared = false,
            )
        }
    }

    fun recordLocalCleanup(
        ownerId: String,
        presentationGeneration: Long,
        localAuthenticationCleared: Boolean,
    ) {
        val current = receipt ?: return
        if (current.ownerId == ownerId) {
            receipt = current.copy(
                presentationGeneration = presentationGeneration,
                localDataCleared = localAuthenticationCleared,
            )
        }
    }

    fun current(
        ownerId: String,
        acceptanceGeneration: Long,
        presentationGeneration: Long,
    ): AccountDeletionReceipt? = receipt?.takeIf {
        it.ownerId == ownerId &&
            it.acceptanceGeneration == acceptanceGeneration &&
            it.presentationGeneration == presentationGeneration
    }

    fun currentForPresentation(
        ownerId: String,
        presentationGeneration: Long,
    ): AccountDeletionReceipt? = receipt?.takeIf {
        it.ownerId == ownerId && it.presentationGeneration == presentationGeneration
    }

    fun currentForPresentation(presentationGeneration: Long): AccountDeletionReceipt? =
        receipt?.takeIf { it.presentationGeneration == presentationGeneration }

    fun invalidateForNewIdentity(origin: SessionOrigin) {
        if (
            receipt?.let {
                it.ownerId != origin.ownerId ||
                    it.presentationGeneration != origin.generation
            } == true
        ) {
            receipt = null
        }
    }

    fun invalidateForOwner(ownerId: String) {
        if (receipt?.ownerId?.let { it != ownerId } == true) {
            receipt = null
        }
    }
}

internal fun resolveAcceptedDeletionSessionRace(
    expectedOrigin: SessionOrigin,
    error: AccountClientException,
    receipt: AccountDeletionReceipt?,
): AccountAccess? {
    if (error !is AccountAuthenticationRequiredException && error.code != "SESSION_CHANGED") {
        return null
    }
    val current = receipt ?: return null
    if (
        current.ownerId != expectedOrigin.ownerId ||
        current.acceptanceGeneration != expectedOrigin.generation
    ) {
        return null
    }
    return AccountAccess.Deleting(current)
}

internal enum class AcceptedDeletionCleanupAction {
    PURGE_EXPECTED_OWNER,
    CLEAR_AUTH_ONLY,
    ALREADY_ABSENT,
    REFUSE_OWNER_MISMATCH,
}

internal class AcceptedDeletionCleanupTracker {
    private val successfullyPurgedOwners = mutableSetOf<String>()

    fun action(
        expectedOwnerId: String,
        sessionOwnerId: String?,
        localDataOwnerId: String?,
    ): AcceptedDeletionCleanupAction {
        if (sessionOwnerId != null && sessionOwnerId != expectedOwnerId) {
            return if (localDataOwnerId == expectedOwnerId) {
                AcceptedDeletionCleanupAction.REFUSE_OWNER_MISMATCH
            } else {
                AcceptedDeletionCleanupAction.ALREADY_ABSENT
            }
        }
        return when (localDataOwnerId) {
            expectedOwnerId -> AcceptedDeletionCleanupAction.PURGE_EXPECTED_OWNER
            null -> AcceptedDeletionCleanupAction.CLEAR_AUTH_ONLY
            else -> if (sessionOwnerId == expectedOwnerId) {
                AcceptedDeletionCleanupAction.REFUSE_OWNER_MISMATCH
            } else {
                AcceptedDeletionCleanupAction.ALREADY_ABSENT
            }
        }
    }

    suspend fun purge(ownerId: String, action: suspend () -> Unit) {
        action()
        successfullyPurgedOwners += ownerId
    }

    fun wasSuccessfullyPurged(ownerId: String): Boolean =
        ownerId in successfullyPurgedOwners
}

data class AccountSummary(
    val profileId: String?,
    val limits: AccountLimits?,
    val usage: AccountUsage?,
)

data class AccountLimits(
    val items: Long,
    val imageBytes: Long,
)

data class AccountUsage(
    val activeItemCount: Long,
    val usedImageBytes: Long,
    val reservedImageBytes: Long,
)

open class AccountClientException(
    override val message: String,
    val retryable: Boolean,
    val code: String? = null,
    val retryAfterSeconds: Int? = null,
    val requestId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class AccountAuthenticationRequiredException(
    cause: Throwable? = null,
) : AccountClientException(
    message = "로그인이 필요해요.",
    retryable = false,
    code = "UNAUTHENTICATED",
    cause = cause,
)

internal class AccountSignInCancelledException(
    cause: Throwable,
) : Exception("로그인이 취소됐어요.", cause)

internal class AccountDeletionReauthenticationCancelledException(
    cause: Throwable,
) : Exception("계정 삭제 재인증이 취소됐어요.", cause)

internal class AccountDeletionStatusUnknownException(
    val expectedOwnerId: String,
    val expectedGeneration: Long,
    cause: Throwable?,
) : AccountClientException(
    message = "삭제 요청 결과를 확인하지 못했어요. 새 삭제 요청을 보내지 말고 계정 상태를 확인해 주세요.",
    retryable = true,
    code = "ACCOUNT_DELETE_STATUS_UNKNOWN",
    cause = cause,
)

internal fun accountDeletionResultIsUnknown(
    deleteRequestMayHaveReachedServer: Boolean,
    error: AccountClientException,
): Boolean = deleteRequestMayHaveReachedServer &&
    (error is AccountAuthenticationRequiredException || error.code == "SESSION_CHANGED")

internal suspend fun guardAmbiguousAccountDeletionRetry(
    expectedOwnerId: String,
    expectedGeneration: Long,
    ambiguousFailure: AccountClientException,
    validateRetry: suspend () -> Unit,
) {
    try {
        validateRetry()
    } catch (validationError: AccountClientException) {
        if (validationError !== ambiguousFailure) {
            validationError.addSuppressed(ambiguousFailure)
        }
        throw AccountDeletionStatusUnknownException(
            expectedOwnerId = expectedOwnerId,
            expectedGeneration = expectedGeneration,
            cause = validationError,
        )
    }
}

private fun refreshUnavailable(cause: Throwable? = null) = AccountClientException(
    message = "로그인 세션을 새로 고치지 못했어요. 네트워크 연결을 확인하고 다시 시도해 주세요.",
    retryable = true,
    code = "SESSION_REFRESH_UNAVAILABLE",
    cause = cause,
)

private fun sessionChanged() = AccountClientException(
    message = "로그인 계정이 변경되어 요청을 중단했어요.",
    retryable = false,
    code = "SESSION_CHANGED",
)

private fun invalidDeletionResponse(
    message: String,
    cause: Throwable? = null,
) = AccountClientException(
    message = message,
    retryable = true,
    code = "INVALID_ACCOUNT_DELETE_RESPONSE",
    cause = cause,
)

private fun invalidSdkSession() = AccountClientException(
    message = "로그인 세션에서 계정 정보를 확인하지 못했어요.",
    retryable = false,
    code = "INVALID_SESSION",
)

internal data class ValidatedAccountConfig(
    val url: String,
    val key: String,
    val googleWebClientId: String,
)

internal sealed interface AccountConfigValidation {
    data class Configured(val config: ValidatedAccountConfig) : AccountConfigValidation
    data class Unconfigured(val issue: AccountConfigIssue) : AccountConfigValidation
}

internal enum class AccountConfigIssue(val message: String) {
    MISSING_URL("Supabase URL 설정이 필요해요."),
    MISSING_KEY("Supabase publishable key 설정이 필요해요."),
    MISSING_GOOGLE_WEB_CLIENT_ID("Google 웹 클라이언트 ID 설정이 필요해요."),
    INVALID_URL("Supabase URL은 유효한 HTTPS 주소여야 해요."),
    INSECURE_URL("배포 앱에서는 HTTPS Supabase URL만 사용할 수 있어요."),
}

internal fun validateAccountConfig(
    url: String,
    key: String,
    googleWebClientId: String,
    debug: Boolean,
): AccountConfigValidation {
    val trimmedUrl = url.trim().trimEnd('/')
    val trimmedKey = key.trim()
    val trimmedClientId = googleWebClientId.trim()
    if (trimmedUrl.isBlank()) {
        return AccountConfigValidation.Unconfigured(AccountConfigIssue.MISSING_URL)
    }
    if (trimmedKey.isBlank()) {
        return AccountConfigValidation.Unconfigured(AccountConfigIssue.MISSING_KEY)
    }
    if (trimmedClientId.isBlank()) {
        return AccountConfigValidation.Unconfigured(AccountConfigIssue.MISSING_GOOGLE_WEB_CLIENT_ID)
    }

    val uri = try {
        URI(trimmedUrl)
    } catch (_: Exception) {
        return AccountConfigValidation.Unconfigured(AccountConfigIssue.INVALID_URL)
    }
    val hasOriginOnly = uri.host?.isNotBlank() == true &&
        uri.userInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
        (uri.port == -1 || uri.port in 1..65535)
    if (!hasOriginOnly) {
        return AccountConfigValidation.Unconfigured(AccountConfigIssue.INVALID_URL)
    }

    val scheme = uri.scheme?.lowercase()
    val isHttps = scheme == "https"
    val isDebugEmulator = debug &&
        scheme == "http" &&
        uri.host == "10.0.2.2" &&
        uri.port == 18021
    if (!isHttps && !isDebugEmulator) {
        val issue = if (scheme == "http") {
            AccountConfigIssue.INSECURE_URL
        } else {
            AccountConfigIssue.INVALID_URL
        }
        return AccountConfigValidation.Unconfigured(issue)
    }

    return AccountConfigValidation.Configured(
        ValidatedAccountConfig(
            url = trimmedUrl,
            key = trimmedKey,
            googleWebClientId = trimmedClientId,
        ),
    )
}

internal fun validateLibraryPath(path: String): String {
    val uri = try {
        URI(path)
    } catch (error: Exception) {
        throw invalidLibraryPath(error)
    }
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null) {
        throw invalidLibraryPath()
    }
    val rawPath = uri.rawPath ?: throw invalidLibraryPath()
    validateLibraryQuery(rawPath, uri.rawQuery)
    if (rawPath == "/items" || rawPath == "/categories") return path
    val parts = rawPath.split('/').drop(1)
    if (
        parts.size == 2 && (parts[0] == "items" || parts[0] == "categories") &&
        parts[1].isCanonicalUuid()
    ) {
        return path
    }
    if (
        parts.size == 3 && parts[0] == "items" && parts[1].isCanonicalUuid() &&
        (
            parts[2] == "reclassify" ||
                parts[2] == "cue-dismiss" ||
                parts[2] == "retry-metadata"
        )
    ) return path
    if (
        parts.size == 4 && parts[0] == "items" && parts[1].isCanonicalUuid() &&
        parts[2] == "assets" &&
        (parts[3] == "reserve" || parts[3].isCanonicalUuid())
    ) return path
    if (
        parts.size == 5 && parts[0] == "items" && parts[1].isCanonicalUuid() &&
        parts[2] == "assets" && parts[3].isCanonicalUuid() &&
        (parts[4] == "complete" || parts[4] == "ocr" || parts[4] == "content")
    ) return path
    throw invalidLibraryPath()
}

private fun validateLibraryQuery(rawPath: String, rawQuery: String?) {
    if (rawQuery == null) return
    val allowedNames = when {
        rawPath == "/items" -> ITEM_LIST_QUERY_NAMES
        rawPath.startsWith("/items/") &&
            rawPath.removePrefix("/items/").isCanonicalUuid() -> ITEM_DETAIL_QUERY_NAMES
        else -> throw invalidLibraryPath()
    }
    if (rawQuery.isEmpty()) throw invalidLibraryPath()
    val seenNames = mutableSetOf<String>()
    rawQuery.split('&').forEach { parameter ->
        val equalsIndex = parameter.indexOf('=')
        if (equalsIndex <= 0) throw invalidLibraryPath()
        val name = parameter.substring(0, equalsIndex)
        if (name !in allowedNames || !seenNames.add(name)) throw invalidLibraryPath()
    }
}

internal fun validateLibraryRouteMethod(path: String, method: HttpMethod) {
    val uri = try {
        URI(path)
    } catch (error: Exception) {
        throw invalidLibraryPath(error)
    }
    val rawPath = uri.rawPath
    val parts = rawPath?.split('/')?.drop(1) ?: throw invalidLibraryPath()
    if (method != HttpMethod.Get && uri.rawQuery != null) {
        throw AccountClientException(
            message = "이 보관함 경로에서 지원하지 않는 요청 방식이에요.",
            retryable = false,
            code = "INVALID_METHOD",
        )
    }
    val allowedMethods = when {
        rawPath == "/items" -> setOf(HttpMethod.Get, HttpMethod.Post)
        rawPath == "/categories" -> setOf(HttpMethod.Get, HttpMethod.Post)
        parts.size == 2 && parts[0] == "items" ->
            setOf(HttpMethod.Get, HttpMethod.Patch, HttpMethod.Delete)
        parts.size == 2 && parts[0] == "categories" ->
            setOf(HttpMethod.Patch, HttpMethod.Delete)
        parts.size == 3 && parts[0] == "items" && parts[2] == "retry-metadata" ->
            setOf(HttpMethod.Post)
        parts.size == 3 && parts[0] == "items" &&
            (parts[2] == "reclassify" || parts[2] == "cue-dismiss") ->
            setOf(HttpMethod.Post)
        parts.size == 4 && parts[0] == "items" && parts[2] == "assets" &&
            parts[3] == "reserve" -> setOf(HttpMethod.Post)
        parts.size == 4 && parts[0] == "items" && parts[2] == "assets" ->
            setOf(HttpMethod.Delete)
        parts.size == 5 && parts[0] == "items" && parts[2] == "assets" &&
            parts[4] == "complete" -> setOf(HttpMethod.Post)
        parts.size == 5 && parts[0] == "items" && parts[2] == "assets" &&
            parts[4] == "ocr" -> setOf(HttpMethod.Patch)
        parts.size == 5 && parts[0] == "items" && parts[2] == "assets" &&
            parts[4] == "content" -> setOf(HttpMethod.Get)
        else -> emptySet()
    }
    if (method !in allowedMethods) {
        throw AccountClientException(
            message = "이 보관함 경로에서 지원하지 않는 요청 방식이에요.",
            retryable = false,
            code = "INVALID_METHOD",
        )
    }
}

internal fun validateLibraryRequestBody(
    path: String,
    method: HttpMethod,
    body: String?,
) {
    val objectBody = body?.let {
        try {
            Json.decodeFromString<JsonObject>(it)
        } catch (error: Exception) {
            throw AccountClientException(
                message = "요청 본문은 JSON 객체여야 해요.",
                retryable = false,
                code = "INVALID_REQUEST_BODY",
                cause = error,
            )
        }
    }
    if (!isItemDeleteRoute(path, method)) return
    if (objectBody?.keys != setOf("expected_version")) throw invalidItemDeleteBody()
    val version = objectBody["expected_version"] as? JsonPrimitive
    if (
        version == null ||
        version.isString ||
        version.longOrNull == null ||
        version.longOrNull !in 1..Int.MAX_VALUE.toLong()
    ) {
        throw invalidItemDeleteBody()
    }
}

internal fun validateItemDeleteResponse(
    path: String,
    status: HttpStatusCode,
    body: String,
): JsonObject {
    if (!isItemDeleteRoute(path, HttpMethod.Delete) || status != HttpStatusCode.Accepted) {
        throw invalidItemDeleteResponse()
    }
    val response = try {
        Json.decodeFromString<JsonObject>(body)
    } catch (error: Exception) {
        throw invalidItemDeleteResponse(error)
    }
    if (response.keys != setOf("item_id", "state")) throw invalidItemDeleteResponse()
    val expectedItemId = URI(path).rawPath.substringAfterLast('/')
    val itemId = (response["item_id"] as? JsonPrimitive)?.contentOrNull
    val state = (response["state"] as? JsonPrimitive)?.contentOrNull
    if (
        itemId == null ||
        !itemId.isCanonicalUuid() ||
        !itemId.equals(expectedItemId, ignoreCase = true) ||
        state != "deleting"
    ) {
        throw invalidItemDeleteResponse()
    }
    return response
}

private fun isItemDeleteRoute(path: String, method: HttpMethod): Boolean {
    if (method != HttpMethod.Delete) return false
    val rawPath = try {
        val uri = URI(path)
        if (uri.rawQuery != null) return false
        uri.rawPath
    } catch (_: Exception) {
        return false
    }
    val parts = rawPath?.split('/')?.drop(1) ?: return false
    return parts.size == 2 && parts[0] == "items" && parts[1].isCanonicalUuid()
}

private fun invalidItemDeleteBody() = AccountClientException(
    message = "항목 삭제에는 현재 버전 하나만 필요해요.",
    retryable = false,
    code = "INVALID_REQUEST_BODY",
)

private fun invalidItemDeleteResponse(cause: Throwable? = null) = AccountClientException(
    message = "서버가 올바른 항목 삭제 접수 결과를 반환하지 않았어요.",
    retryable = true,
    code = "INVALID_ITEM_DELETE_RESPONSE",
    cause = cause,
)

private fun parseLibraryMethod(method: String): HttpMethod = when (method.trim().uppercase()) {
    "GET" -> HttpMethod.Get
    "POST" -> HttpMethod.Post
    "PATCH" -> HttpMethod.Patch
    "DELETE" -> HttpMethod.Delete
    else -> throw AccountClientException(
        message = "지원하지 않는 요청 방식이에요.",
        retryable = false,
        code = "INVALID_METHOD",
    )
}

private fun validateRequestId(requestId: String): String {
    val trimmed = requestId.trim()
    if (!trimmed.isCanonicalUuid()) {
        throw AccountClientException(
            message = "요청 ID는 UUID여야 해요.",
            retryable = false,
            code = "INVALID_REQUEST_ID",
        )
    }
    return trimmed
}

private fun String.isCanonicalUuid(): Boolean = try {
    UUID.fromString(this).toString().equals(this, ignoreCase = true)
} catch (_: IllegalArgumentException) {
    false
}

internal data class ValidatedAssetTransport(
    val expectedOwnerId: String,
    val itemId: String,
    val assetId: String,
)

internal fun validateAssetTransport(
    expectedOwnerId: String,
    itemId: String,
    assetId: String,
): ValidatedAssetTransport {
    return ValidatedAssetTransport(
        expectedOwnerId = validateAssetId(expectedOwnerId, "INVALID_OWNER_ID", "계정 ID"),
        itemId = validateAssetId(itemId, "INVALID_ITEM_ID", "항목 ID"),
        assetId = validateAssetId(assetId, "INVALID_ASSET_ID", "이미지 ID"),
    )
}

internal fun validateAssetMimeType(mimeType: String): String {
    if (mimeType !in ALLOWED_ASSET_MIME_TYPES) {
        throw AccountClientException(
            message = "JPEG, PNG, WebP 이미지만 업로드할 수 있어요.",
            retryable = false,
            code = "INVALID_ASSET_MIME",
        )
    }
    return mimeType
}

internal fun validateAssetByteCount(byteCount: Int): Int {
    if (byteCount !in 0..MAX_ASSET_BYTES) {
        throw AccountClientException(
            message = ASSET_TOO_LARGE_MESSAGE,
            retryable = false,
            code = ASSET_TOO_LARGE,
        )
    }
    return byteCount
}

private fun validateAssetId(value: String, code: String, label: String): String {
    if (!value.isCanonicalUuid()) {
        throw AccountClientException(
            message = "$label 형식이 올바르지 않아요.",
            retryable = false,
            code = code,
        )
    }
    return value.lowercase()
}

private fun invalidLibraryPath(cause: Throwable? = null) = AccountClientException(
    message = "보관함 요청 경로가 올바르지 않아요.",
    retryable = false,
    code = "INVALID_PATH",
    cause = cause,
)

private fun generateRawNonce(byteLength: Int = 32): String {
    val bytes = ByteArray(byteLength)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(
        bytes,
        Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
    )
}

private fun sha256Hex(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun RestException.isDefinitiveRefreshFailure(): Boolean {
    if (statusCode == 401 || statusCode == 403) return true
    if (statusCode != 400) return false
    return when ((this as? AuthRestException)?.errorCode) {
        AuthErrorCode.BadJwt,
        AuthErrorCode.InvalidCredentials,
        AuthErrorCode.NoAuthorization,
        AuthErrorCode.RefreshTokenAlreadyUsed,
        AuthErrorCode.RefreshTokenNotFound,
        AuthErrorCode.SessionExpired,
        AuthErrorCode.SessionNotFound,
        AuthErrorCode.UserBanned,
        AuthErrorCode.UserNotFound,
        -> true
        else -> false
    }
}

private data class RawResponse(
    val status: HttpStatusCode,
    val body: String = "",
    val retryAfterSeconds: Int? = null,
    val bytes: ByteArray? = null,
    val contentType: String? = null,
    val bodyOverflow: Boolean = false,
)

private data class BoundedBody(
    val bytes: ByteArray?,
    val overflow: Boolean,
)

private suspend fun HttpResponse.readBoundedBody(maxBytes: Int): BoundedBody {
    val channel = bodyAsChannel()
    val declaredBytes = headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredBytes != null && declaredBytes > maxBytes) {
        channel.cancel(null)
        return BoundedBody(bytes = null, overflow = true)
    }

    val buffer = ByteArray(maxBytes)
    var size = 0
    while (size < maxBytes) {
        val read = channel.readAvailable(buffer, size, maxBytes - size)
        if (read < 0) {
            return BoundedBody(buffer.copyOf(size), overflow = false)
        }
        if (read == 0) {
            if (!channel.awaitContent()) {
                return BoundedBody(buffer.copyOf(size), overflow = false)
            }
        } else {
            size += read
        }
    }
    if (channel.awaitContent()) {
        channel.cancel(null)
        return BoundedBody(bytes = null, overflow = true)
    }
    return BoundedBody(bytes = buffer, overflow = false)
}

private suspend fun HttpResponse.toBoundedTextResponse(maxBytes: Int): RawResponse {
    val boundedBody = readBoundedBody(maxBytes)
    return RawResponse(
        status = status,
        body = boundedBody.bytes?.toString(StandardCharsets.UTF_8).orEmpty(),
        retryAfterSeconds = parseRetryAfter(headers[HttpHeaders.RetryAfter]),
        bodyOverflow = boundedBody.overflow,
    )
}

internal fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Int? {
    val header = value?.trim() ?: return null
    header.toLongOrNull()?.let { seconds ->
        return if (seconds < 0) null else seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return runCatching {
        val deadline = java.time.ZonedDateTime.parse(
            header,
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
        ).toInstant().toEpochMilli()
        ((deadline - nowMillis).coerceAtLeast(0) + 999L)
            .div(1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }.getOrNull()
}

private data class BoundCredential(
    val origin: SessionOrigin,
    val accessToken: String,
)

private data class BoundResponse(
    val origin: SessionOrigin,
    val raw: RawResponse,
)

@Serializable
private data class BootstrapResponse(
    val profile: ProfileResponse,
    val limits: LimitsResponse,
    val usage: UsageResponse,
    val categories: List<JsonObject>,
)

@Serializable
private data class ProfileResponse(
    val id: String,
    val state: String,
)

@Serializable
private data class MeResponse(
    val state: String,
    val id: String? = null,
    val limits: LimitsResponse? = null,
    val usage: UsageResponse? = null,
)

private data class DeletionChallenge(
    val challengeId: String,
    val nonce: String,
    val expiresAt: Instant,
)

@Serializable
private data class DeleteChallengeResponse(
    @SerialName("challenge_id") val challengeId: String,
    val nonce: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class AccountDeletionRequest(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("google_id_token") val googleIdToken: String,
)

@Serializable
private data class DeleteAccountResponse(
    val state: String,
)

@Serializable
private data class LimitsResponse(
    val items: Long,
    @SerialName("image_bytes") val imageBytes: Long,
) {
    fun toAccountLimits() = AccountLimits(items = items, imageBytes = imageBytes)
}

@Serializable
private data class UsageResponse(
    @SerialName("active_item_count") val activeItemCount: Long,
    @SerialName("used_image_bytes") val usedImageBytes: Long,
    @SerialName("reserved_image_bytes") val reservedImageBytes: Long,
) {
    fun toAccountUsage() = AccountUsage(
        activeItemCount = activeItemCount,
        usedImageBytes = usedImageBytes,
        reservedImageBytes = reservedImageBytes,
    )
}

@Serializable
private data class ApiErrorEnvelope(
    val error: ApiErrorResponse,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
private data class ApiErrorResponse(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@Serializable
private data class StorageErrorResponse(
    val statusCode: String? = null,
    val error: String? = null,
    val code: String? = null,
)

internal const val MAX_ASSET_BYTES = 2_000_000
internal const val ASSET_TOO_LARGE = "ASSET_TOO_LARGE"
internal const val ASSET_TOO_LARGE_MESSAGE = "이미지는 2MB 이하여야 해요."
private val ALLOWED_ASSET_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private val ITEM_LIST_QUERY_NAMES = setOf(
    "q",
    "category_id",
    "unclassified",
    "source",
    "date_from",
    "date_to",
    "aliases",
    "needs_cues",
    "limit",
    "offset",
)
private val ITEM_DETAIL_QUERY_NAMES = setOf("q")
