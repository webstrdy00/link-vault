package com.linkvault.app.discovery

import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.library.LibraryListPage
import com.linkvault.app.library.codePointLength
import com.linkvault.app.library.parseLibraryListResponse
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

const val DISCOVERY_QUERY_MAX_CODE_POINTS = 200
const val DISCOVERY_QUERY_MAX_WORDS = 10
const val DISCOVERY_PAGE_SIZE = 20
const val DISCOVERY_PAGE_LIMIT_MAX = 50
const val DISCOVERY_CATEGORY_NAME_MAX_CODE_POINTS = 30
const val DISCOVERY_CUSTOM_CATEGORY_LIMIT = 30

private val discoveryJson = Json { ignoreUnknownKeys = true }

enum class DiscoverySource(
    val apiValue: String?,
    val displayName: String,
) {
    ALL(null, "전체"),
    INSTAGRAM("instagram", "Instagram"),
    THREADS("threads", "Threads"),
    NAVER_BLOG("naver_blog", "네이버 블로그"),
    OTHER("other", "기타"),
}

data class DiscoveryFilterInput(
    val query: String = "",
    val categoryId: String? = null,
    val unclassified: Boolean = false,
    val source: DiscoverySource = DiscoverySource.ALL,
    val dateFrom: String = "",
    val dateTo: String = "",
    val aliases: Boolean = true,
    val needsCues: Boolean = false,
)

enum class DiscoveryFilterField {
    QUERY,
    CATEGORY,
    DATE_FROM,
    DATE_TO,
    DATE_RANGE,
}

data class DiscoveryFilterIssue(
    val field: DiscoveryFilterField,
    val message: String,
)

sealed interface DiscoveryQueryPreparation {
    data class Valid(val snapshot: DiscoveryQuerySnapshot) : DiscoveryQueryPreparation
    data class Invalid(val issues: List<DiscoveryFilterIssue>) : DiscoveryQueryPreparation
}

data class DiscoveryQuerySnapshot internal constructor(
    val query: String?,
    val categoryId: String?,
    val unclassified: Boolean,
    val source: DiscoverySource,
    val dateFromUtc: String?,
    val dateToExclusiveUtc: String?,
    val aliases: Boolean,
    val needsCues: Boolean,
    val queryKey: String,
) {
    fun itemsPath(
        limit: Int = DISCOVERY_PAGE_SIZE,
        offset: Int = 0,
    ): String = buildDiscoveryItemsPath(this, limit, offset)
}

data class DiscoverySearchToken(
    val ownerId: String,
    val generation: Long,
    val queryKey: String,
    val offset: Int,
)

data class DiscoveryRequestToken(
    val ownerId: String,
    val sessionGeneration: Long,
    val visibilityGeneration: Long,
)

fun isCurrentDiscoveryRequest(
    token: DiscoveryRequestToken,
    ownerId: String?,
    sessionGeneration: Long,
    visibilityGeneration: Long,
    isVisible: Boolean,
): Boolean = isVisible &&
    token.ownerId == ownerId &&
    token.sessionGeneration == sessionGeneration &&
    token.visibilityGeneration == visibilityGeneration

