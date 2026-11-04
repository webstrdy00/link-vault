package com.linkvault.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.auth.AccountAccess
import com.linkvault.app.test.FixtureExportProvider
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Opt-in real local authentication, backend export, and private document I/O.
 * Only the CreateDocument selector result is stubbed; no system/third-party picker is verified.
 */
class ExportIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun realAuthenticatedSessionExportsAllOwnedLinksToJsonDocument(): Unit = runBlocking {
        check(
            BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".titleiconqa") &&
                BuildConfig.SUPABASE_URL in setOf(
                    "http://10.0.2.2:18021",
                    "http://127.0.0.1:18021",
                ),
        ) { "Export integration requires the isolated debug app and local port 18021 backend." }
        val arguments = InstrumentationRegistry.getArguments()
        fun argument(name: String): String = checkNotNull(arguments.getString(name)) {
            "Missing $name instrumentation argument."
        }.also { check(it.isNotBlank()) { "Empty $name instrumentation argument." } }
        val email = argument("fixtureEmail")
        val password = argument("fixturePassword")
        val fixtureUrl = argument("fixtureUrl")
        val fixtureEndpoint = URI(fixtureUrl)
        check(
            fixtureEndpoint.scheme == "https" && fixtureEndpoint.host == "example.test" &&
                fixtureEndpoint.port == -1 && fixtureEndpoint.rawUserInfo == null &&
                fixtureEndpoint.rawQuery == null && fixtureEndpoint.rawFragment == null &&
                fixtureEndpoint.path.startsWith("/export-device-"),
        ) { "Use the runner's unique, non-fetchable export fixture URL." }

        val app = ApplicationProvider.getApplicationContext<LinkVaultApplication>()
        val resolver = app.contentResolver
        val sessionClient = createSupabaseClient(
            BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            defaultLogLevel = LogLevel.NONE
            install(Auth)
        }
        var fixtureFile: File? = null
        var fixtureUri: Uri? = null
        var intentsInitialized = false
        var failure: Throwable? = null
        try {
            app.accountClient.signOut()
            sessionClient.auth.awaitInitialization()
            sessionClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            app.accountClient.restoreAccount()
            assertTrue(app.accountClient.bootstrapAccount() is AccountAccess.Active)
            val owner = checkNotNull(app.accountClient.sessionUserId())
            val categories = app.accountClient.libraryRequest(owner, "/categories")
                .getValue("categories").jsonArray
            val category = categories.first().jsonObject
            val categoryId = category.getValue("id").jsonPrimitive.content
            val categoryName = category.getValue("name")
            val authoredTitle = "  내 제목 \"인용\"\n둘째 줄 𠮷  "
            val authoredNote = "첫 줄\n\"메모\"와 \\ 경로\t𠮷"
            val authoredUrl = Uri.parse(fixtureUrl).buildUpon().appendPath("authored").build().toString()
            val nullableUrl = Uri.parse(fixtureUrl).buildUpon().appendPath("nullable").build().toString()
            val authored = app.accountClient.libraryRequest(
                expectedOwnerId = owner,
                path = "/items",
                method = "POST",
                requestId = UUID.randomUUID().toString(),
                body = buildJsonObject {
                    put("url", authoredUrl)
                    put("title", authoredTitle)
                    put("note", authoredNote)
                    put("shared_text", "EXCLUDED_SHARED_TEXT_𠮷")
                    put("category_ids", JsonArray(listOf(JsonPrimitive(categoryId))))
                }.toString(),
            ).getValue("item").jsonObject
            val nullable = app.accountClient.libraryRequest(
                expectedOwnerId = owner,
                path = "/items",
                method = "POST",
                requestId = UUID.randomUUID().toString(),
                body = buildJsonObject {
                    put("url", nullableUrl)
                    put("category_ids", JsonArray(emptyList()))
                }.toString(),
            ).getValue("item").jsonObject
            val expected = readAllOwnedItems(app, owner)
            assertEquals("The disposable fixture account must contain exactly two saved links.", 2, expected.size)
            assertEquals(
                setOf(authored.getValue("id"), nullable.getValue("id")),
                expected.map { it.getValue("id") }.toSet(),
            )
            val expectedRows = expected.map(::exportRow)
            val expectedAuthored = expectedRows.single { it.getValue("url").jsonPrimitive.content == authoredUrl }
            assertEquals(JsonPrimitive(authoredTitle), expectedAuthored.getValue("user_title"))
            assertEquals(JsonPrimitive(authoredTitle.trim(' ')), expectedAuthored.getValue("title"))
            assertEquals(JsonPrimitive(authoredNote), expectedAuthored.getValue("note"))
            assertEquals(JsonArray(listOf(categoryName)), expectedAuthored.getValue("categories"))
            val expectedNullable = expectedRows.single { it.getValue("url").jsonPrimitive.content == nullableUrl }
            assertEquals(JsonNull, expectedNullable.getValue("user_title"))
            assertEquals(JsonNull, expectedNullable.getValue("note"))
            assertEquals(JsonArray(emptyList()), expectedNullable.getValue("categories"))
            assertTrue(expectedNullable.getValue("title").jsonPrimitive.content.isNotBlank())

            val directory = File(app.cacheDir, "fixture_exports").canonicalFile
            check(directory.isDirectory || directory.mkdirs())
            val file = File.createTempFile("export-integration-", ".json", directory)
            fixtureFile = file
            val destination = FixtureExportProvider.uriForFile(file)
            fixtureUri = destination
            assertDocumentMetadata(resolver, destination, file, 0)
            // A real provider write leaves a longer old document: export must truncate it.
            val staleBytes = ByteArray(128 * 1024) { 'x'.code.toByte() }
            checkNotNull(resolver.openOutputStream(destination, "wt")).use { it.write(staleBytes) }
            assertDocumentMetadata(resolver, destination, file, staleBytes.size.toLong())

            val createDocument = allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("application/json"),
            )
            Intents.init()
            intentsInitialized = true
            Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
                ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(destination).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    ),
                ),
            )
            val intent = Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                awaitTag("root-account")
                compose.onNodeWithTag("root-account", useUnmergedTree = true).performClick()
                compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithText("로그인했어요").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText("승인 상태 다시 확인").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithText("승인 상태 다시 확인").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("승인 상태 다시 확인").performScrollTo().performClick()
                }
                compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithText("로그인했어요").fetchSemanticsNodes().isNotEmpty()
                }
                awaitTag("library-export-start")
                compose.onNodeWithTag("library-export-start", useUnmergedTree = true)
                    .performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(timeoutMillis = EXPORT_TIMEOUT_MILLIS) {
                    hasTag("library-export-success") || hasTag("library-export-error")
                }
                compose.onNodeWithTag("library-export-error", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithTag("library-export-success", useUnmergedTree = true)
                    .performScrollTo().assertIsDisplayed()
                Intents.intended(createDocument)
                val captured = Intents.getIntents().filter { it.action == Intent.ACTION_CREATE_DOCUMENT }
                assertEquals("Exactly one CreateDocument request must be launched.", 1, captured.size)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, captured.single().action)
                assertEquals("application/json", captured.single().type)
                assertTrue(captured.single().hasCategory(Intent.CATEGORY_OPENABLE))

                val bytes = checkNotNull(resolver.openInputStream(destination)).use { it.readBytes() }
                assertTrue("The document must contain the real JSON response.", bytes.isNotEmpty())
                assertTrue("A truncating write must remove the longer old document.", bytes.size < staleBytes.size)
                assertDocumentMetadata(resolver, destination, file, bytes.size.toLong())
                val text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                val snapshot = Json.parseToJsonElement(text).jsonObject
                assertEquals(setOf("items"), snapshot.keys)
                val rows = snapshot.getValue("items").jsonArray.map { it.jsonObject }
                assertEquals("Every active owned saved link must appear exactly once.", 2, rows.size)
                rows.forEach(::assertExportRow)
                assertEquals(expectedRows, rows)
                assertEquals(setOf(authoredUrl, nullableUrl), rows.map { it.getValue("url").jsonPrimitive.content }.toSet())
                assertFalse(text.contains("EXCLUDED_SHARED_TEXT_"))
                // Leave all server data to the runner; its postchecks use a separate live JWT.
                assertEquals(owner, app.accountClient.sessionUserId())
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            runCatching { if (intentsInitialized) Intents.release() }.exceptionOrNull()?.let(cleanupErrors::add)
            runCatching {
                val file = fixtureFile
                if (file != null && file.exists()) {
                    val uri = fixtureUri
                    if (uri == null) {
                        check(file.delete()) { "The unregistered fixture file could not be deleted." }
                    } else {
                        assertTrue("The document-contract fixture must really delete its file.",
                            DocumentsContract.deleteDocument(resolver, uri))
                    }
                    assertFalse("The fixture must not survive document deletion.", file.exists())
                }
            }.exceptionOrNull()?.let(cleanupErrors::add)
            runCatching { sessionClient.auth.clearSession() }.exceptionOrNull()?.let(cleanupErrors::add)
            runCatching { sessionClient.close() }.exceptionOrNull()?.let(cleanupErrors::add)
            cleanupErrors.firstOrNull()?.let { cleanupError ->
                val primary = failure
                if (primary != null) {
                    cleanupErrors.forEach(primary::addSuppressed)
                } else {
                    cleanupErrors.drop(1).forEach(cleanupError::addSuppressed)
                    throw cleanupError
                }
            }
        }
    }

    private suspend fun readAllOwnedItems(app: LinkVaultApplication, owner: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var offset = 0
        do {
            val response = app.accountClient.libraryRequest(owner, "/items?limit=50&offset=$offset")
            val page = response.getValue("items").jsonArray
            for (entry in page) {
                val id = entry.jsonObject.getValue("id").jsonPrimitive.content
                items += app.accountClient.libraryRequest(owner, "/items/$id")
            }
            offset += page.size
            val hasMore = response.getValue("has_more").jsonPrimitive.boolean
            check(!hasMore || page.isNotEmpty()) { "Owned library pagination did not advance." }
        } while (hasMore)
        assertEquals("Owned library pages must not repeat an item.", items.size, items.map { it.getValue("id") }.toSet().size)
        return items.sortedWith(
            compareBy<JsonObject> { Instant.parse(it.getValue("created_at").jsonPrimitive.content) }
                .thenBy { it.getValue("id").jsonPrimitive.content },
        )
    }

    private fun exportRow(item: JsonObject): JsonObject = buildJsonObject {
        put("url", item.getValue("url"))
        put("title", item.getValue("display_title"))
        put("user_title", item.getValue("user_title"))
        put("note", item.getValue("note"))
        put("source", item.getValue("source"))
        put("categories", JsonArray(item.getValue("category_refs").jsonArray.map {
            it.jsonObject.getValue("name")
        }))
        put("saved_at", item.getValue("created_at"))
    }

    private fun assertExportRow(row: JsonObject) {
        assertEquals(
            "Only the seven portable fields are allowed; IDs, OCR, images and body fields are forbidden.",
            setOf("url", "title", "user_title", "note", "source", "categories", "saved_at"),
            row.keys,
        )
        for (field in listOf("url", "title", "source", "saved_at")) {
            assertTrue("$field must be a JSON string.", row.getValue(field).jsonPrimitive.isString)
        }
        for (field in listOf("user_title", "note")) {
            val value = row.getValue(field)
            assertTrue("$field must preserve a string or JSON null.", value == JsonNull || value.jsonPrimitive.isString)
        }
        row.getValue("categories").jsonArray.forEach {
            assertTrue("Categories must contain visible string names, not ID objects.", it.jsonPrimitive.isString)
        }
        Instant.parse(row.getValue("saved_at").jsonPrimitive.content)
    }

    private fun assertDocumentMetadata(resolver: ContentResolver, uri: Uri, file: File, expectedSize: Long) {
        assertEquals("application/json", resolver.getType(uri))
        checkNotNull(resolver.query(uri, null, null, null, null)).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(file.name, cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)))
            assertEquals(file.name, cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            assertEquals("application/json", cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)))
            assertEquals(expectedSize, cursor.getLong(cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
            assertEquals(file.lastModified(), cursor.getLong(cursor.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)))
            assertEquals(
                Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE,
                cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS)),
            )
        }
    }

    private fun hasTag(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) { hasTag(tag) }
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 30_000L
        const val EXPORT_TIMEOUT_MILLIS = 300_000L
    }
}
