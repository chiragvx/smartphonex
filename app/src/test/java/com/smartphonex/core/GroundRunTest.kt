package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.math.sin

class GroundRunTest {
    private var now = 1_000_000L
    private val run = GroundRun { now }
    private val steps = GroundRun.steps(0.3, 0.6)

    /** A restrained motor: static speed for a throttle, a pack [strength], a little jitter. Readings ten a second. */
    private fun motor(u: Double, strength: Double = 1.0) = (40 + 220 * u - 60 * u * u) * strength

    /** Holds the button for [seconds], feeding what the microphone hears. Returns the throttles commanded. */
    private fun hold(seconds: Double, heard: (Double) -> Double? = { u -> motor(u) }): List<Double?> {
        val out = ArrayList<Double?>()
        var u = run.tick(-1, null, null) ?: 0.0
        repeat((seconds * 10).toInt()) { i ->
            now += 100
            run.start(Source.GROUND, steps, 2)
            val rev = heard(u)?.let { it * (1 + 0.002 * sin(i * 1.7)) }
            val tone = rev?.let { PropTone.Tone(it * 2, 30.0, -20.0, it, listOf(2, 4, 7)) }
            val next = run.tick(now, rev, tone)
            out += next
            u = next ?: 0.0
        }
        return out
    }

    @Test fun stepsThroughThreeThrottlesAndRecordsEach() {
        assertEquals(listOf(0.3, 0.45, 0.6), steps)
        assertNull(run.start(Source.GROUND, steps, 2))
        val us = hold(12.0)
        assertEquals(GroundRun.State.DONE, run.state)
        assertEquals("it went up a step at a time, then stopped", listOf(0.3, 0.45, 0.6, null), us.distinct())
        val b = run.result!!
        assertEquals(3, b.steps.size)
        b.steps.forEach { s -> assertEquals(motor(s.throttle), s.revHz, motor(s.throttle) * 0.005); assertEquals(listOf(2, 4, 7), s.harmonics) }
        assertTrue("2 to 3 s a step, at ten readings a second: ${us.count { it != null }}", us.count { it != null } in 60..90)
        assertTrue(run.message!!, run.message!!.startsWith("done"))
    }

    @Test fun lettingGoStopsTheMotorAtOnce() {
        run.start(Source.GROUND, steps, 2)
        hold(1.5)
        now += 500 // No more presses.
        assertNull(run.tick(now, motor(0.3), null))
        assertEquals(GroundRun.State.STOPPED, run.state)
        assertNull(run.result)
    }

    @Test fun aSecondPersonCantTakeItOver() {
        run.start(Source.GROUND, steps, 2)
        assertNotNull(run.start(Source.LOCAL, steps, 2))
    }

    @Test fun failsWithAReasonWhenNothingIsHeardOrTheMotorDoesntRespond() {
        run.start(Source.GROUND, steps, 2)
        hold(9.0) { null }
        assertEquals(GroundRun.State.FAILED, run.state)
        assertTrue(run.message!!, "no propeller tone" in run.message!!)
        hold(1.0) { 100.0 }
        assertEquals("still holding after it failed: it doesn't start again", GroundRun.State.FAILED, run.state)

        now += 1000 // Let go, and press again.
        run.start(Source.GROUND, steps, 2)
        hold(12.0) { 100.0 } // Same speed whatever the throttle: an ESC not responding, or the wrong sound.
        assertEquals(GroundRun.State.FAILED, run.state)
        assertTrue(run.message!!, "didn't speed up" in run.message!!)
    }

    @Test fun aHarmonicJumpBetweenStepsIsCaught() {
        run.start(Source.GROUND, steps, 2)
        hold(12.0) { u -> if (u > 0.4) motor(u) * 2 else motor(u) }
        assertEquals(GroundRun.State.FAILED, run.state)
        assertTrue(run.message!!, "harmonic" in run.message!!)
    }

