package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

private const val LAT = 28.6
private const val LON = 77.2
/** About 111 m north per 0.001 degrees. */
private fun north(m: Double) = LAT + m / 111_195.0

class GeoTest {
    private val square = listOf(LAT - 0.001 to LON - 0.001, LAT - 0.001 to LON + 0.001, LAT + 0.001 to LON + 0.001, LAT + 0.001 to LON - 0.001)

    @Test fun pointInPolygon() {
        assertTrue(Geo.inside(LAT, LON, square))
        assertFalse(Geo.inside(LAT + 0.002, LON, square))
    }

    @Test fun fenceAndKeepOutBreaches() {
        val f = Fence(square, listOf(listOf(LAT - 0.0001 to LON - 0.0001, LAT - 0.0001 to LON + 0.0001, LAT + 0.0001 to LON)))
        assertNotNull(Geo.breach(f, LAT + 0.002, LON, null, null))
        assertEquals("inside keep-out area 1", Geo.breach(f, LAT, LON, null, null))
        assertNull(Geo.breach(f, LAT + 0.0005, LON, null, null))
    }

    @Test fun missionValidationGivesSpecificReasons() {
        val f = Fence(square)
        val m = Mission("m", listOf(MissionItem(LAT, LON, triggers = listOf("nope")), MissionItem(LAT + 0.01, LON, radiusM = 0.0)))
        val e = Geo.validate(m, f, null, setOf("photo"), 0.5)
        assertTrue(e.toString(), e.any { "unknown trigger" in it })
        assertTrue(e.toString(), e.any { "outside the fence" in it })
        assertTrue(e.toString(), e.any { "arrival radius" in it })
    }

    @Test fun legsCrossingAKeepOutAreRefused() {
        val f = Fence(keepOut = listOf(listOf(LAT - 0.0001 to LON - 0.0001, LAT - 0.0001 to LON + 0.0001, LAT + 0.0001 to LON + 0.0001, LAT + 0.0001 to LON - 0.0001)))
        val m = Mission("m", listOf(MissionItem(LAT - 0.001, LON), MissionItem(LAT + 0.001, LON)))
        assertTrue(Geo.validate(m, f, null, emptySet(), 1.0).any { it.startsWith("The leg") })
    }

    @Test fun badMissionsDontParse() {
        assertNull(Geo.parseMission(buildJsonObject { putJsonArray("items") {} }).first)
        val bad = buildJsonObject { putJsonArray("items") { addJsonObject { put("lat", 99.0); put("lon", 0.0) } } }
        assertNull(Geo.parseMission(bad).first)
        val rule = buildJsonObject { put("onLinkLoss", "explode"); putJsonArray("items") { addJsonObject { put("lat", 1.0); put("lon", 1.0) } } }
        assertNull(Geo.parseMission(rule).first)
    }

    @Test fun missionSurvivesJson() {
        val m = Mission("x", listOf(MissionItem(1.0, 2.0, 3.0, 0.2, 4.0, 6.0, listOf("photo"))), "home", "hold")
        assertEquals(m, Geo.parseMission(Geo.missionJson(m)).first)
    }
}

class MissionEngineTest {
    private var now = 0L
    private fun fix(lat: Double) = GpsFix(lat, LON, 0.0, 0.0, 0.0, 3.0, 10, now)