fun prepareDiscoveryQuery(
    input: DiscoveryFilterInput,
    zoneId: ZoneId = ZoneId.systemDefault(),
): DiscoveryQueryPreparation {
    val issues = mutableListOf<DiscoveryFilterIssue>()
    val query = input.query.takeUnless(String::isBlank)
    if (query != null) {
        if (query.codePointLength() > DISCOVERY_QUERY_MAX_CODE_POINTS) {
            issues += DiscoveryFilterIssue(
                DiscoveryFilterField.QUERY,
                "검색어는 최대 ${DISCOVERY_QUERY_MAX_CODE_POINTS}자까지 입력할 수 있어요.",
            )
        }
        val wordCount = discoveryWordCount(query)
        if (wordCount > DISCOVERY_QUERY_MAX_WORDS) {
            issues += DiscoveryFilterIssue(
                DiscoveryFilterField.QUERY,
                "검색어는 최대 ${DISCOVERY_QUERY_MAX_WORDS}개 단어까지 입력할 수 있어요.",
            )
        }
    }
    if (input.categoryId != null && input.unclassified) {
        issues += DiscoveryFilterIssue(
            DiscoveryFilterField.CATEGORY,
            "분류와 미분류 필터는 동시에 선택할 수 없어요.",
        )
    }
    if (input.categoryId?.isBlank() == true) {
        issues += DiscoveryFilterIssue(
            DiscoveryFilterField.CATEGORY,
            "선택한 분류를 다시 확인해 주세요.",
        )
    }

    val fromDate = parseInputDate(input.dateFrom, DiscoveryFilterField.DATE_FROM, issues)
    val toDate = parseInputDate(input.dateTo, DiscoveryFilterField.DATE_TO, issues)
    if (fromDate != null && toDate != null && fromDate > toDate) {
        issues += DiscoveryFilterIssue(
            DiscoveryFilterField.DATE_RANGE,
            "시작 날짜는 마지막 날짜보다 늦을 수 없어요.",
        )
    }
    if (issues.isNotEmpty()) return DiscoveryQueryPreparation.Invalid(issues)

    val fromUtc = try {
        fromDate?.atStartOfDay(zoneId)?.toInstant()?.toString()
    } catch (_: DateTimeException) {
        return DiscoveryQueryPreparation.Invalid(
            listOf(DiscoveryFilterIssue(DiscoveryFilterField.DATE_FROM, "기기 시간대에서 시작 날짜를 해석하지 못했어요.")),
        )
    }
    val toExclusiveUtc = try {
        toDate?.plusDays(1)?.atStartOfDay(zoneId)?.toInstant()?.toString()
    } catch (_: DateTimeException) {
        return DiscoveryQueryPreparation.Invalid(
            listOf(DiscoveryFilterIssue(DiscoveryFilterField.DATE_TO, "기기 시간대에서 마지막 날짜를 해석하지 못했어요.")),
        )
    }

    val parameters = discoveryQueryParameters(
        query = query,
        categoryId = input.categoryId,
        unclassified = input.unclassified,
        source = input.source,
        dateFromUtc = fromUtc,
        dateToExclusiveUtc = toExclusiveUtc,
        aliases = input.aliases,
        needsCues = input.needsCues,
    )
    val queryKey = parameters.joinToString("&") { (name, value) ->
        "${encodeDiscoveryQueryComponent(name)}=${encodeDiscoveryQueryComponent(value)}"
    }
    return DiscoveryQueryPreparation.Valid(
        DiscoveryQuerySnapshot(
            query = query,
            categoryId = input.categoryId,
            unclassified = input.unclassified,
            source = input.source,
            dateFromUtc = fromUtc,
            dateToExclusiveUtc = toExclusiveUtc,
            aliases = input.aliases,
            needsCues = input.needsCues,
            queryKey = queryKey,
        ),
    )
}

fun buildDiscoveryItemsPath(
    snapshot: DiscoveryQuerySnapshot,
    limit: Int = DISCOVERY_PAGE_SIZE,
    offset: Int = 0,
): String {
    require(limit in 1..DISCOVERY_PAGE_LIMIT_MAX) {
        "Page limit must be between 1 and $DISCOVERY_PAGE_LIMIT_MAX."
    }
    require(offset >= 0) { "Page offset must not be negative." }
    val parameters = discoveryQueryParameters(
        query = snapshot.query,
        categoryId = snapshot.categoryId,
        unclassified = snapshot.unclassified,
        source = snapshot.source,
        dateFromUtc = snapshot.dateFromUtc,
        dateToExclusiveUtc = snapshot.dateToExclusiveUtc,
        aliases = snapshot.aliases,
        needsCues = snapshot.needsCues,
    ) + listOf("limit" to limit.toString(), "offset" to offset.toString())
    return "/items?" + parameters.joinToString("&") { (name, value) ->
        "${encodeDiscoveryQueryComponent(name)}=${encodeDiscoveryQueryComponent(value)}"
    }
}

