package com.linkvault.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureInputTest {
    @Test
    fun `extracts multiple unique URLs in encounter order`() {
        val result = CaptureInput.parse(
            "첫 링크 https://example.com/a 다음 http://other.test/path " +
                "중복 https://example.com/a",
        )

        assertEquals(
            listOf("https://example.com/a", "http://other.test/path"),
            result.urls,
        )
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `keeps Korean paths and balanced URL parentheses while trimming sentence punctuation`() {
        val result = CaptureInput.parse(
            "확인: https://example.com/한글_(자료)). 그리고 https://other.test/item?x=1,",
        )

        assertEquals(
            listOf(
                "https://example.com/한글_(자료)",
                "https://other.test/item?x=1",
            ),
            result.urls,
        )
    }

    @Test
    fun `keeps IPv6 host brackets and trims only unmatched closing square brackets`() {
        val result = CaptureInput.parse("첫째 http://[::1] 둘째 http://[2001:db8::1]/path]")

        assertEquals(
            listOf("http://[::1]", "http://[2001:db8::1]/path"),
            result.urls,
        )
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `does not extract a web scheme embedded inside an ftp URL`() {
        val result = CaptureInput.parse("ftp://https://example.com/post")

        assertTrue(result.urls.isEmpty())
        assertEquals(
            UrlValidation.Invalid(InvalidUrlReason.UNSUPPORTED_SCHEME),
            CaptureInput.validate("ftp://example.com/post"),
        )
    }

    @Test
    fun `rejects user info and malformed or hostless URLs`() {
        val result = CaptureInput.parse(
            "https://user:secret@example.com/private https:///missing-host " +
                "https://exa%mple.com https://example.com:99999/path",
        )

        assertTrue(result.urls.isEmpty())
        assertEquals(
            listOf(
                InvalidUrlReason.USER_INFO_NOT_ALLOWED,
                InvalidUrlReason.HOST_REQUIRED,
                InvalidUrlReason.MALFORMED,
                InvalidUrlReason.MALFORMED,
            ),
            result.rejected.map { it.reason },
        )
    }

    @Test
    fun `rejects an oversized candidate instead of accepting a truncated prefix`() {
        val oversized = "https://example.com/" + "a".repeat(CaptureInput.MAX_URL_LENGTH)
        val result = CaptureInput.parse(oversized)

        assertTrue(result.urls.isEmpty())
        assertEquals(
            listOf(InvalidUrlReason.TOO_LONG),
            result.rejected.map { it.reason },
        )
        assertEquals(
            UrlValidation.Invalid(InvalidUrlReason.TOO_LONG),
            CaptureInput.validate(oversized),
        )
    }

    @Test
    fun `rejects input beyond the processing bound without truncating it`() {
        val result = CaptureInput.parse("x".repeat(CaptureInput.MAX_INPUT_LENGTH + 1))

        assertTrue(result.inputTooLong)
        assertTrue(result.urls.isEmpty())
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `accepts URL exactly at the URL length boundary`() {
        val prefix = "https://example.com/"
        val boundary = prefix + "a".repeat(CaptureInput.MAX_URL_LENGTH - prefix.length)
        val result = CaptureInput.parse(boundary)

        assertFalse(result.inputTooLong)
        assertEquals(listOf(boundary), result.urls)
    }
}
