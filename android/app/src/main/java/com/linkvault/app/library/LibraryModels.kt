package com.linkvault.app.library

import java.net.URI
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val LIBRARY_URL_MAX_CODE_POINTS = 4096
const val LIBRARY_TITLE_MAX_CODE_POINTS = 300
const val LIBRARY_NOTE_MAX_CODE_POINTS = 4000
const val LIBRARY_SHARED_TEXT_MAX_CODE_POINTS = 4000
const val LIBRARY_CATEGORY_MAX_SELECTION = 5

private val libraryJson = Json { ignoreUnknownKeys = true }

data class LibrarySaveForm(
    val initialUrl: String?,
    val sharedText: String,
    val title: String = "",
    val note: String = "",
)

enum class LibraryFormField {
    URL,
    TITLE,
    NOTE,
    SHARED_TEXT,
}

data class LibraryFormIssue(
    val field: LibraryFormField,
    val message: String,
)

sealed interface LibrarySavePreparation {
    data class Valid(val operation: LibrarySaveOperation) : LibrarySavePreparation
    data class Invalid(val issues: List<LibraryFormIssue>) : LibrarySavePreparation
}

data class LibraryRequestArguments(
    val path: String,
    val method: String,
    val body: String?,
    val requestId: String?,
)

data class LibrarySaveOperation(
    val ownerId: String,
    val requestId: String,
    val body: String,
) {
    fun requestArguments() = LibraryRequestArguments(
        path = "/items",
        method = "POST",
        body = body,
        requestId = requestId,
    )
}

data class LibraryEditForm(
    val title: String,
    val note: String,
)

sealed interface LibraryEditPreparation {
    data class Valid(val operation: LibraryEditOperation) : LibraryEditPreparation
    data class Invalid(val issues: List<LibraryFormIssue>) : LibraryEditPreparation
}

data class LibraryEditOperation(
    val ownerId: String,
    val itemId: String,
    val requestId: String,
    val expectedVersion: Long,
    val originalTitle: String?,
    val originalNote: String?,
    val form: LibraryEditForm,
    val body: String,
) {
    fun requestArguments() = LibraryRequestArguments(
        path = "/items/$itemId",
        method = "PATCH",
        body = body,
        requestId = requestId,
    )
}

data class LibraryEditPatch(
    val expectedVersion: Long,
    val changesTitle: Boolean,
    val title: String?,
    val changesNote: Boolean,
    val note: String?,
)

sealed interface LibraryCategoryEditPreparation {
    data class Valid(val operation: LibraryCategoryEditOperation) : LibraryCategoryEditPreparation
    data class Invalid(val messages: List<String>) : LibraryCategoryEditPreparation
}

data class LibraryCategoryEditOperation(
    val ownerId: String,
    val itemId: String,
    val requestId: String,
    val expectedVersion: Long,
    val categoryIds: List<String>,
    val body: String,
) {
    fun requestArguments() = LibraryRequestArguments(
        path = "/items/$itemId",
        method = "PATCH",
        body = body,
        requestId = requestId,
    )
}

data class LibraryCategoryPatch(
    val expectedVersion: Long,
    val categoryIds: List<String>,
)

internal data class LibraryCategoryIntentState(
    val selectedCategoryIds: List<String>,
    val pristineVersion: Long?,
    val baseVersion: Long?,
    val reviewedVersion: Long?,
    val isDirty: Boolean,
) {
    val submitVersion: Long?
        get() = if (isDirty) baseVersion else pristineVersion

    fun toggle(categoryId: String): LibraryCategoryIntentState {
        val updatedIds = if (categoryId in selectedCategoryIds) {
            selectedCategoryIds - categoryId
        } else {
            selectedCategoryIds + categoryId
        }
        return copy(
            selectedCategoryIds = updatedIds,
            baseVersion = if (isDirty) baseVersion else pristineVersion,
            isDirty = true,
        )
    }

    fun applyDisplayedSnapshot(
        version: Long?,
        categoryIds: List<String>,
    ): LibraryCategoryIntentState = if (isDirty) {
        this
    } else {
        pristine(version, categoryIds)
    }

    fun restoreBlockedPatch(
        expectedVersion: Long,
        categoryIds: List<String>,
    ): LibraryCategoryIntentState = copy(
        selectedCategoryIds = categoryIds.distinct(),
        baseVersion = if (isDirty) baseVersion else expectedVersion,
        reviewedVersion = null,
        isDirty = true,
    )

    fun reviewLatest(version: Long): LibraryCategoryIntentState = copy(
        reviewedVersion = version,
    )

    fun discardAt(version: Long?): LibraryCategoryIntentState = copy(
        pristineVersion = version,
        baseVersion = version,
        reviewedVersion = null,
    )

    fun acknowledgeSaved(
        version: Long?,
        categoryIds: List<String>,
    ): LibraryCategoryIntentState = pristine(version, categoryIds)

    companion object {
        fun pristine(
            version: Long?,
            categoryIds: List<String>,
        ): LibraryCategoryIntentState = LibraryCategoryIntentState(
            selectedCategoryIds = categoryIds.distinct(),
            pristineVersion = version,
            baseVersion = null,
            reviewedVersion = null,
            isDirty = false,
        )
    }
}