fun isCurrentDiscoveryResponse(
    token: DiscoverySearchToken,
    ownerId: String?,
    generation: Long,
    queryKey: String?,
): Boolean = token.ownerId == ownerId &&
    token.generation == generation &&
    token.queryKey == queryKey

internal fun DiscoveryQuerySnapshot.withoutCategoryFilter(): DiscoveryQuerySnapshot {
    val parameters = discoveryQueryParameters(
        query = query,
        categoryId = null,
        unclassified = unclassified,
        source = source,
        dateFromUtc = dateFromUtc,
        dateToExclusiveUtc = dateToExclusiveUtc,
        aliases = aliases,
        needsCues = needsCues,
    )
    return copy(
        categoryId = null,
        queryKey = parameters.joinToString("&") { (name, value) ->
            "${encodeDiscoveryQueryComponent(name)}=${encodeDiscoveryQueryComponent(value)}"
        },
    )
}

private fun discoveryQueryParameters(
    query: String?,
    categoryId: String?,
    unclassified: Boolean,
    source: DiscoverySource,
    dateFromUtc: String?,
    dateToExclusiveUtc: String?,
    aliases: Boolean,
    needsCues: Boolean,
): List<Pair<String, String>> = buildList {
    query?.let { add("q" to it) }
    categoryId?.let { add("category_id" to it) }
    if (unclassified) add("unclassified" to "true")
    source.apiValue?.let { add("source" to it) }
    dateFromUtc?.let { add("date_from" to it) }
    dateToExclusiveUtc?.let { add("date_to" to it) }
    add("aliases" to aliases.toString())
    add("needs_cues" to needsCues.toString())
}

internal fun encodeDiscoveryQueryComponent(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val result = StringBuilder(bytes.size)
    bytes.forEach { rawByte ->
        val byte = rawByte.toInt() and 0xff
        if (
            byte in 'a'.code..'z'.code || byte in 'A'.code..'Z'.code ||
            byte in '0'.code..'9'.code || byte == '-'.code || byte == '.'.code ||
            byte == '_'.code || byte == '~'.code
        ) {
            result.append(byte.toChar())
        } else {
            result.append('%')
            result.append(HEX_DIGITS[byte ushr 4])
            result.append(HEX_DIGITS[byte and 0x0f])
        }
    }
    return result.toString()
}

private fun discoveryWordCount(value: String): Int {
    var count = 0
    var inWord = false
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val isWhitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        if (isWhitespace) {
            inWord = false
        } else if (!inWord) {
            count += 1
            inWord = true
        }
        offset += Character.charCount(codePoint)
    }
    return count
}

private fun parseInputDate(
    value: String,
    field: DiscoveryFilterField,
    issues: MutableList<DiscoveryFilterIssue>,
): LocalDate? {
    if (value.isBlank()) return null
    return try {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        issues += DiscoveryFilterIssue(field, "날짜는 YYYY-MM-DD 형식으로 입력해 주세요.")
        null
    }
}

@Serializable
data class DiscoveryCategory(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("system_code") val systemCode: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
) {
    val isSystem: Boolean
        get() = kind == "system"

    val isCustom: Boolean
        get() = kind == "custom"
}

@Serializable
private data class DiscoveryCategoriesResponse(
    val categories: List<DiscoveryCategory>,
    val count: Int,
    @SerialName("unclassified_count") val unclassifiedCount: Int,
)

data class DiscoveryCategoryList(
    val categories: List<DiscoveryCategory>,
    val count: Int,
    val unclassifiedCount: Int,
)

data class DiscoveryCategorySnapshot(
    val categoryList: DiscoveryCategoryList,
    val fetchedAt: Long,
)

