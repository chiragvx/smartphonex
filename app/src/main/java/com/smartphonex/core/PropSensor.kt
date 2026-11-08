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
 * Experimental: hears the propeller in the phone's microphone and turns it into motor speed.
 *
 * Everything a spinning motor and propeller sound out is a whole multiple of the revolution rate: the blade-pass tone
 * (revolutions per second × blades) and its harmonics, the motor's magnets and ESC (pole pairs × revolutions, often
 * 7×, sometimes louder than the blades), and a little imbalance at 1×. So the analyzer finds every strong peak, then
 * the highest base frequency that explains them as one family. That way a loud motor tone, or a blade tone faded by
 * the microphone's weak low end, can't be mistaken for the blade tone. Peaks the family doesn't explain are reported,
 * as a second propeller or another aircraft.
 * Audio is only ever analyzed in memory. Nothing is recorded or stored.
 */
class PropTone(val sampleRate: Int, val size: Int = 8192) {
    /**
     * [hz] is the blade-pass frequency, revolutions per second × blades, which is what the rest of the app uses.
     * [revHz] is revolutions per second. [harmonics] are the multiples of [revHz] heard. [peaks] are every strong peak,
     * which count as background while disarmed. [otherFamily]: strong tones this propeller doesn't explain.
     * [clipped]: the microphone was overloaded, so the sound is distorted.
     */
    data class Tone(
        val hz: Double, val snrDb: Double, val levelDb: Double,
        val revHz: Double = hz, val harmonics: List<Int> = listOf(1), val peaks: List<Double> = listOf(hz),
        val otherFamily: Boolean = false, val clipped: Boolean = false,
    )

    private class Peak(val hz: Double, val snrDb: Double)
    private class Family(val f0: Double, val score: Double, val members: List<Pair<Int, Peak>>)

    init { require(size and (size - 1) == 0) { "size must be a power of two" } }

    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val logMag = DoubleArray(size / 2)
    private val floor = DoubleArray(size / 2)
    private val binHz = sampleRate.toDouble() / size

    /** Every strong peak in the last sound analyzed, including excluded ones. */
    var lastPeaks: List<Double> = emptyList()
        private set

    /**
     * The propeller whose blade-pass tone lies between [fMin] and [fMax] Hz, or null if nothing stands out by
     * [minSnrDb]. Peaks within 4% of one in [exclude] are skipped: background sounds such as mains hum or a fan.
     */
    fun analyze(
        samples: DoubleArray, fMin: Double, fMax: Double, minSnrDb: Double,
        exclude: List<Double> = emptyList(), blades: Int = 2,
    ): Tone? {
        require(samples.size >= size)
        val b = blades.coerceAtLeast(1)
        lastPeaks = emptyList()
        var energy = 0.0
        var clippedN = 0
        val off = samples.size - size
        for (i in 0 until size) {
            val s = samples[off + i]
            energy += s * s
            if (abs(s) >= CLIP_LEVEL) clippedN++
            re[i] = s * window[i]; im[i] = 0.0
        }
        val levelDb = 10 * log10(energy / size + 1e-12)
        fft(re, im)
        for (k in 0 until size / 2) logMag[k] = ln(re[k] * re[k] + im[k] * im[k] + 1e-12)

        // Search from the slowest revolution rate to well above the blade band, where the motor's own tone sits.
        val lo = maxOf(3, (fMin / b * 0.9 / binHz).toInt())
        val hi = minOf(size / 2 - 3, (minOf(fMax * FAMILY_SPAN, sampleRate * 0.45) / binHz).toInt())
        if (hi <= lo + 4) return null
        noiseFloor(lo, hi)
        val minLn = minSnrDb * ln(10.0) / 10
        val peaks = ArrayList<Peak>()
        val all = ArrayList<Peak>()
        for (k in lo..hi) {
            val m = logMag[k]
            if (m <= logMag[k - 1] || m < logMag[k + 1] || m < logMag[k - 2] || m < logMag[k + 2]) continue
            if (m - floor[k] < minLn) continue
            val hz = (k + interp(k)) * binHz
            val peak = Peak(hz, (m - floor[k]) * 10 / ln(10.0))
            all += peak
            if (exclude.none { abs(hz - it) <= it * EXCLUDE_WIDTH + binHz }) peaks += peak
        }
        lastPeaks = all.sortedByDescending { it.snrDb }.take(MAX_PEAKS).map { it.hz }
        if (peaks.isEmpty()) return null
        peaks.sortByDescending { it.snrDb }
        val strong = peaks.take(MAX_PEAKS)

        // Every strong peak divided by a small whole number is a candidate revolution (or blade-pass) rate.
        val fLo = fMin / b * 0.9
        val fHi = fMax * 1.05
        val families = ArrayList<Family>()
        for (p in strong.take(8)) for (m in 1..MAX_HARMONIC) {
            val f = p.hz / m
            if (f < fLo) break
            if (f <= fHi) family(f, strong)?.let { families += it }
        }
        if (families.isEmpty()) return null
        // A family at half the true rate explains the same peaks, so of the near-best take the highest.
        val best = families.maxOf { it.score }
        val fam = families.filter { it.score >= best * 0.9 }.maxBy { it.f0 }

        // The base found is either the revolution rate, when anything besides the blades is heard (motor tone,
        // imbalance), or the blade-pass rate itself when only the blades are. The blade family alone has its
        // fundamental as the loudest tone, or a 3rd harmonic that isn't a multiple of the blade count.
        val byH = fam.members.associate { it.first to it.second.snrDb }
        val loudest = fam.members.maxBy { it.second.snrDb }.first
        val bladesOnly = b == 1 || (byH[1] != null && (loudest == 1 || (2..3).any { it % b != 0 && byH[it] != null }))
        val rev = if (bladesOnly) fam.f0 / b else fam.f0
        val bladeHz = rev * b
        if (bladeHz < fMin * 0.97 || bladeHz > fMax * 1.03) return null
        val snr = fam.members.maxOf { it.second.snrDb }
        val explained = fam.members.map { it.second }.toSet()
        val other = strong.any { it !in explained && it.snrDb > snr * 0.7 }
        val harmonics = fam.members.map { if (bladesOnly) it.first * b else it.first }.sorted()
        // A propeller's family has a shape: blade harmonics, including the 1st or 2nd, and besides them at most one other
        // series (the motor tone and its multiples) and a little imbalance at 1x. Room noise just over the threshold
        // makes scattered peaks that some low base explains by chance, without that shape. A very strong lone tone counts.
        val blade = harmonics.filter { it % b == 0 }
        val odd = harmonics.filter { it % b != 0 && it != 1 }
        val looksLikeProp = blade.any { it == b || it == 2 * b } && (blade.size >= 2 || odd.isNotEmpty()) &&
            odd.all { it % odd.min() == 0 }
        if (!looksLikeProp && snr < minSnrDb + LONE_TONE_EXTRA_DB) return null
        return Tone(bladeHz, snr, levelDb, rev, harmonics, strong.map { it.hz }, other, clippedN > size / 1000)
    }