    @Test fun anUnsteadySpeedDoesntCount() {
        run.start(Source.GROUND, steps, 2)
        var k = 0
        hold(10.0) { u -> motor(u) * if (k++ % 2 == 0) 1.0 else 1.05 }
        assertEquals(GroundRun.State.FAILED, run.state)
        assertTrue(run.message!!, "steady" in run.message!!)
    }

    private fun baseline(strength: Double = 1.0, bend: Double = 0.0, at: Long = now) =
        GroundRun.Baseline(at, 2, steps.mapIndexed { i, u -> GroundRun.Step(u, motor(u, strength) * (1 + bend * i), 0.3, listOf(2), 30.0) })

    @Test fun aWeakerPackIsMeasuredButADifferentPropellerIsANewSetup() {
        val ref = baseline()
        val half = GroundRun.compare(baseline(0.93), ref)
        assertEquals(0.93, half.strength, 1e-9)
        assertFalse(half.changed)
        val again = GroundRun.compare(baseline(1.004), ref)
        assertEquals("the same pack twice agrees within 1%", 1.0, again.strength, 0.01)
        val prop = GroundRun.compare(baseline(bend = -0.05), ref) // Heavier propeller: drops off more at high throttle.
        assertTrue(prop.why, prop.changed)
        assertTrue(GroundRun.compare(baseline(0.6), ref).changed)
    }

    @Test fun flightReadingsAreCheckedAgainstTheStaticCurve() {
        val b = baseline()
        val e = motor(0.5)
        assertEquals(e * 1.15 to null, GroundRun.check(e * 1.15, 0.5, b, 1.0)) // Unloaded in the air: fine.
        val (fixed, note) = GroundRun.check(e * 2.3, 0.5, b, 1.0)
        assertEquals(e * 1.15, fixed, 1e-9)
        assertTrue(note!!, "2nd harmonic" in note)
        assertEquals(e * 1.1, GroundRun.check(e * 0.55, 0.5, b, 1.0).first, 1e-9)
        assertTrue(GroundRun.check(e * 3.5, 0.5, b, 1.0).second!!.startsWith("doesn't match"))
        assertEquals("at idle the curve isn't known", 10.0 to null, GroundRun.check(10.0, 0.1, b, 1.0))
        assertEquals("no ground run, nothing checked", 999.0 to null, GroundRun.check(999.0, 0.5, null, 1.0))
        // A weak pack spins slower: the expected speed is scaled, so a reading at its true speed isn't called a mistake.
        assertNull(GroundRun.check(e * 0.82 * 1.05, 0.5, b, 0.82).second)
    }