fun validateLibrarySaveForm(form: LibrarySaveForm): List<LibraryFormIssue> = buildList {
    val url = form.initialUrl
    when {
        url.isNullOrEmpty() -> add(
            LibraryFormIssue(
                LibraryFormField.URL,
                "보관할 원문 URL이 없어요. 원문 입력 화면에서 URL을 먼저 확인해 주세요.",
            ),
        )

        url.codePointLength() > LIBRARY_URL_MAX_CODE_POINTS -> add(
            LibraryFormIssue(
                LibraryFormField.URL,
                "원문 URL은 최대 ${LIBRARY_URL_MAX_CODE_POINTS}자까지 보관할 수 있어요.",
            ),
        )

        !url.isValidLibraryUrl() -> add(
            LibraryFormIssue(
                LibraryFormField.URL,
                "HTTP 또는 HTTPS 원문 URL을 다시 확인해 주세요.",
            ),
        )
    }

    if (form.title.codePointLength() > LIBRARY_TITLE_MAX_CODE_POINTS) {
        add(
            LibraryFormIssue(
                LibraryFormField.TITLE,
                "제목은 최대 ${LIBRARY_TITLE_MAX_CODE_POINTS}자까지 입력할 수 있어요.",
            ),
        )
    }
    if (form.note.codePointLength() > LIBRARY_NOTE_MAX_CODE_POINTS) {
        add(
            LibraryFormIssue(
                LibraryFormField.NOTE,
                "메모는 최대 ${LIBRARY_NOTE_MAX_CODE_POINTS}자까지 입력할 수 있어요.",
            ),
        )
    }
    if (form.sharedText.codePointLength() > LIBRARY_SHARED_TEXT_MAX_CODE_POINTS) {
        add(
            LibraryFormIssue(
                LibraryFormField.SHARED_TEXT,
                "공유 텍스트가 ${LIBRARY_SHARED_TEXT_MAX_CODE_POINTS}자를 넘어요. 원문 입력 화면에서 내용을 줄여 주세요.",
            ),
        )
    }
}

fun prepareLibrarySave(
    form: LibrarySaveForm,
    ownerId: String,
    requestId: String,
): LibrarySavePreparation {
    val issues = validateLibrarySaveForm(form).toMutableList()
    if (ownerId.isBlank()) {
        issues += LibraryFormIssue(
            LibraryFormField.URL,
            "로그인 계정을 확인하지 못했어요. 다시 로그인해 주세요.",
        )
    }
    if (!requestId.isCanonicalUuid()) {
        issues += LibraryFormIssue(
            LibraryFormField.URL,
            "저장 요청을 만들지 못했어요. 다시 시도해 주세요.",
        )
    }
    if (issues.isNotEmpty()) return LibrarySavePreparation.Invalid(issues)

    val body = buildJsonObject {
        put("url", form.initialUrl!!)
        form.title.takeUnless(String::isBlank)?.let { put("title", it) }
        form.note.takeUnless(String::isBlank)?.let { put("note", it) }
        form.sharedText.takeUnless(String::isBlank)?.let { put("shared_text", it) }
    }.toString()
    return LibrarySavePreparation.Valid(
        LibrarySaveOperation(
            ownerId = ownerId,
            requestId = requestId,
            body = body,
        ),
    )
}