    @Test fun followsPointsHoldsAndFinishes() {
        val e = MissionEngine { now }
        val arrived = ArrayList<Int>()
        e.onArrive = { _, i -> arrived += i }
        e.load(Mission("m", listOf(MissionItem(LAT, LON, holdS = 2.0), MissionItem(north(100.0), LON)), onEnd = "home"))
        e.start()
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.Hold)
        assertEquals(listOf(0), arrived)
        now = 2500
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.GoTo)
        assertEquals(1, e.index)
        val end = e.step(fix(north(100.0)))
        assertEquals(listOf(0, 1), arrived)
        assertEquals("home", (end as MissionEngine.Step.End).action)
        assertEquals(MissionEngine.State.DONE, e.state)
    }

    private fun planned(north: Double, every: List<Pair<String, Double>> = emptyList(), alt: Double? = null, miss: Misses? = null, expectS: Double? = null, expectAt: Double? = null, orbit: OrbitSpec? = null, holdS: Double = 0.0) =
        MissionItem(north(north), LON, alt, radiusM = 5.0, every = every, miss = miss, expectS = expectS, expectAt = expectAt, orbit = orbit, holdS = holdS, line = true, step = "s")
    private val defaults = PlanCompiler.defaults("plane")

    @Test fun aCompiledPlanFollowsTheLineFromTheLastPoint() {
        val e = MissionEngine { now }
        e.load(Mission("p", listOf(planned(100.0), planned(200.0)), hash = "h"))
        e.start()
        val s = e.step(fix(north(10.0))) as MissionEngine.Step.GoTo
        assertEquals("the first leg starts where it was", north(10.0), s.fromLat!!, 1e-9)
        e.step(fix(north(98.0)))
        val s2 = e.step(fix(north(110.0))) as MissionEngine.Step.GoTo
        assertEquals("then from the point it reached", north(100.0), s2.fromLat!!, 1e-9)
    }

    @Test fun distanceTriggersFireAlongTheLineAsThePlanCountedThem() {
        val e = MissionEngine { now }
        val fired = ArrayList<String>()
        e.onEvery = { t, _, _ -> fired += t }
        e.load(Mission("p", listOf(planned(0.0), planned(100.0, every = listOf("photo" to 20.0))), hash = "h"))
        e.start()
        var m = 0.0
        while (e.index < 2 && m < 120) { e.step(fix(north(m))); m += 3.0 }
        // 0, 20, 40, 60, 80 and 100 m: floor(100 / 20) + 1, the plan's count. The last fires on arrival.
        assertEquals(6, fired.size)
    }

    @Test fun aLegTakingFarTooLongFollowsItsArrivalRule() {
        fun run(rule: Miss): Pair<MissionEngine, MissionEngine.Step> {
            now = 0
            val e = MissionEngine { now }
            e.load(Mission("p", listOf(planned(100.0, miss = defaults.copy(arrival = rule), expectS = 10.0), planned(200.0)), hash = "h"))
            e.start()
            e.step(fix(LAT))
            now = 49_000
            assertTrue("not yet: twice the plan plus 30 s", e.step(fix(LAT)) is MissionEngine.Step.GoTo)
            now = 51_000
            return e to e.step(fix(LAT))
        }
        val (aborted, a) = run(Miss("abort"))
        assertTrue(a is MissionEngine.Step.Abort)
        assertEquals(MissionEngine.State.ABORTED, aborted.state)
        val (skipped, s) = run(Miss("skip"))
        assertEquals(1, skipped.index)
        assertTrue(s is MissionEngine.Step.GoTo)
        val (held, h) = run(Miss("hold", maxS = 5.0, then = "abort"))
        assertTrue(h is MissionEngine.Step.Wait)
        now = 57_000
        assertTrue("waiting is limited, then its rule", held.step(fix(LAT)) is MissionEngine.Step.Abort)
    }

    @Test fun arrivingLowCirclesUpToItsLimitThenFollowsTheRule() {
        val rule = defaults.copy(altitude = Miss("circle", 10.0, 30.0, "abort"))
        fun engine(): MissionEngine { now = 0; return MissionEngine { now }.also { it.load(Mission("p", listOf(planned(100.0, alt = 100.0, miss = rule), planned(300.0, alt = 100.0)), hash = "h")); it.start() } }
        val e = engine()
        assertTrue(e.step(fix(north(99.0)), 60.0) is MissionEngine.Step.Wait)
        now = 10_000
        assertTrue("still climbing", e.step(fix(north(99.0)), 80.0) is MissionEngine.Step.Wait)
        now = 20_000
        assertTrue("within 10 m: on to the next point", e.step(fix(north(99.0)), 93.0) is MissionEngine.Step.GoTo)
        assertEquals(1, e.index)
        val stuck = engine()
        stuck.step(fix(north(99.0)), 60.0)
        now = 31_000
        assertTrue("it can't climb: after 30 s, abort", stuck.step(fix(north(99.0)), 62.0) is MissionEngine.Step.Abort)
    }

    @Test fun orbitsGoRoundTheirLapsAndLatenessIsMeasured() {
        now = 0
        val e = MissionEngine { now }
        val c = OrbitSpec(north(100.0), LON, 50.0, 1.0, true)
        e.load(Mission("p", listOf(planned(50.0, orbit = c, expectAt = 10.0), planned(300.0)), hash = "h"))
        e.start()
        now = 15_000
        assertTrue(e.step(fix(north(50.0))) is MissionEngine.Step.Circle)
        assertEquals("5 s behind the plan", 5.0, e.lateS!!, 1e-9)
        val fr = LocalFrame(north(100.0), LON)
        // Round clockwise from the south side, in 10° steps.
        for (k in 1..36) {
            val a = Math.toRadians(-90.0 - k * 10.0)
            val p = V2(cos(a), sin(a)) * 50.0
            val s = e.step(GpsFix(fr.lat(p), fr.lon(p), 0.0, 0.0, 0.0, 3.0, 10, now))
            if (k < 36) assertTrue("lap not done at ${k * 10}°", s is MissionEngine.Step.Circle)
            else assertTrue(s is MissionEngine.Step.GoTo)
        }
    }

    @Test fun holdsWithNoPositionPausedOrIdle() {
        val e = MissionEngine { now }
        e.load(Mission("m", listOf(MissionItem(north(50.0), LON))))
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.Hold)
        e.start()
        assertTrue(e.step(null) is MissionEngine.Step.Hold)
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.GoTo)
        e.pause()
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.Hold)
        e.resume()
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.GoTo)
        e.abort()
        assertEquals(MissionEngine.State.ABORTED, e.state)
        assertTrue(e.step(fix(LAT)) is MissionEngine.Step.Hold)
    }
}

