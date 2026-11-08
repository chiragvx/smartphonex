package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

class GpsGuardTest {
    private val lat0 = 18.53
    private val lon0 = 73.91
    private val mPerDeg = 111_320.0
    private var limit = 25.0
    private val guard = GpsGuard({ limit }, { 3.0 }, { 3.0 })
    private val rnd = Random(7)

    /** A point [east] and [north] metres from the start, with GPS noise of about [acc] metres. */
    private fun fix(east: Double, north: Double, t: Long, acc: Double = 5.0, noisy: Boolean = true): GpsFix {
        val s = if (noisy) acc / GpsGuard.RADIUS_PER_SIGMA else 0.0
        val e = east + rnd.nextDouble(-1.0, 1.0) * s
        val n = north + rnd.nextDouble(-1.0, 1.0) * s
        return GpsFix(lat0 + n / mPerDeg, lon0 + e / (mPerDeg * cos(Math.toRadians(lat0))), 0.0, 0.0, 0.0, acc, 9, t)
    }

    private fun offsetM(e: GpsGuard.Estimate, east: Double, north: Double): Double {
        val de = (e.lon - lon0) * mPerDeg * cos(Math.toRadians(lat0)) - east
        val dn = (e.lat - lat0) * mPerDeg - north
        return kotlin.math.sqrt(de * de + dn * dn)
    }

    @Test fun `settles, then is good, and smooths the noise`() {
        assertNull(guard.estimate(0))
        for (i in 0 until 4) guard.feed(fix(0.0, 0.0, i * 1000L), moving = false)
        assertEquals(GpsGuard.Health.SETTLING, guard.estimate(3000)!!.health)
        for (i in 4 until 30) guard.feed(fix(0.0, 0.0, i * 1000L), moving = false)
        val e = guard.estimate(29_000)!!
        assertEquals(GpsGuard.Health.GOOD, e.health)
        assertTrue("within 2 m of the truth: ${offsetM(e, 0.0, 0.0)}", offsetM(e, 0.0, 0.0) < 2.0)
        assertTrue("never claims to beat 60% of the reported accuracy: ${e.radiusM}", e.radiusM >= 3.0)
    }

    @Test fun `a single jump is thrown away`() {
        for (i in 0 until 10) guard.feed(fix(0.0, 0.0, i * 1000L), moving = false)
        val v = guard.feed(fix(80.0, 0.0, 10_000, noisy = false), moving = false)
        assertTrue(v.toString(), v is GpsGuard.Verdict.Rejected)
        assertEquals(GpsGuard.Health.GLITCH, guard.estimate(10_000)!!.health)
        assertTrue(offsetM(guard.estimate(10_000)!!, 0.0, 0.0) < 3.0)
        guard.feed(fix(0.0, 0.0, 11_000), moving = false)
        assertEquals(GpsGuard.Health.GOOD, guard.estimate(11_000)!!.health)
        assertEquals(1L, guard.rejected)
    }

    @Test fun `a jump that stays is believed, but not navigated on until it settles`() {
        for (i in 0 until 10) guard.feed(fix(0.0, 0.0, i * 1000L), moving = false)
        var last: GpsGuard.Verdict? = null
        for (i in 10 until 15) last = guard.feed(fix(60.0, 0.0, i * 1000L), moving = false)
        assertTrue(last.toString(), last is GpsGuard.Verdict.Reset)
        assertEquals(GpsGuard.Health.GLITCH, guard.estimate(15_000)!!.health)
        for (i in 15 until 27) guard.feed(fix(60.0, 0.0, i * 1000L), moving = false)
        val e = guard.estimate(26_000)!!
        assertEquals(GpsGuard.Health.GOOD, e.health)
        assertTrue(offsetM(e, 60.0, 0.0) < 3.0)
    }

    @Test fun `follows a driven vehicle without lagging far behind`() {
        for (i in 0 until 5) guard.feed(fix(0.0, 0.0, i * 1000L), moving = false)
        // 2 m/s north for 30 s.
        for (i in 1..30) guard.feed(fix(0.0, 2.0 * i, (4 + i) * 1000L), moving = true)
        val e = guard.estimate(34_000)!!
        assertEquals(GpsGuard.Health.GOOD, e.health)
        assertTrue("within 4 m of 60 m north: ${offsetM(e, 0.0, 60.0)}", offsetM(e, 0.0, 60.0) < 4.0)
        assertTrue("knows it's moving: ${e.speedMps}", e.speedMps in 1.0..3.0)
    }

    @Test fun `a parked vehicle doesn't wander with drift`() {
        // The GPS wanders 15 m east over two minutes while the vehicle sits still.
        var maxRaw = 0.0
        var maxEst = 0.0
        for (i in 0 until 120) {
            val drift = 15.0 * i / 120
            guard.feed(fix(drift, 0.0, i * 1000L, acc = 8.0), moving = false)
            maxRaw = maxOf(maxRaw, drift)
            maxEst = maxOf(maxEst, offsetM(guard.estimate(i * 1000L)!!, 0.0, 0.0))
        }
        // Smoothing can't remove a slow GPS error, only spread it: about half, here.
        assertTrue("estimate moved $maxEst m, raw $maxRaw m", maxEst < maxRaw * 0.7)
    }

    @Test fun `lost after the timeout, and too rough when accuracy is poor`() {
        for (i in 0 until 6) guard.feed(fix(0.0, 0.0, i * 1000L), moving = false)
        assertEquals(GpsGuard.Health.GOOD, guard.estimate(5000)!!.health)
        assertEquals(GpsGuard.Health.LOST, guard.estimate(9000)!!.health)
        for (i in 10 until 20) guard.feed(fix(0.0, 0.0, i * 1000L, acc = 60.0), moving = false)
        val e = guard.estimate(19_000)!!
        assertEquals(GpsGuard.Health.DEGRADED, e.health)
        assertTrue(guard.describe(e).contains("Too rough"))
    }

    @Test fun `useless fixes are refused`() {
        assertTrue(guard.feed(fix(0.0, 0.0, 0, acc = 900.0), moving = false) is GpsGuard.Verdict.Rejected)
        assertTrue(guard.feed(fix(0.0, 0.0, 0, acc = Double.NaN), moving = false) is GpsGuard.Verdict.Rejected)
        assertNull(guard.estimate(0))
    }

    @Test fun `fence margin counts how unsure the position is`() {
        val k = mPerDeg * cos(Math.toRadians(lat0))
        val square = listOf(0.0 to 0.0, 0.0 to 100.0, 100.0 to 100.0, 100.0 to 0.0).map { (n, e) -> (lat0 + n / mPerDeg) to (lon0 + e / k) }
        val fence = Fence(polygon = square)
        val nearEdgeLat = lat0 + 50.0 / mPerDeg
        val nearEdgeLon = lon0 + 4.0 / k
        assertNull(Geo.breach(fence, nearEdgeLat, nearEdgeLon, null, null))
        assertNotNull("4 m from the edge at ±10 m is a possible breach", Geo.breach(fence, nearEdgeLat, nearEdgeLon, null, null, marginM = 10.0))
        assertNull("the middle is fine", Geo.breach(fence, lat0 + 50.0 / mPerDeg, lon0 + 50.0 / k, null, null, marginM = 10.0))
        assertEquals(4.0, Geo.edgeDistanceM(nearEdgeLat, nearEdgeLon, square), 0.2)
    }
}
