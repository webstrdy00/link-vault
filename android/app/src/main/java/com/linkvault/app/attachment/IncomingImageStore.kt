package com.linkvault.app.attachment

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class IncomingImageStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver = context.contentResolver
    private val incomingDirectory = File(applicationContext.filesDir, DIRECTORY_NAME).canonicalFile
    private val metadataFile = AtomicFile(File(applicationContext.filesDir, METADATA_FILE_NAME))
    private val mutex = Mutex()
    private val boundaryLock = Any()
    private val boundaryGeneration = AtomicLong()
    private val mutableDraft = MutableStateFlow(readPersistedDraft(clock()))

    val draft: StateFlow<IncomingImageDraft?> = mutableDraft.asStateFlow()

    fun generation(): Long = boundaryGeneration.get()

    suspend fun capture(
        uri: Uri,
        expectedGeneration: Long,
    ): IncomingImageDraft = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        requireCurrentGeneration(expectedGeneration)
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.INVALID_URI)
        }
        val mimeType = readSupportedMimeType(uri)
        val extension = extensionFor(mimeType)

        mutex.withLock {
            coroutineContext.ensureActive()
            requireCurrentGeneration(expectedGeneration)
            ensureIncomingDirectory()

            val id = UUID.randomUUID().toString()
            val temporaryFile = File(incomingDirectory, ".$id.tmp")
            val publishedFile = File(incomingDirectory, "$id.$extension")
            var published = false
            try {
                val byteCount = copyBounded(uri, temporaryFile)
                coroutineContext.ensureActive()
                requireCurrentGeneration(expectedGeneration)
                if (!temporaryFile.renameTo(publishedFile)) {
                    throw IncomingImageCaptureException(
                        IncomingImageCaptureFailure.STORAGE_UNAVAILABLE,
                    )
                }
                published = true
                coroutineContext.ensureActive()

                val createdAt = clock()
                val expiresAt = createdAt.checkedAdd(EXPIRATION_MILLIS)
                    ?: throw IncomingImageCaptureException(
                        IncomingImageCaptureFailure.STORAGE_UNAVAILABLE,
                    )
                val captured = IncomingImageDraft(
                    id = id,
                    uri = Uri.fromFile(publishedFile),
                    mimeType = mimeType,
                    byteCount = byteCount,
                    createdAtEpochMillis = createdAt,
                    expiresAtEpochMillis = expiresAt,
                )
                val previous = mutableDraft.value
                synchronized(boundaryLock) {
                    requireCurrentGeneration(expectedGeneration)
                    writeMetadata(captured, publishedFile.name)
                    mutableDraft.value = captured
                }
                previous?.let(::deleteDraftFile)
                captured
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (temporaryFile.exists()) temporaryFile.delete()
                if (published && mutableDraft.value?.id != id && publishedFile.exists()) {
                    publishedFile.delete()
                }
            }
        }
    }

    suspend fun cleanupExpired(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableDraft.value
            if (current == null || current.expiresAtEpochMillis <= clock()) {
                metadataFile.delete()
                mutableDraft.value = null
                deleteAllOwnedFiles()
            } else {
                deleteFilesExcept(current)
            }
        }
    }

    suspend fun consume(draftId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableDraft.value
            if (current?.id != draftId) return@withLock
            metadataFile.delete()
            mutableDraft.value = null
            deleteDraftFile(current)
            deleteFilesExcept(null)
        }
    }

    suspend fun clear(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        synchronized(boundaryLock) {
            boundaryGeneration.incrementAndGet()
        }
        mutex.withLock {
            metadataFile.delete()
            mutableDraft.value = null
            deleteAllOwnedFiles()
        }
    }

    private fun readSupportedMimeType(uri: Uri): String {
        val resolved = try {
            resolver.getType(uri)
        } catch (_: SecurityException) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.SOURCE_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.INVALID_URI)
        }
        val normalized = resolved?.substringBefore(';')?.trim()?.lowercase()
        if (normalized == null || normalized !in SUPPORTED_MIME_TYPES) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.UNSUPPORTED_MIME_TYPE)
        }
        return normalized
    }

    private suspend fun copyBounded(uri: Uri, destination: File): Long {
        val input = try {
            resolver.openInputStream(uri)
        } catch (_: SecurityException) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.SOURCE_UNAVAILABLE)
        } catch (_: FileNotFoundException) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.SOURCE_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.INVALID_URI)
        } ?: throw IncomingImageCaptureException(IncomingImageCaptureFailure.SOURCE_UNAVAILABLE)

        val output = try {
            FileOutputStream(destination, false)
        } catch (_: IOException) {
            input.closeQuietly()
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            input.closeQuietly()
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.STORAGE_UNAVAILABLE)
        }

        try {
            var total = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    throw IncomingImageCaptureException(
                        IncomingImageCaptureFailure.SOURCE_UNAVAILABLE,
                    )
                }
                if (read == -1) break
                total += read
                if (total > MAX_BYTES) {
                    throw IncomingImageCaptureException(IncomingImageCaptureFailure.SOURCE_TOO_LARGE)
                }
                try {
                    output.write(buffer, 0, read)
                } catch (_: IOException) {
                    throw IncomingImageCaptureException(
                        IncomingImageCaptureFailure.STORAGE_UNAVAILABLE,
                    )
                }
            }
            try {
                output.fd.sync()
            } catch (_: IOException) {
                throw IncomingImageCaptureException(IncomingImageCaptureFailure.STORAGE_UNAVAILABLE)
            }
            return total
        } finally {
            input.closeQuietly()
            output.closeQuietly()
        }
    }

    private fun writeMetadata(draft: IncomingImageDraft, fileName: String) {
        val encoded = JSONObject()
            .put(METADATA_VERSION_KEY, METADATA_VERSION)
            .put(ID_KEY, draft.id)
            .put(FILE_NAME_KEY, fileName)
            .put(URI_KEY, draft.uri.toString())
            .put(MIME_TYPE_KEY, draft.mimeType)
            .put(BYTE_COUNT_KEY, draft.byteCount)
            .put(CREATED_AT_KEY, draft.createdAtEpochMillis)
            .put(EXPIRES_AT_KEY, draft.expiresAtEpochMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val output = try {
            metadataFile.startWrite()
        } catch (_: IOException) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.STORAGE_UNAVAILABLE)
        }
        try {
            output.write(encoded)
            metadataFile.finishWrite(output)
        } catch (_: IOException) {
            metadataFile.failWrite(output)
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.STORAGE_UNAVAILABLE)
        }
    }

    private fun readPersistedDraft(now: Long): IncomingImageDraft? {
        val metadata = try {
            metadataFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText())
            }
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: IOException) {
            return null
        } catch (_: JSONException) {
            return null
        }
        return try {
            if (metadata.getInt(METADATA_VERSION_KEY) != METADATA_VERSION) return null
            val id = metadata.getString(ID_KEY)
            UUID.fromString(id)
            val fileName = metadata.getString(FILE_NAME_KEY)
            val mimeType = metadata.getString(MIME_TYPE_KEY)
            val expectedFileName = "$id.${extensionFor(mimeType)}"
            if (fileName != expectedFileName) return null
            val file = File(incomingDirectory, fileName).canonicalFile
            val canonicalDirectory = incomingDirectory.canonicalFile
            if (!file.isStrictDescendantOf(canonicalDirectory) || !file.isFile) return null
            val uri = Uri.parse(metadata.getString(URI_KEY))
            val expectedUri = Uri.fromFile(file)
            if (uri != expectedUri) return null
            val byteCount = metadata.getLong(BYTE_COUNT_KEY)
            if (byteCount < 0L || byteCount > MAX_BYTES || file.length() != byteCount) return null
            val createdAt = metadata.getLong(CREATED_AT_KEY)
            val expiresAt = metadata.getLong(EXPIRES_AT_KEY)
            if (createdAt < 0L || createdAt.checkedAdd(EXPIRATION_MILLIS) != expiresAt) return null
            if (expiresAt <= now) return null
            IncomingImageDraft(
                id = id,
                uri = expectedUri,
                mimeType = mimeType,
                byteCount = byteCount,
                createdAtEpochMillis = createdAt,
                expiresAtEpochMillis = expiresAt,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun ensureIncomingDirectory() {
        if ((!incomingDirectory.exists() && !incomingDirectory.mkdirs()) ||
            !incomingDirectory.isDirectory
        ) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.STORAGE_UNAVAILABLE)
        }
    }

    private fun requireCurrentGeneration(expectedGeneration: Long) {
        if (expectedGeneration != boundaryGeneration.get()) {
            throw IncomingImageCaptureException(IncomingImageCaptureFailure.STALE_CAPTURE)
        }
    }

    private fun deleteDraftFile(draft: IncomingImageDraft) {
        val file = validatedOwnedFile(draft) ?: return
        if (file.exists()) file.delete()
    }

    private fun deleteAllOwnedFiles() {
        incomingDirectory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    private fun deleteFilesExcept(draft: IncomingImageDraft?) {
        val retained = draft?.let(::validatedOwnedFile)
        incomingDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.canonicalFile != retained) file.delete()
        }
    }

    private fun validatedOwnedFile(draft: IncomingImageDraft): File? = try {
        val file = File(requireNotNull(draft.uri.path)).canonicalFile
        val directory = incomingDirectory.canonicalFile
        if (draft.uri.scheme == ContentResolver.SCHEME_FILE &&
            file.isStrictDescendantOf(directory) &&
            file.name == "${draft.id}.${extensionFor(draft.mimeType)}"
        ) {
            file
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> throw IllegalArgumentException("Unsupported incoming image MIME type")
    }

    private fun Long.checkedAdd(other: Long): Long? =
        if (this > Long.MAX_VALUE - other) null else this + other

    private fun File.isStrictDescendantOf(directory: File): Boolean =
        path.startsWith(directory.path + File.separator)

    private fun java.io.Closeable.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // The primary copy result determines whether the draft can be published.
        }
    }

    companion object {
        const val MAX_BYTES = 10_000_000L
        const val EXPIRATION_MILLIS = 24L * 60L * 60L * 1_000L

        private const val DIRECTORY_NAME = "incoming_images"
        private const val METADATA_FILE_NAME = "incoming_image_pointer.json"
        private const val METADATA_VERSION = 1
        private const val METADATA_VERSION_KEY = "version"
        private const val ID_KEY = "id"
        private const val FILE_NAME_KEY = "fileName"
        private const val URI_KEY = "uri"
        private const val MIME_TYPE_KEY = "mimeType"
        private const val BYTE_COUNT_KEY = "byteCount"
        private const val CREATED_AT_KEY = "createdAt"
        private const val EXPIRES_AT_KEY = "expiresAt"
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

data class IncomingImageDraft(
    val id: String,
    val uri: Uri,
    val mimeType: String,
    val byteCount: Long,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

class IncomingImageCaptureException(
    val reason: IncomingImageCaptureFailure,
) : IOException(reason.name)

enum class IncomingImageCaptureFailure {
    INVALID_URI,
    UNSUPPORTED_MIME_TYPE,
    SOURCE_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    STORAGE_UNAVAILABLE,
    STALE_CAPTURE,
}