class TrimAndTesterTest {
    @Test fun trimReverseAndLimits() {
        assertEquals(0.1, OutputTrim(trim = 0.1).apply(0.0), 1e-9)
        assertEquals(-0.5, OutputTrim(reverse = true).apply(0.5), 1e-9)
        assertEquals(0.6, OutputTrim(hi = 0.6).apply(1.0), 1e-9)
        assertEquals(1.0, OutputTrim(trim = 0.3).apply(1.0), 1e-9)
    }

    @Test fun trimUndoAndPerVehicle() {
        var v = "a"
        val store = TrimStore(null, { v })
        store.set("s", OutputTrim(trim = 0.1))
        store.set("s", OutputTrim(trim = 0.2))
        assertEquals(0.2, store.get("s").trim, 1e-9)
        assertTrue(store.undo("s"))
        assertEquals(0.1, store.get("s").trim, 1e-9)
        v = "b"
        assertEquals(0.0, store.get("s").trim, 1e-9)
        assertFalse(store.undo("s"))
    }

    @Test fun holdToRunExpiresByItself() {
        var now = 1000L
        val t = OutputTester { now }
        assertNull(t.hold(Source.LOCAL, "motor", 0.1))
        assertEquals("motor", t.active()?.first)
        assertNotNull("another screen can't take over a live test", t.hold(Source.GROUND, "motor", 0.1))
        now += 1000
        assertNull("released means stopped", t.active())
        assertNull(t.hold(Source.GROUND, "motor", 0.1))
    }
}

class TriggerCodecTest {
    private fun t(body: JsonObjectBuilder.() -> Unit) = TriggerCodec.parse(buildJsonObject(body))

    @Test fun refusesBadDefinitions() {
        assertNotNull(t { put("id", "../x"); putJsonObject("action") { put("type", "marker") } }.second)
        assertNotNull(t { put("id", "a"); putJsonObject("action") { put("type", "launch") } }.second)
        assertNotNull(t { put("id", "a"); putJsonObject("action") { put("type", "pulse") } }.second)
        assertNotNull(t { put("id", "a"); putJsonObject("action") { put("type", "pulse"); put("output", "x"); put("value", 2.0) } }.second)
    }

    @Test fun dangerousTriggersAreNeverAiOrAutomatic() {
        val ok = t { put("id", "drop"); put("danger", true); put("aiAllowed", true); putJsonObject("action") { put("type", "pulse"); put("output", "x"); put("value", 1.0) } }.first
        assertFalse(ok!!.aiAllowed)
        assertNotNull(t { put("id", "drop"); put("danger", true); put("everySeconds", 5.0); putJsonObject("action") { put("type", "marker") } }.second)
    }
}

/** The whole runtime with a fake phone: real controller, flight controller and Ops. */
class OpsRuntimeTest {
    private lateinit var dir: File
    private lateinit var rt: VehicleRuntime
    private var seq = 0L

    @Before fun setUp() {
        dir = Files.createTempDirectory("ops").toFile()
        rt = VehicleRuntime(dir)
    }

    @After fun tearDown() { rt.ops.flush(); rt.stop(); dir.deleteRecursively() }

    private fun cmd(c: Command, s: Source = Source.GROUND) = rt.controller.submit(Envelope(s, ++seq, null, c))
    private fun ops(name: String, s: Source = Source.GROUND, body: JsonObjectBuilder.() -> Unit = {}) = cmd(Command.OpsRequest(name, buildJsonObject(body)), s)
    private fun tick(n: Int = 1) = repeat(n) { rt.controller.tick(0.01) }
    private var settled = false
    private var ns = 0L
    /** A phone lying still, so the autopilot has what navigation modes require. */
    private fun settle() {
        if (settled) return
        settled = true
        repeat(3000) { ns += 5_000_000L; rt.fc.onAccel(0f, 0f, 9.81f); rt.fc.onGyro(ns, 0f, 0f, 0f) }
    }
    /** Puts the vehicle here. A jump to a new place resets the GPS guard, as if it had settled there. */
    private fun gps(lat: Double = LAT, lon: Double = LON) {
        settle()
        val e = rt.fc.navEstimate()
        if (e == null || Navigation.distanceM(e.lat, e.lon, lat, lon) > 1.0) rt.fc.gpsGuard.reset()
        repeat(GpsGuard.SETTLE_FIXES + 1) { rt.fc.onGps(GpsFix(lat, lon, 200.0, 1.0, 0.0, 3.0, 10, System.currentTimeMillis()), null) }
    }
    private fun mission(vararg lat: Double) = buildJsonObject {
        put("name", "walk"); putJsonArray("items") { lat.forEach { l -> addJsonObject { put("lat", l); put("lon", LON); put("radiusM", 3.0) } } }
    }
    private val mode get() = rt.controller.latest().mode

