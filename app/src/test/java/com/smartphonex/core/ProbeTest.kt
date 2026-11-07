package com.smartphonex.core

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {
    @Test fun readsAndroidPingOutput() {
        val out = """
            --- 1.1.1.1 ping statistics ---
            50 packets transmitted, 48 received, 4% packet loss, time 9812ms
            rtt min/avg/max/mdev = 21.103/48.771/312.400/40.122 ms
        """.trimIndent()
        val r = Probe.parse(out, "1.1.1.1", 50)
        assertEquals(4.0, r["lossPct"]!!.jsonPrimitive.doubleOrNull!!, 0.001)
        assertEquals(48.771, r["rttAvgMs"]!!.jsonPrimitive.doubleOrNull!!, 0.001)
        assertEquals(40.122, r["jitterMs"]!!.jsonPrimitive.doubleOrNull!!, 0.001)
        assertTrue(r["ok"]!!.jsonPrimitive.booleanOrNull!!)
    }

    @Test fun allLostHasNoTiming() {
        val r = Probe.parse("50 packets transmitted, 0 received, 100% packet loss, time 10000ms", "1.1.1.1", 50)
        assertEquals(100.0, r["lossPct"]!!.jsonPrimitive.doubleOrNull!!, 0.001)
        assertNull(r["rttAvgMs"]!!.jsonPrimitive.doubleOrNull)
    }

    @Test fun unreadableOutputIsNotAResult() {
        assertEquals(false, Probe.parse("ping: unknown host", "x", 5)["ok"]!!.jsonPrimitive.booleanOrNull)
    }
}
