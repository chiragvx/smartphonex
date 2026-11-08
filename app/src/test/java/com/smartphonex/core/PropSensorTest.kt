package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class PropSensorTest {
    private val rate = 44_100
    private val rnd = Random(3)

    /** A propeller: blade-pass tone with two harmonics, under broadband noise as loud as the tone. */
    private fun prop(hz: Double, noise: Double = 0.3, second: Double = 0.6): DoubleArray = DoubleArray(8192) { i ->
        val t = i.toDouble() / rate
        0.3 * sin(2 * PI * hz * t) + 0.3 * second * sin(2 * PI * 2 * hz * t + 1.0) + 0.1 * sin(2 * PI * 3 * hz * t + 2.0) +
            noise * rnd.nextDouble(-1.0, 1.0)
    }

    @Test fun `hears the blade tone through noise`() {
        val pt = PropTone(rate)
        for (hz in listOf(95.0, 230.0, 412.0, 900.0)) {
            val tone = pt.analyze(prop(hz), 40.0, 1500.0, 12.0)
            assertNotNull("tone at $hz", tone)
            assertEquals("tone at $hz", hz, tone!!.hz, 1.5)
        }
    }

    @Test fun `picks the fundamental even when the second harmonic is louder`() {
        val tone = PropTone(rate).analyze(prop(180.0, second = 1.6), 40.0, 1500.0, 12.0)
        assertEquals(180.0, tone!!.hz, 1.5)
    }

    @Test fun `a background hum heard while disarmed is skipped once armed`() {
        val pt = PropTone(rate)
        // Mains hum at 50 Hz with harmonics, louder than the propeller at 200 Hz.
        val hum = prop(50.0, noise = 0.0, second = 0.8)
        val mixed = prop(200.0, noise = 0.1).mapIndexed { i, s -> s * 0.5 + hum[i] }.toDoubleArray()
        assertEquals("without exclusions the hum wins", 50.0, pt.analyze(mixed, 40.0, 1500.0, 12.0)!!.hz, 1.5)
        assertEquals(200.0, pt.analyze(mixed, 40.0, 1500.0, 12.0, exclude = listOf(50.0))!!.hz, 1.5)
    }

    @Test fun `noise alone is no tone`() {
        val pt = PropTone(rate)
        val noise = DoubleArray(8192) { rnd.nextDouble(-0.5, 0.5) }
        assertNull(pt.analyze(noise, 40.0, 1500.0, 12.0))
        assertNull("silence", pt.analyze(DoubleArray(8192), 40.0, 1500.0, 12.0))
    }

    @Test fun `a one-frame jump is ignored, a lasting change is believed`() {
        val tr = ToneTracker()
        fun t(hz: Double) = PropTone.Tone(hz, 20.0, -20.0)
        tr.update(t(200.0), 0)
        tr.update(t(205.0), 100)
        assertEquals(202.5, tr.update(t(400.0), 200)!!, 0.1)
        assertEquals(202.5, tr.update(t(201.0), 300)!!, 2.0)
        tr.update(t(300.0), 400); tr.update(t(301.0), 500)
        assertEquals(302.0, tr.update(t(302.0), 600)!!, 0.1)
        assertNull("stale", tr.update(null, 3000))
    }

    // The "true" propeller used to make data: airspeed = k0 + k1·n + k2·u/n + k3·u/n·minutes.
    private val kTrue = doubleArrayOf(0.0, 0.12, -1500.0, -30.0)

    /** Motor speed that gives this airspeed at this throttle, by solving the model for n. */
    private fun nFor(v: Double, u: Double, minutes: Double): Double {
        val c = (kTrue[2] + kTrue[3] * minutes) * u
        val b = kTrue[0] - v
        return (-b + sqrt(b * b - 4 * kTrue[1] * c)) / (2 * kTrue[1])
    }

    /** Circles in a wind of [windMps] blowing towards [windToDeg], with real wind-triangle geometry. */
    private fun fly(pa: PropAirspeed, seconds: Int, windMps: Double, windToDeg: Double, headingStep: Double = 12.0) {
        for (i in 0 until seconds) {
            val v = 11.0 + 4.0 * rnd.nextDouble()
            val u = 0.45 + 0.35 * rnd.nextDouble()
            val minutes = i / 60.0
            val h = Math.toRadians(i * headingStep)
            val w = Math.toRadians(windToDeg)
            val e = v * sin(h) + windMps * sin(w)
            val n = v * cos(h) + windMps * cos(w)
            val course = (Math.toDegrees(atan2(e, n)) + 360) % 360
            pa.add(PropAirspeed.Sample(nFor(v, u, minutes), u, minutes, hypot(e, n) + rnd.nextDouble(-0.3, 0.3), course))
        }
    }

    @Test fun `learns airspeed and the wind from circling`() {
        val pa = PropAirspeed()
        fly(pa, 600, windMps = 4.0, windToDeg = 90.0)
        val f = pa.refit()
        assertNotNull(f)
        assertTrue("fit ${f!!.rmsMps}", f.rmsMps < 1.0)
        assertEquals("wind speed", 4.0, f.windMps, 1.0)
        assertEquals("wind blows from the west", 270.0, f.windFromDeg, 20.0)
        for ((v, u) in listOf(11.5 to 0.5, 13.0 to 0.65, 14.5 to 0.75)) {
            val est = pa.airspeed(nFor(v, u, 5.0), u, 5.0)!!
            assertEquals("airspeed $v at throttle $u", v, est, 1.0)
        }
    }

    @Test fun `won't learn until it has flown every direction`() {
        val pa = PropAirspeed()
        // A straight-ish line: only a few headings, so the wind can't be separated out.
        fly(pa, 600, windMps = 4.0, windToDeg = 90.0, headingStep = 0.1)
        assertNull(pa.refit())
        assertTrue(pa.sectorsCovered() < PropAirspeed.SECTORS)
        assertNull(pa.airspeed(150.0, 0.6, 1.0))
    }

    @Test fun `no estimate outside the motor speeds it learned`() {
        val pa = PropAirspeed()
        fly(pa, 600, windMps = 0.0, windToDeg = 0.0)
        pa.refit()
        val f = pa.fit!!
        assertNotNull(pa.airspeed((f.nMin + f.nMax) / 2, 0.6, 2.0))
        assertNull("far above", pa.airspeed(f.nMax * 1.5, 0.6, 2.0))
        assertNull("motor off", pa.airspeed(f.nMin, 0.0, 2.0))
    }
}