fun validateLibraryEditForm(form: LibraryEditForm): List<LibraryFormIssue> = buildList {
    if (form.title.codePointLength() > LIBRARY_TITLE_MAX_CODE_POINTS) {
        add(
            LibraryFormIssue(
                LibraryFormField.TITLE,
                "제목은 최대 ${LIBRARY_TITLE_MAX_CODE_POINTS}자까지 입력할 수 있어요.",
            ),
        )
    }
    if (form.note.codePointLength() > LIBRARY_NOTE_MAX_CODE_POINTS) {
        add(
            LibraryFormIssue(
                LibraryFormField.NOTE,
                "메모는 최대 ${LIBRARY_NOTE_MAX_CODE_POINTS}자까지 입력할 수 있어요.",
            ),
        )
    }
}

fun prepareLibraryEdit(
    ownerId: String,
    itemId: String,
    requestId: String,
    expectedVersion: Long,
    originalTitle: String?,
    originalNote: String?,
    form: LibraryEditForm,
): LibraryEditPreparation {
    val issues = validateLibraryEditForm(form).toMutableList()
    if (ownerId.isBlank()) {
        issues += LibraryFormIssue(
            LibraryFormField.TITLE,
            "로그인 계정을 확인하지 못했어요. 다시 로그인해 주세요.",
        )
    }
    if (itemId.isBlank() || expectedVersion < 0L || !requestId.isCanonicalUuid()) {
        issues += LibraryFormIssue(
            LibraryFormField.TITLE,
            "수정 요청을 만들지 못했어요. 최신 내용을 다시 확인해 주세요.",
        )
    }

    val desiredTitle = form.title.takeUnless(String::isEmpty)
    val desiredNote = form.note.takeUnless(String::isEmpty)
    val changesTitle = desiredTitle != originalTitle
    val changesNote = desiredNote != originalNote
    if (!changesTitle && !changesNote) {
        issues += LibraryFormIssue(
            LibraryFormField.TITLE,
            "변경된 제목이나 메모가 없어요.",
        )
    }
    if (issues.isNotEmpty()) return LibraryEditPreparation.Invalid(issues)

    val body = buildJsonObject {
        put("expected_version", expectedVersion)
        if (changesTitle) {
            put("title", desiredTitle?.let(::JsonPrimitive) ?: JsonNull)
        }
        if (changesNote) {
            put("note", desiredNote?.let(::JsonPrimitive) ?: JsonNull)
        }
    }.toString()
    return LibraryEditPreparation.Valid(
        LibraryEditOperation(
            ownerId = ownerId,
            itemId = itemId,
            requestId = requestId,
            expectedVersion = expectedVersion,
            originalTitle = originalTitle,
            originalNote = originalNote,
            form = form,
            body = body,
        ),
    )
}

internal fun parseLibraryEditPatch(payloadJson: String): LibraryEditPatch {
    val payload = libraryJson.parseToJsonElement(payloadJson) as? JsonObject
        ?: throw IllegalArgumentException("Edit payload must be an object.")
    val expectedVersion = (payload["expected_version"] as? JsonPrimitive)?.longOrNull
        ?: throw IllegalArgumentException("Edit payload is missing expected_version.")
    val changesTitle = payload.containsKey("title")
    val changesNote = payload.containsKey("note")
    return LibraryEditPatch(
        expectedVersion = expectedVersion,
        changesTitle = changesTitle,
        title = payload.nullableString("title"),
        changesNote = changesNote,
        note = payload.nullableString("note"),
    )
}

fun prepareLibraryCategoryEdit(
    ownerId: String,
    itemId: String,
    requestId: String,
    expectedVersion: Long,
    categoryIds: List<String>,
): LibraryCategoryEditPreparation {
    val messages = buildList {
        if (ownerId.isBlank()) add("로그인 계정을 확인하지 못했어요. 다시 로그인해 주세요.")
        if (!itemId.isCanonicalUuid() || expectedVersion < 0L || !requestId.isCanonicalUuid()) {
            add("분류 요청을 만들지 못했어요. 최신 내용을 다시 확인해 주세요.")
        }
        if (categoryIds.size > LIBRARY_CATEGORY_MAX_SELECTION) {
            add("분류는 최대 ${LIBRARY_CATEGORY_MAX_SELECTION}개까지 선택할 수 있어요.")
        }
        if (categoryIds.distinct().size != categoryIds.size ||
            categoryIds.any { !it.isCanonicalUuid() }
        ) {
            add("선택한 분류를 다시 확인해 주세요.")
        }
    }
    if (messages.isNotEmpty()) return LibraryCategoryEditPreparation.Invalid(messages)

    val body = buildJsonObject {
        put("expected_version", expectedVersion)
        put("category_ids", JsonArray(categoryIds.map(::JsonPrimitive)))
    }.toString()
    return LibraryCategoryEditPreparation.Valid(
        LibraryCategoryEditOperation(
            ownerId = ownerId,
            itemId = itemId,
            requestId = requestId,
            expectedVersion = expectedVersion,
            categoryIds = categoryIds,
            body = body,
        ),
    )
}

