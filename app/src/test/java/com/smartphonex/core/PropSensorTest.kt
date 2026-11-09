package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
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
        // What the app stores as background: every peak the hum makes, heard while disarmed.
        val background = pt.analyze(hum, 40.0, 1500.0, 12.0)!!.peaks
        assertEquals(200.0, pt.analyze(mixed, 40.0, 1500.0, 12.0, exclude = background)!!.hz, 1.5)
    }

    private fun tone(hz: Double, amp: Double, phase: Double = 0.0) = DoubleArray(8192) { i -> amp * sin(2 * PI * hz * i / rate + phase) }
    private fun sum(vararg parts: DoubleArray) = DoubleArray(8192) { i -> parts.sumOf { it[i] } + 0.05 * rnd.nextDouble(-1.0, 1.0) }

    @Test fun `a motor tone louder than the blades isn't taken for them`() {
        // 3,000 rpm, two blades: blade tone 100 Hz and harmonics. A 14-pole motor hums at 7 × 50 = 350 Hz, louder.
        val s = sum(tone(100.0, 0.2), tone(200.0, 0.12, 1.0), tone(300.0, 0.05, 2.0), tone(350.0, 0.4), tone(700.0, 0.15, 0.5))
        val t = PropTone(rate).analyze(s, 40.0, 1500.0, 12.0)!!
        assertEquals("blade tone", 100.0, t.hz, 1.0)
        assertEquals("revolutions", 50.0, t.revHz, 0.5)
        assertTrue("the motor tone is one of its harmonics: ${t.harmonics}", 7 in t.harmonics)
        assertFalse(t.otherFamily)
    }

    @Test fun `a fan's tone at an odd multiple of half the speed doesn't halve the reading`() {
        // Found on the phone: 7,611 rpm (blade tone 253.7 Hz, harmonics, motor tone at 7x) beside a laptop fan whose
        // 188 Hz tone is about 3 x half the revolution rate. Half the true rate then explained one more peak and won.
        val rev = 7611 / 60.0
        val s = sum(tone(2 * rev, 0.2), tone(4 * rev, 0.12, 1.0), tone(6 * rev, 0.05, 2.0), tone(7 * rev, 0.06, 0.5), tone(188.0, 0.25))
        val t = PropTone(rate).analyze(s, 40.0, 1500.0, 12.0)!!
        assertEquals("${t.harmonics}", rev, t.revHz, 0.5)
    }

    @Test fun `a blade tone faded by the microphone's weak low end isn't read as double`() {
        // 2,400 rpm: blade tone 80 Hz, nearly lost below the microphone's range, with its 2nd and 3rd harmonics clear.
        val s = sum(tone(80.0, 0.02), tone(160.0, 0.3, 1.0), tone(240.0, 0.2, 2.0))
        assertEquals(80.0, PropTone(rate).analyze(s, 40.0, 1500.0, 12.0)!!.hz, 1.0)
    }

    @Test fun `a slight imbalance gives the revolution rate directly`() {
        val s = sum(tone(60.0, 0.03), tone(120.0, 0.3, 1.0), tone(240.0, 0.15, 2.0), tone(360.0, 0.05))
        assertEquals(120.0, PropTone(rate).analyze(s, 40.0, 1500.0, 12.0)!!.hz, 1.0)
    }

    @Test fun `an overloaded microphone is flagged but still heard`() {
        val loud = sum(tone(150.0, 2.0), tone(300.0, 0.8)).map { it.coerceIn(-1.0, 1.0) }.toDoubleArray()
        val t = PropTone(rate).analyze(loud, 40.0, 1500.0, 12.0)!!
        assertTrue(t.clipped)
        assertEquals(150.0, t.hz, 1.0)
        assertFalse(PropTone(rate).analyze(prop(150.0), 40.0, 1500.0, 12.0)!!.clipped)
    }

    @Test fun `a lone weak tone just over the threshold isn't a propeller`() {
        val pt = PropTone(rate)
        // A faint whine like a laptop fan: one peak, no family.
        val whine = DoubleArray(8192) { i -> 0.004 * sin(2 * PI * 1066.0 * i / rate) + 0.05 * rnd.nextDouble(-1.0, 1.0) }
        assertNull(pt.analyze(whine, 40.0, 1500.0, 12.0))
        assertTrue("but it's still heard, so it can be learned as background", pt.lastPeaks.any { abs(it - 1066.0) < 3 })
        // A strong lone tone still counts.
        assertNotNull(pt.analyze(tone(300.0, 0.3), 40.0, 1500.0, 12.0))
    }

    @Test fun `a second propeller nearby is reported`() {
        val s = sum(tone(200.0, 0.3), tone(400.0, 0.15), tone(263.0, 0.3), tone(526.0, 0.15))
        assertTrue(PropTone(rate).analyze(s, 40.0, 1500.0, 12.0)!!.otherFamily)
        assertFalse(PropTone(rate).analyze(sum(tone(200.0, 0.3), tone(400.0, 0.15)), 40.0, 1500.0, 12.0)!!.otherFamily)
    }

    @Test fun `noise alone is no tone`() {
        val pt = PropTone(rate)
        val noise = DoubleArray(8192) { rnd.nextDouble(-0.5, 0.5) }
        assertNull(pt.analyze(noise, 40.0, 1500.0, 12.0))
        assertNull("silence", pt.analyze(DoubleArray(8192), 40.0, 1500.0, 12.0))
    }

    @Test fun `a one-frame jump or ghost is ignored, a lasting tone is believed`() {
        val tr = ToneTracker()
        fun t(hz: Double) = PropTone.Tone(hz, 20.0, -20.0)
        assertNull("one reading isn't enough to lock on", tr.update(t(200.0), 0))
        assertNull(tr.update(t(1066.0), 50)) // A ghost in between starts over.
        tr.update(t(200.0), 100); tr.update(t(201.0), 200)
        assertEquals(202.0, tr.update(t(202.0), 300)!!, 0.1)
        assertEquals(202.0, tr.update(t(400.0), 400)!!, 0.1)
        assertEquals(201.5, tr.update(t(201.0), 500)!!, 0.1)
        tr.update(t(300.0), 600); tr.update(t(301.0), 700)
        assertEquals(302.0, tr.update(t(302.0), 800)!!, 0.1)
        assertNull("stale", tr.update(null, 3000))
    }

    /**
     * The schedule in tools/prop-sweep.py, through the analyzer and tracker exactly as the phone runs them (a new
     * analysis every 4096 samples over the last 8192), with a laptop-fan-like whine on top the whole time. Prints how
     * fast it locks on after each jump, so the numbers can be compared with the phone's.
     */
    @Test fun `follows holds, jumps and ramps through a fan's whine`() {
        val segs = listOf(Triple(3, 0, 0), Triple(4, 3000, 3000), Triple(4, 4500, 4500), Triple(4, 6000, 6000), Triple(4, 7500, 7500),
            Triple(4, 9000, 9000), Triple(4, 12000, 12000), Triple(4, 5000, 5000), Triple(4, 8000, 8000), Triple(6, 8000, 3000),
            Triple(4, 3000, 10000), Triple(2, 10000, 10000), Triple(3, 0, 0))
        val n = segs.sumOf { it.first } * rate
        val truth = DoubleArray(n)
        val audio = DoubleArray(n)
        var phase = 0.0
        var i = 0
        for ((secs, a, b) in segs) for (j in 0 until secs * rate) {
            val rpm = a + (b - a) * j.toDouble() / (secs * rate)
            truth[i] = rpm
            phase += rpm / 60 / rate
            val p = 2 * PI * phase
            val fan = 0.01 * sin(2 * PI * 1066.0 * i / rate) + 0.006 * sin(2 * PI * 145.0 * i / rate)
            audio[i] = fan + 0.02 * rnd.nextDouble(-1.0, 1.0) + if (rpm > 0)
                0.2 * sin(2 * p) + 0.12 * sin(4 * p + 1) + 0.05 * sin(6 * p + 2) + 0.06 * sin(7 * p + 0.5) else 0.0
            i++
        }
        val pt = PropTone(rate)
        val tr = ToneTracker()
        val hop = 4096
        val out = ArrayList<Pair<Double, Double?>>() // (seconds, rpm heard)
        var end = 8192
        while (end <= n) {
            val ms = (end * 1000L) / rate
            val hz = tr.update(pt.analyze(audio.copyOfRange(end - 8192, end), 40.0, 1500.0, 12.0, exclude = listOf(1066.0, 145.0)), ms)
            out += end.toDouble() / rate to hz?.let { it / 2 * 60 }
            end += hop
        }
        fun truthAt(t: Double) = truth[(t * rate).toInt().coerceIn(0, n - 1)]
        var t0 = 0
        val lags = ArrayList<String>()
        for ((k, seg) in segs.withIndex()) {
            val (secs, a, b) = seg
            val start = t0.toDouble(); val stop = start + secs
            t0 += secs
            if (a == 0) continue
            if (a == b) {
                val hold = out.filter { it.first >= start + 1 && it.first < stop }
                val bad = hold.count { it.second == null || abs(it.second!! - a) / a > 0.01 }
                assertEquals("holding $a rpm: readings off by more than 1%", 0, bad)
            } else {
                val ramp = out.filter { it.first >= start + 0.5 && it.first < stop }
                // Readings trail a ramp: each analysis covers the last 0.19 s, and the tracker smooths a little.
                fun medianErr(lag: Double) = ramp.map { abs((it.second ?: 0.0) - truthAt(it.first - lag)) / truthAt(it.first - lag) }.sorted()[ramp.size / 2]
                val lag = (0..50).map { it / 100.0 }.minBy { medianErr(it) }
                lags += "ramp $a->$b behind by %.2f s, then %.1f%% off".format(lag, medianErr(lag) * 100)
                assertTrue("ramp $a to $b: behind by $lag s", lag <= 0.25)
                assertTrue("ramp $a to $b: ${medianErr(lag)} off after the lag", medianErr(lag) < 0.02)
            }
            val prev = segs.getOrNull(k - 1)?.third ?: 0
            if (a != prev) {
                val lock = out.firstOrNull { it.first >= start && it.second != null && abs(it.second!! - a) / a < 0.03 }
                val lag = lock?.let { it.first - start }
                lags += "$prev->$a ${lag?.let { "%.2f s".format(it) } ?: "never"}"
                assertTrue("lock after $prev -> $a: $lag", lag != null && lag < 0.8)
            }
        }
        val quiet = out.filter { it.first >= n.toDouble() / rate - 1.5 }
        assertTrue("reports nothing once the sound stops", quiet.all { it.second == null })
        println("sweep: ${lags.joinToString(", ")}")
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
    private fun fly(
        pa: PropAirspeed, seconds: Int, windMps: Double, windToDeg: Double, headingStep: Double = 12.0,
        uLo: Double = 0.45, uHi: Double = 0.8, startMs: Long = 0, lie: Boolean = false,
    ) {
        for (i in 0 until seconds) {
            val v = 11.0 + 4.0 * rnd.nextDouble()
            val u = uLo + (uHi - uLo) * rnd.nextDouble()
            val minutes = i / 60.0
            val h = Math.toRadians(i * headingStep)
            val w = Math.toRadians(windToDeg)
            val e = v * sin(h) + windMps * sin(w)
            val n = v * cos(h) + windMps * cos(w)
            val course = (Math.toDegrees(atan2(e, n)) + 360) % 360
            // [lie]: the motor speed runs backwards against airspeed, which no propeller does.
            val rev = if (lie) nFor(26.0 - v, u, minutes) else nFor(v, u, minutes)
            pa.add(PropAirspeed.Sample(rev, u, minutes, hypot(e, n) + rnd.nextDouble(-0.3, 0.3), course, if (startMs > 0) startMs + i * 1000L else 0))
        }
    }

    @Test fun `no estimate at a throttle and motor speed it never flew together`() {
        val pa = PropAirspeed()
        fly(pa, 600, windMps = 3.0, windToDeg = 0.0, uLo = 0.45, uHi = 0.55)
        assertNotNull(pa.refit())
        val f = pa.fit!!
        val n = (f.nMin + f.nMax) / 2
        assertNull(pa.whyNot(nFor(13.0, 0.5, 5.0), 0.5, 5.0))
        // Same motor speed range, but a throttle it never used: the fit matched its samples, but this is a guess.
        assertTrue(pa.whyNot(n, 0.8, 5.0)!!, "hasn't learned together" in pa.whyNot(n, 0.8, 5.0)!!)
        assertTrue("gliding", pa.whyNot(n, 0.0, 5.0)!!.contains("gliding"))
        assertNotNull("a typical speed to compare with", f.cruiseMps.takeIf { it in 11.0..15.0 })
    }

    @Test fun `only recent circles count, so it follows a change in the wind`() {
        val pa = PropAirspeed()
        fly(pa, 600, windMps = 8.0, windToDeg = 180.0, startMs = 1_000_000)
        fly(pa, 600, windMps = 4.0, windToDeg = 90.0, startMs = 1_600_000)
        val f = pa.refit()!!
        assertEquals("the new wind", 4.0, f.windMps, 1.0)
        assertEquals(270.0, f.windFromDeg, 20.0)
        assertTrue("old samples dropped: ${pa.count}", pa.count <= 361)
    }

    @Test fun `a fit that breaks physics is refused`() {
        val pa = PropAirspeed()
        fly(pa, 600, windMps = 2.0, windToDeg = 0.0, lie = true)
        assertNull(pa.refit())
        assertNull(pa.fit)
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