    @Test fun manualArmsWithNoSensorsButNavigationDoesNot() {
        assertEquals("gyro", rt.ops.readiness().first { it.id == "gyro" }.id)
        assertNull(rt.ops.armProblem())
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
        cmd(Command.Stop)
        rt.fc.setModeForce(FlightMode.AUTO)
        assertNotNull("no GPS or mission", rt.ops.armProblem())
        assertFalse(cmd(Command.SetMode(Mode.MANUAL)).accepted)
    }

    @Test fun requiredChecklistBlocksArming() {
        rt.params.set("ARM_REQ_CHECK", 1.0)
        val r = cmd(Command.SetMode(Mode.MANUAL))
        assertFalse(r.accepted)
        assertTrue(r.message, "Checklist" in r.message)
        rt.ops.checklistItems().forEach { i -> assertTrue(ops("checklist_set") { put("item", i); put("done", true) }.accepted) }
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
    }

    @Test fun killIsHumanOnlyAndOnlyClearedByArming() {
        cmd(Command.SetMode(Mode.MANUAL))
        assertFalse(cmd(Command.Kill, Source.AI).accepted)
        assertTrue(cmd(Command.Kill).accepted)
        assertEquals(Mode.FAILSAFE, mode)
        assertTrue(rt.controller.killed)
        tick()
        assertTrue(rt.controller.latest().outputs.all { it.pulseUs == 0 })
        assertFalse("AI can't rearm after a kill", cmd(Command.SetMode(Mode.AUTO), Source.AI).accepted)
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
        assertFalse(rt.controller.killed)
        tick()
        assertTrue(rt.controller.latest().outputs.all { it.pulseUs > 0 })
    }

    @Test fun outputTestsOnlyWhileDisarmedWithLimitsAndConfirmation() {
        val motor = rt.profiles.active.channels.first { it.kind != ChannelKind.SERVO }.name
        val servo = rt.profiles.active.channels.first { it.kind == ChannelKind.SERVO }.name
        assertEquals("needs_confirm", ops("test_output") { put("output", motor); put("value", 0.1) }.code)
        assertEquals("out_of_range", ops("test_output") { put("output", motor); put("value", 0.9); put("confirmed", true) }.code)
        assertTrue(ops("test_output") { put("output", motor); put("value", 0.1); put("confirmed", true) }.accepted)
        tick()
        assertEquals(0.1, rt.controller.latest().outputs.first { it.name == motor }.value, 1e-9)
        assertTrue(ops("test_stop").accepted)
        tick()
        assertEquals(0.0, rt.controller.latest().outputs.first { it.name == motor }.value, 0.001)
        assertEquals("out_of_range", ops("test_output") { put("output", "nope"); put("value", 0.1) }.code)
        assertFalse("AI never runs output tests", ops("test_output", Source.AI) { put("output", servo); put("value", 0.1) }.accepted)
        ops("test_output") { put("output", servo); put("value", 0.2) }
        cmd(Command.SetMode(Mode.MANUAL))
        assertNull("arming ends a test", rt.ops.tester.active())
        assertEquals("not_permitted", ops("test_output") { put("output", servo); put("value", 0.2) }.code)
    }

    @Test fun theGroundRunIsAPersonsHoldToRunAndEndsWhenArmed() {
        rt.profiles.select("plane")
        assertEquals("not_permitted", ops("ground_run") { put("confirmed", true) }.code) // Microphone off.
        rt.params.set("MIC_PROP", 1.0)
        assertEquals("needs_confirm", ops("ground_run").code)
        assertFalse("never the AI's", ops("ground_run", Source.AI) { put("confirmed", true) }.accepted)
        assertTrue(ops("ground_run") { put("confirmed", true) }.accepted)
        tick()
        assertEquals(0.3, rt.controller.latest().outputs.first { it.name == "Motor" }.value, 1e-9)
        assertTrue(rt.ops.readiness().any { it.id == "groundrun" && it.status == "warn" })
        cmd(Command.SetMode(Mode.MANUAL))
        assertFalse("arming ends it", rt.ops.groundRun.running())
        assertEquals("not_permitted", ops("ground_run") { put("confirmed", true) }.code)
        cmd(Command.SetMode(Mode.DISARMED))
        tick()
        assertEquals(0.0, rt.controller.latest().outputs.first { it.name == "Motor" }.value, 1e-9)
    }

