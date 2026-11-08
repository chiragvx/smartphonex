package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FlightControllerTest {
    private var nowMs = 1_000_000L
    private var nowNs = 1_000_000_000L
    private val log = EventLog(clock = { nowMs })
    private var type = "plane"
    private val params = ParamStore(null) { type }
    private val fc = FlightController(params, log, { type }, { nowMs }, { nowNs })

    /**
     * Feeds what a phone lying flat, screen up, reads when the vehicle has this attitude, with
     * the magnetic field pointing north and down.
     */
    private fun feed(rollDeg: Double, pitchDeg: Double, headingDeg: Double, seconds: Double, gyro: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)) {
        val r = Math.toRadians(rollDeg); val p = Math.toRadians(pitchDeg); val y = Math.toRadians(headingDeg)
        // Gravity reaction in vehicle axes.
        val ax = 9.80665 * sin(p)
        val ay = -9.80665 * cos(p) * sin(r)
        val az = -9.80665 * cos(p) * cos(r)
        // Earth field (north 30, down 40) rotated into vehicle axes.
        val mn = 30.0; val md = 40.0
        val cy = cos(y); val sy = sin(y); val cp = cos(p); val sp = sin(p); val cr = cos(r); val sr = sin(r)
        val mx = cy * cp * mn - sp * md
        val my = (cy * sp * sr - sy * cr) * mn + cp * sr * md
        val mz = (cy * sp * cr + sy * sr) * mn + cp * cr * md
        repeat((seconds * 100).toInt()) {
            nowMs += 10
            nowNs += 10_000_000L
            // Phone axes for the flat mount: phone x = vehicle y, phone y = vehicle x, phone z = -vehicle z.
            fc.onAccel(ay.toFloat(), ax.toFloat(), (-az).toFloat())
            fc.onMag(my.toFloat(), mx.toFloat(), (-mz).toFloat())
            fc.onGyro(nowNs, gyro[1].toFloat(), gyro[0].toFloat(), (-gyro[2]).toFloat())
            fc.process(ActuatorOutput(0.0, 0.0), false, 0.01)
        }
    }

    private fun assertAttitude(roll: Double, pitch: Double, yaw: Double, tol: Double = 2.0) {
        val a = fc.attitudeNow
        assertEquals("roll", roll, a.roll, tol)
        assertEquals("pitch", pitch, a.pitch, tol)
        assertEquals("yaw", 0.0, Frames.wrap180(a.yaw - yaw), tol)
    }

    private fun step(input: ActuatorOutput, armed: Boolean = true): ActuatorOutput { nowMs += 10; nowNs += 10_000_000L; return fc.process(input, armed, 0.01) }

    @Test fun readsLevelAndHeading() { feed(0.0, 0.0, 90.0, 3.0); assertAttitude(0.0, 0.0, 90.0) }
    @Test fun readsRoll() { feed(30.0, 0.0, 0.0, 3.0); assertAttitude(30.0, 0.0, 0.0) }
    @Test fun readsPitch() { feed(0.0, 20.0, 0.0, 3.0); assertAttitude(0.0, 20.0, 0.0) }
    @Test fun readsCombinedTilt() { feed(-25.0, -15.0, 200.0, 3.0); assertAttitude(-25.0, -15.0, 200.0, 3.0) }

    @Test fun correctsToANewAttitude() {
        feed(0.0, 0.0, 0.0, 3.0)
        feed(30.0, 0.0, 0.0, 10.0)
        assertAttitude(30.0, 0.0, 0.0, 3.0)
    }

    @Test fun gyroTracksFastMotionBeforeGravityCatchesUp() {
        feed(0.0, 0.0, 0.0, 3.0)
        // One second of roll at 20 deg/s. Gravity still says level, and pulls it back only slowly.
        feed(0.0, 0.0, 0.0, 1.0, doubleArrayOf(Math.toRadians(20.0), 0.0, 0.0))
        val roll = fc.attitudeNow.roll
        assertTrue("roll $roll", roll in 8.0..20.0)
    }

    @Test fun recoversFromShaking() {
        feed(0.0, 0.0, 0.0, 3.0)
        repeat(100) {
            nowMs += 10; nowNs += 10_000_000L
            fc.onAccel(25f * (if (it % 2 == 0) 1 else -1), -20f, 40f)
            fc.onGyro(nowNs, 2f * (if (it % 3 == 0) 1 else -1), 1f, -1f)
        }
        feed(10.0, 0.0, 0.0, 12.0)
        assertAttitude(10.0, 0.0, 0.0, 4.0)
    }

    @Test fun referenceMatchesOurFrame() {
        val mount = Frames.mount(0, 0.0)
        // Identity rotation: phone flat, top pointing north.
        val a = Frames.fromRotationVector(0.0, 0.0, 0.0, 1.0, mount)
        assertEquals(0.0, a.roll, 1e-6); assertEquals(0.0, a.pitch, 1e-6); assertEquals(0.0, Frames.wrap180(a.yaw), 1e-6)
        // Rotated 90 degrees counter-clockwise from above: top points west.
        val s = sin(Math.toRadians(45.0))
        assertEquals(270.0, Frames.fromRotationVector(0.0, 0.0, s, s, mount).yaw, 1e-6)
    }

    @Test fun everyMountIsAProperRotation() {
        for (t in 0..3) {
            val m = Frames.mount(t, 0.0)
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            assertEquals("mount $t", 1.0, det, 1e-9)
        }
    }

    @Test fun uprightMountSeesLevelWhenPhoneStandsUp() {
        val m = Frames.mount(1, 0.0)
        // Phone upright: its y axis points up, so gravity reaction is +y.
        val b = Frames.apply(m, 0.0, 9.8, 0.0)
        assertEquals(0.0, b[0], 1e-9); assertEquals(0.0, b[1], 1e-9); assertEquals(-9.8, b[2], 1e-9)
    }

    @Test fun manualPassesThrough() {
        feed(30.0, 0.0, 0.0, 3.0)
        val input = ActuatorOutput(0.5, 0.2, -0.3, 0.4)
        assertEquals(input, step(input))
    }

    @Test fun stabilizeCountersRollAndPitch() {
        feed(30.0, 20.0, 0.0, 3.0)
        assertNull(fc.setMode(FlightMode.STABILIZE))
        val out = step(ActuatorOutput(0.5, 0.0))
        assertTrue("rolled right, so command left: ${out.roll}", out.roll > 0)
        assertTrue("nose up, so command down: ${out.pitch}", out.pitch < 0)
        assertEquals(0.5, out.throttle, 1e-9)
    }

    @Test fun stabilizeOutputGrowsWithTilt() {
        feed(10.0, 0.0, 0.0, 3.0)
        fc.setMode(FlightMode.STABILIZE)
        val small = abs(step(ActuatorOutput(0.0, 0.0)).roll)
        feed(40.0, 0.0, 0.0, 12.0)
        val large = abs(step(ActuatorOutput(0.0, 0.0)).roll)
        assertTrue("$small then $large", large > small * 1.5)
    }

    @Test fun stabilizeFollowsStickAsAngle() {
        feed(0.0, 0.0, 0.0, 3.0)
        fc.setMode(FlightMode.STABILIZE)
        // Roll stick full left asks for a 45 degree left bank, so the command is to roll left.
        assertTrue(step(ActuatorOutput(0.0, 0.0, 0.0, 1.0)).roll > 0)
    }

    @Test fun integralStaysLimitedWhenSurfacesCannotMoveThePhone() {
        feed(30.0, 0.0, 0.0, 3.0)
        fc.setMode(FlightMode.STABILIZE)
        var out = ActuatorOutput(0.0, 0.0)
        repeat(6000) { out = step(ActuatorOutput(0.0, 0.0)) }
        assertTrue("output ${out.roll}", abs(out.roll) <= 1.0)
        val pidPart = 0.01 * 90 + params.get("I_MAX")
        assertTrue("output ${out.roll} vs $pidPart", abs(out.roll) <= pidPart + 0.01)
    }

    @Test fun holdHeadingSteersBackOnEveryVehicleClass() {
        for (t in listOf("plane", "boat")) {
            type = t
            feed(0.0, 0.0, 90.0, 3.0)
            assertNull(fc.setMode(FlightMode.HOLD_HEADING))
            step(ActuatorOutput(0.3, 0.0))
            // Turned 40 degrees right of the held heading: it must command a left turn.
            feed(0.0, 0.0, 130.0, 0.2)
            val out = (1..10).map { step(ActuatorOutput(0.3, 0.0)) }.last()
            val turnLeft = if (t == "plane") out.roll else out.steering
            assertTrue("$t turn left $turnLeft", turnLeft > 0)
            fc.setMode(FlightMode.MANUAL)
        }
    }

    /** Puts the vehicle here. A jump to a new place resets the GPS guard, as if it had settled there. */
    private fun gpsAt(lat: Double, lon: Double, acc: Double = 5.0) {
        val e = fc.navEstimate()
        if (e == null || Navigation.distanceM(e.lat, e.lon, lat, lon) > 1.0) fc.gpsGuard.reset()
        repeat(GpsGuard.SETTLE_FIXES + 1) { fc.onGps(GpsFix(lat, lon, 100.0, 1.0, 0.0, acc, 8, nowMs), 0.0) }
    }

    @Test fun missionStopsAndWaitsWhenGpsIsTooRoughThenHandsOver() {
        type = "boat"
        feed(0.0, 0.0, 0.0, 3.0)
        val engine = MissionEngine { nowMs }
        fc.missions = engine
        engine.load(Mission("m", listOf(MissionItem(28.001, 77.0, radiusM = 5.0))))
        gpsAt(28.0, 77.0)
        assertNull(fc.setMode(FlightMode.AUTO))
        engine.start()
        val going = fc.process(ActuatorOutput(0.0, 0.0), true, 0.01)
        assertTrue("drives toward the point: ${going.throttle}", going.throttle > 0.0)
        // Accuracy collapses: the guard calls it too rough, and the boat stops where it is.
        repeat(10) { nowMs += 1000; nowNs += 1_000_000_000L; fc.onGyro(nowNs, 0f, 0f, 0f); fc.onGps(GpsFix(28.0, 77.0, 100.0, 0.0, 0.0, 80.0, 4, nowMs), 0.0) }
        val waiting = fc.process(ActuatorOutput(0.0, 0.0), true, 0.01)
        assertEquals(0.0, waiting.throttle, 1e-9)
        assertEquals(FlightMode.AUTO, fc.mode)
        assertTrue(log.allEvents().any { it.kind == "gps" && it.code == "waiting" })
        // Still bad after NAV_WAIT_S: paused, and a person decides.
        repeat(35) { nowMs += 1000; nowNs += 1_000_000_000L; fc.onGyro(nowNs, 0f, 0f, 0f); fc.onGps(GpsFix(28.0, 77.0, 100.0, 0.0, 0.0, 80.0, 4, nowMs), 0.0); fc.process(ActuatorOutput(0.0, 0.0), true, 0.01) }
        assertEquals(FlightMode.HOLD, fc.mode)
        assertEquals(MissionEngine.State.PAUSED, engine.state)
    }

    @Test fun aGpsJumpDoesNotSteerTheMission() {
        type = "boat"
        feed(0.0, 0.0, 0.0, 3.0)
        val engine = MissionEngine { nowMs }
        fc.missions = engine
        engine.load(Mission("m", listOf(MissionItem(28.001, 77.0, radiusM = 5.0))))
        gpsAt(28.0, 77.0)
        assertNull(fc.setMode(FlightMode.AUTO))
        engine.start()
        fc.process(ActuatorOutput(0.0, 0.0), true, 0.01)
        // One fix 200 m north, beyond the point: ignored, so the mission doesn't think it arrived.
        nowMs += 1000; nowNs += 1_000_000_000L; fc.onGyro(nowNs, 0f, 0f, 0f)
        fc.onGps(GpsFix(28.0018, 77.0, 100.0, 0.0, 0.0, 5.0, 8, nowMs), 0.0)
        fc.process(ActuatorOutput(0.0, 0.0), true, 0.01)
        assertEquals(0, engine.index)
        assertTrue(log.allEvents().any { it.kind == "gps" && it.code == "rejected" })
    }

    @Test fun returnHomeRequiresGpsAndHome() {
        feed(0.0, 0.0, 0.0, 3.0)
        assertTrue(fc.setMode(FlightMode.RETURN_HOME)!!.contains("no GPS"))
        gpsAt(28.0, 77.0, acc = 90.0)
        assertTrue(fc.setMode(FlightMode.RETURN_HOME)!!.contains("only good to"))
        gpsAt(28.0, 77.0)
        assertTrue(fc.setMode(FlightMode.RETURN_HOME)!!.contains("home"))
        assertNull(fc.setHomeHere())
        assertNull(fc.setMode(FlightMode.RETURN_HOME))
    }

    @Test fun returnHomeTurnsTowardHomeAndAppliesThrottle() {
        type = "boat"
        feed(0.0, 0.0, 0.0, 3.0)
        gpsAt(28.0, 77.0)
        fc.setHomeHere()
        gpsAt(28.0, 77.01) // About a kilometre east, facing north: home is to the west, on the left.
        assertNull(fc.setMode(FlightMode.RETURN_HOME))
        val out = (1..10).map { step(ActuatorOutput(0.0, 0.0)) }.last()
        assertTrue("turn left ${out.steering}", out.steering > 0)
        assertEquals(0.4, out.throttle, 1e-9)
    }

    @Test fun boatStopsWhenHomeAndPlaneOrbits() {
        for (t in listOf("boat", "plane")) {
            type = t
            feed(0.0, 0.0, 0.0, 3.0)
            gpsAt(28.0, 77.0)
            fc.setHomeHere()
            assertNull(fc.setMode(FlightMode.RETURN_HOME))
            val out = step(ActuatorOutput(0.0, 0.0))
            if (t == "boat") assertEquals(0.0, out.throttle, 1e-9) else assertEquals(0.4, out.throttle, 1e-9)
            fc.setMode(FlightMode.MANUAL)
        }
    }

    @Test fun lostGpsLeavesReturnHome() {
        type = "plane"
        feed(0.0, 0.0, 0.0, 3.0)
        gpsAt(28.0, 77.0)
        fc.setHomeHere()
        fc.setMode(FlightMode.RETURN_HOME)
        feed(0.0, 0.0, 0.0, 11.0)
        assertEquals(FlightMode.STABILIZE, fc.mode)
    }

    @Test fun lostGyroDropsToManualAndAsksForFailsafe() {
        feed(0.0, 0.0, 0.0, 3.0)
        fc.setMode(FlightMode.STABILIZE)
        repeat(60) { step(ActuatorOutput(0.0, 0.0)) }
        assertEquals(FlightMode.MANUAL, fc.mode)
        assertEquals("gyroscope stopped", fc.takeFailsafeRequest())
    }

    @Test fun phoneWithoutGyroIsManualOnly() {
        assertTrue(fc.setMode(FlightMode.STABILIZE)!!.contains("gyroscope"))
        assertNull(fc.setMode(FlightMode.MANUAL))
    }

    @Test fun lowBatteryBlocksArming() {
        fc.onDeviceState(5.0, 0)
        assertTrue(fc.armProblem()!!.contains("battery"))
        fc.onDeviceState(50.0, 0)
        assertNull(fc.armProblem())
    }

    @Test fun deviceWithoutBatteryCanArm() {
        fc.onDeviceState(null, 0)
        assertNull(fc.armProblem())
        assertEquals("none", fc.healthMap()["battery"])
        assertEquals("No battery: mains power", fc.batteryText())
    }

    @Test fun linkLossPolicyByVehicle() {
        for ((t, expectHome) in listOf("plane" to true, "boat" to true, "car" to false)) {
            type = t
            feed(0.0, 0.0, 0.0, 3.0)
            gpsAt(28.0, 77.0)
            fc.setHomeHere()
            assertNull(fc.setMode(FlightMode.STABILIZE))
            assertEquals(t, expectHome, fc.returnHomeOnLinkLoss())
            fc.setMode(FlightMode.MANUAL)
        }
    }

    @Test fun manualNeverAutoReturnsHome() {
        feed(0.0, 0.0, 0.0, 3.0); gpsAt(28.0, 77.0); fc.setHomeHere()
        assertFalse(fc.returnHomeOnLinkLoss())
    }

    @Test fun setLevelMakesCurrentAttitudeZero() {
        feed(5.0, -3.0, 0.0, 3.0)
        assertNull(fc.setLevel())
        assertEquals(5.0, params.get("TRIM_ROLL"), 1.0)
        assertTrue(fc.fastJson().contains("\"a\":[0.0,0.0") || fc.slowJson(true).toString().isNotEmpty())
    }

    @Test fun gyroCalibrationLearnsOffsetAndRefusesMotion() {
        feed(0.0, 0.0, 0.0, 3.0)
        fc.calibrateGyro()
        feed(0.0, 0.0, 0.0, 2.2, doubleArrayOf(0.02, 0.0, 0.0))
        assertEquals(0.02, abs(params.get("GYRO_BY")), 0.005) // Phone y is vehicle x on this mount.
        val before = params.get("GYRO_BY")
        fc.calibrateGyro()
        feed(0.0, 0.0, 0.0, 2.2, doubleArrayOf(1.0, 0.0, 0.0))
        assertEquals(before, params.get("GYRO_BY"), 1e-9)
    }

    @Test fun locationIsLeftOutForThoseNotAllowed() {
        feed(0.0, 0.0, 0.0, 3.0); gpsAt(28.5, 77.5); fc.setHomeHere()
        val full = fc.slowJson(true).toString()
        val public = fc.slowJson(false).toString()
        assertTrue(full.contains("28.5"))
        assertFalse(public.contains("28.5"))
        assertFalse(public.contains("\"home\""))
    }

    @Test fun paramsAreBoundedAndPerVehicle() {
        assertNotNull(params.set("ROLL_P", 99.0))
        assertNotNull(params.set("NOPE", 1.0))
        assertNotNull(params.set("ROLL_P", Double.NaN))
        assertNull(params.set("ROLL_P", 0.5))
        assertEquals(0.5, params.get("ROLL_P"), 1e-9)
        type = "boat"
        assertEquals(0.01, params.get("ROLL_P"), 1e-9)
    }

    @Test fun paramsSurviveRestart() {
        val f = java.io.File.createTempFile("params", ".json"); f.delete()
        ParamStore(f) { "plane" }.set("HDG_P", 2.5)
        assertEquals(2.5, ParamStore(f) { "plane" }.get("HDG_P"), 1e-9)
        f.delete()
    }

    @Test fun navigationMatchesKnownValues() {
        assertEquals(111_195.0, Navigation.distanceM(0.0, 0.0, 1.0, 0.0), 50.0)
        assertEquals(0.0, Navigation.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, Navigation.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, Navigation.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-6)
    }

    // --- With the vehicle controller in charge.

    private inner class Rig {
        val store = ProfileStore(null).also { it.select("plane") }
        val c = VehicleController(OutputPreview(), store, log, clock = { nowMs }, adapterFor = { null }, fc = fc)
        fun send(cmd: Command, s: Source = Source.GROUND) = c.submit(Envelope(s, null, null, cmd))
    }

    @Test fun armingRefusedWithReason() {
        val r = Rig()
        r.send(Command.SetFlightMode(FlightMode.MANUAL))
        fc.onDeviceState(2.0, 0)
        val res = r.send(Command.SetMode(Mode.MANUAL))
        assertFalse(res.accepted)
        assertTrue(res.message.contains("battery"))
    }

    @Test fun disarmedHoldsFailsafeButShowsWhatItWouldCommand() {
        val r = Rig()
        feed(30.0, 0.0, 0.0, 3.0)
        assertTrue(r.send(Command.SetFlightMode(FlightMode.STABILIZE)).accepted)
        var t = r.c.latest()
        repeat(20) { nowMs += 10; nowNs += 10_000_000L; t = r.c.tick(0.01) }
        assertEquals(Mode.DISARMED, t.mode)
        assertEquals(1500, t.outputs.first { it.name == "Aileron" }.pulseUs)
        assertTrue("would ${t.would}", t.would[1] > 1510) // Aileron: rolled right, would command left.
    }

    @Test fun armedStabilizeMovesOutputs() {
        val r = Rig()
        feed(30.0, 0.0, 0.0, 3.0)
        r.send(Command.SetFlightMode(FlightMode.STABILIZE))
        assertTrue(r.send(Command.SetMode(Mode.MANUAL)).accepted)
        r.send(Command.Drive(0.2, 0.0))
        nowMs += 10; nowNs += 10_000_000L
        val t = r.c.tick(0.01)
        assertTrue(t.outputs.first { it.name == "Aileron" }.pulseUs > 1510)
    }

    @Test fun aiCannotChangeModeOnAPlane() {
        assertEquals("not_permitted", Rig().send(Command.SetFlightMode(FlightMode.STABILIZE), Source.AI).code)
    }

    @Test fun linkLossInAssistedPlaneReturnsHomeAndHumanCanRetake() {
        val r = Rig()
        feed(0.0, 0.0, 0.0, 3.0)
        gpsAt(28.0, 77.0)
        fc.setHomeHere()
        r.send(Command.SetFlightMode(FlightMode.STABILIZE))
        r.send(Command.SetMode(Mode.MANUAL))
        r.send(Command.Drive(0.3, 0.0))
        var t = r.c.latest()
        repeat(60) {
            nowMs += 10; nowNs += 10_000_000L
            fc.onGyro(nowNs, 0f, 0f, 0f)
            gpsAt(28.0, 77.0)
            t = r.c.tick(0.01)
        }
        assertEquals(FlightMode.RETURN_HOME, fc.mode)
        assertEquals(Mode.AUTO, t.mode)
        assertTrue(r.send(Command.Drive(0.1, 0.0)).accepted)
        assertEquals(Mode.MANUAL, r.c.latest().mode)
    }

    @Test fun linkLossWithoutGpsIsANormalFailsafe() {
        val r = Rig()
        feed(0.0, 0.0, 0.0, 3.0)
        r.send(Command.SetFlightMode(FlightMode.STABILIZE))
        r.send(Command.SetMode(Mode.MANUAL))
        var t = r.c.latest()
        repeat(60) { nowMs += 10; nowNs += 10_000_000L; fc.onGyro(nowNs, 0f, 0f, 0f); t = r.c.tick(0.01) }
        assertEquals(Mode.FAILSAFE, t.mode)
        assertEquals(listOf(1000, 1500, 1500, 1500), t.outputs.map { it.pulseUs })
    }

    @Test fun simulatorBypassesTheFlightController() {
        val store = ProfileStore(null)
        val c = VehicleController(SimulatedCar(), store, log, clock = { nowMs }, fc = fc)
        assertEquals("unsupported", c.submit(Envelope(Source.GROUND, null, null, Command.SetFlightMode(FlightMode.STABILIZE))).code)
    }
}