enum class DiscoveryCategorySnapshotProvenance {
    ROOM_CACHE,
    IN_MEMORY,
}

data class DiscoveryCategoryFallback(
    val snapshot: DiscoveryCategorySnapshot,
    val provenance: DiscoveryCategorySnapshotProvenance,
)

internal fun resolveDiscoveryCategoryFallback(
    cached: DiscoveryCategorySnapshot?,
    inMemory: DiscoveryCategorySnapshot?,
): DiscoveryCategoryFallback? = when {
    cached != null -> DiscoveryCategoryFallback(
        snapshot = cached,
        provenance = DiscoveryCategorySnapshotProvenance.ROOM_CACHE,
    )
    inMemory != null -> DiscoveryCategoryFallback(
        snapshot = inMemory,
        provenance = DiscoveryCategorySnapshotProvenance.IN_MEMORY,
    )
    else -> null
}

@Serializable
data class DiscoveryAliasExplanation(
    val field: String,
    val expression: String,
    @SerialName("concept_id") val conceptId: String? = null,
)

@Serializable
private data class DiscoveryAliasDetailResponse(
    val id: String,
    @SerialName("alias_explanations") val aliasExplanations: List<DiscoveryAliasExplanation> = emptyList(),
)

data class DiscoveryAliasDetail(
    val itemId: String,
    val explanations: List<DiscoveryAliasExplanation>,
)

data class DiscoveryCategoryNameIssue(val message: String)

internal fun validateDiscoveryCategoryName(name: String): DiscoveryCategoryNameIssue? = when {
    name.isBlank() -> DiscoveryCategoryNameIssue("분류 이름을 입력해 주세요.")
    name.codePointLength() > DISCOVERY_CATEGORY_NAME_MAX_CODE_POINTS -> DiscoveryCategoryNameIssue(
        "분류 이름은 최대 ${DISCOVERY_CATEGORY_NAME_MAX_CODE_POINTS}자까지 입력할 수 있어요.",
    )
    else -> null
}

internal fun discoveryCategoryPayload(name: String): String = buildJsonObject {
    put("name", name)
}.toString()

internal fun parseDiscoveryItemsResponse(response: JsonObject): LibraryListPage =
    parseLibraryListResponse(response)

internal fun parseDiscoveryCategoriesResponse(response: JsonObject): DiscoveryCategoryList {
    val parsed = discoveryJson.decodeFromJsonElement<DiscoveryCategoriesResponse>(response)
    require(parsed.count >= 0 && parsed.unclassifiedCount >= 0) { "Category counts must not be negative." }
    require(parsed.categories.size == parsed.count) { "Category count does not match the category list." }
    require(parsed.categories.all { category ->
        category.id.isNotBlank() && category.name.isNotBlank() &&
            category.itemCount >= 0 && (category.isSystem || category.isCustom) &&
            (category.isSystem || category.systemCode == null)
    }) { "The server returned an invalid category." }
    return DiscoveryCategoryList(
        categories = parsed.categories,
        count = parsed.count,
        unclassifiedCount = parsed.unclassifiedCount,
    )
}

internal fun parseDiscoveryAliasDetail(response: JsonObject): DiscoveryAliasDetail {
    val parsed = discoveryJson.decodeFromJsonElement<DiscoveryAliasDetailResponse>(response)
    require(parsed.id.isNotBlank()) { "The detail response is missing its item ID." }
    require(parsed.aliasExplanations.all { it.field.isNotBlank() && it.expression.isNotBlank() }) {
        "The detail response contains an invalid alias explanation."
    }
    return DiscoveryAliasDetail(parsed.id, parsed.aliasExplanations)
}

internal fun LibraryItemSummary.cueLabel(): String? = when (cueState) {
    "pending" -> "단서 분석 중"
    "limited" -> "단서가 제한적이에요"
    "missing" -> "단서가 부족해요"
    "available" -> "단서 준비됨"
    else -> null
}

private const val HEX_DIGITS = "0123456789ABCDEF"
