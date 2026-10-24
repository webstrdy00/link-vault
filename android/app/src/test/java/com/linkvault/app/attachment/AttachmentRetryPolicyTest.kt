package com.linkvault.app.attachment

import com.linkvault.app.library.LibraryExtractionMeta
import com.linkvault.app.library.LibraryItemDetail
import com.linkvault.app.library.parseLibraryDetailResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRetryPolicyTest {
    @Test
    fun ambiguousCompletionMustReplayEvenAfterReservationTimerExpires() {
        assertFalse(AttachmentPolicy.reservationExpiredBeforeUpload(
            AttachmentStage.COMPLETE, 1_000L, 1_000L + AttachmentPolicy.RESERVATION_LIFETIME_MILLIS * 2,
        ))
    }

    @Test
    fun uncompletedUploadRotatesOnlyAtItsReservationDeadline() {
        val deadline = 1_000L + AttachmentPolicy.RESERVATION_LIFETIME_MILLIS
        assertFalse(AttachmentPolicy.reservationExpiredBeforeUpload(AttachmentStage.UPLOAD, 1_000L, deadline - 1))
        assertTrue(AttachmentPolicy.reservationExpiredBeforeUpload(AttachmentStage.UPLOAD, 1_000L, deadline))
    }

    @Test
    fun missingOrFutureReceiptTimeDoesNotInventAnExpiry() {
        assertFalse(AttachmentPolicy.reservationExpiredBeforeUpload(AttachmentStage.UPLOAD, null, Long.MAX_VALUE))
        assertFalse(AttachmentPolicy.reservationExpiredBeforeUpload(AttachmentStage.UPLOAD, 10_000L, 1_000L))
        assertFalse(AttachmentPolicy.reservationExpiredBeforeUpload(AttachmentStage.RESERVE, 1_000L, Long.MAX_VALUE))
    }

    @Test
    fun metadataProcessingRecognizesOnlyCanonicalActiveStates() {
        listOf("queued", "running").forEach { state ->
            assertTrue(state, item(metadataState = state).isMetadataProcessing)
        }
        listOf("ready", "partial", "unsupported", "failed", "pending", "unknown", null).forEach { state ->
            assertFalse(state, item(metadataState = state).isMetadataProcessing)
        }
    }

    @Test
    fun pollingContinuesFromMetadataThroughReclassificationCompletion() {
        val snapshots = listOf(
            "queued" to "unclassified",
            "running" to "unclassified",
            "ready" to "pending",
            "ready" to "automatic",
        ).map { (metadata, classification) ->
            parseLibraryDetailResponse(buildJsonObject {
                put("id", "item-a")
                put("url", "https://example.com/item")
                put("metadata_state", metadata)
                put("classification_state", classification)
            })
        }

        snapshots.dropLast(1).forEach { snapshot ->
            assertTrue(snapshot.toString(), snapshot.hasPendingEnrichment)
        }
        assertFalse(snapshots.last().hasPendingEnrichment)
        assertFalse(snapshots[2].isMetadataProcessing)
    }

    @Test
    fun terminalMetadataPollsOnlyForPendingClassification() {
        listOf("ready", "partial", "unsupported", "failed").forEach { metadata ->
            val terminalItem = item(metadataState = metadata)
            assertTrue(terminalItem.copy(classificationState = "pending").hasPendingEnrichment)
            listOf("automatic", "manual", "unclassified", null).forEach { classification ->
                val settled = terminalItem.copy(classificationState = classification)
                assertFalse(settled.toString(), settled.hasPendingEnrichment)
            }
        }
        assertFalse(item(metadataState = "pending").hasPendingEnrichment)
    }

    @Test
    fun metadataProgressAndFailureMessagesReflectServerStates() {
        assertEquals(
            "서버에서 메타데이터 조회를 기다리고 있어요.",
            metadataStatusMessage(item(metadataState = "queued")),
        )
        assertEquals(
            "서버에서 메타데이터를 조회하고 있어요.",
            metadataStatusMessage(item(metadataState = "running")),
        )
        assertEquals(
            "메타데이터 조회를 완료하지 못했어요.",
            metadataStatusMessage(item(metadataState = "failed")),
        )
        assertEquals(
            "링크 정보를 가져오지 못했어요. 다시 조회해 현재 지원 여부를 확인할 수 있어요.",
            metadataStatusMessage(item(metadataState = "unsupported")),
        )
    }

    @Test
    fun legacyUnsupportedMetadataOffersExplicitRetryWithKnownVersion() {
        listOf(null, "INVALID_CONTENT", "NETWORK_ERROR").forEach { errorCode ->
            val state = retryState("unsupported", errorCode)
            assertTrue(state.supportsMetadataRetry)
            assertTrue(state.canRetryMetadata)
            assertFalse(state.item.hasPendingEnrichment)

            val withoutVersion = state.copy(item = state.item.copy(version = null))
            assertFalse(withoutVersion.supportsMetadataRetry)
            assertFalse(withoutVersion.canRetryMetadata)
        }
    }

    @Test
    fun metadataRetryRemainsDisabledWhileSessionOrQueueIsBlocked() {
        val state = retryState("unsupported", null)
        listOf(
            state.copy(sessionValid = false),
            state.copy(actionRunning = true),
            state.copy(attachmentQueueLoaded = false),
            state.copy(outboxLoaded = false),
            state.copy(localStage = AttachmentLocalStage.PREPARING),
        ).forEach { blocked ->
            assertTrue(blocked.supportsMetadataRetry)
            assertFalse(blocked.canRetryMetadata)
        }
    }

    @Test
    fun processingAndCompletedMetadataDoNotOfferRetryDespiteOldErrors() {
        listOf("queued", "running", "ready", "pending", "unknown", null).forEach { metadata ->
            val state = retryState(metadata, "NETWORK_ERROR")
            assertFalse(metadata, state.supportsMetadataRetry)
            assertFalse(metadata, state.canRetryMetadata)
        }
    }

    @Test
    fun partialAndFailedMetadataKeepExistingTransientErrorRetryRules() {
        listOf("partial", "failed").forEach { metadata ->
            listOf("METADATA_TIMEOUT", "NETWORK_ERROR", "RATE_LIMITED", "INTERNAL_ERROR").forEach { errorCode ->
                val state = retryState(metadata, errorCode)
                assertTrue("$metadata: $errorCode", state.supportsMetadataRetry)
                assertTrue("$metadata: $errorCode", state.canRetryMetadata)
                assertFalse(state.copy(item = state.item.copy(version = null)).supportsMetadataRetry)
            }
            listOf(
                "ACCESS_DENIED",
                "INVALID_CONTENT",
                "RESPONSE_TOO_LARGE",
                "METADATA_BUDGET_EXHAUSTED",
                "INVALID_RESULT",
                "RETRY_AFTER_EXCEEDED",
                "unknown",
                null,
            ).forEach { errorCode ->
                val state = retryState(metadata, errorCode)
                assertFalse("$metadata: $errorCode", state.supportsMetadataRetry)
                assertFalse("$metadata: $errorCode", state.canRetryMetadata)
            }
        }
    }

    @Test
    fun disallowedMetadataRetryExplainsUnsupportedAddress() {
        assertEquals(
            "이 주소는 메타데이터 조회를 지원하지 않아요.",
            commandFailureMessage("METADATA_UNSUPPORTED"),
        )
    }

    private fun retryState(metadataState: String?, errorCode: String?): AttachmentUiState =
        AttachmentUiState(
            item = item(metadataState).copy(
                version = 1L,
                extractionMeta = LibraryExtractionMeta(errorCode = errorCode),
            ),
            attachmentQueueLoaded = true,
            outboxLoaded = true,
        )

    private fun item(metadataState: String?): LibraryItemDetail = LibraryItemDetail(
        id = "item-a",
        url = "https://example.com/item",
        metadataState = metadataState,
    )
}