    @Test fun trimLimitsWhileArmedAndForAi() {
        val servo = rt.profiles.active.channels.first { it.kind == ChannelKind.SERVO }.name
        assertEquals("out_of_range", ops("trim_set") { put("output", servo); put("trim", 0.5) }.code)
        assertTrue(ops("trim_set") { put("output", servo); put("trim", 0.1) }.accepted)
        tick()
        assertEquals(0.1, rt.controller.latest().outputs.first { it.name == servo }.value, 1e-9)
        assertFalse(ops("trim_set", Source.AI) { put("output", servo); put("trim", 0.0) }.accepted)
        cmd(Command.SetMode(Mode.MANUAL))
        assertEquals("while armed only small steps", "out_of_range", ops("trim_set") { put("output", servo); put("trim", 0.2) }.code)
        assertTrue(ops("trim_set") { put("output", servo); put("trim", 0.115) }.accepted)
        assertEquals("not_permitted", ops("trim_set") { put("output", servo); put("reverse", true) }.code)
        assertTrue(ops("trim_undo") { put("output", servo) }.accepted)
        assertEquals(0.1, rt.ops.trims.get(servo).trim, 1e-9)
    }

    @Test fun dangerousTriggerTakesTwoSteps() {
        val servo = rt.profiles.active.channels.first { it.kind == ChannelKind.SERVO }.name
        assertTrue(ops("trigger_save") {
            putJsonObject("trigger") {
                put("id", "drop"); put("label", "Release"); put("danger", true); put("armedOnly", true)
                putJsonObject("action") { put("type", "pulse"); put("output", servo); put("value", 0.8); put("ms", 1000) }
            }
        }.accepted)
        assertEquals("arm first", ops("trigger_fire") { put("id", "drop") }.message)
        cmd(Command.SetMode(Mode.MANUAL))
        assertTrue(ops("trigger_fire") { put("id", "drop") }.message, "two steps" in ops("trigger_fire") { put("id", "drop") }.message)
        assertTrue(ops("trigger_prepare") { put("id", "drop") }.accepted)
        assertTrue(ops("trigger_fire") { put("id", "drop") }.accepted)
        assertNotNull(rt.ops.fire("drop", Source.AI, "test"))
        tick()
        assertEquals(0.8, rt.controller.latest().outputs.first { it.name == servo }.value, 1e-9)
    }

    @Test fun triggerCooldownAndCount() {
        ops("trigger_save") { putJsonObject("trigger") { put("id", "m"); put("armedOnly", false); put("cooldownMs", 0); put("maxCount", 2); putJsonObject("action") { put("type", "marker") } } }
        assertNull(rt.ops.fire("m", Source.GROUND, "t"))
        assertNull(rt.ops.fire("m", Source.GROUND, "t"))
        assertTrue(rt.ops.fire("m", Source.GROUND, "t")!!.contains("2 times"))
        assertNotNull(rt.ops.fire("photo", Source.GROUND, "t").let { rt.ops.fire("photo", Source.GROUND, "t") })
    }

    @Test fun aMotorTriggerCantRunDisarmed() {
        val motor = rt.profiles.active.channels.first { it.kind != ChannelKind.SERVO }.name
        ops("trigger_save") { putJsonObject("trigger") { put("id", "spin"); put("armedOnly", false); putJsonObject("action") { put("type", "pulse"); put("output", motor); put("value", 0.2) } } }
        assertTrue(rt.ops.fire("spin", Source.GROUND, "t")!!.contains("arm first"))
    }

    @Test fun missionRunsOnThePhoneAndFiresTriggersAtPoints() {
        gps()
        assertEquals("not_permitted", ops("mission_start").code)
        val bad = ops("mission_upload") { put("mission", buildJsonObject { putJsonArray("items") { addJsonObject { put("lat", LAT); put("lon", LON); putJsonArray("triggers") { add("nope") } } } }) }
        assertEquals("invalid", bad.code)
        assertTrue(bad.message, "unknown trigger" in bad.message)
        assertTrue(ops("mission_upload") {
            put("mission", buildJsonObject {
                put("name", "walk"); put("onEnd", "stop")
                putJsonArray("items") {
                    addJsonObject { put("lat", LAT); put("lon", LON); put("radiusM", 3.0); putJsonArray("triggers") { add("mark") } }
                    addJsonObject { put("lat", north(50.0)); put("lon", LON); put("radiusM", 3.0) }
                }
            })
        }.accepted)
        val r = ops("mission_start")
        assertTrue(r.message, r.accepted)
        assertEquals(Mode.AUTO, mode)
        assertEquals(FlightMode.AUTO, rt.fc.mode)
        tick(2)
        assertEquals(1, rt.ops.missions.index)
        assertTrue(rt.log.allEvents().any { it.kind == "trigger" && it.code == "mark" })
        assertTrue("drives toward the next point", rt.controller.latest().throttle > 0.0)
        // Pause, resume.
        assertTrue(ops("mission_pause").accepted)
        tick(2)
        assertEquals(0.0, rt.controller.latest().throttle, 0.001)
        assertTrue(ops("mission_resume").accepted)
        tick(2)
        assertTrue(rt.controller.latest().throttle > 0.0)
        // Arrive at the end.
        gps(north(50.0))
        tick(3)
        assertEquals(MissionEngine.State.DONE, rt.ops.missions.state)
        tick(3)
        assertEquals("ended with stop", Mode.DISARMED, mode)
        assertEquals(FlightMode.MANUAL, rt.fc.mode)
    }

