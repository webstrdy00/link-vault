package com.linkvault.app.auth

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FilterReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal data class LibraryExportRequest(
    val requestId: String,
    val ownerId: String,
    val generation: Long,
    val fileName: String,
)

internal class LibraryExportDestinationException(cause: Throwable) :
    IOException("내보내기 파일을 저장하지 못했어요. 저장 위치와 여유 공간을 확인해 주세요.", cause)

internal class LibraryExportAction internal constructor(
    private val stagingDirectory: File,
    private val sessions: StateFlow<AccountSessionState>,
    private val download: suspend (String, Long, OutputStream) -> Long,
    private val openDestination: suspend (Uri) -> OutputStream?,
    private val deleteDestination: suspend (Uri) -> Boolean,
    private val deleteStagingFile: (File) -> Boolean = { it.delete() },
) {
    suspend fun export(
        request: LibraryExportRequest,
        destination: Uri,
        onCleanupFailure: (Uri) -> Unit,
    ): Long {
        try {
            val copied = withContext(Dispatchers.IO) {
                val operationContext = currentCoroutineContext()
                val checkSession = {
                    operationContext.ensureActive()
                    requireLibraryExportSession(sessions.value, request.ownerId, request.generation)
                }
                var staging: File? = null
                var failure: Throwable? = null
                try {
                    checkSession()
                    if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
                        throw IOException("내보내기 임시 파일을 만들지 못했어요.")
                    }
                    val file = File.createTempFile("export-", ".json", stagingDirectory)
                    staging = file
                    val downloaded = file.outputStream().use { output ->
                        download(request.ownerId, request.generation, output).also {
                            checkSession()
                            output.flush()
                        }
                    }
                    checkSession()
                    if (file.length() > LIBRARY_EXPORT_MAX_BYTES) throw libraryExportTooLarge()
                    if (downloaded != file.length()) throw invalidLibraryExportResponse()
                    validateLibraryExport(file, checkSession)
                    checkSession()

                    val output = try {
                        openDestination(destination)
                            ?: throw IOException("저장 위치를 열지 못했어요.")
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        throw LibraryExportDestinationException(error)
                    }
                    val written = try {
                        output.use { target ->
                            checkSession()
                            val total = file.inputStream().use { source ->
                                val buffer = ByteArray(LIBRARY_EXPORT_BUFFER_BYTES)
                                var total = 0L
                                while (true) {
                                    checkSession()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    checkSession()
                                    target.write(buffer, 0, count)
                                    total += count
                                }
                                total
                            }
                            checkSession()
                            target.flush()
                            checkSession()
                            total
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException || error is AccountClientException) throw error
                        throw LibraryExportDestinationException(error)
                    }
                    // Provider close can fail, cancel, or outlive a changed session.
                    checkSession()
                    if (written != downloaded) throw invalidLibraryExportResponse()
                    written
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    withContext(NonCancellable) {
                        try {
                            staging?.let { file ->
                                if (!deleteStagingFile(file) && file.exists()) {
                                    throw IOException("내보내기 임시 파일을 지우지 못했어요.")
                                }
                            }
                        } catch (cleanupError: Exception) {
                            val safeError = IOException("내보내기 임시 파일을 지우지 못했어요.", cleanupError)
                            val original = failure
                            if (original == null) throw safeError
                            original.addSuppressed(safeError)
                            Log.w("LibraryExport", "내보내기 임시 파일을 지우지 못했어요.")
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            requireLibraryExportSession(sessions.value, request.ownerId, request.generation)
            return copied
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!discard(destination)) {
                    try {
                        onCleanupFailure(destination)
                    } catch (cleanupWarningError: Exception) {
                        if (cleanupWarningError !== error) error.addSuppressed(cleanupWarningError)
                    }
                }
            }
            throw error
        }
    }

    suspend fun discard(destination: Uri): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            deleteDestination(destination)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun create(context: Context, client: AccountClient): LibraryExportAction {
            val app = context.applicationContext
            val resolver = app.contentResolver
            return LibraryExportAction(
                stagingDirectory = stagingDirectory(app),
                sessions = client.sessionState,
                download = client::exportLibrary,
                openDestination = { resolver.openOutputStream(it, "wt") },
                deleteDestination = { DocumentsContract.deleteDocument(resolver, it) },
            )
        }

        fun cleanupOrphanedStagingFiles(context: Context): Boolean = try {
            val directory = stagingDirectory(context.applicationContext)
            if (!directory.exists()) {
                true
            } else {
                val files = directory.listFiles()
                if (files == null) {
                    false
                } else {
                    var cleaned = true
                    files.forEach { if (!it.delete() && it.exists()) cleaned = false }
                    cleaned
                }
            }
        } catch (_: Exception) {
            false
        }

        private fun stagingDirectory(context: Context) = File(context.cacheDir, "library-export")
    }
}

internal const val LIBRARY_EXPORT_MAX_BYTES = 64L * 1024 * 1024
internal const val LIBRARY_EXPORT_BUFFER_BYTES = 32 * 1024
internal const val LIBRARY_EXPORT_TIMEOUT_MILLIS = 300_000L

internal fun requireLibraryExportSession(
    session: AccountSessionState,
    ownerId: String,
    generation: Long,
) {
    if (session.ownerId != ownerId || session.generation != generation) {
        throw AccountClientException(
            message = "로그인 계정이 변경되어 내보내기를 중단했어요.",
            retryable = false,
            code = "SESSION_CHANGED",
        )
    }
}

