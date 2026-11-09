package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What plans can really count on, measured while the vehicle flies them: its climb rate, how its legs' times compare
 * with the plan's, and its glide. From these it suggests better planning settings, which a person approves. It never
 * changes them itself, and it rounds towards caution: plans should be a little pessimistic, not hopeful.
 */
class PlanLearning(private val file: File?, private val io: (() -> Unit) -> Unit = { it() }) {
    data class Suggestion(val param: String, val current: Double, val suggested: Double, val basis: String)

    private class Samples {
        val climb = ArrayDeque<Double>()
        val pace = ArrayDeque<Double>()
        val glide = ArrayDeque<Double>()
    }
    private val byVehicle = HashMap<String, Samples>()

    init {
        runCatching {
            Json.parseToJsonElement(file!!.readText()).jsonObject.forEach { (v, o) ->
                val s = byVehicle.getOrPut(v) { Samples() }
                fun read(k: String, into: ArrayDeque<Double>) = (o.jsonObject[k] as? JsonArray)?.forEach { x -> (x as? JsonPrimitive)?.doubleOrNull?.let { into.addLast(it) } }
                read("climb", s.climb); read("pace", s.pace); read("glide", s.glide)
            }
        }
    }

    private fun add(vehicle: String, pick: (Samples) -> ArrayDeque<Double>, v: Double) = synchronized(this) {
        if (!v.isFinite()) return
        val q = pick(byVehicle.getOrPut(vehicle) { Samples() })
        q.addLast(v)
        while (q.size > KEEP) q.removeFirst()
        save()
    }

    /** A climb rate the autopilot achieved while climbing towards a point's height, m/s. */
    fun climb(vehicle: String, mps: Double) { if (mps in 0.1..30.0) add(vehicle, { it.climb }, mps) }
    /** A leg's planned time over its actual time: above 1 is faster than planned. */
    fun pace(vehicle: String, ratio: Double) { if (ratio in 0.2..5.0) add(vehicle, { it.pace }, ratio) }
    /** Metres forward per metre down, gliding with the motor stopped. */
    fun glide(vehicle: String, ratio: Double) { if (ratio in 1.0..40.0) add(vehicle, { it.glide }, ratio) }

    fun forget(vehicle: String) = synchronized(this) { byVehicle.remove(vehicle); save() }

    fun counts(vehicle: String) = synchronized(this) { byVehicle[vehicle]?.let { Triple(it.climb.size, it.pace.size, it.glide.size) } ?: Triple(0, 0, 0) }

    /**
     * Settings worth changing, from at least [MIN_SAMPLES] measurements (one glide is enough: they're rare and
     * telling), when the measured value differs from the setting by more than [MIN_CHANGE].
     */
    fun suggestions(vehicle: String, current: (String) -> Double): List<Suggestion> = synchronized(this) {
        val s = byVehicle[vehicle] ?: return emptyList()
        val out = ArrayList<Suggestion>()
        fun offer(param: String, value: Double, basis: String, decimals: Int = 1) {
            val cur = current(param)
            val v = Math.round(value * 10.0.pow(decimals)) / 10.0.pow(decimals)
            if (cur > 0 && abs(v - cur) / cur > MIN_CHANGE) out += Suggestion(param, cur, v, basis)
        }
        if (s.climb.size >= MIN_SAMPLES) {
            val m = median(s.climb)
            offer("PLAN_CLIMB", m * 0.9, "${s.climb.size} climbs measured, typically ${"%.1f".format(m)} m/s; planning with 90% of that")
        }
        if (s.pace.size >= MIN_SAMPLES) {
            val m = median(s.pace)
            val cur = current("PLAN_SPEED")
            offer("PLAN_SPEED", cur * m, "${s.pace.size} legs took ${if (m < 1) "${((1 / m - 1) * 100).roundToInt()}% longer" else "${((1 - 1 / m) * 100).roundToInt()}% less time"} than planned")
        }
        if (s.glide.isNotEmpty()) {
            val m = median(s.glide)
            offer("PLAN_GLIDE", m * 0.85, "${s.glide.size} motor-off glide${if (s.glide.size == 1) "" else "s"} measured at ${"%.1f".format(m)}:1; planning with 85% of that")
        }
        out
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
    private fun median(q: Collection<Double>) = q.sorted().let { l -> if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2 }

    private fun save() {
        val f = file ?: return
        val text = buildJsonObject {
            byVehicle.forEach { (v, s) -> putJsonObject(v) {
                put("climb", JsonArray(s.climb.map { JsonPrimitive(it) })); put("pace", JsonArray(s.pace.map { JsonPrimitive(it) })); put("glide", JsonArray(s.glide.map { JsonPrimitive(it) }))
            } }
        }.toString()
        io { runCatching { f.parentFile?.mkdirs(); val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } } }
    }

    companion object {
        const val KEEP = 60
        const val MIN_SAMPLES = 3
        const val MIN_CHANGE = 0.1
    }
}
