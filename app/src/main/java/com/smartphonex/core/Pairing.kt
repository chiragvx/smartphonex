package com.smartphonex.core

import java.security.MessageDigest
import java.security.SecureRandom

sealed class PairResult {
    data class Ok(val token: String) : PairResult()
    data object Wrong : PairResult()
    data class Locked(val seconds: Long) : PairResult()
}

/**
 * Swaps a short code shown on the phone's screen for the long access token, so nobody has to
 * type the token. A six-digit code is guessable given unlimited tries, so failures lock pairing
 * out for longer each time. It is meant for the local network: Phase 2 must not expose it as is.
 */
class Pairing(
    private val token: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 300_000,
) {
    private val rng = SecureRandom()
    private var code = ""
    private var expiresAt = 0L
    private var failures = 0
    private var lockouts = 0
    private var lockedUntil = 0L

    private fun rotate(now: Long) {
        code = "%06d".format(rng.nextInt(1_000_000))
        expiresAt = now + ttlMs
    }

    @Synchronized fun currentCode(): String {
        val now = clock()
        if (now >= expiresAt) rotate(now)
        return code
    }

    @Synchronized fun lockedSeconds(): Long = maxOf(0L, (lockedUntil - clock() + 999) / 1000)

    @Synchronized fun redeem(attempt: String): PairResult {
        val now = clock()
        if (now < lockedUntil) return PairResult.Locked(lockedSeconds())
        if (now >= expiresAt) rotate(now)
        if (MessageDigest.isEqual(attempt.toByteArray(), code.toByteArray())) {
            failures = 0
            lockouts = 0
            rotate(now)
            return PairResult.Ok(token)
        }
        if (++failures >= MAX_FAILURES) {
            failures = 0
            lockouts++
            lockedUntil = now + minOf(BASE_LOCK_MS shl (lockouts - 1).coerceAtMost(8), MAX_LOCK_MS)
            rotate(now)
            return PairResult.Locked(lockedSeconds())
        }
        return PairResult.Wrong
    }

    private companion object {
        const val MAX_FAILURES = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 600_000L
    }
}
