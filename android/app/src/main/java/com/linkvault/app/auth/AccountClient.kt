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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

class AccountClient(
    context: Context,
    url: String,
    key: String,
    googleWebClientId: String,
    debug: Boolean,
) {
    private val configuration = validateAccountConfig(url, key, googleWebClientId, debug)
    private val credentialManager = CredentialManager.create(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionBoundary = SessionBoundary()

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

    fun hasSession(): Boolean = sessionBoundary.hasSession()

    fun sessionUserId(): String? = sessionBoundary.ownerId()

    suspend fun restoreAccount(): AccountAccess? {
        val auth = requireSupabase().auth
        val hasRestoredSession = try {
            sessionBoundary.locked {
                when (val status = auth.sessionStatus.value) {
                    is SessionStatus.Authenticated -> {
                        val ownerId = status.session.user?.id
                            ?: throw invalidSdkSession()
                        val origin = currentOrigin() ?: commitLogin(ownerId)
                        if (origin.ownerId != ownerId) {
                            auth.clearSession()
                            clear(origin)
                            throw AccountAuthenticationRequiredException()
                        }
                        confirmAuthenticated(origin, ownerId)
                        true
                    }
                    is SessionStatus.RefreshFailure -> {
                        restoreStoredSession(auth)
                    }
                    is SessionStatus.NotAuthenticated,
                    SessionStatus.Initializing,
                    -> restoreStoredSession(auth)
                }
            }
        } catch (error: SessionBoundaryChangedException) {
            throw sessionChanged()
        }
        return if (hasRestoredSession) getAccount() else null
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
                commitLogin(ownerId)
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
        return acceptResponse(response, ::parseBootstrap)
    }

    suspend fun getAccount(): AccountAccess {
        val requestId = UUID.randomUUID().toString()
        val response = authorizedRequest { accessToken ->
            val httpResponse = requireHttpClient().get(
                "${requireConfig().url}$FUNCTIONS_PATH/me",
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(API_KEY_HEADER, requireConfig().key)
                header(REQUEST_ID_HEADER, requestId)
            }
            RawResponse(httpResponse.status, httpResponse.bodyAsText())
        }
        return acceptResponse(response, ::parseMe)
    }

    suspend fun libraryRequest(
        path: String,
        method: String = "GET",
        body: String? = null,
        requestId: String? = null,
    ): JsonObject {
        val safePath = validateLibraryPath(path)
        val httpMethod = parseLibraryMethod(method)
        val safeRequestId = requestId?.let(::validateRequestId)
        if (httpMethod != HttpMethod.Get && safeRequestId == null) {
            throw AccountClientException(
                message = "변경 요청에는 UUID 요청 ID가 필요해요.",
                retryable = false,
                code = INVALID_REQUEST_ID,
            )
        }
        body?.let { validateJsonObject(it) }

        val response = authorizedRequest { accessToken ->
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
            RawResponse(httpResponse.status, httpResponse.bodyAsText())
        }
        return acceptResponse(response) { rawResponse ->
            if (!rawResponse.status.isSuccess()) throw apiException(rawResponse)
            try {
                json.decodeFromString<JsonObject>(rawResponse.body)
            } catch (error: Exception) {
                throw AccountClientException(
                    message = "서버 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
                    retryable = true,
                    cause = error,
                )
            }
        }
    }

    suspend fun signOut() {
        val auth = requireSupabase().auth
        var failure: Exception? = null
        withContext(NonCancellable) {
            sessionBoundary.locked {
                try {
                    auth.signOut()
                } catch (error: Exception) {
                    failure = error
                }
                try {
                    auth.clearSession()
                } catch (error: Exception) {
                    if (failure == null) failure = error
                }
                clearCurrent()
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (error: Exception) {
                    if (failure == null) failure = error
                }
            }
        }

        failure?.let { error ->
            if (error is CancellationException) throw error
            throw AccountClientException(
                message = "이 기기의 로그인 정보는 지웠지만 서버 또는 Google 로그인 상태를 정리하지 못했어요.",
                retryable = false,
                cause = error,
            )
        }
    }

    private suspend fun authorizedRequest(
        request: suspend (accessToken: String) -> RawResponse,
    ): BoundResponse {
        val auth = requireSupabase().auth
        val initialCredential = captureCredential(auth)
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
                        commitLogin(currentOwnerId)
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

    private suspend fun captureCredential(auth: Auth): BoundCredential = try {
        sessionBoundary.locked {
            val origin = currentOrigin() ?: throw AccountAuthenticationRequiredException()
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
        val origin = beginRestore(storedOwnerId)
        try {
            withTimeout(AUTH_SESSION_OPERATION_TIMEOUT_MILLIS) {
                auth.importSession(storedSession, autoRefresh = false)
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

    private fun parseBootstrap(response: RawResponse): AccountAccess {
        if (!response.status.isSuccess()) return parseApiError(response)
        val body = decodeOrThrow<BootstrapResponse>(response.body)
        return accountAccess(
            state = body.profile.state,
            summary = AccountSummary(
                profileId = body.profile.id,
                limits = body.limits.toAccountLimits(),
                usage = body.usage.toAccountUsage(),
            ),
        )
    }

    private fun parseMe(response: RawResponse): AccountAccess {
        if (!response.status.isSuccess()) return parseApiError(response)
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
        )
    }

    private fun parseApiError(response: RawResponse): AccountAccess {
        val envelope = decodeApiError(response.body)
        return when {
            response.status == HttpStatusCode.Forbidden &&
                envelope?.error?.code == BETA_ACCESS_REQUIRED -> AccountAccess.PendingApproval
            response.status == HttpStatusCode.Forbidden &&
                envelope?.error?.code == ACCOUNT_DELETING -> AccountAccess.Deleting
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
    )

    private fun decodeApiError(body: String): ApiErrorEnvelope? = try {
        json.decodeFromString(body)
    } catch (_: Exception) {
        null
    }

    private fun validateJsonObject(body: String) {
        try {
            json.decodeFromString<JsonObject>(body)
        } catch (error: Exception) {
            throw AccountClientException(
                message = "요청 본문은 JSON 객체여야 해요.",
                retryable = false,
                code = INVALID_REQUEST_BODY,
                cause = error,
            )
        }
    }

    private fun accountAccess(state: String, summary: AccountSummary?): AccountAccess = when (state) {
        PROFILE_ACTIVE -> AccountAccess.Active(
            summary ?: throw AccountClientException(
                message = "계정 응답에 사용량 정보가 없어요.",
                retryable = true,
            ),
        )
        PROFILE_PENDING -> AccountAccess.PendingApproval
        PROFILE_DELETING -> AccountAccess.Deleting
        else -> throw AccountClientException(
            message = "서버에서 알 수 없는 계정 상태를 받았어요.",
            retryable = false,
        )
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
        const val EMPTY_JSON_OBJECT = "{}"
        const val BETA_ACCESS_REQUIRED = "BETA_ACCESS_REQUIRED"
        const val ACCOUNT_DELETING = "ACCOUNT_DELETING"
        const val INVALID_REQUEST_ID = "INVALID_REQUEST_ID"
        const val INVALID_REQUEST_BODY = "INVALID_REQUEST_BODY"
        const val PROFILE_ACTIVE = "active"
        const val PROFILE_PENDING = "pending_approval"
        const val PROFILE_DELETING = "deleting"
        const val MAX_ERROR_MESSAGE_LENGTH = 300
        const val AUTH_REQUEST_TIMEOUT_SECONDS = 15
        const val AUTH_SESSION_OPERATION_TIMEOUT_MILLIS = 16_000L
        const val API_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val API_CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

sealed interface AccountAccess {
    data class Active(val summary: AccountSummary) : AccountAccess
    data object PendingApproval : AccountAccess
    data object Deleting : AccountAccess
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
        uri.port == 54321
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

private fun validateLibraryPath(path: String): String {
    val uri = try {
        URI(path)
    } catch (error: Exception) {
        throw invalidLibraryPath(error)
    }
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null) {
        throw invalidLibraryPath()
    }
    val rawPath = uri.rawPath ?: throw invalidLibraryPath()
    if (rawPath == "/items") return path

    val itemId = rawPath.removePrefix("/items/")
    if (itemId == rawPath || itemId.contains('/') || !itemId.isCanonicalUuid()) {
        throw invalidLibraryPath()
    }
    return path
}

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
    val body: String,
)

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
