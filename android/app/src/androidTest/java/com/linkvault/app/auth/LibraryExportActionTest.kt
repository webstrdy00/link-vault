package com.linkvault.app.auth

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryExportActionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val roots = mutableListOf<File>()

    @After
    fun removeFixtures() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun generatedThousandMaximumFieldRowsStreamThroughPrivateStagingWithExactByteHash() = runBlocking {
        val fixture = fixture()
        val expectedHash = MessageDigest.getInstance("SHA-256")
        val destinationFile = File(fixture.root, "saved.json")
        var downloaded = 0L
        fixture.download = { output ->
            generateLibraryExport(DigestOutputStream(output, expectedHash)).also { downloaded = it }
        }
        fixture.open = { destinationFile.outputStream() }

        val copied = fixture.action().export(REQUEST, DESTINATION) { fail("Unexpected cleanup failure") }

        assertTrue(copied > 10_000_000L)
        assertEquals(downloaded, copied)
        assertEquals(copied, destinationFile.length())
        assertArrayEquals(expectedHash.digest(), hash(destinationFile))
        validateLibraryExport(destinationFile) {}
        assertEquals(1, fixture.opens)
        assertEquals(0, fixture.deletes)
        assertStagingEmpty(fixture)
    }

    @Test
    fun nullUnicodeEscapesWhitespaceAndEmptyLibraryKeepTheirOriginalBytes() = runBlocking {
        val nullableNote = ROW.replace("\"note\":\"line\\nquote\\\"\\t\\u0001\"", "\"note\":null")
        val rawTitle = ROW.replace("\"user_title\":null", "\"user_title\":\"  사용자\\n제목𐐷  \"")
        listOf(" {\n \"items\" : [] }\n", "{\"items\":[$ROW]}", "{\"items\":[$nullableNote]}", "{\"items\":[$rawTitle]}").forEach { payload ->
            val fixture = fixture()
            fixture.download = { writeUtf8(it, payload) }
            assertEquals(
                payload.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                fixture.action().export(REQUEST, DESTINATION) { fail("Unexpected cleanup failure") },
            )
            assertEquals(payload, fixture.output.toString(StandardCharsets.UTF_8.name()))
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun malformedTruncatedUnknownDuplicateAndWrongTypeJsonNeverOpensSaf() = runBlocking {
        val valid = "{\"items\":[$ROW]}"
        val invalid = listOf(
            "", "[]", "{}", "{\"items\":null}", "{\"items\":{}}", "{\"items\":[null]}",
            "{\"items\":[{}]}", valid.dropLast(1), valid.dropLast(3),
            "$valid {}", "$valid trailing", "$valid\u0000", "{\"items\":[],\"items\":[]}",
            "{\"items\":[],\"secret\":true}", "{\"secret\":true,\"items\":[]}",
            valid.replace("\"url\":", "\"url\":\"duplicate\",\"url\":"),
            valid.replace("\"url\":", "\"\\u0075rl\":\"duplicate\",\"url\":"),
            valid.replace("\"url\":", "\"internal_id\":null,\"url\":"),
            valid.replace("\"title\":\"한글 𐐷\"", "\"title\":42"),
            valid.replace("\"title\":\"한글 𐐷\"", "\"title\":null"),
            valid.replace("\"user_title\":null", "\"user_title\":false"),
            valid.replace("\"note\":\"", "\"note\":\"\\q"),
            valid.replace("\\u0001", "\\u00xz"),
            valid.replace("line\\n", "line\n"),
            valid.replace("\"categories\":[\"분류\",\"café\"]", "\"categories\":[1]"),
            valid.replace("\"categories\":[\"분류\",\"café\"]", "\"categories\":null"),
            valid.replace("\"source\":\"web\"", "\"source\":true"),
            valid.replace("\"saved_at\":\"2026-11-01T01:02:03Z\"", "\"saved_at\":1"),
            valid.replace("\"items\"", "items"),
            valid.replace("[", "[/* comment */", ignoreCase = false),
            valid.replace("\"url\"", "'url'"),
            valid.replace("}]}", "},]}"),
        )
        invalid.forEach { payload ->
            val fixture = fixture()
            fixture.download = { writeUtf8(it, payload) }
            val error = expectFailure<AccountClientException> {
                fixture.action().export(REQUEST, DESTINATION) { fail("Unexpected cleanup failure") }
            }
            assertEquals("INVALID_EXPORT_RESPONSE", error.code)
            assertEquals(0, fixture.opens)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun malformedUtf8IsRejectedRatherThanSilentlyReplaced() = runBlocking {
        val fixture = fixture()
        fixture.download = { output ->
            val prefix = "{\"items\":[$ROW]}".replace("한글 𐐷", "MARKER")
                .substringBefore("MARKER").toByteArray(StandardCharsets.UTF_8)
            val suffix = "{\"items\":[$ROW]}".replace("한글 𐐷", "MARKER")
                .substringAfter("MARKER").toByteArray(StandardCharsets.UTF_8)
            output.write(prefix)
            output.write(byteArrayOf(0xc3.toByte(), 0x28))
            output.write(suffix)
            (prefix.size + suffix.size + 2).toLong()
        }
        val error = expectFailure<AccountClientException> {
            fixture.action().export(REQUEST, DESTINATION) {}
        }
        assertEquals("INVALID_EXPORT_RESPONSE", error.code)
        assertEquals(0, fixture.opens)
        assertEquals(1, fixture.deletes)
        assertStagingEmpty(fixture)
    }

    @Test
    fun reportedDownloadLengthMismatchNeverOpensSaf() = runBlocking {
        val fixture = fixture()
        fixture.download = { writeUtf8(it, "{\"items\":[]}") + 1 }
        expectFailure<AccountClientException> { fixture.action().export(REQUEST, DESTINATION) {} }
        assertEquals(0, fixture.opens)
        assertEquals(1, fixture.deletes)
        assertStagingEmpty(fixture)
    }

    @Test
    fun stagingCreationIoFailureDoesNotDownloadOrOpenSafAndDeletesNewDocument() = runBlocking {
        val fixture = fixture()
        fixture.staging.writeText("not a directory")
        var downloads = 0
        fixture.download = { downloads++; 0 }
        val error = expectFailure<IOException> { fixture.action().export(REQUEST, DESTINATION) {} }
        assertEquals("내보내기 임시 파일을 만들지 못했어요.", error.message)
        assertEquals(0, downloads)
        assertEquals(0, fixture.opens)
        assertEquals(1, fixture.deletes)
    }

    @Test
    fun staleOwnerOrGenerationBeforeDownloadDoesNotRequestOrOpenDestination() = runBlocking {
        listOf(session(owner = "different"), session(generation = GENERATION + 1), session(owner = null))
            .forEach { changed ->
                val fixture = fixture()
                fixture.sessions.value = changed
                var downloads = 0
                fixture.download = { downloads++; writeUtf8(it, "{\"items\":[]}") }
                assertStale { fixture.action().export(REQUEST, DESTINATION) {} }
                assertEquals(0, downloads)
                assertEquals(0, fixture.opens)
                assertEquals(1, fixture.deletes)
                assertStagingEmpty(fixture)
            }
    }

    @Test
    fun ownerAndGenerationChangesAfterDownloadNeverOpenDestination() = runBlocking {
        listOf(session(owner = "different"), session(generation = GENERATION + 1)).forEach { changed ->
            val fixture = fixture()
            fixture.download = { output ->
                writeUtf8(output, "{\"items\":[]}").also { fixture.sessions.value = changed }
            }
            assertStale { fixture.action().export(REQUEST, DESTINATION) {} }
            assertEquals(0, fixture.opens)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun ownerAndSameOwnerGenerationChangesDuringSafCopyCloseAndDeletePartialFile() = runBlocking {
        listOf(session(owner = "different"), session(generation = GENERATION + 1)).forEach { changed ->
            val fixture = fixture()
            fixture.download = { generateLibraryExport(it, rows = 8) }
            val target = ObservedOutput()
            target.onWrite = { fixture.sessions.value = changed }
            fixture.open = { target }
            assertStale { fixture.action().export(REQUEST, DESTINATION) {} }
            assertEquals(1, target.writes)
            assertTrue(target.closed)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun ownerAndGenerationChangesDuringProviderCloseCannotPublishSuccess() = runBlocking {
        listOf(session(owner = "different"), session(generation = GENERATION + 1)).forEach { changed ->
            val fixture = fixture()
            val target = ObservedOutput()
            target.onClose = { fixture.sessions.value = changed }
            fixture.open = { target }
            assertStale { fixture.action().export(REQUEST, DESTINATION) {} }
            assertTrue(target.flushed)
            assertTrue(target.closed)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun successIsReturnedOnlyAfterProviderFlushAndClose() = runBlocking {
        val fixture = fixture()
        val target = ObservedOutput()
        target.onClose = { assertTrue(target.flushed) }
        fixture.open = { target }
        val copied = fixture.action().export(REQUEST, DESTINATION) {}
        assertTrue(copied > 0)
        assertTrue(target.flushed)
        assertTrue(target.closed)
        assertEquals(0, fixture.deletes)
        assertStagingEmpty(fixture)
    }

    @Test
    fun providerOpenWriteFlushAndCloseFailuresAreDistinctAndRemoveNewDocument() = runBlocking {
        listOf("open", "write", "flush", "close", "null").forEach { operation ->
            val fixture = fixture()
            val primary = IOException("provider-private-path")
            val target = ObservedOutput()
            when (operation) {
                "open" -> fixture.open = { throw primary }
                "null" -> fixture.open = { null }
                else -> {
                    when (operation) {
                        "write" -> target.onWrite = { throw primary }
                        "flush" -> target.onFlush = { throw primary }
                        "close" -> target.onClose = { throw primary }
                    }
                    fixture.open = { target }
                }
            }
            val error = expectFailure<LibraryExportDestinationException> {
                fixture.action().export(REQUEST, DESTINATION) {}
            }
            assertFalse(error.message.orEmpty().contains("provider-private-path"))
            if (operation != "null") assertSame(primary, error.cause)
            if (operation != "open" && operation != "null") assertTrue(target.closed)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun cleanupRefusalAndWarningFailureNeverMaskPrimaryDownloadError() = runBlocking {
        val fixture = fixture()
        val primary = IOException("download failure")
        val warningFailure = IllegalStateException("warning failure")
        fixture.download = { throw primary }
        fixture.delete = { throw SecurityException("refused") }
        fixture.deleteStage = { false }
        var warnings = 0
        val error = expectFailure<IOException> {
            fixture.action().export(REQUEST, DESTINATION) { uri ->
                assertEquals(DESTINATION, uri)
                warnings++
                throw warningFailure
            }
        }
        assertSame(primary, error)
        assertEquals(1, warnings)
        assertTrue(error.suppressed.any { it.message == "내보내기 임시 파일을 지우지 못했어요." })
        assertTrue(error.suppressed.any { it === warningFailure })
        assertEquals(0, fixture.opens)
        assertEquals(1, fixture.deletes)
    }

    @Test
    fun stagingCleanupFailureTurnsOtherwiseSuccessfulCopyIntoFailureAndDeletesSaf() = runBlocking {
        val fixture = fixture()
        fixture.deleteStage = { false }
        val error = expectFailure<IOException> {
            fixture.action().export(REQUEST, DESTINATION) {}
        }
        assertEquals("내보내기 임시 파일을 지우지 못했어요.", error.message)
        assertEquals(1, fixture.opens)
        assertEquals(1, fixture.deletes)
        assertTrue(fixture.staging.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun cancellationWhileDownloadingCleansStageAndSafInNonCancellableContext() = runBlocking {
        withTimeout(10_000) {
            coroutineScope {
                val fixture = fixture()
                val started = CompletableDeferred<Unit>()
                fixture.download = { output ->
                    output.write('{'.code)
                    started.complete(Unit)
                    CompletableDeferred<Long>().await()
                }
                var cleanupActive = false
                fixture.delete = { cleanupActive = currentCoroutineContext()[Job]?.isActive == true; true }
                val transfer = async { fixture.action().export(REQUEST, DESTINATION) {} }
                started.await()
                transfer.cancel()
                expectFailure<CancellationException> { transfer.await() }
                assertTrue(cleanupActive)
                assertEquals(0, fixture.opens)
                assertEquals(1, fixture.deletes)
                assertStagingEmpty(fixture)
            }
        }
    }

    @Test
    fun cancellationDuringProviderCloseDoesNotEscapeFinalCleanup() = runBlocking {
        coroutineScope {
            val fixture = fixture()
            val target = ObservedOutput()
            fixture.open = {
                val job = currentCoroutineContext()[Job]!!
                target.onClose = { job.cancel(CancellationException("cancel during close")) }
                target
            }
            val transfer = async { fixture.action().export(REQUEST, DESTINATION) {} }
            expectFailure<CancellationException> { transfer.await() }
            assertTrue(target.closed)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun cancellationDuringSafWriteClosesAndWarnsOnProviderCleanupRefusal() = runBlocking {
        coroutineScope {
            val fixture = fixture()
            fixture.download = { generateLibraryExport(it, rows = 8) }
            val target = ObservedOutput()
            fixture.open = {
                val job = currentCoroutineContext()[Job]!!
                target.onWrite = { job.cancel(CancellationException("cancel during copy")) }
                target
            }
            fixture.delete = { false }
            var warnings = 0
            val transfer = async {
                fixture.action().export(REQUEST, DESTINATION) { warnings++ }
            }
            expectFailure<CancellationException> { transfer.await() }
            assertEquals(1, target.writes)
            assertTrue(target.closed)
            assertEquals(1, warnings)
            assertEquals(1, fixture.deletes)
            assertStagingEmpty(fixture)
        }
    }

    @Test
    fun validationChecksSessionRepeatedlyInsteadOfOnlyAtStartAndEnd() = runBlocking {
        val root = newRoot()
        val file = File(root, "generated.json")
        withContext(Dispatchers.IO) { file.outputStream().use { generateLibraryExport(it, rows = 4) } }
        var checks = 0
        val error = expectFailure<AccountClientException> {
            validateLibraryExport(file) {
                checks++
                requireLibraryExportSession(
                    if (checks < 15) session() else session(generation = GENERATION + 1),
                    OWNER,
                    GENERATION,
                )
            }
        }
        assertEquals("SESSION_CHANGED", error.code)
        assertEquals(15, checks)
    }

    @Test
    fun discardReportsProviderRefusalWithoutOpeningOrDownloading() = runBlocking {
        val fixture = fixture()
        fixture.delete = { false }
        assertFalse(fixture.action().discard(DESTINATION))
        fixture.delete = { throw SecurityException("private provider details") }
        assertFalse(fixture.action().discard(DESTINATION))
        assertEquals(0, fixture.opens)
        assertEquals(2, fixture.deletes)
    }

    @Test
    fun startupCleanupIsSynchronousReservedAndDoesNotCreateMissingDirectory() {
        val root = newRoot()
        val scopedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
        }
        val reserved = File(root, "library-export")
        val unrelated = File(root, "unrelated.cache").apply { writeText("keep") }
        assertTrue(LibraryExportAction.cleanupOrphanedStagingFiles(scopedContext))
        assertFalse(reserved.exists())
        assertTrue(reserved.mkdir())
        File(reserved, "orphan-one.json").writeText("private")
        File(reserved, "orphan-two.json").writeText("private")
        assertTrue(LibraryExportAction.cleanupOrphanedStagingFiles(scopedContext))
        assertTrue(reserved.listFiles().orEmpty().isEmpty())
        assertEquals("keep", unrelated.readText())

        val refused = File(reserved, "nonempty-directory").apply { mkdir() }
        File(refused, "keep").writeText("private")
        File(reserved, "later-orphan.json").writeText("private")
        assertFalse(LibraryExportAction.cleanupOrphanedStagingFiles(scopedContext))
        assertFalse(File(reserved, "later-orphan.json").exists())
        assertEquals("keep", unrelated.readText())
    }

    private fun fixture() = Fixture(newRoot())

    private fun newRoot(): File = File(context.cacheDir, "export-action-test-${UUID.randomUUID()}").also {
        check(it.mkdirs())
        roots += it
    }

    private fun assertStagingEmpty(fixture: Fixture) {
        assertTrue(fixture.staging.listFiles().orEmpty().isEmpty())
    }

    private suspend fun assertStale(block: suspend () -> Any?) {
        assertEquals("SESSION_CHANGED", expectFailure<AccountClientException>(block).code)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Any?): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private fun hash(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(LIBRARY_EXPORT_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    private class Fixture(val root: File) {
        val staging = File(root, "staging")
        val sessions = MutableStateFlow(session())
        val output = ByteArrayOutputStream()
        var opens = 0
        var deletes = 0
        var download: suspend (OutputStream) -> Long = { writeUtf8(it, "{\"items\":[$ROW]}") }
        var open: suspend (Uri) -> OutputStream? = { output }
        var delete: suspend (Uri) -> Boolean = { true }
        var deleteStage: (File) -> Boolean = { it.delete() }

        fun action() = LibraryExportAction(
            stagingDirectory = staging,
            sessions = sessions,
            download = { owner, generation, output ->
                assertEquals(OWNER, owner)
                assertEquals(GENERATION, generation)
                download(output)
            },
            openDestination = { opens++; open(it) },
            deleteDestination = { deletes++; delete(it) },
            deleteStagingFile = { deleteStage(it) },
        )
    }

    private class ObservedOutput : OutputStream() {
        var writes = 0
        var closed = false
        var flushed = false
        var onWrite: () -> Unit = {}
        var onFlush: () -> Unit = {}
        var onClose: () -> Unit = {}

        override fun write(value: Int) { throw AssertionError("Copy must use the bounded buffer") }
        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            assertTrue(count in 1..LIBRARY_EXPORT_BUFFER_BYTES)
            writes++
            onWrite()
        }
        override fun flush() { flushed = true; onFlush() }
        override fun close() { closed = true; onClose() }
    }

    private companion object {
        const val OWNER = "export-owner"
        const val GENERATION = 9L
        val DESTINATION: Uri = Uri.parse("content://export-test/document/new-file")
        val REQUEST = LibraryExportRequest("request", OWNER, GENERATION, "library.json")
        const val ROW = "{\"url\":\"https://example.com/한글\",\"title\":\"한글 𐐷\",\"user_title\":null,\"note\":\"line\\nquote\\\"\\t\\u0001\",\"source\":\"web\",\"categories\":[\"분류\",\"café\"],\"saved_at\":\"2026-11-01T01:02:03Z\"}"
        fun session(owner: String? = OWNER, generation: Long = GENERATION) =
            AccountSessionState(ownerId = owner, generation = generation, initialized = true)
        fun writeUtf8(output: OutputStream, payload: String): Long = payload.toByteArray(StandardCharsets.UTF_8).let {
            output.write(it)
            it.size.toLong()
        }
    }
}

// Generates one row at a time; neither test path constructs a whole export byte array or item list.
internal fun generateLibraryExport(output: OutputStream, rows: Int = 1_000): Long {
    var count = 0L
    val counted = object : OutputStream() {
        override fun write(value: Int) { output.write(value); count++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            output.write(buffer, offset, length)
            count += length
        }
        override fun flush() = output.flush()
    }
    val writer = OutputStreamWriter(counted, StandardCharsets.UTF_8)
    val urlPadding = "x".repeat(4_000)
    val title = "제𐐷".repeat(150)
    val note = "메모\\n\\\"\\t".repeat(800)
    val category = "분류".repeat(14)
    writer.write("{\"items\":[")
    repeat(rows) { index ->
        if (index > 0) writer.write(",")
        writer.write("{\"url\":\"https://example.com/$index/$urlPadding\",\"title\":\"$title\",")
        writer.write("\"user_title\":")
        writer.write(if (index % 2 == 0) "null" else "\"$title\"")
        writer.write(",\"note\":\"$note\",\"source\":\"other\",\"categories\":[")
        repeat(5) { categoryIndex ->
            if (categoryIndex > 0) writer.write(",")
            writer.write("\"$category$categoryIndex\"")
        }
        writer.write("],\"saved_at\":\"2026-11-01T01:02:03Z\"}")
    }
    writer.write("]}")
    writer.flush()
    return count
}
