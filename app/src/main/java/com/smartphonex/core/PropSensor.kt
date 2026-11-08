package com.smartphonex.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Experimental: hears the propeller's blade tone in the phone's microphone and turns it into motor speed.
 *
 * A spinning propeller makes a loud tone at the blade-pass frequency (revolutions per second × blades), with
 * harmonics at 2× and 3×. The analyzer looks for the frequency whose harmonics together stand out most (a
 * harmonic product spectrum), which ignores most wind noise and picks the propeller over other tones.
 * Audio is only ever analyzed in memory. Nothing is recorded or stored.
 */
class PropTone(val sampleRate: Int, val size: Int = 8192) {
    data class Tone(val hz: Double, val snrDb: Double, val levelDb: Double)

    init { require(size and (size - 1) == 0) { "size must be a power of two" } }

    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val logMag = DoubleArray(size / 2)

    /**
     * The strongest harmonic tone between [fMin] and [fMax] Hz, or null if nothing stands out by [minSnrDb].
     * Tones within 3% of one in [exclude] are skipped: background sounds such as mains hum or a fan.
     */
    fun analyze(samples: DoubleArray, fMin: Double, fMax: Double, minSnrDb: Double, exclude: List<Double> = emptyList()): Tone? {
        require(samples.size >= size)
        var energy = 0.0
        val off = samples.size - size
        for (i in 0 until size) { val s = samples[off + i]; energy += s * s; re[i] = s * window[i]; im[i] = 0.0 }
        val levelDb = 10 * log10(energy / size + 1e-12)
        fft(re, im)
        for (k in 0 until size / 2) logMag[k] = ln(re[k] * re[k] + im[k] * im[k] + 1e-12)
        val binHz = sampleRate.toDouble() / size
        val lo = maxOf(2, (fMin / binHz).toInt())
        val hi = minOf(size / 6 - 2, (fMax / binHz).toInt())
        if (hi <= lo + 2) return null
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (k in lo..hi) {
            val f = k * binHz
            if (exclude.any { abs(f - it) <= it * 0.03 + binHz }) continue
            // Harmonics land within a bin of 2k and 3k; take the strongest nearby so a slightly off bin still counts.
            val score = logMag[k] + near(2 * k) + near(3 * k)
            if (score > bestScore) { bestScore = score; best = k }
        }
        if (best < 0) return null
        // How far the fundamental stands out from the band's typical level, in dB.
        val band = (lo..hi).map { logMag[it] }.sorted()
        val median = band[band.size / 2]
        val snrDb = (logMag[best] - median) * 10 / ln(10.0)
        if (snrDb < minSnrDb) return null
        // Parabolic interpolation for a frequency finer than one bin.
        val a = logMag[best - 1]; val b = logMag[best]; val c = logMag[best + 1]
        val d = a - 2 * b + c
        val shift = if (d < 0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
        return Tone((best + shift) * binHz, snrDb, levelDb)
    }

    private fun near(k: Int) = maxOf(logMag[k - 1], logMag[k], logMag[k + 1])

    private fun fft(x: DoubleArray, y: DoubleArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = x[i]; x[i] = x[j]; x[j] = t; t = y[i]; y[i] = y[j]; y[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val ai = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k + len / 2] = x[i + k] - ar; y[i + k + len / 2] = y[i + k] - ai
                    x[i + k] += ar; y[i + k] += ai
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Keeps one believable motor speed from a stream of tones. A jump of more than 15% (a harmonic picked by mistake,
 * a passing sound) is only believed after it repeats for [confirm] frames in a row.
 */
class ToneTracker(private val confirm: Int = 3) {
    var hz: Double? = null; private set
    private var candidate: Double? = null
    private var count = 0
    private var lastMs = 0L

    fun update(tone: PropTone.Tone?, nowMs: Long, staleMs: Long = 1000): Double? {
        if (tone == null) { if (nowMs - lastMs > staleMs) hz = null; return hz }
        val cur = hz
        if (cur == null || abs(tone.hz - cur) / cur < 0.15) {
            hz = if (cur == null) tone.hz else cur + 0.5 * (tone.hz - cur)
            lastMs = nowMs; candidate = null; count = 0
        } else {
            val c = candidate
            if (c != null && abs(tone.hz - c) / c < 0.15) count++ else { candidate = tone.hz; count = 1 }
            if (count >= confirm) { hz = tone.hz; lastMs = nowMs; candidate = null; count = 0 }
        }
        return hz
    }
}

/**
 * Experimental: airspeed from the propeller. A fixed-pitch propeller unloads as air flows through it faster, so at the
 * same throttle it spins faster. Balancing motor torque against propeller torque (torque coefficient falling roughly
 * linearly with advance ratio) gives
 *
 *     airspeed = k0 + k1·n + k2·(u/n) + k3·(u/n)·minutes
 *
 * with n in revolutions per second, u the throttle, and the last term following the battery's sag through the flight.
 * The k values differ for every motor, propeller and battery, so they're learned in flight from GPS ground speed.
 * Ground speed includes the wind, so learning only finishes once the vehicle has flown in every direction (eight
 * sectors), and each sector is weighted equally so the wind cancels. What's left over is fitted as a wind, which
 * also comes out as an estimate.
 *
 * Never used to set throttle. See docs/SPEED_SAFETY.md.
 */
class PropAirspeed {
    data class Sample(val n: Double, val u: Double, val minutes: Double, val groundMps: Double, val courseDeg: Double)
    data class Fit(val k: DoubleArray, val rmsMps: Double, val windMps: Double, val windFromDeg: Double, val nMin: Double, val nMax: Double, val samples: Int)

    private val samples = ArrayDeque<Sample>()
    var fit: Fit? = null; private set

    val count get() = samples.size
    fun sectorsCovered(): Int = (0 until SECTORS).count { s -> samples.any { sector(it.courseDeg) == s } }

    fun add(s: Sample) {
        if (s.n <= 0 || s.u <= 0.05 || s.groundMps < MIN_GROUND_MPS || !s.groundMps.isFinite()) return
        samples.addLast(s)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    fun clear() { samples.clear(); fit = null }

    fun restore(f: Fit?) { fit = f }

    /** Fits when every direction has enough samples. Returns the new fit, or null with nothing changed. */
    fun refit(): Fit? {
        val perSector = IntArray(SECTORS)
        for (s in samples) perSector[sector(s.courseDeg)]++
        if (perSector.any { it < MIN_PER_SECTOR }) return null
        // Ground speed = airspeed from the propeller + the wind along the course. Both fitted together.
        val rows = samples.map { regressors(it.n, it.u, it.minutes) + doubleArrayOf(cos(rad(it.courseDeg)), sin(rad(it.courseDeg))) }
        val w = samples.map { 1.0 / perSector[sector(it.courseDeg)] }
        val all = leastSquares(rows, samples.map { it.groundMps }, w) ?: return null
        val k = all.copyOfRange(0, 4)
        val wind = all.copyOfRange(4, 6)
        var sse = 0.0; var sw = 0.0
        samples.forEachIndexed { i, s -> val e = s.groundMps - dot(all, rows[i]); sse += w[i] * e * e; sw += w[i] }
        // The wind along the course adds to ground speed, so it blows from the opposite direction.
        val windFrom = (Math.toDegrees(atan2(wind[1], wind[0])) + 180 + 360) % 360
        return Fit(k, sqrt(sse / sw), hypot(wind[0], wind[1]), windFrom, samples.minOf { it.n }, samples.maxOf { it.n }, samples.size).also { fit = it }
    }

    /** Airspeed, or null when there's no fit or the motor speed is outside what the fit has seen. */
    fun airspeed(n: Double, u: Double, minutes: Double): Double? {
        val f = fit ?: return null
        if (n < f.nMin * 0.85 || n > f.nMax * 1.15 || u <= 0.05) return null
        return dot(f.k, regressors(n, u, minutes)).takeIf { it.isFinite() }?.coerceAtLeast(0.0)
    }

    companion object {
        const val SECTORS = 8
        const val MIN_PER_SECTOR = 10
        const val MAX_SAMPLES = 2000
        const val MIN_GROUND_MPS = 3.0
        fun sector(courseDeg: Double) = (((courseDeg % 360 + 360) % 360) / (360.0 / SECTORS)).toInt().coerceIn(0, SECTORS - 1)
        fun regressors(n: Double, u: Double, minutes: Double) = doubleArrayOf(1.0, n, u / n, u / n * minutes)
        private fun rad(d: Double) = Math.toRadians(d)
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

        /** Weighted least squares by the normal equations, with a little ridge so a flat fit doesn't blow up. */
        fun leastSquares(rows: List<DoubleArray>, y: List<Double>, w: List<Double>): DoubleArray? {
            if (rows.isEmpty()) return null
            val m = rows[0].size
            // Columns differ by orders of magnitude (n is about 100, u/n about 0.005), so scale them first.
            val scale = DoubleArray(m) { i -> sqrt(rows.indices.sumOf { w[it] * rows[it][i] * rows[it][i] }).takeIf { it > 0 } ?: 1.0 }
            val a = Array(m) { DoubleArray(m + 1) }
            for (r in rows.indices) for (i in 0 until m) {
                for (j in 0 until m) a[i][j] += w[r] * rows[r][i] / scale[i] * rows[r][j] / scale[j]
                a[i][m] += w[r] * rows[r][i] / scale[i] * y[r]
            }
            for (i in 0 until m) a[i][i] += 1e-9 * (1 + abs(a[i][i]))
            for (c in 0 until m) {
                val p = (c until m).maxByOrNull { abs(a[it][c]) }!!
                if (abs(a[p][c]) < 1e-12) return null
                val t = a[c]; a[c] = a[p]; a[p] = t
                for (r in 0 until m) if (r != c) {
                    val f = a[r][c] / a[c][c]
                    for (j in c..m) a[r][j] -= f * a[c][j]
                }
            }
            return DoubleArray(m) { a[it][m] / a[it][it] / scale[it] }
        }
    }
}
