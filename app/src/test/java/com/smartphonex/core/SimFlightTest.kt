package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Plans flown by the real flight controller and mission engine against the simulated plane, in fast time: what a
 * flight would do, minutes in a second. Logic, not aerodynamics: the model is tuned to the planning defaults.
 */
class SimFlightTest {
    private var t = 1_000_000L
    private val log = EventLog(clock = { t })
    private val params = ParamStore(null) { "plane" }
    private val fc = FlightController(params, log, { "plane" }, { t }, { t * 1_000_000 })
    private val sim = SimulatedPlane(fc) { t }
    private val missions = MissionEngine { t }
    private val lat0 = 18.52
    private val lon0 = 73.85
    private val fr = LocalFrame(lat0, lon0)
    private val photos = ArrayList<String>()
    private var maxH = 0.0
    private var minFlyingSpeed = 99.0

    init {
        fc.simulation = { true }
        fc.missions = missions
        missions.onEvery = { tr, _, _ -> photos += tr }
        missions.onMiss = { log.event("mission", "phone", "miss", it) }
        sim.placeAt(lat0, lon0)
    }

    private fun at(east: Double, north: Double) = buildJsonArray { add(fr.lat(V2(east, north))); add(fr.lon(V2(east, north))) }

    /** The control loop at 50 Hz: the controller's output moves the plane, the plane's sensors feed the controller. */
    private fun run(seconds: Double, armed: Boolean, until: () -> Boolean = { false }) {
        repeat((seconds * 50).toInt()) {
            t += 20
            val out = fc.process(ActuatorOutput(0.0, 0.0), armed, 0.02)
            sim.exchange(out.copy(safe = !armed), 0.02)
            // Slowing to land, motor off, is meant to happen: only flight before the landing counts.
            if (!sim.onGround && !fc.landing) { maxH = maxOf(maxH, sim.h); minFlyingSpeed = minOf(minFlyingSpeed, sim.airspeed) }
            if (until()) return
        }
    }

    private fun flyPlan(plan: JsonObject, seconds: Double = 900.0): PlanResult {
        run(3.0, armed = false) // GPS settles on the ground.
        assertNull(fc.setHomeHere())
        val r = PlanCompiler.compile(plan, PlanContext(PlanPerf.from(params, "plane"), fc.homeNow, Fence(), mapOf("photo" to false)))
        assertTrue(r.problems.toString(), r.ok)
        missions.load(r.mission!!)
        missions.start()
        fc.setModeForce(FlightMode.AUTO)
        run(seconds, armed = true) { missions.state != MissionEngine.State.RUNNING && sim.onGround }
        return r
    }

