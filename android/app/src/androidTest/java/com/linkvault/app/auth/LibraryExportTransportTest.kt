package com.linkvault.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExportTransportTest {
    @Test
    fun scopedStreamingDeliversBytesBeforeEofAndNeverOwnsDestination() = runBlocking {
        withTimeout(10_000) {
            coroutineScope {
                val source = ByteChannel(autoFlush = true)
                val prefixWritten = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val target = CountingOutput { written ->
                    if (written == LIBRARY_EXPORT_BUFFER_BYTES.toLong()) prefixWritten.complete(Unit)
                }
                val fixture = fixture { Reply(body = source) }
                val producer = launch {
                    try {
                        val chunk = ByteArray(LIBRARY_EXPORT_BUFFER_BYTES / 4) { ' '.code.toByte() }
                        repeat(4) {
                            source.writeFully(chunk)
                            source.flush()
                        }
                        finish.await()
                        source.writeFully("{\"items\":[]}".toByteArray())
                        source.close()
                    } catch (error: Exception) {
                        source.cancel(error)
                    }
                }
                try {
                    val transfer = async { fixture.export(target) }
                    prefixWritten.await()
                    assertFalse(transfer.isCompleted)
                    assertEquals(LIBRARY_EXPORT_BUFFER_BYTES.toLong(), target.bytes)
                    finish.complete(Unit)
                    assertEquals(LIBRARY_EXPORT_BUFFER_BYTES + 12L, transfer.await())
                    assertFalse(target.closed)
                    assertFalse(target.flushed)
                    assertEquals(1, fixture.exports)
                } finally {
                    finish.complete(Unit)
                    producer.cancelAndJoin()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun generatedThousandRowResponseStreamsWithoutWholePayloadArraysAndPreservesHash() = runBlocking {
        withTimeout(60_000) {
            coroutineScope {
                val source = ByteChannel(autoFlush = true)
                val expected = MessageDigest.getInstance("SHA-256")
                val actual = MessageDigest.getInstance("SHA-256")
                val fixture = fixture { Reply(body = source) }
                var generated = 0L
                val producer = launch(Dispatchers.IO) {
                    try {
                        val output = object : OutputStream() {
                            override fun write(value: Int) = runBlocking { source.writeFully(byteArrayOf(value.toByte())) }
                            override fun write(buffer: ByteArray, offset: Int, length: Int) = runBlocking {
                                source.writeFully(buffer, offset, offset + length)
                            }
                        }
                        generated = generateLibraryExport(DigestOutputStream(output, expected))
                        source.close()
                    } catch (error: Exception) {
                        source.cancel(error)
                    }
                }
                val target = CountingOutput()
                try {
                    val copied = fixture.export(DigestOutputStream(target, actual))
                    producer.join()
                    assertTrue(copied > 10_000_000L)
                    assertEquals(generated, copied)
                    assertEquals(copied, target.bytes)
                    assertTrue(target.maxWrite <= LIBRARY_EXPORT_BUFFER_BYTES)
                    assertArrayEquals(expected.digest(), actual.digest())
                    assertFalse(target.closed)
                    assertEquals(1, fixture.exports)
                    assertEquals(0, fixture.refreshes)
                } finally {
                    producer.cancelAndJoin()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun rejectsEveryNon200StatusBeforeWritingAndBoundsErrorBody() = runBlocking {
        listOf(201, 204, 206, 302, 400, 403, 404, 429, 500, 503).forEach { status ->
            val source = ByteChannel(autoFlush = true)
            val fixture = fixture {
                Reply(
                    status = HttpStatusCode.fromValue(status),
                    body = source,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append(HttpHeaders.ContentLength, (64 * 1024 + 1).toString())
                    },
                )
            }
            val target = CountingOutput()
            try {
                expectFailure<AccountClientException> { fixture.export(target) }
                assertEquals(0L, target.bytes)
                assertTrue(source.isClosedForRead)
                assertEquals(1, fixture.exports)
                assertEquals(0, fixture.refreshes)
            } finally {
                source.cancel(null)
                fixture.close()
            }
        }
    }

    @Test
    fun rejectsMissingNonJsonWildcardAndNonUtf8ContentTypesBeforeWriting() = runBlocking {
        listOf(null, "text/html", "application/jsonp", "application/*", "application/json; charset=utf-16")
            .forEach { type ->
                val source = ByteChannel(autoFlush = true)
                val fixture = fixture {
                    Reply(body = source, headers = Headers.build { if (type != null) append(HttpHeaders.ContentType, type) })
                }
                val target = CountingOutput()
                try {
                    val error = expectFailure<AccountClientException> { fixture.export(target) }
                    assertEquals("INVALID_EXPORT_RESPONSE", error.code)
                    assertEquals(0L, target.bytes)
                    assertTrue(source.isClosedForRead)
                    assertEquals(1, fixture.exports)
                } finally {
                    source.cancel(null)
                    fixture.close()
                }
            }
    }

    @Test
    fun unauthorizedBodyIsCanceledAndExactlyOneRefreshOccursBeforeAnyDestinationWrite() = runBlocking {
        val rejectedBody = ByteChannel(autoFlush = true)
        val target = CountingOutput()
        lateinit var fixture: Fixture
        fixture = fixture { request ->
            if (request.url.encodedPath == "/auth/v1/token") {
                assertEquals(0L, target.bytes)
                assertTrue(rejectedBody.isClosedForRead)
                Reply.text(Json.encodeToString(session(token = "refreshed-token")))
            } else if (fixture.exports == 1) {
                assertEquals("Bearer initial-token", request.headers[HttpHeaders.Authorization])
                Reply(status = HttpStatusCode.Unauthorized, body = rejectedBody)
            } else {
                assertEquals("Bearer refreshed-token", request.headers[HttpHeaders.Authorization])
                Reply.text("{\"items\":[]}")
            }
        }
        try {
            assertEquals(12L, fixture.export(target))
            assertEquals(12L, target.bytes)
            assertEquals(2, fixture.exports)
            assertEquals(1, fixture.refreshes)
            assertFalse(target.closed)
        } finally {
            rejectedBody.cancel(null)
            fixture.close()
        }
    }

    @Test
    fun secondUnauthorizedFailsAuthenticationWithoutAnotherRefreshOrAnyWrite() = runBlocking {
        val target = CountingOutput()
        val fixture = fixture { request ->
            if (request.url.encodedPath == "/auth/v1/token") {
                Reply.text(Json.encodeToString(session(token = "refreshed-token")))
            } else {
                Reply(status = HttpStatusCode.Unauthorized)
            }
        }
        try {
            expectFailure<AccountAuthenticationRequiredException> { fixture.export(target) }
            assertEquals(0L, target.bytes)
            assertEquals(2, fixture.exports)
            assertEquals(1, fixture.refreshes)
            assertEquals(null, fixture.client.sessionUserId())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun midstreamConnectionFailureNeverRetriesOrRefreshes() = runBlocking {
        withTimeout(10_000) {
            coroutineScope {
                val source = ByteChannel(autoFlush = true)
                val wrote = CompletableDeferred<Unit>()
                val target = CountingOutput { wrote.complete(Unit) }
                val fixture = fixture { Reply(body = source) }
                val primary = IOException("connection interrupted")
                val producer = launch {
                    source.writeFully("{\"items\":[".toByteArray())
                    source.flush()
                    wrote.await()
                    source.cancel(primary)
                }
                try {
                    val error = expectFailure<AccountClientException> { fixture.export(target) }
                    assertTrue(error.retryable)
                    assertTrue(target.bytes > 0)
                    assertEquals(1, fixture.exports)
                    assertEquals(0, fixture.refreshes)
                    assertFalse(target.closed)
                } finally {
                    producer.cancelAndJoin()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun bufferedValidJsonWithFailedEofNeverBecomesSuccessfulExport() = runBlocking {
        val payload = "{\"items\":[]}".toByteArray()
        val buffered = ByteReadChannel(payload)
        val primary = IOException("response failed at EOF")
        val source = object : ByteReadChannel by buffered {
            override val closedCause: Throwable?
                get() = if (buffered.isClosedForRead) primary else null
        }
        val fixture = fixture { Reply(body = source) }
        val target = CountingOutput()
        try {
            val error = expectFailure<AccountClientException> { fixture.export(target) }
            assertTrue(error.retryable)
            assertEquals(payload.size.toLong(), target.bytes)
            assertEquals(1, fixture.exports)
            assertEquals(0, fixture.refreshes)
            assertFalse(target.closed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun destinationWriteFailureRemainsDistinctFromConnectionFailureWithoutRetry() = runBlocking {
        val primary = IOException("private destination path")
        val fixture = fixture { Reply.text("{\"items\":[]}") }
        val target = CountingOutput { throw primary }
        try {
            val error = expectFailure<LibraryExportDestinationException> { fixture.export(target) }
            assertSame(primary, error.cause)
            assertFalse(error.message.orEmpty().contains("private destination path"))
            assertEquals(1, fixture.exports)
            assertEquals(0, fixture.refreshes)
            assertFalse(target.closed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleOwnerAndSameOwnerGenerationBeforeRequestDoNotSendHttp() = runBlocking {
        listOf("other-owner" to 1L, OWNER to 2L).forEach { (owner, generation) ->
            val fixture = fixture { throw AssertionError("Stale request sent HTTP") }
            try {
                val error = expectFailure<AccountClientException> {
                    fixture.client.exportLibrary(owner, generation, CountingOutput())
                }
                assertEquals("SESSION_CHANGED", error.code)
                assertEquals(0, fixture.exports)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun ownerAndGenerationFencesRejectChangesDuringLastWriteWithoutSuccess() = runBlocking {
        listOf(OWNER, "other-owner").forEach { changedOwner ->
            val fixture = fixture { Reply.text("{\"items\":[]}") }
            val target = CountingOutput {
                // One tiny response makes this the final write, so EOF must still be fenced.
                runBlocking { fixture.boundary.locked { commitLogin(changedOwner) } }
            }
            try {
                val error = expectFailure<AccountClientException> { fixture.export(target) }
                assertEquals("SESSION_CHANGED", error.code)
                assertEquals(12L, target.bytes)
                assertEquals(1, fixture.exports)
                assertEquals(0, fixture.refreshes)
                assertFalse(target.closed)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun eofFenceRejectsOwnerOrGenerationChangeDuringFinalChannelRead() = runBlocking {
        listOf(
            AccountSessionState(ownerId = "different-owner", generation = 1),
            AccountSessionState(ownerId = OWNER, generation = 2),
        ).forEach { changed ->
            var session = AccountSessionState(ownerId = OWNER, generation = 1)
            val source = object : ByteReadChannel by ByteReadChannel(byteArrayOf()) {
                override val isClosedForRead: Boolean
                    get() { session = changed; return true }
            }
            val target = CountingOutput()
            val error = expectFailure<AccountClientException> {
                copyLibraryExportChannel(source, target, null) {
                    requireLibraryExportSession(session, OWNER, 1)
                }
            }
            assertEquals("SESSION_CHANGED", error.code)
            assertEquals(0L, target.bytes)
        }
    }

    @Test
    fun exact64MiBBoundPassesAndCapPlusOneFailsWithoutLargePreallocation() = runBlocking {
        withTimeout(60_000) {
            listOf(LIBRARY_EXPORT_MAX_BYTES, LIBRARY_EXPORT_MAX_BYTES + 1).forEach { length ->
                coroutineScope {
                    val source = ByteChannel(autoFlush = true)
                    val fixture = fixture { Reply(body = source) }
                    val producer = launch(Dispatchers.IO) {
                        val buffer = ByteArray(LIBRARY_EXPORT_BUFFER_BYTES) { ' '.code.toByte() }
                        var remaining = length
                        try {
                            while (remaining > 0) {
                                val count = minOf(remaining, buffer.size.toLong()).toInt()
                                source.writeFully(buffer, 0, count)
                                remaining -= count
                            }
                            source.close()
                        } catch (error: Exception) {
                            source.cancel(error)
                        }
                    }
                    val target = CountingOutput()
                    try {
                        if (length == LIBRARY_EXPORT_MAX_BYTES) {
                            assertEquals(length, fixture.export(target))
                            assertEquals(length, target.bytes)
                        } else {
                            val error = expectFailure<AccountClientException> { fixture.export(target) }
                            assertEquals("EXPORT_TOO_LARGE", error.code)
                            assertTrue(target.bytes <= LIBRARY_EXPORT_MAX_BYTES)
                        }
                        assertTrue(target.maxWrite <= LIBRARY_EXPORT_BUFFER_BYTES)
                        assertEquals(1, fixture.exports)
                        assertEquals(0, fixture.refreshes)
                    } finally {
                        producer.cancelAndJoin()
                        fixture.close()
                    }
                }
            }
        }
    }

    @Test
    fun oversizedDeclaredLengthFailsBeforeWritingAndShortBodyCannotSucceed() = runBlocking {
        listOf(LIBRARY_EXPORT_MAX_BYTES + 1, 100L).forEach { declared ->
            val source = ByteReadChannel("{\"items\":[]}".toByteArray())
            val fixture = fixture {
                Reply(body = source, headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                    append(HttpHeaders.ContentLength, declared.toString())
                })
            }
            val target = CountingOutput()
            try {
                val error = expectFailure<AccountClientException> { fixture.export(target) }
                assertEquals(if (declared > LIBRARY_EXPORT_MAX_BYTES) "EXPORT_TOO_LARGE" else "INVALID_EXPORT_RESPONSE", error.code)
                assertEquals(if (declared > LIBRARY_EXPORT_MAX_BYTES) 0L else 12L, target.bytes)
                assertEquals(1, fixture.exports)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun cancellationDuringBodyReadPropagatesAndCancelsScopedResponse() = runBlocking {
        withTimeout(10_000) {
            coroutineScope {
                val source = ByteChannel(autoFlush = true)
                val wrote = CompletableDeferred<Unit>()
                val fixture = fixture { Reply(body = source) }
                val target = CountingOutput { wrote.complete(Unit) }
                source.writeFully(byteArrayOf('{'.code.toByte()))
                try {
                    val transfer = async { fixture.export(target) }
                    wrote.await()
                    transfer.cancel()
                    expectFailure<CancellationException> { transfer.await() }
                    assertTrue(source.isClosedForRead)
                    assertEquals(1, fixture.exports)
                    assertEquals(0, fixture.refreshes)
                    assertFalse(target.closed)
                } finally {
                    source.cancel(null)
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun exportDeadlineOverridesOnlyTotalTimeoutAndKeepsFiniteConnectAndIdleTimeouts() = runBlocking {
        var captured: HttpRequestData? = null
        val fixture = fixture { captured = it; Reply.text("{\"items\":[]}") }
        try {
            fixture.export(CountingOutput())
            val request = checkNotNull(captured)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/functions/v1/library-api/v1/items/export", request.url.encodedPath)
            assertTrue(request.url.parameters.isEmpty())
            assertEquals("fixture-public-key", request.headers["apikey"])
            assertNotNull(request.headers["X-Request-Id"])
            val exportTimeout = checkNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
            assertEquals(300_000L, exportTimeout.requestTimeoutMillis)
            assertEquals(10_000L, exportTimeout.connectTimeoutMillis)
            assertEquals(15_000L, exportTimeout.socketTimeoutMillis)
            fixture.client.libraryRequest(OWNER, "/categories")
            val normalTimeout = checkNotNull(checkNotNull(captured).getCapabilityOrNull(HttpTimeoutCapability))
            assertEquals(15_000L, normalTimeout.requestTimeoutMillis)
        } finally {
            fixture.close()
        }
    }

    private suspend fun fixture(handler: suspend (HttpRequestData) -> Reply): Fixture = Fixture.create(handler)

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Any?): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class CountingOutput(private val onWrite: (Long) -> Unit = {}) : OutputStream() {
        var bytes = 0L
        var maxWrite = 0
        var closed = false
        var flushed = false
        override fun write(value: Int) { throw AssertionError("Expected fixed-buffer streaming") }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            assertTrue(length in 1..LIBRARY_EXPORT_BUFFER_BYTES)
            bytes += length
            maxWrite = maxOf(maxWrite, length)
            onWrite(bytes)
        }
        override fun close() { closed = true }
        override fun flush() { flushed = true }
    }

    private data class Reply(
        val status: HttpStatusCode = HttpStatusCode.OK,
        val body: ByteReadChannel = ByteReadChannel(byteArrayOf()),
        val headers: Headers = Headers.build { append(HttpHeaders.ContentType, "application/json; charset=utf-8") },
    ) {
        companion object {
            fun text(body: String) = Reply(body = ByteReadChannel(body.toByteArray(Charsets.UTF_8)))
        }
    }

    private class Fixture(
        val client: AccountClient,
        val boundary: SessionBoundary,
        private val authClient: SupabaseClient,
        private val transport: HttpClient,
        private val apiEngine: FixtureEngine,
        private val authEngine: FixtureEngine,
    ) {
        var exports = 0
        var refreshes = 0
        suspend fun export(output: OutputStream) = client.exportLibrary(OWNER, 1L, output)
        suspend fun close() {
            transport.close()
            authClient.close()
            apiEngine.close()
            authEngine.close()
        }

        companion object {
            suspend fun create(handler: suspend (HttpRequestData) -> Reply): Fixture {
                val client = AccountClient(
                    context = ApplicationProvider.getApplicationContext<Context>(),
                    url = ORIGIN,
                    key = "fixture-public-key",
                    googleWebClientId = "fixture-client",
                    debug = true,
                    beforeSignOut = {},
                    localDataOwner = { OWNER },
                    onSessionOwner = {},
                )
                val originalSupabase = field(client, "supabase").get(client) as SupabaseClient
                val originalHttp = field(client, "httpClient").get(client) as HttpClient
                originalSupabase.close()
                originalHttp.close()
                lateinit var fixture: Fixture
                val apiEngine = FixtureEngine { request ->
                    if (request.url.encodedPath.endsWith("/items/export")) fixture.exports++
                    handler(request)
                }
                val authEngine = FixtureEngine { request -> fixture.refreshes++; handler(request) }
                val transport = HttpClient(apiEngine) {
                    followRedirects = false
                    install(HttpTimeout) {
                        requestTimeoutMillis = 15_000
                        connectTimeoutMillis = 10_000
                        socketTimeoutMillis = 15_000
                    }
                }
                val authClient = createSupabaseClient(ORIGIN, "fixture-public-key") {
                    defaultLogLevel = LogLevel.NONE
                    httpEngine = authEngine
                    install(Auth) {
                        alwaysAutoRefresh = false
                        autoLoadFromStorage = false
                        enableLifecycleCallbacks = false
                        sessionManager = MemorySessionManager()
                    }
                }
                authClient.auth.awaitInitialization()
                authClient.auth.importSession(session(), autoRefresh = false)
                field(client, "supabase").set(client, authClient)
                field(client, "httpClient").set(client, transport)
                val boundary = field(client, "sessionBoundary").get(client) as SessionBoundary
                boundary.locked { commitLogin(OWNER); markInitialized() }
                fixture = Fixture(client, boundary, authClient, transport, apiEngine, authEngine)
                return fixture
            }

            private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply {
                isAccessible = true
            }
        }
    }

    private class FixtureEngine(private val handler: suspend (HttpRequestData) -> Reply) :
        HttpClientEngineBase("library-export-test") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities = setOf(HttpTimeoutCapability)

        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val reply = handler(data)
            return HttpResponseData(
                reply.status,
                GMTDate(),
                reply.headers,
                HttpProtocolVersion.HTTP_1_1,
                reply.body,
                callContext(),
            )
        }
    }

    private companion object {
        const val OWNER = "export-owner"
        const val ORIGIN = "https://export-fixture.invalid"
        fun session(token: String = "initial-token") = UserSession(
            accessToken = token,
            refreshToken = "fixture-refresh-token",
            expiresIn = 3_600,
            tokenType = "bearer",
            user = UserInfo(aud = "authenticated", id = OWNER),
        )
    }
}
