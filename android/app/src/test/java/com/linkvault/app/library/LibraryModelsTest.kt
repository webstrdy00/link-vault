package com.linkvault.app.library

import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelsTest {
    private val requestId = "11111111-2222-4333-8444-555555555555"
    private val deleteItemId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val otherItemId = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"

    @Test
    fun compactTitleNormalizesWhitespaceAndPrefersFirstSentenceOrLine() {
        val examples = mapOf(
            "  제주\t맛집   안내  " to "제주 맛집 안내",
            "\n  첫 줄에서 찾을 수 있는 제목 \t\r\n두 번째 줄도 보관합니다." to
                "첫 줄에서 찾을 수 있는 제목",
            "맛집을 찾았습니다. 다음 주에 방문할 계획입니다." to "맛집을 찾았습니다.",
            "A useful English summary! More details remain." to "A useful English summary!",
            "버전 3.14의 새 기능입니다. 나머지 설명도 남겨 둡니다." to "버전 3.14의 새 기능입니다.",
            "\"Keep it simple.\" There is more to read." to "\"Keep it simple.\"",
            "😀 이모지도 보관합니다! 다음 문장입니다." to "😀 이모지도 보관합니다!",
        )

        examples.forEach { (original, expected) ->
            assertEquals(original, expected, compactLibraryTitle(original, "https://example.com"))
        }
    }

    @Test
    fun compactTitleCapsLongUnpunctuatedTextAndDoesNotSkipTheFirstLongLine() {
        val korean = "아주 긴 한글 제목을 그대로 찾을 수 있어야 합니다 ".repeat(6).trim()
        val english = "An English post without punctuation keeps all its original words ".repeat(4)
            .trim()

        listOf(korean, english, "가".repeat(70) + "\n짧은 두 번째 줄").forEach { original ->
            assertEquals(
                original.take(59).trimEnd() + "…",
                compactLibraryTitle(original, "https://example.com"),
            )
        }
        assertEquals("가".repeat(60), compactLibraryTitle("가".repeat(60), "unused"))
        assertEquals("가".repeat(59) + "…", compactLibraryTitle("가".repeat(61), "unused"))
    }

    @Test
    fun compactTitleCountsTheEllipsisAndDoesNotSplitEmojiSurrogatePairs() {
        assertEquals("😀".repeat(60), compactLibraryTitle("😀".repeat(60), "unused"))
        val compact = compactLibraryTitle("가".repeat(58) + "😀나다", "unused")

        assertEquals("가".repeat(58) + "😀…", compact)
        assertEquals(60, compact.codePointLength())
        assertEquals(
            "😀".repeat(59) + "…",
            compactLibraryTitle("😀".repeat(61), "unused"),
        )
    }

    @Test
    fun compactTitleFallsBackToTheUrlForMissingOrBlankTitles() {
        val url = "https://example.com/article"
        val longUrl = "https://example.com/" + "a".repeat(80)

        listOf(null, "", " \t\r\n ").forEach { title ->
            assertEquals(url, compactLibraryTitle(title, url))
            assertEquals(longUrl.take(59) + "…", compactLibraryTitle(title, longUrl))
        }
        assertEquals("@example_account", compactLibraryTitle("@example_account", url))
    }

    @Test
    fun compactPresentationLeavesAcquiredFieldsAndManualEditSourceUntouched() {
        val userTitle = "  내가 정한 제목\t" + "원문 단어 ".repeat(20) + "\n마지막 줄"
        val fetchedTitle = "  가져온 제목입니다.\n" + "가져온 전체 내용 ".repeat(20)
        val displayTitle = "  표시 제목입니다.  두 번째 문장입니다.\n세 번째 줄"
        val description = "미리보기 설명 첫 줄\n" + "설명을 생략하지 않습니다. ".repeat(20)
        val bodyText = "본문 첫 줄\n" + "본문도 그대로 보관합니다. ".repeat(20)
        val sharedText = "공유한 원래 글\nhttps://example.com/article"
        val detail = parseLibraryDetailResponse(
            buildJsonObject {
                put("id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
                put("version", 7)
                put("url", "https://example.com/article")
                put("display_title", displayTitle)
                put("user_title", userTitle)
                put("fetched_title", fetchedTitle)
                put("description", description)
                put("body_text", bodyText)
                put("shared_text", sharedText)
                put("note", "수동 메모")
            },
        )

        assertEquals("표시 제목입니다.", compactLibraryTitle(detail.displayTitle, detail.url))
        assertTrue(compactLibraryTitle(detail.userTitle, detail.url).endsWith("…"))
        assertEquals(displayTitle, detail.displayTitle)
        assertEquals(userTitle, detail.userTitle)
        assertEquals(fetchedTitle, detail.fetchedTitle)
        assertEquals(description, detail.description)
        assertEquals(bodyText, detail.bodyText)
        assertEquals(sharedText, detail.sharedText)

        val preparation = prepareLibraryEdit(
            ownerId = "member-a",
            itemId = detail.id,
            requestId = requestId,
            expectedVersion = requireNotNull(detail.version),
            originalTitle = detail.userTitle,
            originalNote = detail.note,
            form = LibraryEditForm(title = detail.userTitle.orEmpty(), note = "메모만 수정"),
        ) as LibraryEditPreparation.Valid
        val body = Json.parseToJsonElement(preparation.operation.body).jsonObject
        assertEquals(userTitle, preparation.operation.originalTitle)
        assertEquals(userTitle, preparation.operation.form.title)
        assertFalse(body.containsKey("title"))
        assertEquals("메모만 수정", body.getValue("note").jsonPrimitive.content)
    }

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

    @Test
    fun listDeleteUsesCurrentSummaryVersionWithoutOpeningOrReplacingDetail() {
        val state = listDeleteState().copy(
            detail = LibraryDetailState.Loaded(
                LibraryItemDetail(id = deleteItemId, version = 2, url = "https://example.com/old"),
            ),
        )
        val confirming = state.withListDeleteConfirmation(deleteItemId)
        val deletion = requireNotNull(confirming.delete)

        assertEquals(deleteItemId, deletion.itemId)
        assertEquals(9L, deletion.expectedVersion)
        assertEquals(LibraryDeleteStatus.Confirming, deletion.status)
        assertEquals(state.detail, confirming.detail)
        assertEquals(state.items, confirming.items)
        assertEquals(state.outboxEntries, confirming.outboxEntries)

        val refreshed = state.copy(items = state.items.map { it.copy(version = 12) })
            .withListDeleteConfirmation(deleteItemId)
        assertEquals(12L, refreshed.delete?.expectedVersion)
        val operation = prepareLibraryDelete(
            ownerId = "member-a",
            itemId = deletion.itemId,
            requestId = requestId,
            expectedVersion = deletion.expectedVersion,
        )
        assertEquals("member-a", operation.ownerId)
        assertEquals(deleteItemId, operation.itemId)
        assertEquals(requestId, operation.requestId)
        assertEquals("""{"expected_version":9}""", operation.body)
    }

    @Test
    fun listDeleteRejectsMissingInvalidAndUnavailableTargets() {
        val state = listDeleteState()
        assertEquals(state, state.withListDeleteConfirmation("missing"))
        listOf(null, 0L, -1L).forEach { version ->
            val unknownVersion = state.copy(items = listOf(state.items.first().copy(version = version)))
            assertEquals(unknownVersion, unknownVersion.withListDeleteConfirmation(deleteItemId))
        }
        val invalidId = state.copy(items = listOf(state.items.first().copy(id = "invalid")))
        assertEquals(invalidId, invalidId.withListDeleteConfirmation("invalid"))
        listOf(LibraryAvailability.Checking, LibraryAvailability.SignInRequired("로그인 필요"))
            .forEach { availability ->
                val unavailable = state.copy(availability = availability)
                assertEquals(unavailable, unavailable.withListDeleteConfirmation(deleteItemId))
            }
    }

    @Test
    fun listDeleteCannotReplaceAnExistingEditOrDeletion() {
        val state = listDeleteState()
        val editing = state.copy(
            edit = LibraryEditUiState(otherItemId, 5, null, null, LibraryEditForm("", "")),
        )
        assertEquals(editing, editing.withListDeleteConfirmation(deleteItemId))
        val confirming = state.withListDeleteConfirmation(deleteItemId)
        assertEquals(confirming, confirming.withListDeleteConfirmation(otherItemId))
        val queued = confirming.copy(
            delete = confirming.delete?.copy(status = LibraryDeleteStatus.Queued(requestId)),
        )
        assertEquals(queued, queued.withListDeleteConfirmation(otherItemId))
    }

    @Test
    fun cancellingListConfirmationRestoresListWithoutCreatingAnOutboxRequest() {
        val original = listDeleteState().copy(outboxEntries = listOf(deleteEntry(OutboxState.RETRY)))
        val confirming = original.withListDeleteConfirmation(otherItemId)

        assertEquals(LibraryDetailState.None, confirming.detail)
        assertEquals(original, confirming.withDeleteConfirmationCancelled())
        assertEquals(original.outboxEntries, confirming.outboxEntries)
    }

    @Test
    fun listDeleteRestoresQueuedFailedAndBlockedRequestsWithoutSelectingDetail() {
        val state = listDeleteState()
        val expectedStatuses = mapOf(
            OutboxState.PENDING to LibraryDeleteStatus.Queued(requestId),
            OutboxState.RUNNING to LibraryDeleteStatus.Queued(requestId),
            OutboxState.RETRY to LibraryDeleteStatus.Queued(requestId),
            OutboxState.WAITING_LOGIN to LibraryDeleteStatus.Queued(requestId),
            OutboxState.FAILED to LibraryDeleteStatus.Failed(requestId, "요청 실패"),
            OutboxState.CONFLICT to LibraryDeleteStatus.Blocked(requestId, LibraryDeleteBlockReason.VERSION_CONFLICT),
            OutboxState.EXPIRED to LibraryDeleteStatus.Blocked(requestId, LibraryDeleteBlockReason.EXPIRED),
        )
        expectedStatuses.forEach { (outboxState, expectedStatus) ->
            val entry = deleteEntry(outboxState)
            val restored = state.copy(delete = state.restoredDelete(listOf(entry), "member-a"))

            assertEquals(outboxState.name, expectedStatus, restored.delete?.status)
            assertEquals(deleteItemId, restored.delete?.itemId)
            assertEquals(7L, restored.delete?.expectedVersion)
            assertEquals(state.items, restored.items)
            assertEquals(LibraryDetailState.None, restored.detail)
            assertNull(restored.restoredDelete(listOf(entry), "member-a"))
        }
    }

    @Test
    fun listDeleteRestorationRejectsOtherOwnersItemsReceiptsAndMalformedVersions() {
        val state = listDeleteState()
        val entry = deleteEntry(OutboxState.CONFLICT)
        val invalidEntries = listOf(
            entry.copy(ownerId = "member-b"),
            entry.copy(path = "/items/cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa"),
            entry.copy(state = OutboxState.SAVED),
            entry.copy(method = "PATCH"),
            entry.copy(payloadJson = """{"expected_version":0}"""),
            entry.copy(payloadJson = """{"expected_version":"7"}"""),
            entry.copy(payloadJson = """{"expected_version":7,"extra":true}"""),
        )

        assertNull(state.restoredDelete(invalidEntries, "member-a"))
        val selectedOther = state.copy(detail = LibraryDetailState.Loading(otherItemId))
        assertNull(selectedOther.restoredDelete(listOf(entry), "member-a"))
        val editing = state.copy(
            edit = LibraryEditUiState(otherItemId, 5, null, null, LibraryEditForm("", "")),
        )
        assertNull(editing.restoredDelete(listOf(entry), "member-a"))
    }

    @Test
    fun reviewingListDeleteConflictKeepsListAndUnrelatedDetailUntilReconfirmed() {
        val loading = listDeleteState().copy(
            delete = LibraryDeleteUiState(
                deleteItemId,
                9,
                LibraryDeleteStatus.LoadingLatest(requestId, LibraryDeleteBlockReason.VERSION_CONFLICT),
            ),
        )
        val latest = LibraryDetailState.Loaded(
            LibraryItemDetail(deleteItemId, 10, url = "https://example.com/latest"),
        )
        val ready = loading.withLatestDeleteVersion(latest, requestId)

        assertEquals(LibraryDetailState.None, ready.detail)
        assertEquals(loading.items, ready.items)
        assertEquals(10L, ready.delete?.expectedVersion)
        assertEquals(
            LibraryDeleteStatus.ReadyToConfirm(requestId, LibraryDeleteBlockReason.VERSION_CONFLICT),
            ready.delete?.status,
        )
        val cancelled = ready.withDeleteConfirmationCancelled()
        assertEquals(
            LibraryDeleteStatus.Blocked(requestId, LibraryDeleteBlockReason.VERSION_CONFLICT),
            cancelled.delete?.status,
        )
        assertEquals(loading.items, cancelled.items)
        assertEquals(10L, cancelled.delete?.expectedVersion)

        val unrelated = loading.copy(detail = LibraryDetailState.Loading(otherItemId))
        assertEquals(unrelated.detail, unrelated.withLatestDeleteVersion(latest, requestId).detail)
        val selectedTarget = loading.copy(detail = LibraryDetailState.Loading(deleteItemId))
        assertEquals(latest, selectedTarget.withLatestDeleteVersion(latest, requestId).detail)
        assertEquals(loading, loading.withLatestDeleteVersion(latest, "different-request"))
        assertEquals(
            loading,
            loading.withLatestDeleteVersion(latest.copy(item = latest.item.copy(id = otherItemId)), requestId),
        )
    }

    @Test
    fun listDeleteReceiptRemovesOnlyItsTargetAndKeepsUnrelatedSelectedDetailAndEditor() {
        val state = listDeleteState().withListDeleteConfirmation(deleteItemId).copy(
            detail = LibraryDetailState.Loading(otherItemId),
            edit = LibraryEditUiState(otherItemId, 5, null, null, LibraryEditForm("draft", "note")),
        )
        val applied = state.withDeletedItems(setOf(deleteItemId), "삭제 완료")

        assertEquals(listOf(state.items.last()), applied.items)
        assertEquals(state.detail, applied.detail)
        assertEquals(state.edit, applied.edit)
        assertNull(applied.delete)
        assertEquals("삭제 완료", applied.deletionNotice)
        val listOnly = state.copy(detail = LibraryDetailState.None, edit = null)
            .withDeletedItems(setOf(deleteItemId), "삭제 완료")
        assertEquals(LibraryDetailState.None, listOnly.detail)
        assertEquals(listOf(state.items.last()), listOnly.items)
        val targetSelected = state.copy(detail = LibraryDetailState.Loading(deleteItemId), edit = null)
            .withDeletedItems(setOf(deleteItemId), "삭제 완료")
        assertEquals(LibraryDetailState.None, targetSelected.detail)
    }

    private fun listDeleteState() = LibraryUiState(
        availability = LibraryAvailability.Ready,
        form = LibrarySaveForm(initialUrl = null, sharedText = ""),
        items = listOf(
            LibraryItemSummary(deleteItemId, 9, url = "https://example.com/target"),
            LibraryItemSummary(otherItemId, 5, url = "https://example.com/other"),
        ),
        isListLoading = false,
    )

    private fun deleteEntry(state: OutboxState) = OutboxEntry(
        requestId = requestId,
        ownerId = "member-a",
        method = "DELETE",
        path = "/items/$deleteItemId",
        payloadJson = """{"expected_version":7}""",
        state = state,
        attemptCount = 1,
        createdAt = 1,
        expiresAt = 86_400_001,
        nextAttemptAt = 1,
        errorMessage = "요청 실패",
    )

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
