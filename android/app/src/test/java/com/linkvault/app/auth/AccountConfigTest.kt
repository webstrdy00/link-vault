package com.linkvault.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConfigTest {
    @Test
    fun `https configuration is accepted and normalized`() {
        val result = validateAccountConfig(
            url = "  https://project.supabase.co/  ",
            key = " publishable-key ",
            googleWebClientId = " web-client-id ",
            debug = false,
        )

        assertTrue(result is AccountConfigValidation.Configured)
        val config = (result as AccountConfigValidation.Configured).config
        assertEquals("https://project.supabase.co", config.url)
        assertEquals("publishable-key", config.key)
        assertEquals("web-client-id", config.googleWebClientId)
    }

    @Test
    fun `emulator supabase http origin is allowed in debug`() {
        val result = validateAccountConfig(
            url = "http://10.0.2.2:54321",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = true,
        )

        assertTrue(result is AccountConfigValidation.Configured)
    }

    @Test
    fun `emulator supabase http origin is rejected outside debug`() {
        val result = validateAccountConfig(
            url = "http://10.0.2.2:54321",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = false,
        )

        assertIssue(AccountConfigIssue.INSECURE_URL, result)
    }

    @Test
    fun `other http origins are rejected even in debug`() {
        val wrongHost = validateAccountConfig(
            url = "http://localhost:54321",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = true,
        )
        val wrongPort = validateAccountConfig(
            url = "http://10.0.2.2:8000",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = true,
        )

        assertIssue(AccountConfigIssue.INSECURE_URL, wrongHost)
        assertIssue(AccountConfigIssue.INSECURE_URL, wrongPort)
    }

    @Test
    fun `blank google web client id is unconfigured`() {
        val result = validateAccountConfig(
            url = "https://project.supabase.co",
            key = "publishable-key",
            googleWebClientId = "   ",
            debug = false,
        )

        assertIssue(AccountConfigIssue.MISSING_GOOGLE_WEB_CLIENT_ID, result)
    }

    @Test
    fun `blank supabase values are unconfigured`() {
        val missingUrl = validateAccountConfig(
            url = " ",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = false,
        )
        val missingKey = validateAccountConfig(
            url = "https://project.supabase.co",
            key = " ",
            googleWebClientId = "web-client-id",
            debug = false,
        )

        assertIssue(AccountConfigIssue.MISSING_URL, missingUrl)
        assertIssue(AccountConfigIssue.MISSING_KEY, missingKey)
    }

    @Test
    fun `url must be an origin without credentials path query or fragment`() {
        val invalidUrls = listOf(
            "project.supabase.co",
            "https://",
            "https://user@project.supabase.co",
            "https://project.supabase.co:0",
            "https://project.supabase.co:65536",
            "https://project.supabase.co/rest",
            "https://project.supabase.co?query=value",
            "https://project.supabase.co#fragment",
        )

        invalidUrls.forEach { url ->
            val result = validateAccountConfig(
                url = url,
                key = "publishable-key",
                googleWebClientId = "web-client-id",
                debug = false,
            )
            assertIssue(AccountConfigIssue.INVALID_URL, result)
        }
    }

    private fun assertIssue(
        expected: AccountConfigIssue,
        result: AccountConfigValidation,
    ) {
        assertTrue(result is AccountConfigValidation.Unconfigured)
        assertEquals(expected, (result as AccountConfigValidation.Unconfigured).issue)
    }
}