    @Test fun theStoreKeepsTheReferenceAndSurvivesARestart() {
        val dir = Files.createTempDirectory("gr").toFile()
        try {
            val f = java.io.File(dir, "groundrun.json")
            val store = GroundRunStore(f)
            val first = store.add("plane", baseline(at = 1))
            assertSame(first.reference, first.last)
            val next = store.add("plane", baseline(0.95, at = 2))
            assertEquals(1L, next.reference.atMs)
            assertEquals(0.95, next.comparison.strength, 1e-9)
            val again = GroundRunStore(f).get("plane")!!
            assertEquals(1L, again.reference.atMs)
            assertEquals(2L, again.last.atMs)
            assertEquals(0.95, again.comparison.strength, 1e-6)
            val changed = store.add("plane", baseline(bend = 0.06, at = 3))
            assertTrue(changed.comparison.changed)
            assertEquals("a changed setup becomes the new reference", 3L, changed.reference.atMs)
            store.clear("plane")
            assertNull(GroundRunStore(f).get("plane"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun airspeedIsLearnedOnlyInLevelSteadyFlightAndShownHonestly() {
        var t = 1_000_000L
        val params = ParamStore(null) { "plane" }.also { it.set("MIC_PROP", 2.0) }
        val fc = FlightController(params, EventLog(clock = { t }), { "plane" }, { t }, { t * 1_000_000 })
        fun samples() = fc.slowJson(true)["prop"]!!.toString().let { Regex("\"samples\":(\\d+)").find(it)!!.groupValues[1].toInt() }
        var lat = 18.5; var alt = 600.0
        // Flies north at 13 m/s for [seconds], climbing at [climb] m/s, with the motor steady.
        fun fly(seconds: Int, climb: Double, rev: Double = 150.0) = repeat(seconds * 10) { i ->
            t += 100
            fc.process(ActuatorOutput(0.6, 0.0), true, 0.1)
            if (i % 10 == 0) {
                lat += 13.0 / 111_195; alt += climb
                fc.onGps(GpsFix(lat, 73.9, alt, 13.0, 0.0, 3.0, 12, t), 0.0)
            }
            fc.onPropTone(PropTone.Tone(rev * 2, 30.0, -20.0, rev, listOf(2, 4, 7)))
        }
        fly(15, 0.0) // Settle the GPS guard.
        val level = samples()
        assertTrue("level and steady flight teaches it: $level", level > 50)
        fly(15, 4.0) // Climbing: trading airspeed for height.
        assertEquals("nothing learned while climbing", level, samples())
        fly(15, 0.0)
        assertTrue(samples() > level + 50)

        // Presented honestly once learned: an estimate with its error, against this plane's cruise.
        listOf("PROP_K0" to 13.0, "PROP_K1" to 0.0, "PROP_K2" to 0.0, "PROP_K3" to 0.0, "PROP_KQ" to 1.4, "PROP_NMIN" to 140.0, "PROP_NMAX" to 160.0,
            "PROP_CELLS" to (1L shl PropAirspeed.cell(150.0, 0.6, 140.0, 160.0)).toDouble(), "PROP_CRUISE" to 14.2, "PROP_VMIN" to 10.0).forEach { (n, v) -> params.set(n, v) }
        val fc2 = FlightController(params, EventLog(clock = { t }), { "plane" }, { t }, { t * 1_000_000 })
        repeat(10) { t += 100; fc2.process(ActuatorOutput(0.6, 0.0), true, 0.1); fc2.onPropTone(PropTone.Tone(300.0, 30.0, -20.0, 150.0, listOf(2, 4, 7))) }
        val prop = fc2.slowJson(true)["prop"].toString()
        assertTrue(prop, "~13 m/s ±1, 8% below your cruise" in prop)
        assertTrue(prop, "on an earlier flight" in prop)
        repeat(10) { t += 100; fc2.process(ActuatorOutput(0.0, 0.0), true, 0.1); fc2.onPropTone(PropTone.Tone(300.0, 30.0, -20.0, 150.0, listOf(2, 4, 7))) }
        assertTrue("hidden while gliding", "gliding" in fc2.slowJson(true)["prop"].toString())
    }

    @Test fun anArmedPlaneReadingDoubleIsCorrectedFromTheGroundRun() {
        var t = 1_000_000L
        val params = ParamStore(null) { "plane" }.also { it.set("MIC_PROP", 1.0) }
        val fc = FlightController(params, EventLog(clock = { t }), { "plane" }, { t }, { t * 1_000_000 })
        val ref = baseline(at = t)
        fc.groundRuns = { GroundRunStore.Entry(ref, ref, GroundRun.Comparison(1.0, 0.0, false, null)) }
        val rev = motor(0.5) * 1.1
        repeat(20) {
            t += 100
            fc.process(ActuatorOutput(0.5, 0.0), true, 0.1)
            fc.onPropTone(PropTone.Tone(rev * 4, 30.0, -20.0, rev * 2, listOf(2, 4))) // Heard as double.
        }
        val prop = fc.slowJson(true)["prop"].toString()
        assertTrue(prop, "\"rpm\":${Math.round(rev * 60)}" in prop)
        assertTrue(prop, "2nd harmonic" in prop)
    }
}