    /** The peaks that are whole multiples of about [f], with [f] refined to fit them best. Null if none fits. */
    private fun family(f: Double, peaks: List<Peak>): Family? {
        var f0 = f
        var members = emptyList<Pair<Int, Peak>>()
        repeat(2) {
            members = peaks.mapNotNull { p ->
                val h = Math.round(p.hz / f0).toInt()
                if (h < 1 || h > MAX_HARMONIC || abs(p.hz - h * f0) > maxOf(0.6 * binHz, p.hz * 0.006)) null else h to p
            }
            if (members.isEmpty()) return null
            // Least squares fit of f0 to the members: high harmonics pin it down finely.
            f0 = members.sumOf { (h, p) -> p.snrDb * h * p.hz } / members.sumOf { (h, p) -> p.snrDb * h * h }
        }
        return Family(f0, members.sumOf { it.second.snrDb }, members)
    }

    /** Typical level near each bin: the median of its stretch of 64 bins, so a noisy low end doesn't hide quiet highs. */
    private fun noiseFloor(lo: Int, hi: Int) {
        var s = lo
        while (s <= hi) {
            val e = minOf(hi, s + 63)
            val seg = (maxOf(lo, e - 63)..e).map { logMag[it] }.sorted()
            val med = seg[seg.size / 2]
            for (k in s..e) floor[k] = med
            s = e + 1
        }
    }

    /** Parabolic interpolation for a peak finer than one bin. */
    private fun interp(k: Int): Double {
        val a = logMag[k - 1]; val b = logMag[k]; val c = logMag[k + 1]
        val d = a - 2 * b + c
        return if (d < 0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
    }

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

    companion object {
        /** Samples at or above this are taken as the microphone overloading. */
        const val CLIP_LEVEL = 0.995
        /** How far above the blade band to look for the motor's own tone. */
        const val FAMILY_SPAN = 4.0
        const val MAX_PEAKS = 16
        const val MAX_HARMONIC = 16
        /** How much stronger than the threshold a tone without a family must be to count. */
        const val LONE_TONE_EXTRA_DB = 12.0
        /** Background tones drift (a fan hunting for speed), so peaks this close to one are skipped. */
        const val EXCLUDE_WIDTH = 0.04
    }
}

/**
 * Keeps one believable motor speed from a stream of tones. A first reading, or a jump of more than 15% (a harmonic
 * picked by mistake, a passing sound), is only believed after it repeats for [confirm] frames close together.
 */
class ToneTracker(private val confirm: Int = 3) {
    var hz: Double? = null; private set
    private var candidate: Double? = null
    private var candidateMs = 0L
    private var count = 0
    private var lastMs = 0L

    fun update(tone: PropTone.Tone?, nowMs: Long, staleMs: Long = 1000): Double? {
        if (tone == null) { if (nowMs - lastMs > staleMs) hz = null; return hz }
        val cur = hz
        if (cur != null && abs(tone.hz - cur) / cur < 0.15) {
            hz = cur + 0.5 * (tone.hz - cur)
            lastMs = nowMs; candidate = null; count = 0
        } else {
            // Locking on, or jumping, takes the same tone in [confirm] frames close together.
            val c = candidate
            if (c != null && abs(tone.hz - c) / c < 0.15 && nowMs - candidateMs <= CANDIDATE_GAP_MS) count++ else { candidate = tone.hz; count = 1 }
            candidateMs = nowMs
            if (count >= confirm) { hz = tone.hz; lastMs = nowMs; candidate = null; count = 0 }
        }
        return hz
    }

    private companion object { const val CANDIDATE_GAP_MS = 400L }
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
