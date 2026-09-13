package com.linkvault.app.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelsTest {
    private val requestId = "11111111-2222-4333-8444-555555555555"

    @Test
    fun validationCountsUnicodeCodePointsAtEveryServerLimit() {
        val urlPrefix = "https://example.com/"
        val maximumUrl = urlPrefix + "a".repeat(LIBRARY_URL_MAX_CODE_POINTS - urlPrefix.length)
        val maximumForm = LibrarySaveForm(
            initialUrl = maximumUrl,
            sharedText = "😀".repeat(LIBRARY_SHARED_TEXT_MAX_CODE_POINTS),
            title = "😀".repeat(LIBRARY_TITLE_MAX_CODE_POINTS),
            note = "😀".repeat(LIBRARY_NOTE_MAX_CODE_POINTS),
        )

        assertTrue(validateLibrarySaveForm(maximumForm).isEmpty())
        assertEquals(LIBRARY_SHARED_TEXT_MAX_CODE_POINTS, maximumForm.sharedText.codePointLength())

        assertHasIssue(
            maximumForm.copy(initialUrl = maximumUrl + "a"),
            LibraryFormField.URL,
        )
        assertHasIssue(
            maximumForm.copy(title = maximumForm.title + "😀"),
            LibraryFormField.TITLE,
        )
        assertHasIssue(
            maximumForm.copy(note = maximumForm.note + "😀"),
            LibraryFormField.NOTE,
        )
        assertHasIssue(
            maximumForm.copy(sharedText = maximumForm.sharedText + "😀"),
            LibraryFormField.SHARED_TEXT,
        )
    }

    @Test
    fun validationRejectsUrlsThatTheSaveContractDoesNotAllow() {
        listOf(
            "ftp://example.com/item",
            "https://user:password@example.com/item",
            "https://example.com/has space",
            "https:///missing-host",
        ).forEach { url ->
            assertHasIssue(
                LibrarySaveForm(initialUrl = url, sharedText = ""),
                LibraryFormField.URL,
            )
        }
    }

    @Test
    fun requestBodyPreservesUnicodeAndOmitsEmptyOptionalFields() {
        val title = "  제주 맛집 🧭  "
        val sharedText = "친구가 보낸 원문\nhttps://example.com/길?값=하나 😀"
        val preparation = prepareLibrarySave(
            form = LibrarySaveForm(
                initialUrl = "https://example.com/path",
                sharedText = sharedText,
                title = title,
                note = "   ",
            ),
            ownerId = "member-a",
            requestId = requestId,
        )

        assertTrue(preparation is LibrarySavePreparation.Valid)
        val operation = (preparation as LibrarySavePreparation.Valid).operation
        val body = Json.parseToJsonElement(operation.body).jsonObject
        assertEquals("https://example.com/path", body.getValue("url").jsonPrimitive.content)
        assertEquals(title, body.getValue("title").jsonPrimitive.content)
        assertEquals(sharedText, body.getValue("shared_text").jsonPrimitive.content)
        assertFalse(body.containsKey("note"))

        val emptyOptionalBody = (
            prepareLibrarySave(
                form = LibrarySaveForm(
                    initialUrl = "https://example.com/path",
                    sharedText = "",
                ),
                ownerId = "member-a",
                requestId = requestId,
            ) as LibrarySavePreparation.Valid
            ).operation.body
        assertEquals(
            setOf("url"),
            Json.parseToJsonElement(emptyOptionalBody).jsonObject.keys,
        )
    }

    @Test
    fun responseParsingAcceptsNullableAndUnknownFields() {
        val response = Json.parseToJsonElement(
            """
            {
              "items": [
                {
                  "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "url": "https://example.com/item",
                  "version": 3,
                  "display_title": "선택할 제목",
                  "note_excerpt": null,
                  "category_refs": [
                    {"id":"category-1","name":"맛집","origin":"user","future":true}
                  ],
                  "cue_flags": [],
                  "match_type": null,
                  "future_item_field": {"enabled":true}
                }
              ],
              "has_more": true,
              "future_page_field": "ignored"
            }
            """.trimIndent(),
        ).jsonObject

        val page = parseLibraryListResponse(response)

        assertTrue(page.hasMore)
        assertEquals(1, page.items.size)
        assertEquals("선택할 제목", page.items.single().displayTitle)
        assertNull(page.items.single().noteExcerpt)
        assertNull(page.items.single().matchType)
        assertEquals("맛집", page.items.single().categoryRefs.single().name)
    }

    @Test
    fun detailAndSaveResponsesParseDirectServerShapes() {
        val detail = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "url":"https://example.com/item",
                  "display_title":"표시 제목",
                  "user_title":null,
                  "fetched_title":"수집 제목",
                  "shared_text":null,
                  "description":null,
                  "body_text":"본문",
                  "note":"메모",
                  "unknown":"ignored"
                }
                """.trimIndent(),
            ).jsonObject,
        )
        val save = parseLibrarySaveResponse(
            Json.parseToJsonElement(
                """
                {
                  "duplicate":true,
                  "item":{
                    "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                    "url":"https://example.com/item",
                    "display_title":"표시 제목"
                  },
                  "unknown":"ignored"
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertNull(detail.userTitle)
        assertEquals("본문", detail.bodyText)
        assertEquals("메모", detail.note)
        assertTrue(save.duplicate)
        assertEquals(detail.id, save.item.id)
    }

    @Test
    fun retryArgumentsReuseTheSameRequestIdAndBody() {
        val preparation = prepareLibrarySave(
            form = LibrarySaveForm(
                initialUrl = "https://example.com/item",
                sharedText = "공유 내용 😀",
                title = "제목",
                note = "메모",
            ),
            ownerId = "member-a",
            requestId = requestId,
        ) as LibrarySavePreparation.Valid

        val firstAttempt = preparation.operation.requestArguments()
        val retryAttempt = preparation.operation.requestArguments()

        assertEquals(firstAttempt, retryAttempt)
        assertEquals(requestId, retryAttempt.requestId)
        assertEquals(firstAttempt.body, retryAttempt.body)
        assertEquals("POST", retryAttempt.method)
        assertEquals("/items", retryAttempt.path)
    }

    private fun assertHasIssue(form: LibrarySaveForm, field: LibraryFormField) {
        assertTrue(validateLibrarySaveForm(form).any { it.field == field })
    }
}
