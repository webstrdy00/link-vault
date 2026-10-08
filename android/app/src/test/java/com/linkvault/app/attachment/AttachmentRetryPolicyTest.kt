package com.linkvault.app.attachment

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
}
