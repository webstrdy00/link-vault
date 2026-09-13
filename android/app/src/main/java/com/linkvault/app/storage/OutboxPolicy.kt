package com.linkvault.app.storage

import kotlin.math.max

object OutboxPolicy {
    const val MAX_BATCH_SIZE = 20
    const val REQUEST_LIFETIME_MILLIS = 24L * 60L * 60L * 1_000L
    const val CLAIM_LEASE_MILLIS = 5L * 60L * 1_000L
    const val MIN_BACKOFF_MILLIS = 30_000L
    private const val MAX_BACKOFF_MILLIS = 6L * 60L * 60L * 1_000L

    fun expiresAt(createdAt: Long): Long = saturatedAdd(createdAt, REQUEST_LIFETIME_MILLIS)

    fun leaseUntil(now: Long): Long = saturatedAdd(now, CLAIM_LEASE_MILLIS)

    fun classifyFailure(
        now: Long,
        expiresAt: Long,
        attemptCount: Int,
        retryable: Boolean,
        errorCode: String?,
        retryAfterSeconds: Int?,
    ): FailureAction {
        return when (errorCode) {
            "VERSION_CONFLICT" -> FailureAction.Conflict
            "REQUEST_EXPIRED" -> FailureAction.Expire
            "UNAUTHENTICATED", "SESSION_CHANGED" -> {
                if (now >= expiresAt) FailureAction.Expire else FailureAction.WaitForLogin
            }
            else -> {
                val isTerminal = errorCode?.let(TERMINAL_ERROR_CODES::contains) == true
                val isTransient = errorCode?.let(TRANSIENT_ERROR_CODES::contains) == true
                val shouldRetry = retryable || retryAfterSeconds != null || isTransient
                if (isTerminal || !shouldRetry) {
                    FailureAction.Fail
                } else if (now >= expiresAt) {
                    FailureAction.Expire
                } else {
                    val nextAttemptAt = saturatedAdd(
                        now,
                        retryDelayMillis(attemptCount, retryAfterSeconds),
                    )
                    if (nextAttemptAt >= expiresAt) {
                        FailureAction.Expire
                    } else {
                        FailureAction.Retry(nextAttemptAt)
                    }
                }
            }
        }
    }

    fun retryDelayMillis(attemptCount: Int, retryAfterSeconds: Int?): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 16)
        val exponential = (MIN_BACKOFF_MILLIS shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
        val retryAfterMillis = retryAfterSeconds
            ?.coerceAtLeast(0)
            ?.toLong()
            ?.let { seconds -> saturatedMultiply(seconds, 1_000L) }
            ?: 0L
        return max(exponential, retryAfterMillis)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatedMultiply(left: Long, right: Long): Long =
        if (left > 0 && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

    private val TERMINAL_ERROR_CODES = setOf(
        "ACCOUNT_DELETING",
        "BETA_ACCESS_REQUIRED",
        "IDEMPOTENCY_MISMATCH",
        "INVALID_BODY",
        "INVALID_REQUEST_BODY",
        "ITEM_DELETED",
        "ITEM_LIMIT_REACHED",
        "ITEM_NOT_FOUND",
        "STORAGE_LIMIT_REACHED",
    )
    private val TRANSIENT_ERROR_CODES = setOf(
        "DEPENDENCY_UNAVAILABLE",
        "RATE_LIMITED",
        "SESSION_REFRESH_UNAVAILABLE",
    )
}

sealed interface FailureAction {
    data object WaitForLogin : FailureAction
    data object Conflict : FailureAction
    data class Retry(val nextAttemptAt: Long) : FailureAction
    data object Fail : FailureAction
    data object Expire : FailureAction
}