internal fun invalidLibraryExportResponse(cause: Throwable? = null) = AccountClientException(
    message = "내보내기 응답이 올바르지 않아요. 다시 시도해 주세요.",
    retryable = true,
    code = "INVALID_EXPORT_RESPONSE",
    cause = cause,
)

private fun libraryExportTooLarge() = AccountClientException(
    message = "내보내기 파일이 허용 크기를 초과했어요.",
    retryable = false,
    code = "EXPORT_TOO_LARGE",
)

internal fun isLibraryExportJsonContentType(value: String?): Boolean = try {
    val type = ContentType.parse(value.orEmpty())
    type.contentType.equals("application", ignoreCase = true) &&
        type.contentSubtype.equals("json", ignoreCase = true) &&
        type.parameters.all { parameter ->
            !parameter.name.equals("charset", ignoreCase = true) ||
                parameter.value.equals("utf-8", ignoreCase = true)
        }
} catch (_: Exception) {
    false
}

internal suspend fun copyLibraryExportChannel(
    channel: ByteReadChannel,
    destination: OutputStream,
    declaredBytes: Long?,
    checkSession: () -> Unit,
): Long {
    if (declaredBytes != null && declaredBytes > LIBRARY_EXPORT_MAX_BYTES) {
        channel.cancel(null)
        throw libraryExportTooLarge()
    }
    val buffer = ByteArray(LIBRARY_EXPORT_BUFFER_BYTES)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        checkSession()
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            if (!channel.awaitContent()) break
            continue
        }
        currentCoroutineContext().ensureActive()
        checkSession()
        if (total + read > LIBRARY_EXPORT_MAX_BYTES) throw libraryExportTooLarge()
        try {
            destination.write(buffer, 0, read)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw LibraryExportDestinationException(error)
        }
        total += read
    }
    currentCoroutineContext().ensureActive()
    checkSession()
    // Ktor readAvailable may report -1 for a channel closed with a failure.
    channel.closedCause?.let { throw it }
    if (declaredBytes != null && declaredBytes != total) throw invalidLibraryExportResponse()
    return total
}

internal fun validateLibraryExport(file: File, checkSession: () -> Unit) {
    try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        file.inputStream().use { input ->
            JsonReader(ExportJsonReader(InputStreamReader(input, decoder), checkSession)).use { json ->
                json.isLenient = false
                checkSession()
                json.beginObject()
                if (!json.hasNext() || json.nextName() != "items") throw invalidLibraryExportResponse()
                json.beginArray()
                while (json.hasNext()) {
                    checkSession()
                    json.beginObject()
                    var fields = 0
                    while (json.hasNext()) {
                        checkSession()
                        val field = when (json.nextName()) {
                            "url" -> 1
                            "title" -> 2
                            "user_title" -> 4
                            "note" -> 8
                            "source" -> 16
                            "categories" -> 32
                            "saved_at" -> 64
                            else -> throw invalidLibraryExportResponse()
                        }
                        if (fields and field != 0) throw invalidLibraryExportResponse()
                        fields = fields or field
                        if (field == 32) {
                            json.beginArray()
                            while (json.hasNext()) {
                                checkSession()
                                json.readExportString()
                            }
                            json.endArray()
                        } else if ((field == 4 || field == 8) && json.peek() == JsonToken.NULL) {
                            json.nextNull()
                        } else {
                            json.readExportString()
                        }
                    }
                    json.endObject()
                    if (fields != 127) throw invalidLibraryExportResponse()
                }
                json.endArray()
                if (json.hasNext()) throw invalidLibraryExportResponse()
                json.endObject()
                if (json.peek() != JsonToken.END_DOCUMENT) throw invalidLibraryExportResponse()
                checkSession()
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException || error is AccountClientException) throw error
        throw invalidLibraryExportResponse(error)
    }
}

private fun JsonReader.readExportString() {
    // JsonReader.nextString alone also coerces JSON numbers into strings.
    if (peek() != JsonToken.STRING) throw invalidLibraryExportResponse()
    nextString()
}

// Android JsonReader's strict mode still accepts some invalid quoted-string escapes and
// literal control characters. Check those lexically while it consumes the UTF-8 stream.
private class ExportJsonReader(reader: Reader, private val checkSession: () -> Unit) : FilterReader(reader) {
    private var quoted = false
    private var escaped = false
    private var hexRemaining = 0

    override fun read(): Int {
        checkSession()
        return super.read().also { if (it >= 0) checkCharacter(it.toChar()) }
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        checkSession()
        val count = super.read(buffer, offset, length)
        for (index in offset until offset + maxOf(count, 0)) checkCharacter(buffer[index])
        return count
    }

    private fun checkCharacter(character: Char) {
        if (!quoted) {
            if (character == '"') quoted = true
        } else if (hexRemaining > 0) {
            if (character !in '0'..'9' && character !in 'a'..'f' && character !in 'A'..'F') {
                throw invalidLibraryExportResponse()
            }
            hexRemaining--
        } else if (escaped) {
            escaped = false
            when (character) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                'u' -> hexRemaining = 4
                else -> throw invalidLibraryExportResponse()
            }
        } else {
            when (character) {
                '"' -> quoted = false
                '\\' -> escaped = true
                else -> if (character < ' ') throw invalidLibraryExportResponse()
            }
        }
    }
}
