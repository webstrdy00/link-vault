package com.linkvault.app.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
        assertNull(detail.textRevision)
        assertNull(detail.manualOverride)
        assertTrue(detail.classificationReasons.isEmpty())
        assertTrue(detail.classificationExplanations.isEmpty())
        assertNull(detail.cuePromptDismissed)
        assertTrue(save.duplicate)
        assertEquals(detail.id, save.item.id)
    }

    @Test
    fun detailParsesActiveAndMissingAssetsWithoutConflatingFailedOcrWithImageFailure() {
        val active = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "url":"https://example.com/item",
                  "has_attachment":true,
                  "ocr_state":"failed",
                  "active_asset":{
                    "id":"20000000-0000-4000-8000-000000000001",
                    "object_path":"owners/member/items/item/assets/image.webp",
                    "mime_type":"image/webp",
                    "byte_size":123456,
                    "width":1440,
                    "height":1080,
                    "ocr_text":null,
                    "ocr_truncated":false
                  }
                }
                """.trimIndent(),
            ).jsonObject,
        )
        val missing = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "url":"https://example.com/item",
                  "has_attachment":false,
                  "ocr_state":"not_requested",
                  "active_asset":null
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertTrue(active.hasAttachment!!)
        assertEquals("failed", active.ocrState)
        assertEquals("image/webp", active.activeAsset?.mimeType)
        assertEquals(123456L, active.activeAsset?.byteSize)
        assertNull(active.activeAsset?.ocrText)
        assertFalse(active.activeAsset?.ocrTruncated!!)
        assertFalse(missing.hasAttachment!!)
        assertEquals("not_requested", missing.ocrState)
        assertNull(missing.activeAsset)
    }

    @Test
    fun detailMapsExtractionTruncationAndUsesSafeFallbackWhenMetadataIsAbsent() {
        val truncated = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "url":"https://example.com/item",
                  "extraction_meta":{
                    "adapter_version":"naver-metadata-v1",
                    "final_url":"https://m.blog.naver.com/owner/post",
                    "title_truncated":false,
                    "description_truncated":true,
                    "body_truncated":false,
                    "last_checked_at":"2026-09-15T11:00:00Z",
                    "error_code":"NETWORK_ERROR"
                  }
                }
                """.trimIndent(),
            ).jsonObject,
        )
        val fallback = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "url":"https://example.com/old-detail"
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals("naver-metadata-v1", truncated.extractionMeta.adapterVersion)
        assertEquals("NETWORK_ERROR", truncated.extractionMeta.errorCode)
        assertTrue(truncated.extractionMeta.hasTruncatedText)
        assertFalse(fallback.extractionMeta.hasTruncatedText)
        assertNull(fallback.extractionMeta.adapterVersion)
        assertNull(fallback.extractionMeta.errorCode)
    }

    @Test
    fun detailParsesCurrentClassificationAndCueMetadata() {
        val detail = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "version":8,
                  "text_revision":3,
                  "url":"https://example.com/item",
                  "category_refs":[{
                    "id":"10000000-0000-4000-8000-000000000001",
                    "name":"업무",
                    "kind":"system",
                    "system_code":"work",
                    "origin":"automatic"
                  }],
                  "classification_state":"automatic",
                  "manual_override":false,
                  "classification_reasons":[{
                    "code":"work",
                    "score":3,
                    "rules":[{
                      "id":"work:strong:0",
                      "fields":["user_title","note"]
                    }]
                  }],
                  "classification_explanations":[{
                    "category_code":"work",
                    "rule_id":"work:strong:0",
                    "field":"note",
                    "expression":"회의록"
                  }],
                  "rules_version":"rules-v2.0.0",
                  "search_version":"search-v2.0.0",
                  "cue_state":"limited",
                  "cue_flags":["short_note"],
                  "cue_prompt_dismissed":false
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals(3L, detail.textRevision)
        assertFalse(detail.manualOverride!!)
        assertEquals("system", detail.categoryRefs.single().kind)
        assertEquals("work", detail.categoryRefs.single().systemCode)
        assertEquals("work:strong:0", detail.classificationReasons.single().rules.single().id)
        assertEquals("회의록", detail.classificationExplanations.single().expression)
        assertEquals(
            detail.classificationExplanations,
            detail.currentClassificationExplanations(detail.categoryRefs.single()),
        )
        assertEquals("rules-v2.0.0", detail.rulesVersion)
        assertEquals("search-v2.0.0", detail.searchVersion)
        assertEquals("limited", detail.cueState)
        assertEquals(listOf("short_note"), detail.cueFlags)
        assertFalse(detail.cuePromptDismissed!!)
    }

    @Test
    fun staleClassificationMetadataDoesNotExposeProvenance() {
        val detail = parseLibraryDetailResponse(
            Json.parseToJsonElement(
                """
                {
                  "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                  "text_revision":4,
                  "url":"https://example.com/item",
                  "category_refs":[{
                    "id":"10000000-0000-4000-8000-000000000001",
                    "name":"업무",
                    "kind":"system",
                    "system_code":"work"
                  }],
                  "classification_state":"automatic",
                  "classification_reasons":[{
                    "code":"work",
                    "rules":[{"id":"work:strong:0","fields":["note"]}]
                  }],
                  "classification_explanations":[{
                    "category_code":"work",
                    "rule_id":"work:strong:0",
                    "field":"note",
                    "expression":"예전 표현"
                  }],
                  "rules_version":null,
                  "search_version":null
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertTrue(
            detail.currentClassificationExplanations(detail.categoryRefs.single()).isEmpty(),
        )
    }

    @Test
    fun manualCategoryPatchContainsOnlyVersionAndExplicitCategoryIds() {
        val categoryIds = listOf(
            "10000000-0000-4000-8000-000000000001",
            "10000000-0000-4000-8000-000000000002",
        )
        val preparation = prepareLibraryCategoryEdit(
            ownerId = "member-a",
            itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            requestId = requestId,
            expectedVersion = 7,
            categoryIds = categoryIds,
        ) as LibraryCategoryEditPreparation.Valid
        val body = Json.parseToJsonElement(preparation.operation.body).jsonObject

        assertEquals(setOf("expected_version", "category_ids"), body.keys)
        assertFalse(body.containsKey("title"))
        assertFalse(body.containsKey("note"))
        assertEquals(
            categoryIds,
            body.getValue("category_ids").jsonArray.map { it.jsonPrimitive.content },
        )

        val emptyBody = (
            prepareLibraryCategoryEdit(
                ownerId = "member-a",
                itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                requestId = requestId,
                expectedVersion = 8,
                categoryIds = emptyList(),
            ) as LibraryCategoryEditPreparation.Valid
            ).operation.body
        assertTrue(
            Json.parseToJsonElement(emptyBody).jsonObject
                .getValue("category_ids").jsonArray.isEmpty(),
        )
    }

    @Test
    fun manualCategoryPatchAcceptsFiveAndRejectsSixChoices() {
        val categoryIds = (1..6).map { index ->
            "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        }

        assertTrue(
            prepareLibraryCategoryEdit(
                ownerId = "member-a",
                itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                requestId = requestId,
                expectedVersion = 1,
                categoryIds = categoryIds.take(5),
            ) is LibraryCategoryEditPreparation.Valid,
        )
        assertTrue(
            prepareLibraryCategoryEdit(
                ownerId = "member-a",
                itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                requestId = requestId,
                expectedVersion = 1,
                categoryIds = categoryIds,
            ) is LibraryCategoryEditPreparation.Invalid,
        )
    }

    @Test
    fun dirtyCategoryIntentKeepsVersionFromFirstToggleAcrossBackgroundSnapshots() {
        val firstCategoryId = "10000000-0000-4000-8000-000000000001"
        val secondCategoryId = "10000000-0000-4000-8000-000000000002"
        val intent = LibraryCategoryIntentState
            .pristine(version = 10, categoryIds = listOf(firstCategoryId))
            .toggle(secondCategoryId)
            .applyDisplayedSnapshot(version = 11, categoryIds = listOf(secondCategoryId))
        val preparation = prepareLibraryCategoryEdit(
            ownerId = "member-a",
            itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            requestId = requestId,
            expectedVersion = requireNotNull(intent.submitVersion),
            categoryIds = intent.selectedCategoryIds,
        ) as LibraryCategoryEditPreparation.Valid
        val request = preparation.operation.requestArguments()
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject

        assertTrue(intent.isDirty)
        assertEquals(10L, intent.baseVersion)
        assertEquals(listOf(firstCategoryId, secondCategoryId), intent.selectedCategoryIds)
        assertEquals("PATCH", request.method)
        assertEquals(10L, body.getValue("expected_version").jsonPrimitive.content.toLong())
    }

    @Test
    fun categoryReconfirmationVersionOnlyAdvancesOnExplicitLatestReview() {
        val firstCategoryId = "10000000-0000-4000-8000-000000000001"
        val secondCategoryId = "10000000-0000-4000-8000-000000000002"
        val reviewedAtEleven = LibraryCategoryIntentState
            .pristine(version = 10, categoryIds = listOf(firstCategoryId))
            .toggle(secondCategoryId)
            .reviewLatest(11)
        val backgroundAtTwelve = reviewedAtEleven.applyDisplayedSnapshot(
            version = 12,
            categoryIds = listOf(secondCategoryId),
        )

        assertEquals(10L, backgroundAtTwelve.baseVersion)
        assertEquals(11L, backgroundAtTwelve.reviewedVersion)
        assertEquals(reviewedAtEleven.selectedCategoryIds, backgroundAtTwelve.selectedCategoryIds)
        assertEquals(
            11L,
            categoryExpectedVersion(
                backgroundAtTwelve,
                requireNotNull(backgroundAtTwelve.reviewedVersion),
            ),
        )

        val explicitlyReviewedAtTwelve = backgroundAtTwelve.reviewLatest(12)

        assertEquals(10L, explicitlyReviewedAtTwelve.baseVersion)
        assertEquals(12L, explicitlyReviewedAtTwelve.reviewedVersion)
        assertEquals(
            12L,
            categoryExpectedVersion(
                explicitlyReviewedAtTwelve,
                requireNotNull(explicitlyReviewedAtTwelve.reviewedVersion),
            ),
        )
    }

    @Test
    fun categoryIntentBaseOnlyResetsForDiscardOrOwnedSavedReceipt() {
        val firstCategoryId = "10000000-0000-4000-8000-000000000001"
        val missingCategoryId = "10000000-0000-4000-8000-000000000002"
        val savedCategoryId = "10000000-0000-4000-8000-000000000003"
        val dirty = LibraryCategoryIntentState
            .pristine(version = 10, categoryIds = listOf(firstCategoryId))
            .toggle(missingCategoryId)
        val refreshedAndReviewed = dirty
            .applyDisplayedSnapshot(version = 11, categoryIds = listOf(firstCategoryId))
            .reviewLatest(11)
            .toggle(missingCategoryId)
            .toggle(missingCategoryId)

        assertTrue(refreshedAndReviewed.isDirty)
        assertEquals(10L, refreshedAndReviewed.baseVersion)
        assertEquals(
            listOf(firstCategoryId, missingCategoryId),
            refreshedAndReviewed.selectedCategoryIds,
        )

        val restoredBlockedRequest = refreshedAndReviewed.restoreBlockedPatch(
            expectedVersion = 99,
            categoryIds = refreshedAndReviewed.selectedCategoryIds,
        )

        assertEquals(10L, restoredBlockedRequest.baseVersion)

        val discarded = restoredBlockedRequest.discardAt(11)
        val laterBackground = discarded.applyDisplayedSnapshot(
            version = 12,
            categoryIds = listOf(firstCategoryId),
        )

        assertTrue(laterBackground.isDirty)
        assertEquals(11L, laterBackground.baseVersion)
        assertNull(laterBackground.reviewedVersion)
        assertEquals(discarded.selectedCategoryIds, laterBackground.selectedCategoryIds)

        val saved = laterBackground.acknowledgeSaved(
            version = 13,
            categoryIds = listOf(savedCategoryId),
        )

        assertFalse(saved.isDirty)
        assertNull(saved.baseVersion)
        assertNull(saved.reviewedVersion)
        assertEquals(13L, saved.submitVersion)
        assertEquals(listOf(savedCategoryId), saved.selectedCategoryIds)
    }

    @Test
    fun categoryRequestArgumentsPreserveOriginalUuidAndRawBody() {
        val preparation = prepareLibraryCategoryEdit(
            ownerId = "member-a",
            itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            requestId = requestId,
            expectedVersion = 10,
            categoryIds = emptyList(),
        ) as LibraryCategoryEditPreparation.Valid

        val original = preparation.operation.requestArguments()
        val retry = preparation.operation.requestArguments()
        val reconfirmation = (
            prepareLibraryCategoryEdit(
                ownerId = "member-a",
                itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                requestId = "66666666-7777-4888-8999-aaaaaaaaaaaa",
                expectedVersion = 11,
                categoryIds = emptyList(),
            ) as LibraryCategoryEditPreparation.Valid
            ).operation.requestArguments()

        assertEquals(original, retry)
        assertEquals(requestId, retry.requestId)
        assertEquals(original.body, retry.body)
        assertFalse(original.requestId == reconfirmation.requestId)
        assertFalse(original.body == reconfirmation.body)
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

    private fun categoryExpectedVersion(
        intent: LibraryCategoryIntentState,
        reviewedVersion: Long,
    ): Long {
        val preparation = prepareLibraryCategoryEdit(
            ownerId = "member-a",
            itemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            requestId = requestId,
            expectedVersion = reviewedVersion,
            categoryIds = intent.selectedCategoryIds,
        ) as LibraryCategoryEditPreparation.Valid
        return Json.parseToJsonElement(preparation.operation.body).jsonObject
            .getValue("expected_version").jsonPrimitive.content.toLong()
    }
}
