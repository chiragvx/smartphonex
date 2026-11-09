package com.smartphonex.core

import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * Experimental: the ground run. With the plane restrained, the motor is stepped through a few throttle settings while
 * the microphone measures how fast it spins at each, and which tones it makes. That static curve is what flight
 * readings are matched against:
 *  - **This pack's strength.** Static speed at a fixed throttle tracks the battery's voltage, which changes speed as much
 *    as airspeed does. Compared with the setup's reference run, it scales throttle for the airspeed model.
 *  - **A changed setup.** A new propeller or motor bends the curve differently. If it no longer has the reference's
 *    shape, airspeed learning starts again rather than being confidently wrong.
 *  - **Reading double.** In flight the propeller unloads and speeds up a little at a given throttle, never by half or
 *    double. A reading that's exactly that far off is a harmonic mistaken for the fundamental, and is corrected.
 * Hold-to-run like an output test: the motor stops as soon as the person lets go, or the page goes away.
 */
class GroundRun(private val clock: () -> Long) {
    data class Step(val throttle: Double, val revHz: Double, val spreadPct: Double, val harmonics: List<Int>, val snrDb: Double)

    data class Baseline(val atMs: Long, val blades: Int, val steps: List<Step>, val warnings: List<String> = emptyList()) {
        /**
         * Static revolutions per second expected at throttle [u], times [strength]. Straight lines between steps,
         * extended beyond them, which is coarse but enough to tell a speed from double or half of it. Null below
         * half the lowest step, where an ESC's start-up behaviour makes it unknowable.
         */
        fun expectedRevHz(u: Double, strength: Double = 1.0): Double? {
            if (steps.size < 2 || u < steps.first().throttle * 0.5) return null
            val i = (1 until steps.size).firstOrNull { u <= steps[it].throttle } ?: (steps.size - 1)
            val a = steps[i - 1]; val b = steps[i]
            val n = a.revHz + (b.revHz - a.revHz) * (u - a.throttle) / (b.throttle - a.throttle)
            return (n * strength).takeIf { it > 0 }
        }
        fun sameStepsAs(o: Baseline) = blades == o.blades && steps.size == o.steps.size && steps.zip(o.steps).all { (x, y) -> abs(x.throttle - y.throttle) < 1e-6 }
    }

    /** How a run compares with the setup's reference. [strength] above 1 is a stronger pack than the reference's. */
    data class Comparison(val strength: Double, val shapePct: Double, val changed: Boolean, val why: String?)

    enum class State { IDLE, RUNNING, DONE, STOPPED, FAILED }

    var state = State.IDLE; private set
    var message: String? = null; private set
    var result: Baseline? = null; private set
    private var owner: Source? = null
    private var lastBeat = 0L
    private var throttles = listOf<Double>()
    private var blades = 2
    private var step = 0
    private var stepAtMs = 0L
    private var lastSampleMs = -1L
    private val window = ArrayDeque<Pair<Long, Double>>()
    private val stepHarmonics = HashMap<String, Int>()
    private var stepSnr = ArrayList<Double>()
    private val done = ArrayList<Step>()
    private val warnings = LinkedHashSet<String>()

    @Synchronized fun start(source: Source, steps: List<Double>, blades: Int): String? {
        val now = clock()
        if (state == State.RUNNING && owner != source && now - lastBeat <= HOLD_MS) return "$owner is running the ground run"
        if (state == State.RUNNING && owner == source) { lastBeat = now; return null }
        // Still holding after it finished or failed: let go before it starts again.
        val held = now - lastBeat <= HOLD_MS
        lastBeat = now
        if (state != State.IDLE && held) return null
        owner = source; throttles = steps; this.blades = blades
        state = State.RUNNING; message = "step 1 of ${steps.size}: spinning up"; result = null
        step = 0; stepAtMs = now; lastSampleMs = -1L; done.clear(); warnings.clear(); resetStep()
        return null
    }

    @Synchronized fun stop(why: String = "stopped") {
        if (state == State.RUNNING) { state = State.STOPPED; message = why }
        owner = null
    }

    @Synchronized fun running() = state == State.RUNNING

