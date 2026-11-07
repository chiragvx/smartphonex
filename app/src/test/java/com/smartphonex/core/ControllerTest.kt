package com.smartphonex.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ControllerTest {
    private var now = 1_000_000L
    private val car = SimulatedCar()
    private val log = EventLog(clock = { now })
    private val c = VehicleController(car, ProfileStore(null), log, clock = { now })
    private var seq = 0L

    private fun send(source: Source, cmd: Command, s: Long? = ++seq, sent: Long? = now) = c.submit(Envelope(source, s, sent, cmd))
    private fun ai(cmd: Command) = send(Source.AI, cmd, null, null)
    private fun run(ms: Int): Telemetry {
        var t = c.latest()
        repeat(ms / 20) { now += 20; t = c.tick(0.02) }
        return t
    }

    @Test fun duplicateAndOldSequenceRejected() {
        assertTrue(send(Source.GROUND, Command.SetMode(Mode.MANUAL), 10).accepted)
        assertEquals("duplicate", send(Source.GROUND, Command.Drive(0.1, 0.0), 10).code)
        assertEquals("duplicate", send(Source.GROUND, Command.Drive(0.1, 0.0), 9).code)
        assertTrue(send(Source.GROUND, Command.Drive(0.1, 0.0), 11).accepted)
    }

    @Test fun staleAndFutureRejected() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        assertEquals("stale", send(Source.GROUND, Command.Drive(0.1, 0.0), sent = now - 1001).code)
        assertEquals("stale", send(Source.GROUND, Command.Drive(0.1, 0.0), sent = now + 1001).code)
    }

    @Test fun lateStopIsStillHonoured() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL), 100)
        assertTrue(send(Source.GROUND, Command.Stop, 1, now - 60_000).accepted)
        assertEquals(Mode.DISARMED, c.latest().mode)
    }

    @Test fun outOfRangeRejected() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        assertEquals("out_of_range", send(Source.GROUND, Command.Drive(0.81, 0.0)).code)
        assertEquals("out_of_range", send(Source.GROUND, Command.Drive(0.1, 1.5)).code)
    }

    @Test fun manualLinkLossFailsafesAndLatches() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        send(Source.GROUND, Command.Drive(0.5, 0.0))
        assertEquals(Mode.MANUAL, run(400).mode)
        val t = run(4000)
        assertEquals(Mode.FAILSAFE, t.mode)
        assertTrue(abs(t.sensors.speedMps!!) < 0.1)
        assertEquals("not_permitted", send(Source.GROUND, Command.Drive(0.5, 0.0)).code)
        assertEquals("not_permitted", ai(Command.SetMode(Mode.AUTO)).code)
        assertTrue(send(Source.GROUND, Command.SetMode(Mode.MANUAL)).accepted)
    }

    @Test fun aiStopCannotClearFailsafe() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        run(1000)
        assertEquals(Mode.FAILSAFE, c.latest().mode)
        assertTrue(ai(Command.Stop).accepted)
        assertEquals(Mode.FAILSAFE, c.latest().mode)
        assertEquals("not_permitted", ai(Command.SetMode(Mode.DISARMED)).code)
    }

    @Test fun aiLeaseExpiresToStopWithoutFailsafe() {
        assertTrue(ai(Command.SetMode(Mode.AUTO)).accepted)
        assertTrue(ai(Command.SetSpeed(1.0, 1000)).accepted)
        assertTrue(run(900).sensors.speedMps!! > 0.3)
        val t = run(4000)
        assertEquals(Mode.AUTO, t.mode)
        assertTrue(abs(t.sensors.speedMps!!) < 0.1)
    }

    @Test fun overLongLeaseRejected() {
        ai(Command.SetMode(Mode.AUTO))
        assertEquals("out_of_range", ai(Command.SetSpeed(1.0, 999_999)).code)
        assertEquals("out_of_range", ai(Command.SetSpeed(1.0, 0)).code)
    }

    @Test fun pidHoldsTargetSpeed() {
        ai(Command.SetMode(Mode.AUTO))
        ai(Command.SetSpeed(1.5, 5000))
        assertEquals(1.5, run(2500).sensors.speedMps!!, 0.1)
    }

    @Test fun aiCannotOverrideHuman() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        assertEquals("not_permitted", ai(Command.SetMode(Mode.AUTO)).code)
        assertEquals("not_permitted", ai(Command.SetThrottle(0.5, 1000)).code)
        assertEquals("not_permitted", ai(Command.Drive(0.5, 0.0)).code)
    }

    @Test fun aiCannotUndoHumanStopButCanStop() {
        ai(Command.SetMode(Mode.AUTO))
        send(Source.GROUND, Command.Stop)
        assertEquals("not_permitted", ai(Command.SetMode(Mode.AUTO)).code)
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        assertTrue(ai(Command.Stop).accepted)
        assertEquals(Mode.DISARMED, c.latest().mode)
    }

    @Test fun humanDrivingOverridesAi() {
        ai(Command.SetMode(Mode.AUTO))
        ai(Command.SetSpeed(1.0, 5000))
        assertTrue(send(Source.GROUND, Command.Drive(0.0, 0.0)).accepted)
        assertEquals(Mode.MANUAL, c.latest().mode)
    }

    @Test fun obstacleGuardStopsAiBeforeImpact() {
        ai(Command.SetMode(Mode.AUTO))
        var collided = false
        repeat(8) {
            ai(Command.SetSpeed(2.5, 2000))
            repeat(100) { now += 20; if (c.tick(0.02).sensors.collided) collided = true }
        }
        assertFalse(collided)
        assertEquals(Mode.AUTO, c.latest().mode)
        assertTrue(c.latest().sensors.rangeM!! < 3.0)
    }

    @Test fun collisionInManualFailsafes() {
        send(Source.GROUND, Command.SetMode(Mode.MANUAL))
        repeat(400) {
            if (c.latest().mode == Mode.MANUAL) send(Source.GROUND, Command.Drive(0.8, 0.0))
            now += 20
            c.tick(0.02)
        }
        assertEquals(Mode.FAILSAFE, c.latest().mode)
        assertEquals("collision", c.latest().failsafeReason)
    }

    @Test fun missionRunsToCompletion() {
        ai(Command.SetMode(Mode.AUTO))
        assertTrue(ai(Command.RunMission("straight_and_stop")).accepted)
        assertEquals("straight_and_stop", run(1000).mission)
        val t = run(5000)
        assertNull(t.mission)
        assertTrue(abs(t.sensors.speedMps!!) < 0.1)
    }

    @Test fun squareMissionMakesNearRightAngles() {
        ai(Command.SetMode(Mode.AUTO))
        assertTrue(ai(Command.RunMission("square")).accepted)
        var t = run(20)
        var steps = 0
        while (t.mission != null && steps++ < 2000) { now += 20; t = c.tick(0.02) }
        assertNull(t.mission)
        // Four corners should add up to one full turn, give or take about 7 degrees per corner.
        assertEquals(2 * Math.PI, t.sensors.headingRad!!, 0.5)
        assertFalse(t.sensors.collided)
    }

    @Test fun pidValidatedAndAiCannotChangeLimits() {
        assertEquals("out_of_range", ai(Command.ConfigurePid(9.0, null, null, null)).code)
        assertTrue(ai(Command.ConfigurePid(0.5, null, null, null)).accepted)
        assertEquals("not_permitted", ai(Command.UpdateProfile(mapOf("maxThrottle" to 1.0))).code)
    }

    @Test fun profilesPersist() {
        val f = java.io.File.createTempFile("profiles", ".json")
        f.delete()
        val a = ProfileStore(f)
        a.updateActive(a.active.copy(kp = 1.23))
        a.flush()
        assertEquals(1.23, ProfileStore(f).active.kp, 1e-9)
        f.delete()
    }

    @Test fun malformedRejectedByCodec() {
        val parse = { s: String -> Codec.parseEnvelope(Json.parseToJsonElement(s) as JsonObject, Source.GROUND) }
        assertThrows(MalformedCommand::class.java) { parse("""{"cmd":"drive","throttle":"lots","steering":0,"seq":1,"sentAt":1}""") }
        assertThrows(MalformedCommand::class.java) { parse("""{"cmd":"explode","seq":1,"sentAt":1}""") }
        assertThrows(MalformedCommand::class.java) { parse("""{"cmd":"stop"}""") }
    }
}
