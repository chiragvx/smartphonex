package com.smartphonex.core

/**
 * A face for the phone's screen, mostly for robots. This part decides what the face shows from the device's state.
 * The phone app draws it. Safety states always win over an expression someone asked for.
 */
object Face {
    enum class Expr { NEUTRAL, HAPPY, EXCITED, SAD, ANGRY, SURPRISED, SCARED, CONFUSED, SLEEPY, CURIOUS, WINK, LOVE, FOCUSED, ALARM, DEAD }

    /** The ones a person or AI can ask for. The others belong to the device's state. */
    val REQUESTABLE = listOf(Expr.NEUTRAL, Expr.HAPPY, Expr.EXCITED, Expr.SAD, Expr.ANGRY, Expr.SURPRISED, Expr.SCARED, Expr.CONFUSED, Expr.SLEEPY, Expr.CURIOUS, Expr.WINK, Expr.LOVE)
    /** Longest timed expression. Seconds of 0 means until changed. */
    const val MAX_REQUEST_S = 3600.0
    /** The name that hands the face back to the device's own state. */
    const val AUTO = "auto"

    /** Settings, from FACE_MODE. */
    enum class Mode { OFF, IDLE, ROBOT }

    /** What to draw. [gazeX] and [gazeY] run from -1 to 1. [alarm] tints the face red. [label] is shown under it, if any. */
    data class Look(val expr: Expr, val gazeX: Float, val gazeY: Float, val alarm: Boolean, val label: String?)

    data class Input(
        val killed: Boolean,
        val mode: com.smartphonex.core.Mode,
        val failsafeReason: String?,
        val steering: Double,
        val throttle: Double,
        val missionRunning: Boolean,
        /** An expression asked for, and until when (ms). */
        val requested: Pair<Expr, Long>?,
        val now: Long,
        /** Seconds since anyone touched or commanded it. Long quiet makes it sleepy. */
        val quietS: Double,
        val batteryLow: Boolean,
        val hot: Boolean,
    )

    fun look(i: Input): Look {
        val armed = i.mode == com.smartphonex.core.Mode.MANUAL || i.mode == com.smartphonex.core.Mode.AUTO
        // Steering is +1 left. The face looks the way it's turning, from the viewer's side.
        val gx = (-i.steering).coerceIn(-1.0, 1.0).toFloat()
        return when {
            i.killed -> Look(Expr.DEAD, 0f, 0f, true, "Killed")
            i.mode == com.smartphonex.core.Mode.FAILSAFE -> Look(Expr.ALARM, 0f, 0f, true, i.failsafeReason?.let { "Failsafe: $it" } ?: "Failsafe")
            i.requested != null && i.now < i.requested.second -> Look(i.requested.first, if (armed) gx else 0f, 0f, false, null)
            armed && (i.missionRunning || kotlin.math.abs(i.throttle) > 0.05 || kotlin.math.abs(i.steering) > 0.05) ->
                Look(Expr.FOCUSED, gx, if (i.throttle < -0.05) 0.4f else -0.15f, false, null)
            armed -> Look(Expr.NEUTRAL, 0f, 0f, false, null)
            i.hot -> Look(Expr.SLEEPY, 0f, 0.3f, false, "Too warm")
            i.batteryLow -> Look(Expr.SLEEPY, 0f, 0.3f, false, "Battery low")
            i.quietS > 600 -> Look(Expr.SLEEPY, 0f, 0.2f, false, null)
            else -> Look(Expr.NEUTRAL, 0f, 0f, false, null)
        }
    }

    /** A checked request: an expression and until when (ms), or null [expr] to go back to automatic. */
    data class Request(val expr: Expr?, val untilMs: Long)

    /** Checks a request. [seconds] defaults to 4, and 0 holds the expression until it's changed. */
    fun parseRequest(name: String?, seconds: Double?, now: Long): Pair<Request?, String?> {
        if (name?.trim().equals(AUTO, ignoreCase = true)) return Request(null, 0) to null
        val e = REQUESTABLE.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
            ?: return null to "expression must be $AUTO or one of ${REQUESTABLE.joinToString { it.name.lowercase() }}"
        val s = seconds ?: 4.0
        if (!s.isFinite() || s < 0 || s > MAX_REQUEST_S) return null to "seconds must be 0 (until changed) to ${MAX_REQUEST_S.toInt()}"
        return Request(e, if (s == 0.0) Long.MAX_VALUE else now + (s * 1000).toLong()) to null
    }

    fun mode(v: Double) = when (v.toInt()) { 1 -> Mode.IDLE; 2 -> Mode.ROBOT; else -> Mode.OFF }

    /**
     * Seconds without a touch before the face comes up. In robot mode the face is the main screen, so it comes back
     * quickly after someone has used the controls.
     */
    fun idleSeconds(mode: Mode, setting: Double) = when (mode) {
        Mode.OFF -> Double.POSITIVE_INFINITY
        Mode.IDLE -> setting.coerceAtLeast(5.0)
        Mode.ROBOT -> setting.coerceIn(3.0, 10.0)
    }
}