    @Test fun aPlanIsFlownFromLaunchToLandingAsPlanned() {
        val r = flyPlan(buildJsonObject {
            put("format", PlanCompiler.FORMAT)
            putJsonArray("steps") {
                addJsonObject { put("do", "takeoff"); put("heading", 0.0); put("alt", 60.0) }
                addJsonObject {
                    put("do", "survey"); put("id", "field"); put("alt", 60.0); put("spacing", 100.0)
                    put("area", buildJsonArray { listOf(V2(200.0, 400.0), V2(600.0, 400.0), V2(600.0, 800.0), V2(200.0, 800.0)).forEach { add(at(it.x, it.y)) } })
                    putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "photo"); put("everyM", 20.0) } }
                }
                addJsonObject { put("do", "land"); put("id", "down"); put("at", at(0.0, 0.0)); put("heading", 180.0) }
            }
        })
        assertEquals("landed", MissionEngine.State.DONE, missions.state)
        assertTrue(sim.onGround)
        assertFalse("touched down gently", sim.crashed)
        assertEquals("it held the planned height", 60.0, maxH, 12.0)
        assertTrue("never near a stall: $minFlyingSpeed m/s", minFlyingSpeed > SimulatedPlane.STALL_MPS + 1)
        // The camera fired along the lines, close to the plan's count.
        val planned = r.json["timeline"]!!.jsonArray.first { "trigger photo" in it.jsonObject["text"].toString() }.jsonObject["text"]!!.jsonPrimitive.content
        val count = Regex("×(\\d+)").find(planned)!!.groupValues[1].toInt()
        assertEquals(planned, count.toDouble(), photos.size.toDouble(), count * 0.1)
        assertTrue("it kept to the plan's time: ${missions.lateS} s late", kotlin.math.abs(missions.lateS ?: 0.0) < 40)
        val near = Navigation.distanceM(lat0, lon0, fc.gpsNow!!.lat, fc.gpsNow!!.lon)
        assertTrue("came down near the touchdown point: $near m", near < 150)
    }

    @Test fun aClimbTooSteepForItsLegCirclesUpAndCarriesOn() {
        flyPlan(buildJsonObject {
            put("format", PlanCompiler.FORMAT)
            putJsonArray("steps") {
                addJsonObject { put("do", "takeoff"); put("heading", 0.0); put("alt", 30.0) }
                addJsonObject { put("do", "goto"); put("id", "steep"); put("at", at(0.0, 600.0)); put("alt", 160.0) }
                addJsonObject { put("do", "goto"); put("id", "next"); put("at", at(400.0, 600.0)); put("alt", 160.0) }
                addJsonObject { put("do", "home"); put("alt", 160.0) }
            }
        }, seconds = 300.0)
        assertTrue("it circled to make height", log.allEvents().any { it.code == "miss" && "steep" in it.message && "Circling" in it.message })
        assertTrue("then carried on", log.allEvents().any { it.code == "miss" && "reached 160 m" in it.message })
        assertTrue(missions.index >= 2)
        assertTrue("never traded speed for height: $minFlyingSpeed m/s", minFlyingSpeed > SimulatedPlane.STALL_MPS + 1)
    }

    @Test fun withTheMotorStoppedItGlidesToTheZoneWithoutStalling() {
        run(3.0, armed = false)
        assertNull(fc.setHomeHere())
        // Climb out under power, heading north, to 80 m.
        fc.setModeForce(FlightMode.AUTO)
        missions.load(Mission("out", listOf(MissionItem(fr.lat(V2(0.0, 2000.0)), lon0, alt = 80.0, radiusM = 40.0, line = true))))
        missions.start()
        run(120.0, armed = true) { sim.h > 78 && sim.y > 500 }
        assertTrue(sim.h > 70)
        sim.motorFailed = true
        // The phone takes a second or two to be sure the motor has stopped; meanwhile it's still holding height.
        run(2.5, armed = true)
        // A field off to one side, 250 m away: well within an 8:1 glide from 80 m.
        val zone = V2(150.0, sim.y + 200)
        fc.divertTo(FlightController.Divert("field", "landing", fr.lat(zone), fr.lon(zone), 40.0, glide = true, why = "test"))
        run(150.0, armed = true) { sim.onGround }
        assertTrue("down", sim.onGround)
        assertTrue("never stalled gliding: $minFlyingSpeed m/s", minFlyingSpeed > SimulatedPlane.STALL_MPS)
        assertFalse(sim.crashed)
        // It circles down over the field, inside its turning circle of it.
        val miss = kotlin.math.hypot(sim.x - zone.x, sim.y - zone.y)
        assertTrue("came down over the field: $miss m from its centre", miss < 90)
    }

    @Test fun leavingTheSimulationForgetsItsHomeAndMode() {
        run(3.0, armed = false)
        assertNull(fc.setHomeHere())
        fc.setModeForce(FlightMode.RETURN_HOME)
        var simulating = true
        fc.simulation = { simulating }
        simulating = false
        // Switched away, the simulator is no longer stepped: only the controller runs.
        t += 20
        fc.process(ActuatorOutput(0.0, 0.0), false, 0.02)
        assertNull("a real vehicle never goes to a home set in a simulation", fc.homeNow)
        assertNull(fc.gpsNow)
        assertEquals(FlightMode.MANUAL, fc.mode)
        assertTrue(log.allEvents().any { it.code == "simulation" && "cleared" in it.message })
    }

    @Test fun anArmedPlaneGlidingWithTheThrottleClosedKeepsAGoodPosition() {
        run(3.0, armed = false)
        assertNull(fc.setHomeHere())
        fc.setModeForce(FlightMode.AUTO)
        missions.load(Mission("out", listOf(MissionItem(fr.lat(V2(0.0, 2000.0)), lon0, alt = 80.0, radiusM = 40.0, line = true))))
        missions.start()
        run(120.0, armed = true) { sim.h > 78 && sim.y > 500 }
        sim.motorFailed = true
        fc.divertTo(FlightController.Divert("home", "landing", lat0, lon0, 0.0, glide = true, why = "test"))
        var lost = 0
        run(20.0, armed = true) { if (fc.navEstimate()?.health != GpsGuard.Health.GOOD) lost++; false }
        // Before: an armed vehicle at zero throttle counted as parked, so every fix of a glide looked like a GPS jump.
        assertEquals("position good throughout the glide", 0, lost)
    }
}
