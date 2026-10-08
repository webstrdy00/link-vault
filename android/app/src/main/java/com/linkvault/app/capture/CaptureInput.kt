package com.linkvault.app.capture

import java.net.URI
import java.net.URISyntaxException

/** Pure URL extraction and validation for text received by the Android share surface. */
object CaptureInput {
    const val MAX_URL_LENGTH = 4096
    const val MAX_INPUT_LENGTH = 20_000

    private val urlPattern = Regex(
        pattern = """(?i)(?<![\p{L}\p{N}_./+\-])https?://[^\s<>\"“”]+""",
    )

    fun parse(text: String): ParseResult {
        if (text.length > MAX_INPUT_LENGTH) {
            return ParseResult(inputTooLong = true)
        }

        val urls = linkedSetOf<String>()
        val rejected = mutableListOf<RejectedCandidate>()

        urlPattern.findAll(text).forEach { match ->
            val candidate = trimSharedTextPunctuation(match.value)
            if (candidate.isEmpty()) return@forEach

            when (val validation = validate(candidate)) {
                is UrlValidation.Valid -> urls += validation.url
                is UrlValidation.Invalid -> rejected += RejectedCandidate(candidate, validation.reason)
            }
        }

        return ParseResult(urls = urls.toList(), rejected = rejected)
    }

    fun validate(value: String): UrlValidation {
        val candidate = value.trim()
        if (candidate.length > MAX_URL_LENGTH) {
            return UrlValidation.Invalid(InvalidUrlReason.TOO_LONG)
        }
        if (candidate.isEmpty()) {
            return UrlValidation.Invalid(InvalidUrlReason.MALFORMED)
        }

        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return UrlValidation.Invalid(InvalidUrlReason.MALFORMED)
        }

        if (
            !uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)
        ) {
            return UrlValidation.Invalid(InvalidUrlReason.UNSUPPORTED_SCHEME)
        }
        if (uri.rawUserInfo != null) {
            return UrlValidation.Invalid(InvalidUrlReason.USER_INFO_NOT_ALLOWED)
        }
        if (uri.host.isNullOrBlank()) {
            return UrlValidation.Invalid(InvalidUrlReason.HOST_REQUIRED)
        }
        if (uri.port !in -1..65_535) {
            return UrlValidation.Invalid(InvalidUrlReason.MALFORMED)
        }

        return UrlValidation.Valid(candidate)
    }

    private fun trimSharedTextPunctuation(value: String): String {
        var candidate = value
        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            val shouldTrim = when (last) {
                '.', ',', ';', ':', '!', '?',
                '。', '，', '、', '；', '：', '！', '？', '…',
                '\'', '’', '}', '>' -> true
                ')' -> candidate.count { it == ')' } > candidate.count { it == '(' }
                ']' -> candidate.count { it == ']' } > candidate.count { it == '[' }
                else -> false
            }
            if (!shouldTrim) break
            candidate = candidate.dropLast(1)
        }
        return candidate
    }
}

data class ParseResult(
    val urls: List<String> = emptyList(),
    val rejected: List<RejectedCandidate> = emptyList(),
    val inputTooLong: Boolean = false,
)

data class RejectedCandidate(
    val candidate: String,
    val reason: InvalidUrlReason,
)

sealed interface UrlValidation {
    data class Valid(val url: String) : UrlValidation

    data class Invalid(val reason: InvalidUrlReason) : UrlValidation
}

enum class InvalidUrlReason {
    TOO_LONG,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    USER_INFO_NOT_ALLOWED,
    HOST_REQUIRED,
}
