package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private var now = 1_000_000L
    private val p = Pairing("the-token", clock = { now })
    private fun wrong(): String = if (p.currentCode() == "000000") "000001" else "000000"

    @Test fun rightCodeGivesTokenAndIsSingleUse() {
        val code = p.currentCode()
        assertEquals(6, code.length)
        assertEquals(PairResult.Ok("the-token"), p.redeem(code))
        assertNotEquals(code, p.currentCode().takeIf { it != code } ?: "")
        assertTrue(p.redeem(code) !is PairResult.Ok || p.currentCode() == code)
    }

    @Test fun wrongCodeRefused() {
        assertEquals(PairResult.Wrong, p.redeem(wrong()))
    }

    @Test fun codeExpires() {
        val code = p.currentCode()
        now += 300_001
        p.currentCode()
        assertTrue(p.redeem(code) !is PairResult.Ok || p.currentCode() == code)
    }

    @Test fun fiveFailuresLockEvenTheRightCode() {
        repeat(4) { assertEquals(PairResult.Wrong, p.redeem(wrong())) }
        assertEquals(PairResult.Locked(30), p.redeem(wrong()))
        assertEquals(PairResult.Locked(30), p.redeem(p.currentCode()))
        now += 30_001
        assertEquals(PairResult.Ok("the-token"), p.redeem(p.currentCode()))
    }

    @Test fun lockoutsGrow() {
        repeat(5) { p.redeem(wrong()) }
        now += 30_001
        repeat(4) { p.redeem(wrong()) }
        assertEquals(PairResult.Locked(60), p.redeem(wrong()))
    }
}
