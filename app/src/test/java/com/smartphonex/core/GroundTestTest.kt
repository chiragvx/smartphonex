package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroundTestTest {
    private var now = 1_000_000L
    private var saved = ""
    private val g = GroundTest({ now }) { saved = it }
    private val outs = listOf("motor" to true, "steering" to false)
    private val R = MissionEngine.State.RUNNING

    private fun status(id: String, extra: Map<String, GroundTest.Stage> = emptyMap()) =
        g.json("car", extra, outs)["stages"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject["status"]!!.jsonPrimitive.content

    /** A square, about 20 m a side, from (0, 0) going north first. */
    private val dLat = 20 / 111_320.0
    private val dLon = 20 / 111_320.0
    private val square = listOf(MissionItem(dLat, 0.0, radiusM = 5.0), MissionItem(dLat, dLon, radiusM = 5.0), MissionItem(0.0, dLon, radiusM = 5.0), MissionItem(0.0, 0.0, radiusM = 5.0))

    @Test fun outputsNeedEachWayRunBeforeAPersonCanSayTheyMoveRight() {
        g.outputRun("car", "motor", 0.1)
        g.outputRun("car", "steering", 0.2)
        val refused = g.confirmOutputs("car", outs, true, "ground")
        assertNotNull("steering reverse wasn't run", refused)
        assertTrue(refused!!, "steering reverse" in refused)
        g.outputRun("car", "steering", -0.2)
        assertNull("a forward-only motor needs no reverse", g.confirmOutputs("car", outs, true, "ground"))
        assertEquals("pass", status("outputs"))
        assertNull(g.confirmOutputs("car", outs, false, "ground"))
        assertEquals("a wrong way is a failure", "fail", status("outputs"))
    }

    @Test fun stopOnlyCountsWhenArmed() {
        g.stopped("car", "GROUND", armed = false, speedMps = 0.0, lat = 0.0, lon = 0.0)
        assertEquals("todo", status("stop"))
        g.stopped("car", "GROUND", armed = true, speedMps = 0.0, lat = 0.0, lon = 0.0)
        assertEquals("pass", status("stop"))
    }

    @Test fun drivingStopIsTimedFromGpsSpeed() {
        assertEquals("walking: a moving stop isn't asked for", "skip", status("stop-moving"))
        g.setHow("car", GroundTest.How.DRIVE)
        g.stopped("car", "GROUND", armed = true, speedMps = 3.0, lat = 0.0, lon = 0.0)
        now += 1500
        g.tick("car", null, MissionEngine.State.IDLE, 0, emptyList(), 3 / 111_320.0, 0.0, true, 0.1, null)
        assertEquals("pass", status("stop-moving"))
        val detail = g.json("car", emptyMap(), outs)["stages"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "stop-moving" }.jsonObject["detail"]!!.jsonPrimitive.content
        assertTrue(detail, "1.5 s" in detail && "3 m" in detail)
    }

    @Test fun aSlowStopFails() {
        g.setHow("car", GroundTest.How.DRIVE)
        g.stopped("car", "GROUND", armed = true, speedMps = 3.0, lat = 0.0, lon = 0.0)
        now += 5000
        g.tick("car", null, MissionEngine.State.IDLE, 0, emptyList(), 0.0, 0.0, true, 0.2, null)
        assertEquals("fail", status("stop-moving"))
    }

    @Test fun aWalkedRouteRecordsPointsAndHowFarOffTheLine() {
        // Walking the square, wandering 4 m east of the first leg.
        g.tick("car", "Ground test", R, 0, square, 0.0, 0.0, true, 1.4, null)
        for (k in 1..9) { now += 1000; g.tick("car", "Ground test", R, 0, square, dLat * k / 10, 4 / 111_320.0, true, 1.4, null) }
        for (i in 1..3) { now += 15_000; g.tick("car", "Ground test", R, i, square, square[i - 1].lat, square[i - 1].lon, true, 1.4, null) }
        now += 15_000
        g.tick("car", "Ground test", MissionEngine.State.DONE, 4, square, 0.0, 0.0, true, 0.0, 3.0)
        assertEquals("pass", status("route"))
        val d = g.json("car", emptyMap(), outs)["stages"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "route" }.jsonObject["detail"]!!.jsonPrimitive.content
        assertTrue(d, "all 4 points" in d && "4.0 m" in d && "Walked" in d && "on time" in d)
    }

    @Test fun aRouteThatEndsEarlyFailsAndTooFewPointsIsNoTest() {
        g.tick("car", "two", R, 0, square.take(2), 0.0, 0.0, true, 1.0, null)
        g.tick("car", "two", MissionEngine.State.ABORTED, 1, square.take(2), 0.0, 0.0, true, 1.0, null)
        assertEquals("two points aren't a route test", "todo", status("route"))
        g.tick("car", "sq", R, 0, square, 0.0, 0.0, true, 1.0, null)
        g.tick("car", "sq", R, 1, square, dLat, 0.0, true, 1.0, null)
        g.tick("car", "sq", MissionEngine.State.ABORTED, 1, square, dLat, 0.0, true, 0.0, null)
        assertEquals("fail", status("route"))
    }

    @Test fun drivingIsHeldToATighterLine() {
        g.setHow("car", GroundTest.How.DRIVE)
        g.tick("car", "sq", R, 0, square, 0.0, 0.0, true, 1.0, null)
        g.tick("car", "sq", R, 0, square, dLat / 2, 8 / 111_320.0, true, 1.0, null)
        g.tick("car", "sq", MissionEngine.State.DONE, 4, square, 0.0, 0.0, true, 0.0, null)
        assertEquals("8 m off is fine walking, not driving", "fail", status("route"))
    }

    @Test fun fenceAndReportStagesAndSaving() {
        g.fenceCaught("car", "outside the fence", "hold")
        assertEquals("pass", status("fence"))
        g.fenceCaught("car", "outside the fence", "warn")
        assertEquals("a fence that only warns isn't a pass", "warn", status("fence"))
        assertEquals("pass", status("selftest", mapOf("selftest" to GroundTest.Stage("pass", "Passed", now))))
        val back = GroundTest({ now })
        back.load(saved)
        assertEquals("warn", back.stage("car", "fence")!!.status)
        g.reset("car")
        assertEquals("todo", status("fence"))
    }

    @Test fun allStagesPassingIsDone() {
        val extra = mapOf("selftest" to GroundTest.Stage("pass", "", now), "link" to GroundTest.Stage("pass", "", now))
        listOf("motor" to 1.0, "steering" to 1.0, "steering" to -1.0).forEach { (o, v) -> g.outputRun("car", o, v) }
        g.confirmOutputs("car", outs, true, "ground")
        g.stopped("car", "GROUND", true, 0.0, 0.0, 0.0)
        g.fenceCaught("car", "out", "hold")
        assertFalse(g.json("car", extra, outs)["done"]!!.jsonPrimitive.boolean)
        g.tick("car", "sq", R, 0, square, 0.0, 0.0, true, 1.0, null)
        g.tick("car", "sq", R, 0, square, dLat / 2, 0.0, true, 1.0, null)
        g.tick("car", "sq", MissionEngine.State.DONE, 4, square, 0.0, 0.0, true, 0.0, null)
        val j = g.json("car", extra, outs)
        assertEquals(6, j["needed"]!!.jsonPrimitive.int)
        assertTrue(j["done"]!!.jsonPrimitive.boolean)
    }

    @Test fun startingAgainIgnoresReportsFromBefore() {
        val before = mapOf("selftest" to GroundTest.Stage("pass", "Passed", now))
        assertEquals("pass", status("selftest", before))
        now += 1000
        g.reset("car")
        assertEquals("an old self-test doesn't count after starting again", "todo", status("selftest", before))
        now += 1000
        assertEquals("pass", status("selftest", mapOf("selftest" to GroundTest.Stage("pass", "Passed", now))))
        val back = GroundTest({ now }); back.load(saved)
        assertEquals("todo", back.json("car", before, outs)["stages"]!!.jsonArray.first().jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test fun offLineIsDistanceToTheSegment() {
        assertEquals(4.0, GroundTest.offLineM(0.0 to 0.0, dLat to 0.0, dLat / 2 to 4 / 111_320.0), 0.01)
        assertEquals("beyond the end: distance to the end", 5.0, GroundTest.offLineM(0.0 to 0.0, dLat to 0.0, (25 / 111_320.0) to 0.0), 0.01)
    }
}
