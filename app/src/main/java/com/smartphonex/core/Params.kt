package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File

data class ParamDef(
    val name: String,
    val group: String,
    val description: String,
    val default: Double,
    val min: Double,
    val max: Double,
    /** Different starting values for some vehicle types. */
    val byType: Map<String, Double> = emptyMap(),
)

/** Every setting of the flight controller: named, bounded, and saved per vehicle. */
class ParamStore(private val file: File?, private val vehicle: () -> String) {
    private val saved = HashMap<String, HashMap<String, Double>>()

    init { load() }

    private fun defaultOf(d: ParamDef, type: String) = d.byType[type] ?: d.default

    @Synchronized fun get(name: String, vehicleType: String = ""): Double {
        val d = BY_NAME[name] ?: error("unknown parameter $name")
        return saved[vehicle()]?.get(name) ?: defaultOf(d, vehicleType)
    }

    /** Returns an error message, or null when saved. */
    @Synchronized fun set(name: String, value: Double): String? {
        val d = BY_NAME[name] ?: return "unknown parameter"
        if (!value.isFinite() || value !in d.min..d.max) return "$name must be ${d.min} to ${d.max}"
        saved.getOrPut(vehicle()) { HashMap() }[name] = value
        save()
        return null
    }

    @Synchronized fun toJson(vehicleType: String) = buildJsonArray {
        for (d in ALL) addJsonObject {
            put("name", d.name); put("group", d.group); put("description", d.description)
            put("value", get(d.name, vehicleType)); put("min", d.min); put("max", d.max)
        }
    }

    private fun save() {
        val f = file ?: return
        val json = buildJsonObject {
            for ((v, m) in saved) putJsonObject(v) { m.forEach { (k, x) -> put(k, x) } }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            Json.parseToJsonElement(f.readText()).jsonObject.forEach { (v, o) ->
                val m = saved.getOrPut(v) { HashMap() }
                o.jsonObject.forEach { (k, x) -> if (k in BY_NAME) m[k] = x.jsonPrimitive.double }
            }
        }
    }