    @Test fun stopEndsAMissionAndLeavesNormalArming() {
        gps()
        ops("mission_upload") { put("mission", mission(north(50.0))) }
        assertTrue(ops("mission_start").accepted)
        tick(2)
        cmd(Command.Stop)
        tick(2)
        assertEquals(Mode.DISARMED, mode)
        assertEquals(MissionEngine.State.ABORTED, rt.ops.missions.state)
        assertEquals(FlightMode.MANUAL, rt.fc.mode)
        assertEquals(0.0, rt.controller.latest().throttle, 0.001)
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
    }

    @Test fun aPersonOnTheSticksTakesOverAMission() {
        gps()
        ops("mission_upload") { put("mission", mission(north(50.0))) }
        ops("mission_start")
        tick(2)
        assertTrue(cmd(Command.Drive(0.2, 0.0)).accepted)
        assertEquals(Mode.MANUAL, mode)
        assertEquals(FlightMode.MANUAL, rt.fc.mode)
        assertEquals(MissionEngine.State.PAUSED, rt.ops.missions.state)
    }

    @Test fun fenceBreachHoldsAndFenceNeedsAPosition() {
        assertTrue(ops("fence_set") { put("fence", buildJsonObject { put("maxDistM", 20.0); put("action", "hold") }) }.accepted)
        val refused = cmd(Command.SetMode(Mode.MANUAL))
        assertFalse(refused.accepted)
        assertTrue(refused.message, "Geofence" in refused.message || "GPS" in refused.message)
        gps()
        assertTrue(rt.fc.setHomeHere() == null)
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
        cmd(Command.Drive(0.1, 0.0))
        rt.ops.tick(rt.controller.tick(0.01))
        assertEquals(FlightMode.MANUAL, rt.fc.mode)
        gps(north(50.0))
        rt.ops.tick(rt.controller.tick(0.01))
        assertEquals(FlightMode.HOLD, rt.fc.mode)
        assertTrue(rt.log.allEvents().any { it.kind == "failsafe" && it.code == "fence" })
    }