    /**
     * The throttle to hold right now, or null for none. Feeds in the latest propeller reading: [atMs] when it was
     * heard, [revHz] revolutions per second, [tone] what was heard. The same reading twice counts once.
     */
    @Synchronized fun tick(atMs: Long, revHz: Double?, tone: PropTone.Tone?): Double? {
        if (state != State.RUNNING) return null
        val now = clock()
        if (now - lastBeat > HOLD_MS) { stop("stopped: let go before it finished"); return null }
        val u = throttles[step]
        if (revHz != null && atMs != lastSampleMs && atMs >= stepAtMs + SPINUP_MS) {
            lastSampleMs = atMs
            window.addLast(atMs to revHz)
            while (window.isNotEmpty() && atMs - window.first().first > STEADY_MS) window.removeFirst()
            tone?.let {
                stepHarmonics.merge(it.harmonics.joinToString(","), 1, Int::plus); stepSnr += it.snrDb
                if (it.clipped) warnings += "too loud: the microphone was distorting"
                if (it.otherFamily) warnings += "another propeller or engine was audible"
            }
            settled(atMs)?.let { return it }
        }
        if (state == State.RUNNING && now - stepAtMs > STEP_TIMEOUT_MS) return fail(timeoutWhy(u))
        return if (state == State.RUNNING) throttles[step] else null
    }

    /** Takes the step once the last [STEADY_MS] of readings agree, and moves on. Returns null to keep going. */
    private fun settled(atMs: Long): Double? {
        if (window.size < MIN_READINGS || atMs - window.first().first < STEADY_MS * 0.8) return null
        val hz = window.map { it.second }
        val spread = (hz.max() / hz.min() - 1) * 100
        if (spread > STEADY_SPREAD_PCT) return null
        val n = hz.sorted()[hz.size / 2]
        val u = throttles[step]
        done.lastOrNull()?.let { prev ->
            val r = n / prev.revHz
            // A faster throttle must spin it faster, but by no more than the throttle rose plus a margin: a jump
            // bigger than that is a harmonic taken for the fundamental.
            if (r < MIN_RISE) return null
            if (r > u / prev.throttle * MAX_RISE) return fail("the speed jumped more than the throttle can explain, from ${rpm(prev.revHz)} to ${rpm(n)} rpm: the microphone may have picked a harmonic. Try again, or move the phone")
        }
        val h = stepHarmonics.maxByOrNull { it.value }?.key?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        done += Step(u, n, Math.round(spread * 100) / 100.0, h, stepSnr.sorted().getOrElse(stepSnr.size / 2) { 0.0 })
        if (step + 1 >= throttles.size) {
            state = State.DONE; owner = null
            result = Baseline(clock(), blades, done.toList(), warnings.toList())
            message = "done: " + done.joinToString(", ") { "${rpm(it.revHz)} rpm at ${pct(it.throttle)}" }
            return null
        }
        step++; stepAtMs = clock(); resetStep()
        message = "step ${step + 1} of ${throttles.size}: ${pct(throttles[step])}, spinning up"
        return throttles[step]
    }

    private fun timeoutWhy(u: Double) = when {
        window.isEmpty() -> "no propeller tone heard at ${pct(u)} throttle. Check MIC_F_MIN and MIC_F_MAX, and that the motor turns"
        done.isNotEmpty() && window.last().second / done.last().revHz < MIN_RISE -> "the motor didn't speed up from ${pct(done.last().throttle)} to ${pct(u)} throttle"
        else -> "the speed didn't hold steady at ${pct(u)} throttle (within $STEADY_SPREAD_PCT% for ${STEADY_MS / 1000.0} s)"
    }

    private fun fail(why: String): Double? { state = State.FAILED; message = "failed: $why"; owner = null; return null }
    private fun resetStep() { window.clear(); stepHarmonics.clear(); stepSnr = ArrayList() }
    private fun rpm(rev: Double) = Math.round(rev * 60)
    private fun pct(u: Double) = "${Math.round(u * 100)}%"

    @Synchronized fun json() = buildJsonObject {
        put("state", state.name); put("message", message); put("step", step + 1); put("steps", throttles.size)
        if (state == State.RUNNING) put("throttle", throttles[step])
    }

