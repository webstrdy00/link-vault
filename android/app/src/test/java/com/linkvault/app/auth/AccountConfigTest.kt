package com.linkvault.app.auth

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConfigTest {
    @Test
    fun `discovery routes preserve encoded queries and bounded resource paths`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val assetId = "00000000-0000-0000-0000-000000000002"
        listOf(
            "/items?q=%25_%20%EC%B9%B4%ED%86%A1&aliases=true",
            "/items/$id?q=excel",
            "/items/$id/reclassify",
            "/items/$id/cue-dismiss",
            "/items/$id/retry-metadata",
            "/items/$id/assets/reserve",
            "/items/$id/assets/$assetId/complete",
            "/items/$id/assets/$assetId",
            "/items/$id/assets/$assetId/ocr",
            "/items/$id/assets/$assetId/content",
            "/categories",
            "/categories/$id",
        ).forEach { path -> assertEquals(path, validateLibraryPath(path)) }
    }

    @Test
    fun `discovery routes reject authority traversal and internal worker access`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val assetId = "00000000-0000-0000-0000-000000000002"
        listOf(
            "https://other.example/categories",
            "//other.example/items",
            "/internal/classify",
            "/categories/",
            "/categories/not-a-uuid",
            "/categories/$id/reclassify",
            "/items/$id/unknown",
            "/items/$id/delete",
            "/items/$id/delete/extra",
            "/items/$id%2Fdelete",
            "/items/$id/reclassify/extra",
            "/items/$id#fragment",
            "/items/$id/../categories",
            "/categories/$id%2Freclassify",
            "/items//reclassify",
            "/items?internal=/op",
            "/items?q=one&q=two",
            "/items/$id?aliases=true",
            "/items/$id/retry-metadata?force=true",
            "/items/not-a-uuid/retry-metadata",
            "/items/$id/retry-metadata/internal",
            "/items/$id/assets/not-a-uuid",
            "/items/$id/assets/not-a-uuid/complete",
            "/items/not-a-uuid/assets/$assetId/content",
            "/items/$id/assets/$assetId/content?download=true",
            "/items/$id/assets/$assetId%2Fcontent",
            "/items/$id/assets/$assetId/%63ontent",
            "/items/$id/assets/$assetId/../content",
            "/items/$id/assets/$assetId/internal/op",
            "/internal/items/$id/assets/$assetId/content",
            "/account/delete",
            "/account/delete-challenge",
        ).forEach { path ->
            assertThrows(path, AccountClientException::class.java) { validateLibraryPath(path) }
        }
    }

    @Test
    fun `asset routes accept only their exact HTTP methods`() {
        val itemId = "00000000-0000-0000-0000-000000000001"
        val assetId = "00000000-0000-0000-0000-000000000002"
        listOf(
            "/items/$itemId/retry-metadata" to HttpMethod.Post,
            "/items/$itemId/assets/reserve" to HttpMethod.Post,
            "/items/$itemId/assets/$assetId/complete" to HttpMethod.Post,
            "/items/$itemId/assets/$assetId" to HttpMethod.Delete,
            "/items/$itemId/assets/$assetId/ocr" to HttpMethod.Patch,
            "/items/$itemId/assets/$assetId/content" to HttpMethod.Get,
            "/items/$itemId" to HttpMethod.Delete,
        ).forEach { (path, method) ->
            validateLibraryRouteMethod(validateLibraryPath(path), method)
        }

        listOf(
            "/items/$itemId/retry-metadata" to HttpMethod.Get,
            "/items/$itemId/assets/reserve" to HttpMethod.Patch,
            "/items/$itemId/assets/$assetId/complete" to HttpMethod.Delete,
            "/items/$itemId/assets/$assetId" to HttpMethod.Get,
            "/items/$itemId/assets/$assetId/ocr" to HttpMethod.Post,
            "/items/$itemId/assets/$assetId/content" to HttpMethod.Post,
            "/items" to HttpMethod.Delete,
            "/items/$itemId?q=delete" to HttpMethod.Delete,
            "/items/$itemId/reclassify" to HttpMethod.Delete,
            "/categories/$itemId" to HttpMethod.Post,
        ).forEach { (path, method) ->
            assertThrows(path, AccountClientException::class.java) {
                validateLibraryRouteMethod(validateLibraryPath(path), method)
            }
        }
    }

    @Test
    fun `item deletion accepts only expected version body and exact accepted response`() {
        val itemId = "00000000-0000-0000-0000-000000000001"
        val path = "/items/$itemId"

        validateLibraryRequestBody(
            path = path,
            method = HttpMethod.Delete,
            body = """{"expected_version":7}""",
        )
        assertEquals(
            setOf("item_id", "state"),
            validateItemDeleteResponse(
                path = path,
                status = HttpStatusCode.Accepted,
                body = """{"item_id":"$itemId","state":"deleting"}""",
            ).keys,
        )

        listOf(
            null,
            "{}",
            """{"expected_version":0}""",
            """{"expected_version":-1}""",
            """{"expected_version":1.5}""",
            """{"expected_version":"7"}""",
            """{"expected_version":7,"force":true}""",
            """{"expected_version":7,"expectedVersion":7}""",
        ).forEach { body ->
            val error = assertThrows(AccountClientException::class.java) {
                validateLibraryRequestBody(path, HttpMethod.Delete, body)
            }
            assertEquals("INVALID_REQUEST_BODY", error.code)
        }

        listOf(
            HttpStatusCode.OK to """{"item_id":"$itemId","state":"deleting"}""",
            HttpStatusCode.Accepted to """{"item_id":"$itemId","state":"deleted"}""",
            HttpStatusCode.Accepted to """{"item_id":"00000000-0000-0000-0000-000000000002","state":"deleting"}""",
            HttpStatusCode.Accepted to """{"item_id":"$itemId","state":"deleting","extra":true}""",
            HttpStatusCode.Accepted to """{"state":"deleting"}""",
        ).forEach { (status, body) ->
            val error = assertThrows(AccountClientException::class.java) {
                validateItemDeleteResponse(path, status, body)
            }
            assertEquals("INVALID_ITEM_DELETE_RESPONSE", error.code)
        }
    }

    @Test
    fun `accepted deletion retries failed owner cleanup after the auth session is cleared`() = runBlocking {
        val ownerA = "00000000-0000-0000-0000-000000000001"
        val tracker = AcceptedDeletionCleanupTracker()

        assertEquals(
            AcceptedDeletionCleanupAction.PURGE_EXPECTED_OWNER,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = ownerA,
                localDataOwnerId = ownerA,
            ),
        )

        val failedPurge = runCatching {
            tracker.purge(ownerA) { error("filesystem cleanup failed") }
        }
        assertTrue(failedPurge.isFailure)
        assertFalse(tracker.wasSuccessfullyPurged(ownerA))

        // A failed purge was not recorded. A later definitive 401 may clear the auth
        // session, but persistent ownership still requires the same purge.
        assertEquals(
            AcceptedDeletionCleanupAction.PURGE_EXPECTED_OWNER,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = null,
                localDataOwnerId = ownerA,
            ),
        )

        tracker.purge(ownerA) {}
        assertTrue(tracker.wasSuccessfullyPurged(ownerA))
        assertEquals(
            AcceptedDeletionCleanupAction.CLEAR_AUTH_ONLY,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = null,
                localDataOwnerId = null,
            ),
        )
        assertEquals(
            AcceptedDeletionCleanupAction.PURGE_EXPECTED_OWNER,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = null,
                localDataOwnerId = ownerA,
            ),
        )
    }

    @Test
    fun `accepted deletion cleanup never purges a different local owner`() {
        val ownerA = "00000000-0000-0000-0000-000000000001"
        val ownerB = "00000000-0000-0000-0000-000000000002"
        val tracker = AcceptedDeletionCleanupTracker()

        assertEquals(
            AcceptedDeletionCleanupAction.REFUSE_OWNER_MISMATCH,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = ownerA,
                localDataOwnerId = ownerB,
            ),
        )
        assertEquals(
            AcceptedDeletionCleanupAction.ALREADY_ABSENT,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = ownerB,
                localDataOwnerId = ownerB,
            ),
        )
        assertEquals(
            AcceptedDeletionCleanupAction.ALREADY_ABSENT,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = null,
                localDataOwnerId = ownerB,
            ),
        )
        assertEquals(
            AcceptedDeletionCleanupAction.REFUSE_OWNER_MISMATCH,
            tracker.action(
                expectedOwnerId = ownerA,
                sessionOwnerId = ownerB,
                localDataOwnerId = ownerA,
            ),
        )
    }

    @Test
    fun `authentication failure after delete send remains an unknown deletion result`() {
        val authenticationFailure = AccountAuthenticationRequiredException()
        assertFalse(
            accountDeletionResultIsUnknown(
                deleteRequestMayHaveReachedServer = false,
                error = authenticationFailure,
            ),
        )
        assertTrue(
            accountDeletionResultIsUnknown(
                deleteRequestMayHaveReachedServer = true,
                error = authenticationFailure,
            ),
        )

        val unknown = AccountUiState.DeletionStatusUnknown(
            expectedSessionOwner = "00000000-0000-0000-0000-000000000001",
            expectedSessionGeneration = 9,
            message = "응답을 확인하지 못했어요.",
        )
        val afterRecovery401 = preserveUnknownDeletionStatus(
            unknown = unknown,
            message = "로그인이 만료되어 삭제 접수 여부를 확인할 수 없어요.",
        )
        assertEquals(unknown.expectedSessionOwner, afterRecovery401.expectedSessionOwner)
        assertEquals(unknown.expectedSessionGeneration, afterRecovery401.expectedSessionGeneration)
        assertTrue(afterRecovery401.message.contains("삭제 접수 여부"))
    }

    @Test
    fun `ambiguous delete stays unknown when recovery clears origin before retry`() = runBlocking {
        val ownerId = "00000000-0000-0000-0000-000000000001"
        val generation = 17L
        val ambiguousNetworkFailure = AccountClientException(
            message = "Delete response was lost.",
            retryable = true,
        )
        var originPresent = true

        // A definitive 401 while checking /me clears the captured session origin.
        val recoveryResult: Boolean? = run {
            originPresent = false
            null
        }
        assertEquals(null, recoveryResult)

        val thrown = runCatching {
            guardAmbiguousAccountDeletionRetry(
                expectedOwnerId = ownerId,
                expectedGeneration = generation,
                ambiguousFailure = ambiguousNetworkFailure,
            ) {
                if (!originPresent) {
                    throw AccountClientException(
                        message = "The authenticated session changed",
                        retryable = false,
                        code = "SESSION_CHANGED",
                    )
                }
            }
        }.exceptionOrNull()

        assertTrue(thrown is AccountDeletionStatusUnknownException)
        val unknown = thrown as AccountDeletionStatusUnknownException
        assertEquals(ownerId, unknown.expectedOwnerId)
        assertEquals(generation, unknown.expectedGeneration)
        assertEquals("SESSION_CHANGED", (unknown.cause as AccountClientException).code)
        assertTrue(unknown.cause?.suppressed?.contains(ambiguousNetworkFailure) == true)
    }

    @Test
    fun `asset transport accepts only canonical identifiers exact mime and bounded bytes`() {
        val ownerId = "00000000-0000-0000-0000-000000000001"
        val itemId = "00000000-0000-0000-0000-000000000002"
        val assetId = "00000000-0000-0000-0000-000000000003"

        assertEquals(
            ValidatedAssetTransport(ownerId, itemId, assetId),
            validateAssetTransport(ownerId, itemId, assetId),
        )
        listOf("image/jpeg", "image/png", "image/webp").forEach { mimeType ->
            assertEquals(mimeType, validateAssetMimeType(mimeType))
        }
        assertEquals(MAX_ASSET_BYTES, validateAssetByteCount(MAX_ASSET_BYTES))

        listOf(
            Triple("../owner", itemId, assetId) to "INVALID_OWNER_ID",
            Triple(ownerId, "$itemId%2Fassets", assetId) to "INVALID_ITEM_ID",
            Triple(ownerId, itemId, "$assetId/complete") to "INVALID_ASSET_ID",
        ).forEach { (identifiers, expectedCode) ->
            val error = assertThrows(AccountClientException::class.java) {
                validateAssetTransport(identifiers.first, identifiers.second, identifiers.third)
            }
            assertEquals(expectedCode, error.code)
            assertEquals(false, error.retryable)
        }

        listOf(
            "image/jpg",
            "Image/JPEG",
            "image/jpeg; charset=binary",
            " image/png",
            "application/octet-stream",
        ).forEach { mimeType ->
            val error = assertThrows(AccountClientException::class.java) {
                validateAssetMimeType(mimeType)
            }
            assertEquals("INVALID_ASSET_MIME", error.code)
            assertEquals(false, error.retryable)
        }

        listOf(-1, MAX_ASSET_BYTES + 1).forEach { byteCount ->
            val error = assertThrows(AccountClientException::class.java) {
                validateAssetByteCount(byteCount)
            }
            assertEquals(ASSET_TOO_LARGE, error.code)
            assertEquals(false, error.retryable)
        }
    }

    @Test
    fun `retry after accepts seconds and rounds future dates upwards`() {
        assertEquals(60, parseRetryAfter("60", 0))
        assertEquals(1, parseRetryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 1))
        assertEquals(0, parseRetryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 2000))
        assertEquals(null, parseRetryAfter("-1", 0))
        assertEquals(null, parseRetryAfter("not a deadline", 0))
    }

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
            url = "http://10.0.2.2:18021",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = true,
        )

        assertTrue(result is AccountConfigValidation.Configured)
    }

    @Test
    fun `emulator supabase http origin is rejected outside debug`() {
        val result = validateAccountConfig(
            url = "http://10.0.2.2:18021",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = false,
        )

        assertIssue(AccountConfigIssue.INSECURE_URL, result)
    }

    @Test
    fun `other http origins are rejected even in debug`() {
        val wrongHost = validateAccountConfig(
            url = "http://localhost:18021",
            key = "publishable-key",
            googleWebClientId = "web-client-id",
            debug = true,
        )
        val oldPort = validateAccountConfig(
            url = "http://10.0.2.2:54321",
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
        assertIssue(AccountConfigIssue.INSECURE_URL, oldPort)
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
