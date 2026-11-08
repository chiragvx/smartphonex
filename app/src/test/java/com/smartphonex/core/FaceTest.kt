package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test

class FaceTest {
    private fun input(
        mode: Mode = Mode.DISARMED, killed: Boolean = false, steering: Double = 0.0, throttle: Double = 0.0,
        requested: Pair<Face.Expr, Long>? = null, quietS: Double = 0.0, batteryLow: Boolean = false, hot: Boolean = false, mission: Boolean = false,
    ) = Face.Input(killed, mode, if (mode == Mode.FAILSAFE) "link lost" else null, steering, throttle, mission, requested, 1000, quietS, batteryLow, hot)

    @Test fun `safety states win over a requested expression`() {
        val happy = Face.Expr.HAPPY to 5000L
        assertEquals(Face.Expr.DEAD, Face.look(input(killed = true, requested = happy)).expr)
        val fs = Face.look(input(mode = Mode.FAILSAFE, requested = happy))
        assertEquals(Face.Expr.ALARM, fs.expr)
        assertTrue(fs.alarm)
        assertEquals("Failsafe: link lost", fs.label)
    }

    @Test fun `a request shows until it expires`() {
        assertEquals(Face.Expr.HAPPY, Face.look(input(requested = Face.Expr.HAPPY to 5000L)).expr)
        assertEquals(Face.Expr.NEUTRAL, Face.look(input(requested = Face.Expr.HAPPY to 900L)).expr)
    }

    @Test fun `moving looks where it's turning`() {
        val left = Face.look(input(mode = Mode.MANUAL, steering = 1.0, throttle = 0.4))
        assertEquals(Face.Expr.FOCUSED, left.expr)
        assertEquals(-1f, left.gazeX)
        assertEquals(1f, Face.look(input(mode = Mode.MANUAL, steering = -1.0, throttle = 0.4)).gazeX)
        assertEquals(Face.Expr.FOCUSED, Face.look(input(mode = Mode.AUTO, mission = true)).expr)
        assertEquals(Face.Expr.NEUTRAL, Face.look(input(mode = Mode.MANUAL)).expr)
    }

    @Test fun `tired when quiet, low or warm, but not while armed`() {
        assertEquals(Face.Expr.SLEEPY, Face.look(input(quietS = 700.0)).expr)
        assertEquals("Battery low", Face.look(input(batteryLow = true)).label)
        assertEquals("Too warm", Face.look(input(hot = true)).label)
        assertEquals(Face.Expr.NEUTRAL, Face.look(input(mode = Mode.MANUAL, batteryLow = true, quietS = 700.0)).expr)
    }

    @Test fun `requests are checked`() {
        assertEquals(Face.Request(Face.Expr.LOVE, 5000), Face.parseRequest("Love", null, 1000).first)
        assertEquals(Face.Request(Face.Expr.SAD, Long.MAX_VALUE), Face.parseRequest("sad", 0.0, 1000).first)
        assertEquals(Face.Request(null, 0), Face.parseRequest("Auto", null, 1000).first)
        for (e in listOf("excited", "confused", "scared")) assertNotNull(e, Face.parseRequest(e, 2.0, 0).first)
        assertNotNull(Face.parseRequest("dead", 2.0, 0).second)
        assertNotNull(Face.parseRequest("alarm", 2.0, 0).second)
        assertNotNull(Face.parseRequest("focused", 2.0, 0).second)
        assertNotNull(Face.parseRequest("happy", -1.0, 0).second)
        assertNotNull(Face.parseRequest("happy", 3601.0, 0).second)
        assertNotNull(Face.parseRequest("happy", Double.NaN, 0).second)
        assertNotNull(Face.parseRequest(null, 1.0, 0).second)
    }

    @Test fun `a held expression still gives way to safety`() {
        val held = Face.Expr.HAPPY to Long.MAX_VALUE
        assertEquals(Face.Expr.HAPPY, Face.look(input(requested = held)).expr)
        assertEquals(Face.Expr.ALARM, Face.look(input(mode = Mode.FAILSAFE, requested = held)).expr)
    }

    @Test fun `modes and idle time`() {
        assertEquals(Face.Mode.OFF, Face.mode(0.0))
        assertEquals(Face.Mode.ROBOT, Face.mode(2.0))
        assertTrue(Face.idleSeconds(Face.Mode.OFF, 60.0).isInfinite())
        assertEquals(60.0, Face.idleSeconds(Face.Mode.IDLE, 60.0), 0.0)
        assertEquals(10.0, Face.idleSeconds(Face.Mode.ROBOT, 60.0), 0.0)
    }
}