    companion object {
        const val HOLD_MS = 400L
        /** Ignore the first part of each step while the motor gets there. */
        const val SPINUP_MS = 1000L
        /** How long the speed must hold steady, and within how much, for a step to count. */
        const val STEADY_MS = 1500L
        const val STEADY_SPREAD_PCT = 1.5
        const val MIN_READINGS = 8
        const val STEP_TIMEOUT_MS = 8000L
        const val MIN_RISE = 1.03
        const val MAX_RISE = 1.6
        /** A ground run counts for the flight that follows it within this long. */
        const val FRESH_MS = 30 * 60_000L
        /** Differences from the reference beyond these mean a different propeller, motor or ESC, not a battery. */
        const val SHAPE_LIMIT_PCT = 3.0
        val STRENGTH_RANGE = 0.75..1.2

        fun steps(lo: Double, hi: Double, n: Int = 3): List<Double> = (0 until n).map { Math.round((lo + (hi - lo) * it / (n - 1)) * 100) / 100.0 }

        fun compare(run: Baseline, ref: Baseline): Comparison {
            val r = run.steps.zip(ref.steps).map { (a, b) -> a.revHz / b.revHz }
            val s = r.sorted()[r.size / 2]
            val shape = r.maxOf { abs(it / s - 1) } * 100
            val why = when {
                shape > SHAPE_LIMIT_PCT -> "the speed curve has a different shape from the reference (${"%.1f".format(shape)}% off)"
                s !in STRENGTH_RANGE -> "it spins ${"%.0f".format(abs(s - 1) * 100)}% ${if (s > 1) "faster" else "slower"} than the reference, more than a battery explains"
                else -> null
            }
            return Comparison(s, shape, why != null, why)
        }

        /**
         * Checks a flight reading of [revHz] at throttle [u] against the static curve. In the air the propeller unloads,
         * so it spins up to half as fast again as on the ground, and never much slower. Returns the reading to use (a
         * harmonic mistake undone) and a note when it was corrected or doesn't fit.
         */
        fun check(revHz: Double, u: Double, base: Baseline?, strength: Double): Pair<Double, String?> {
            val e = base?.expectedRevHz(u, strength) ?: return revHz to null
            fun fits(x: Double) = x / e in FLIGHT_RANGE
            return when {
                fits(revHz) -> revHz to null
                fits(revHz / 2) -> revHz / 2 to "corrected: heard the 2nd harmonic as the fundamental"
                fits(revHz * 2) -> revHz * 2 to "corrected: heard half the speed"
                else -> revHz to "doesn't match the ground run: ${Math.round(revHz * 60)} rpm where about ${Math.round(e * 60)} was expected"
            }
        }
        val FLIGHT_RANGE = 0.85..1.6

        fun toJson(b: Baseline) = buildJsonObject {
            put("t", b.atMs); put("blades", b.blades)
            put("steps", JsonArray(b.steps.map { s -> buildJsonObject {
                put("throttle", s.throttle); put("revHz", Math.round(s.revHz * 100) / 100.0); put("rpm", Math.round(s.revHz * 60))
                put("spreadPct", s.spreadPct); put("harmonics", s.harmonics.joinToString(",")); put("snr", Math.round(s.snrDb * 10) / 10.0)
            } }))
            put("warnings", JsonArray(b.warnings.map { JsonPrimitive(it) }))
        }

        fun fromJson(o: JsonObject): Baseline? = runCatching {
            Baseline(
                o["t"]!!.jsonPrimitive.long, o["blades"]!!.jsonPrimitive.int,
                o["steps"]!!.jsonArray.map { e -> val s = e.jsonObject
                    Step(s["throttle"]!!.jsonPrimitive.double, s["revHz"]!!.jsonPrimitive.double, s["spreadPct"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        s["harmonics"]?.jsonPrimitive?.contentOrNull?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(), s["snr"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                },
                (o["warnings"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            ).takeIf { it.steps.size >= 2 }
        }.getOrNull()
    }
}

/** Per vehicle profile: the reference run that defines the setup, and the latest run, which measures this pack. */
class GroundRunStore(private val file: java.io.File?, private val io: (() -> Unit) -> Unit = { it() }) {
    data class Entry(val reference: GroundRun.Baseline, val last: GroundRun.Baseline, val comparison: GroundRun.Comparison)
    private val data = HashMap<String, Entry>()

    init {
        runCatching {
            Json.parseToJsonElement(file!!.readText()).jsonObject.forEach { (id, e) ->
                val o = e.jsonObject
                val ref = GroundRun.fromJson(o["reference"]!!.jsonObject) ?: return@forEach
                val last = (o["last"] as? JsonObject)?.let(GroundRun::fromJson) ?: ref
                data[id] = Entry(ref, last, if (last.sameStepsAs(ref)) GroundRun.compare(last, ref) else GroundRun.Comparison(1.0, 0.0, false, null))
            }
        }
    }

    @Synchronized fun get(profile: String) = data[profile]

    /** Stores a finished run. Returns the entry, with [Entry.comparison] saying whether the setup changed. */
    @Synchronized fun add(profile: String, run: GroundRun.Baseline): Entry {
        val old = data[profile]
        val e = when {
            old == null || !run.sameStepsAs(old.reference) -> Entry(run, run, GroundRun.Comparison(1.0, 0.0, false, null))
            else -> {
                val c = GroundRun.compare(run, old.reference)
                if (c.changed) Entry(run, run, c) else Entry(old.reference, run, c)
            }
        }
        data[profile] = e
        save()
        return e
    }

    @Synchronized fun clear(profile: String) { data.remove(profile); save() }

    private fun save() {
        val f = file ?: return
        val text = buildJsonObject {
            data.forEach { (id, e) -> putJsonObject(id) { put("reference", GroundRun.toJson(e.reference)); put("last", GroundRun.toJson(e.last)) } }
        }.toString()
        io {
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