    companion object {
        val ALL = listOf(
            ParamDef("MOUNT_TYPE", "Mounting", "0 flat, screen up. 1 upright, camera forward. 2 sideways, camera forward. 3 flat, screen down", 0.0, 0.0, 3.0),
            ParamDef("MOUNT_YAW", "Mounting", "Extra yaw of the mounting, degrees", 0.0, -180.0, 180.0),
            ParamDef("TRIM_ROLL", "Mounting", "Level trim for roll, degrees. Set with Set level", 0.0, -45.0, 45.0),
            ParamDef("TRIM_PITCH", "Mounting", "Level trim for pitch, degrees. Set with Set level", 0.0, -45.0, 45.0),
            ParamDef("AHRS_KP", "Estimator", "How fast gravity corrects attitude", 1.0, 0.0, 10.0),
            ParamDef("AHRS_KI", "Estimator", "How fast gyro drift is learned", 0.05, 0.0, 1.0),
            ParamDef("AHRS_MAG", "Estimator", "How fast the compass corrects heading", 0.5, 0.0, 5.0),
            ParamDef("GYRO_BX", "Estimator", "Gyro offset X, rad/s. Set with Calibrate gyro", 0.0, -1.0, 1.0),
            ParamDef("GYRO_BY", "Estimator", "Gyro offset Y, rad/s. Set with Calibrate gyro", 0.0, -1.0, 1.0),
            ParamDef("GYRO_BZ", "Estimator", "Gyro offset Z, rad/s. Set with Calibrate gyro", 0.0, -1.0, 1.0),
            ParamDef("ANG_MAX_ROLL", "Stabilize", "Largest commanded bank, degrees", 45.0, 5.0, 80.0),
            ParamDef("ANG_MAX_PITCH", "Stabilize", "Largest commanded pitch, degrees", 25.0, 5.0, 60.0),
            ParamDef("RATE_MAX", "Stabilize", "Largest commanded rate, degrees/s", 90.0, 10.0, 720.0),
            ParamDef("ANG_P", "Stabilize", "Angle error to rate command", 3.0, 0.0, 20.0),
            ParamDef("ROLL_P", "Rate control", "Roll rate P", 0.01, 0.0, 1.0),
            ParamDef("ROLL_I", "Rate control", "Roll rate I", 0.002, 0.0, 1.0),
            ParamDef("ROLL_D", "Rate control", "Roll rate D", 0.0, 0.0, 0.2),
            ParamDef("PITCH_P", "Rate control", "Pitch rate P", 0.01, 0.0, 1.0),
            ParamDef("PITCH_I", "Rate control", "Pitch rate I", 0.002, 0.0, 1.0),
            ParamDef("PITCH_D", "Rate control", "Pitch rate D", 0.0, 0.0, 0.2),
            ParamDef("YAW_P", "Rate control", "Yaw rate P", 0.01, 0.0, 1.0),
            ParamDef("YAW_I", "Rate control", "Yaw rate I", 0.002, 0.0, 1.0),
            ParamDef("YAW_D", "Rate control", "Yaw rate D", 0.0, 0.0, 0.2),
            ParamDef("I_MAX", "Rate control", "Largest I-term output. Kept low: in hand tests the surfaces can't move the phone", 0.1, 0.0, 1.0),
            ParamDef("HDG_P", "Navigation", "Heading error to bank (plane) or turn rate (ground), per degree", 1.0, 0.0, 10.0),
            ParamDef("RTH_THROTTLE", "Navigation", "Throttle while returning home", 0.4, 0.0, 1.0),
            ParamDef("RTH_RADIUS", "Navigation", "Arrived-home distance, metres", 10.0, 1.0, 500.0),
            ParamDef("RTH_ORBIT", "Navigation", "Bank while orbiting home (plane), degrees", 20.0, 0.0, 45.0),
            ParamDef("WP_THROTTLE", "Navigation", "Throttle on the way to a waypoint", 0.4, 0.0, 1.0),
            ParamDef("AUTO_DISARM", "Failsafe", "Disarm after this many idle seconds. 0 turns it off", 120.0, 0.0, 3600.0, mapOf("plane" to 0.0)),
            ParamDef("ARM_REQ_TEST", "Failsafe", "1: arming needs a passing test report from the last hour", 0.0, 0.0, 1.0),
            ParamDef("ARM_REQ_CHECK", "Failsafe", "1: arming needs the checklist finished", 0.0, 0.0, 1.0),
            ParamDef("TEST_MAX_SERVO", "Failsafe", "Largest output test on a servo, as a fraction of range", 0.5, 0.0, 1.0),
            ParamDef("TEST_MAX_MOTOR", "Failsafe", "Largest output test on a motor", 0.15, 0.0, 0.5),
            ParamDef("FS_LINK", "Failsafe", "On link loss in an assisted mode: 0 stop, 1 return home", 0.0, 0.0, 1.0, mapOf("plane" to 1.0, "boat" to 1.0)),
            ParamDef("FS_BATT_MIN", "Failsafe", "Phone battery % below which arming is refused", 10.0, 0.0, 90.0),
            ParamDef("FS_THERMAL", "Failsafe", "Android thermal status that raises a warning", 3.0, 1.0, 6.0),
            ParamDef("ARM_GPS_ACC", "Failsafe", "Worst GPS accuracy allowed for missions and Return Home, metres (68% radius, after smoothing)", 25.0, 1.0, 200.0),
            ParamDef("GPS_TIMEOUT_S", "Failsafe", "Seconds without a usable GPS fix before the position counts as lost: missions hold, Return Home fails safe", 3.0, 1.0, 10.0),
            ParamDef("NAV_WAIT_S", "Failsafe", "Seconds a mission waits, stopped, for GPS to become good enough before pausing for a person", 30.0, 5.0, 600.0),
            ParamDef("FENCE_MARGIN", "Failsafe", "Extra metres kept from the fence, on top of how unsure the GPS position is", 2.0, 0.0, 50.0),
            ParamDef("ARM_RATE_MIN", "Failsafe", "Lowest gyro rate allowed for assisted modes, Hz", 30.0, 1.0, 500.0),
            ParamDef("VIS_LINE", "Vision", "Line tracking from the camera: 0 off, 1 dark line, 2 bright line. Needs the camera on. Feeds the sensor vision.line", 0.0, 0.0, 2.0),
            ParamDef("TLM_HZ", "Telemetry", "Flight-state rate on the link, Hz", 20.0, 1.0, 50.0),
            ParamDef("LOG_ALWAYS", "Telemetry", "0 log while armed. 1 log always", 0.0, 0.0, 1.0),
            ParamDef("FACE_MODE", "Face", "Face on the phone's screen: 0 off, 1 after the screen is left alone, 2 robot (the face is the main screen)", 0.0, 0.0, 2.0),
            ParamDef("FACE_IDLE_S", "Face", "Seconds untouched before the face comes up. Robot mode uses at most 10", 60.0, 5.0, 3600.0),
            ParamDef("FACE_STYLE", "Face", "0 eyes, 1 eyes and mouth, 2 visor", 0.0, 0.0, 2.0),
            ParamDef("FACE_HUE", "Face", "Colour of the face, as a hue from 0 to 360. 190 is cyan", 190.0, 0.0, 360.0),
            ParamDef("FACE_BRIGHT", "Face", "Screen brightness while the face shows, 0.05 to 1", 0.6, 0.05, 1.0),
        )
        val BY_NAME = ALL.associateBy { it.name }
    }
}