    @Test fun aPlanIsCheckedOnThePhoneAndTheAnswerComesBack() {
        gps()
        assertNull(rt.fc.setHomeHere())
        assertTrue(ops("fence_set") { put("fence", buildJsonObject { put("maxDistM", 300.0); put("action", "hold") }) }.accepted)
        fun plan(north: Double) = buildJsonObject {
            put("format", PlanCompiler.FORMAT)
            putJsonArray("steps") {
                addJsonObject { put("do", "goto"); put("at", buildJsonArray { add(north(north)); add(LON) }); putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "photo") } } }
                addJsonObject { put("do", "home") }
            }
        }
        val good = ops("plan_check") { put("plan", plan(100.0)) }
        assertTrue(good.accepted)
        assertEquals(true, good.data!!["ok"]!!.jsonPrimitive.boolean)
        assertTrue(good.data!!["timeline"]!!.jsonArray.any { "trigger photo" in it.jsonObject["text"].toString() })
        val far = ops("plan_check") { put("plan", plan(400.0)) }
        assertTrue("checking worked, the plan didn't", far.accepted)
        assertEquals("problems", far.code)
        assertTrue(far.message, "from home" in far.message)
        assertTrue("the AI may check plans", ops("plan_check", Source.AI) { put("plan", plan(100.0)) }.accepted)
        assertNull("checking changes nothing", rt.ops.missions.mission)
    }

    private fun roverPlan(north: Double) = buildJsonObject {
        put("format", PlanCompiler.FORMAT); put("name", "walk")
        putJsonArray("steps") {
            addJsonObject { put("do", "goto"); put("id", "out"); put("at", buildJsonArray { add(north(north)); add(LON) }) }
            addJsonObject { put("do", "home"); put("id", "back") }
        }
    }

    @Test fun whatRunsIsWhatWasCheckedAndSent() {
        assertEquals("needs the real home", "not_permitted", ops("plan_upload") { put("plan", roverPlan(60.0)) }.code)
        gps()
        assertNull(rt.fc.setHomeHere())
        assertFalse("a person sends plans", ops("plan_upload", Source.AI) { put("plan", roverPlan(60.0)) }.accepted)
        val bad = ops("plan_upload") { put("plan", roverPlan(60.0).let { JsonObject(it + ("format" to JsonPrimitive("nope"))) }) }
        assertEquals("invalid", bad.code)
        assertNotNull("the problems come back with it", bad.data)

        val up = ops("plan_upload") { put("plan", roverPlan(60.0)) }
        assertTrue(up.message, up.accepted)
        val hash = rt.ops.missions.mission!!.hash!!
        assertEquals(hash, up.data!!["hash"]!!.jsonPrimitive.content)
        assertEquals("out", rt.ops.missions.mission!!.items[0].step)

        assertTrue(ops("mission_start") { put("hash", "0000") }.message.contains("isn't the plan"))
        // A setting it was planned with changes: the plan has to be checked and sent again.
        rt.params.set("PLAN_SPEED", 3.0)
        val changed = ops("mission_start") { put("hash", hash) }
        assertFalse(changed.accepted)
        assertTrue(changed.message, "something changed" in changed.message)
        rt.params.set("PLAN_SPEED", 2.0)
        // A fence that now cuts across it.
        ops("fence_set") { put("fence", buildJsonObject { put("maxDistM", 30.0); put("action", "hold") }) }
        assertTrue(ops("mission_start").message.contains("no longer checks out"))
        ops("fence_set") { put("fence", buildJsonObject { put("maxDistM", 0.0) }) }

        assertTrue(ops("mission_start") { put("hash", hash) }.accepted)
        tick(2)
        assertEquals(Mode.AUTO, mode)
        assertEquals("out", rt.ops.opsJson(true)["mission"]!!.jsonObject["step"]!!.jsonPrimitive.content)

        // It survives a restart as its file, compiled again.
        rt.ops.flush()
        val again = Ops(rt, dir)
        assertEquals(hash, again.missions.mission?.hash)
    }

    @Test fun aPlaneFliesOnlyCheckedPlansAndOnlyWhenAllowed() {
        rt.profiles.select("plane")
        gps()
        assertNull(rt.fc.setHomeHere())
        val plan = buildJsonObject {
            put("format", PlanCompiler.FORMAT)
            putJsonArray("steps") { addJsonObject { put("do", "goto"); put("at", buildJsonArray { add(north(300.0)); add(LON) }); put("alt", 60.0) }; addJsonObject { put("do", "home"); put("alt", 60.0) } }
        }
        val up = ops("plan_upload") { put("plan", plan) }
        assertTrue(up.message, up.accepted)
        val r = ops("mission_start")
        assertEquals("unsupported", r.code)
        assertTrue(r.message, "NAV_PLANE_MISS" in r.message)
        rt.params.set("NAV_PLANE_MISS", 1.0)
        val r2 = ops("mission_start")
        assertTrue(r2.message, r2.accepted)
        assertEquals(MissionEngine.State.RUNNING, rt.ops.missions.state)
        cmd(Command.Stop)
        tick(2)
        // An old-style mission still can't fly a plane.
        assertTrue(ops("mission_upload") { put("mission", mission(north(50.0))) }.accepted)
        assertTrue(ops("mission_start").message.contains("NAV_PLANE_MISS"))
    }

    @Test fun aFlyingPlaneThatHitsAStopFenceFliesHomeInsteadOfCuttingTheMotor() {
        rt.profiles.select("plane")
        assertTrue(ops("fence_set") { put("fence", buildJsonObject { put("maxDistM", 20.0); put("action", "stop") }) }.accepted)
        gps()
        assertNull(rt.fc.setHomeHere())
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
        // Flying north at 15 m/s, out through the fence.
        rt.fc.gpsGuard.reset()
        repeat(GpsGuard.SETTLE_FIXES + 2) { rt.fc.onGps(GpsFix(north(40.0), LON, 230.0, 15.0, 0.0, 3.0, 10, System.currentTimeMillis()), null) }
        assertNotNull(rt.fc.flyingReason())
        cmd(Command.Drive(0.6, 0.0))
        rt.ops.tick(rt.controller.tick(0.01))
        rt.ops.tick(rt.controller.tick(0.01))
        assertEquals("still armed, flown by the autopilot", Mode.AUTO, mode)
        assertEquals(FlightMode.RETURN_HOME, rt.fc.mode)
        assertTrue(rt.log.allEvents().any { it.kind == "failsafe" && it.code == "airborne" })
        val motor = rt.controller.latest().outputs.first { it.name == "Motor" }
        assertTrue("the motor keeps running: ${motor.value}", motor.value > 0.1)

        // A person's STOP still cuts it: that's the deliberate kill.
        assertTrue(cmd(Command.Kill).accepted)
        assertEquals(Mode.FAILSAFE, mode)
    }

    @Test fun missionWontStartWhenItsRadiusIsTighterThanTheGps() {
        gps()
        ops("mission_upload") { put("mission", buildJsonObject { putJsonArray("items") { addJsonObject { put("lat", north(50.0)); put("lon", LON); put("radiusM", 1.0) } } }) }
        val r = ops("mission_start")
        assertFalse(r.accepted)
        assertTrue(r.message, "arrival radius" in r.message && "Widen" in r.message)
    }

    @Test fun losingThePositionWithAFenceSetActsOnTheFence() {
        assertTrue(ops("fence_set") { put("fence", buildJsonObject { put("maxDistM", 200.0); put("action", "hold") }) }.accepted)
        gps()
        assertNull(rt.fc.setHomeHere())
        assertTrue(cmd(Command.SetMode(Mode.MANUAL)).accepted)
        rt.ops.tick(rt.controller.tick(0.01))
        assertEquals(FlightMode.MANUAL, rt.fc.mode)
        // The guard calls the position lost after GPS_TIMEOUT_S without a fix. Simulate it with an old fix.
        rt.fc.gpsGuard.reset()
        rt.fc.onGps(GpsFix(LAT, LON, 200.0, 1.0, 0.0, 3.0, 10, System.currentTimeMillis() - 60_000), null)
        rt.ops.tick(rt.controller.tick(0.01))
        assertEquals(FlightMode.HOLD, rt.fc.mode)
        assertTrue(rt.log.allEvents().any { it.kind == "failsafe" && it.code == "fence" && "position lost" in it.message })
    }

    @Test fun linkLossAppliesTheMissionsRule() {
        gps()
        ops("mission_upload") { put("mission", buildJsonObject { put("onLinkLoss", "hold"); putJsonArray("items") { addJsonObject { put("lat", north(50.0)); put("lon", LON) } } }) }
        ops("mission_start")
        val t = rt.controller.tick(0.01)
        rt.ops.tick(t)
        assertEquals(FlightMode.AUTO, rt.fc.mode)
        rt.ops.tick(t.copy(t = t.t + 5000))
        assertEquals(FlightMode.HOLD, rt.fc.mode)
        assertEquals(MissionEngine.State.PAUSED, rt.ops.missions.state)
        assertEquals("the phone still holds it armed", Mode.AUTO, mode)
    }

    @Test fun simulatorRefusesGpsMissions() {
        gps()
        assertTrue(cmd(Command.SelectProfile("sim-buggy")).accepted)
        val r = ops("goto") { put("lat", LAT); put("lon", LON) }
        assertFalse(r.accepted)
        assertEquals("unsupported", r.code)
    }

    @Test fun drillsReportAndLeaveItDisarmed() {
        rt.start()
        try {
            val ms = rt.controller.stopDrill()
            assertNotNull(ms)
            assertEquals(Mode.DISARMED, mode)
            val (took, text) = rt.controller.failsafeDrill()
            assertNotNull(text, took)
            assertEquals(Mode.DISARMED, mode)
        } finally { rt.stop() }
    }

    @Test fun autoDisarmsWhenIdleButNotOnAPlane() {
        val params = rt.params
        params.set("AUTO_DISARM", 1.0)
        cmd(Command.SetMode(Mode.MANUAL))
        val start = rt.controller.latest().t
        while (rt.controller.latest().t - start < 300) { rt.controller.tick(0.01); cmd(Command.Drive(0.0, 0.0)); Thread.sleep(10) }
        assertEquals(Mode.MANUAL, mode)
        while (rt.controller.latest().t - start < 1200) { rt.controller.tick(0.01); cmd(Command.Drive(0.0, 0.0)); Thread.sleep(10) }
        assertEquals("zero sticks count as idle", Mode.DISARMED, mode)
    }

    @Test fun reportsAndFilesCantEscapeTheirFolders() {
        File(dir, "logs").mkdirs()
        File(dir, "logs/flight-1.csv").writeText("t,mode\n1,MANUAL\n")
        File(dir, "secret.csv").writeText("nope")
        assertNotNull(rt.ops.readFile("log", "flight-1.csv"))
        assertNull(rt.ops.readFile("log", "../secret.csv"))
        assertNull(rt.ops.readFile("log", "..\\secret.csv"))
        assertNull(rt.ops.readFile("nope", "flight-1.csv"))
        assertNull(rt.ops.readFrame("..", 0))
        assertEquals(1, rt.ops.filesJson()["logs"]!!.jsonArray.size)
    }

    @Test fun capabilitiesFollowTheDevice() {
        assertTrue(rt.ops.capsJson()["map"]!!.jsonPrimitive.boolean)
        val status = rt.ops.opsJson(false)
        assertFalse("viewers don't get the fence's shape", status["fence"]!!.jsonObject.containsKey("data"))
        assertTrue(rt.ops.opsJson(true)["fence"]!!.jsonObject.containsKey("data"))
    }
}