internal fun parseLibraryCategoryPatch(payloadJson: String): LibraryCategoryPatch {
    val payload = libraryJson.parseToJsonElement(payloadJson) as? JsonObject
        ?: throw IllegalArgumentException("Category payload must be an object.")
    val expectedVersion = (payload["expected_version"] as? JsonPrimitive)?.longOrNull
        ?: throw IllegalArgumentException("Category payload is missing expected_version.")
    val categoryIds = (payload["category_ids"] as? JsonArray)?.map { element ->
        (element as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Category IDs must be strings.")
    } ?: throw IllegalArgumentException("Category payload is missing category_ids.")
    return LibraryCategoryPatch(expectedVersion, categoryIds)
}

@Serializable
data class LibraryCategoryRef(
    val id: String? = null,
    val name: String? = null,
    val kind: String? = null,
    @SerialName("system_code") val systemCode: String? = null,
    val origin: String? = null,
)

@Serializable
data class LibraryClassificationRuleReason(
    val id: String? = null,
    val fields: List<String> = emptyList(),
)

@Serializable
data class LibraryClassificationReason(
    val code: String? = null,
    val rules: List<LibraryClassificationRuleReason> = emptyList(),
)

@Serializable
data class LibraryClassificationExplanation(
    @SerialName("category_code") val categoryCode: String? = null,
    @SerialName("rule_id") val ruleId: String? = null,
    val field: String? = null,
    val expression: String? = null,
)

@Serializable
data class LibraryExtractionMeta(
    @SerialName("adapter_version") val adapterVersion: String? = null,
    @SerialName("final_url") val finalUrl: String? = null,
    @SerialName("title_truncated") val titleTruncated: Boolean = false,
    @SerialName("description_truncated") val descriptionTruncated: Boolean = false,
    @SerialName("body_truncated") val bodyTruncated: Boolean = false,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
) {
    val hasTruncatedText: Boolean
        get() = titleTruncated || descriptionTruncated || bodyTruncated
}

@Serializable
data class LibraryActiveAsset(
    val id: String,
    @SerialName("object_path") val objectPath: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Long,
    val width: Int,
    val height: Int,
    @SerialName("ocr_text") val ocrText: String? = null,
    @SerialName("ocr_truncated") val ocrTruncated: Boolean = false,
)

@Serializable
data class LibraryItemSummary(
    val id: String,
    val version: Long? = null,
    val url: String,
    @SerialName("display_title") val displayTitle: String? = null,
    val source: String? = null,
    @SerialName("note_excerpt") val noteExcerpt: String? = null,
    @SerialName("category_refs") val categoryRefs: List<LibraryCategoryRef> = emptyList(),
    @SerialName("has_attachment") val hasAttachment: Boolean? = null,
    @SerialName("metadata_state") val metadataState: String? = null,
    @SerialName("ocr_state") val ocrState: String? = null,
    @SerialName("classification_state") val classificationState: String? = null,
    @SerialName("cue_state") val cueState: String? = null,
    @SerialName("cue_flags") val cueFlags: List<String> = emptyList(),
    @SerialName("match_type") val matchType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LibraryItemDetail(
    val id: String,
    val version: Long? = null,
    @SerialName("text_revision") val textRevision: Long? = null,
    val url: String,
    @SerialName("display_title") val displayTitle: String? = null,
    val source: String? = null,
    @SerialName("note_excerpt") val noteExcerpt: String? = null,
    @SerialName("category_refs") val categoryRefs: List<LibraryCategoryRef> = emptyList(),
    @SerialName("has_attachment") val hasAttachment: Boolean? = null,
    @SerialName("metadata_state") val metadataState: String? = null,
    @SerialName("ocr_state") val ocrState: String? = null,
    @SerialName("classification_state") val classificationState: String? = null,
    @SerialName("manual_override") val manualOverride: Boolean? = null,
    @SerialName("classification_reasons")
    val classificationReasons: List<LibraryClassificationReason> = emptyList(),
    @SerialName("classification_explanations")
    val classificationExplanations: List<LibraryClassificationExplanation> = emptyList(),
    @SerialName("rules_version") val rulesVersion: String? = null,
    @SerialName("search_version") val searchVersion: String? = null,
    @SerialName("cue_state") val cueState: String? = null,
    @SerialName("cue_flags") val cueFlags: List<String> = emptyList(),
    @SerialName("cue_prompt_dismissed") val cuePromptDismissed: Boolean? = null,
    @SerialName("match_type") val matchType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("user_title") val userTitle: String? = null,
    @SerialName("fetched_title") val fetchedTitle: String? = null,
    @SerialName("shared_text") val sharedText: String? = null,
    val description: String? = null,
    @SerialName("body_text") val bodyText: String? = null,
    val note: String? = null,
    @SerialName("extraction_meta")
    val extractionMeta: LibraryExtractionMeta = LibraryExtractionMeta(),
    @SerialName("active_asset") val activeAsset: LibraryActiveAsset? = null,
)

fun LibraryItemDetail.currentClassificationExplanations(
    category: LibraryCategoryRef,
): List<LibraryClassificationExplanation> {
    val categoryCode = category.systemCode ?: return emptyList()
    if (textRevision == null || rulesVersion.isNullOrBlank() || classificationState != "automatic") {
        return emptyList()
    }
    val currentRuleIds = classificationReasons
        .filter { it.code == categoryCode }
        .flatMap { reason -> reason.rules.mapNotNull(LibraryClassificationRuleReason::id) }
        .toSet()
    if (currentRuleIds.isEmpty()) return emptyList()
    return classificationExplanations.filter { explanation ->
        explanation.categoryCode == categoryCode &&
            explanation.ruleId in currentRuleIds &&
            !explanation.field.isNullOrBlank() &&
            !explanation.expression.isNullOrBlank()
    }
}

@Serializable
private data class LibraryListResponse(
    val items: List<LibraryItemSummary>,
    @SerialName("has_more") val hasMore: Boolean,
)

data class LibraryListPage(
    val items: List<LibraryItemSummary>,
    val hasMore: Boolean,
)

@Serializable
private data class LibrarySaveResponse(
    val duplicate: Boolean,
    val item: LibraryItemSummary,
)

data class LibrarySaveResult(
    val duplicate: Boolean,
    val item: LibraryItemSummary,
)

internal fun parseLibraryListResponse(response: JsonObject): LibraryListPage {
    val parsed = libraryJson.decodeFromJsonElement<LibraryListResponse>(response)
    return LibraryListPage(parsed.items, parsed.hasMore)
}

internal fun parseLibrarySaveResponse(response: JsonObject): LibrarySaveResult {
    val parsed = libraryJson.decodeFromJsonElement<LibrarySaveResponse>(response)
    return LibrarySaveResult(parsed.duplicate, parsed.item)
}

internal fun parseLibraryDetailResponse(response: JsonObject): LibraryItemDetail =
    libraryJson.decodeFromJsonElement(response)

internal fun parseLibrarySummaryResponse(response: JsonObject): LibraryItemSummary =
    libraryJson.decodeFromJsonElement(response)

internal fun String.codePointLength(): Int = codePointCount(0, length)

private fun String.isValidLibraryUrl(): Boolean {
    if (any(Char::isWhitespace)) return false
    val uri = try {
        URI(this)
    } catch (_: Exception) {
        return false
    }
    val schemeIsAllowed = uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true)
    return schemeIsAllowed &&
        !uri.isOpaque &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.port in -1..65_535
}

private fun String.isCanonicalUuid(): Boolean = try {
    UUID.fromString(this).toString().equals(this, ignoreCase = true)
} catch (_: IllegalArgumentException) {
    false
}

private fun JsonObject.nullableString(key: String): String? {
    val element = this[key] ?: return null
    if (element === JsonNull) return null
    return (element as? JsonPrimitive)?.content
        ?: throw IllegalArgumentException("$key must be a string or null.")
}
