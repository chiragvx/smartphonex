package com.smartphonex.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.smartphonex.core.GpsFix
import com.smartphonex.core.Priority
import com.smartphonex.core.SensorConfig
import com.smartphonex.core.SensorInfo
import com.smartphonex.core.VehicleRuntime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Discovers whatever this particular device has and feeds it to the hub. Hardware is only kept
 * running while its sensor is enabled, which also saves battery.
 */
class AndroidSensors(private val context: Context, private val rt: VehicleRuntime) {
    private class Known(val label: String, val group: String, val unit: String, val axes: List<String>, val defaultOn: Boolean, val defaultHz: Double, val priority: Int)

    private val sm = context.getSystemService(SensorManager::class.java)
    private val lm = context.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private val hardware = LinkedHashMap<String, Sensor>()
    private val listeners = HashMap<String, SensorEventListener>()
    private val rates = HashMap<String, Double>()
    private var locationOn = false
    private var satellites = 0
    private var lastGpsFixMs = 0L

    private val xyz = listOf("x", "y", "z")
    private val known = mapOf(
        Sensor.TYPE_ACCELEROMETER to Known("Accelerometer", "motion", "m/s²", xyz, true, 10.0, Priority.HIGH),
        Sensor.TYPE_GYROSCOPE to Known("Gyroscope", "motion", "rad/s", xyz, true, 10.0, Priority.HIGH),
        Sensor.TYPE_ROTATION_VECTOR to Known("Rotation vector", "motion", "", listOf("x", "y", "z", "w"), true, 10.0, Priority.HIGH),
        Sensor.TYPE_LINEAR_ACCELERATION to Known("Linear acceleration", "motion", "m/s²", xyz, false, 5.0, Priority.NORMAL),
        Sensor.TYPE_GRAVITY to Known("Gravity", "motion", "m/s²", xyz, false, 5.0, Priority.LOW),
        Sensor.TYPE_GAME_ROTATION_VECTOR to Known("Game rotation", "motion", "", listOf("x", "y", "z", "w"), false, 5.0, Priority.LOW),
        Sensor.TYPE_MAGNETIC_FIELD to Known("Magnetometer", "position", "µT", xyz, true, 5.0, Priority.NORMAL),
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to Known("Compass rotation", "position", "", listOf("x", "y", "z", "w"), false, 5.0, Priority.LOW),
        Sensor.TYPE_PRESSURE to Known("Barometer", "environment", "hPa", listOf("pressure"), true, 2.0, Priority.NORMAL),
        Sensor.TYPE_LIGHT to Known("Light", "environment", "lx", listOf("light"), true, 2.0, Priority.LOW),
        Sensor.TYPE_PROXIMITY to Known("Proximity", "environment", "cm", listOf("distance"), true, 2.0, Priority.LOW),
        Sensor.TYPE_AMBIENT_TEMPERATURE to Known("Air temperature", "environment", "°C", listOf("temp"), true, 1.0, Priority.LOW),
        Sensor.TYPE_RELATIVE_HUMIDITY to Known("Humidity", "environment", "%", listOf("humidity"), true, 1.0, Priority.LOW),
    )

    /** These fire once, or need permissions unrelated to vehicles, so they're listed but not used. */
    private val blocked = mapOf(
        Sensor.TYPE_HEART_RATE to "needs a body sensor permission this app doesn't request",
        Sensor.TYPE_HEART_BEAT to "needs a body sensor permission this app doesn't request",
        Sensor.TYPE_STEP_COUNTER to "needs an activity permission this app doesn't request",
        Sensor.TYPE_STEP_DETECTOR to "needs an activity permission this app doesn't request",
        Sensor.TYPE_SIGNIFICANT_MOTION to "fires only once, so it can't be streamed",
    )

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private val flightThread = HandlerThread("flight-sensors", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val flightHandler = Handler(flightThread.looper)
    private val flightListeners = ArrayList<SensorEventListener>()

    /**
     * The flight controller's own sensor feed. It always runs at full rate, whatever the
     * streaming settings say, because those only decide what's sent over the link.
     */
    private fun startFlightSensors() {
        fun feed(type: Int, periodUs: Int, onEvent: (SensorEvent) -> Unit) {
            val s = sm.getDefaultSensor(type) ?: return
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) = onEvent(e)
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(l, s, periodUs, flightHandler)
            flightListeners += l
        }
        val fc = rt.fc
        feed(Sensor.TYPE_GYROSCOPE, 5000) { fc.onGyro(it.timestamp, it.values[0], it.values[1], it.values[2]) }
        feed(Sensor.TYPE_ACCELEROMETER, 5000) { fc.onAccel(it.values[0], it.values[1], it.values[2]) }
        feed(Sensor.TYPE_MAGNETIC_FIELD, 20000) { fc.onMag(it.values[0], it.values[1], it.values[2]) }
        feed(Sensor.TYPE_ROTATION_VECTOR, 20000) { e ->
            fc.onRotationVector(e.values[0], e.values[1], e.values[2], if (e.values.size > 3) e.values[3] else Float.NaN)
        }
        feed(Sensor.TYPE_PRESSURE, 100000) { fc.onBaro(it.values[0]) }
    }

    fun start() {
        startFlightSensors()
        discover()
        sync()
        poller.scheduleWithFixedDelay({ runCatching { pollDeviceState() } }, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        listeners.values.forEach { sm.unregisterListener(it) }
        listeners.clear()
        flightListeners.forEach { sm.unregisterListener(it) }
        flightListeners.clear()
        flightThread.quitSafely()
        stopLocation()
        poller.shutdownNow()
        thread.quitSafely()
    }

    private fun idOf(s: Sensor) = "s${s.type}_" + s.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(40)

    private fun discover() {
        // A wake-up copy reads the same as the normal sensor and costs more battery.
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        val normal = all.filterNot { it.isWakeUpSensor }
        val usable = normal + all.filter { w -> w.isWakeUpSensor && normal.none { it.type == w.type } }
        val seen = HashSet<String>()
        for (s in usable.sortedBy { if (it.type in known) it.type else 1000 + it.type }) {
            val id = idOf(s)
            if (!seen.add(id)) continue
            val k = known[s.type]
            val reason = blocked[s.type] ?: if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) "fires only once, so it can't be streamed" else null
            // Only the first sensor of a known type starts on, or duplicates would waste data.
            val firstOfType = hardware.values.none { it.type == s.type }
            rt.sensors.register(
                SensorInfo(id, k?.label?.let { if (firstOfType) it else "$it (${s.name})" } ?: s.name, k?.group ?: "other", k?.unit ?: "", k?.axes ?: emptyList(), s.vendor ?: "", unavailableReason = reason),
                SensorConfig(k?.defaultOn == true && firstOfType && reason == null, k?.defaultHz ?: 2.0, k?.priority ?: Priority.LOW),
            )
            if (reason == null) hardware[id] = s
        }
        registerVirtual()
    }

    private fun registerVirtual() {
        val locReason = when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) -> "this device has no location hardware"
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "allow location for SmartPhoneX on the phone"
            else -> null
        }
        rt.sensors.register(
            SensorInfo("phone.location", "Location", "position", "", listOf("lat", "lon", "alt m", "speed m/s", "bearing °", "accuracy m", "satellites"), isPrivate = true, unavailableReason = locReason, decimals = 7),
            SensorConfig(false, 1.0, Priority.NORMAL),
        )
        rt.sensors.register(
            SensorInfo("vision.line", "Line tracker (camera)", "vision", "", listOf("found", "position")),
            SensorConfig(true, 10.0, Priority.NORMAL),
        )
        rt.sensors.register(
            SensorInfo("phone.battery", "Phone battery", "device", "", listOf("%", "°C", "volts", "charging"), decimals = 2),
            SensorConfig(true, 0.5, Priority.NORMAL),
        )
        rt.sensors.register(
            SensorInfo("phone.thermal", "Phone thermal state", "device", "", listOf("status 0-6"), decimals = 0),
            SensorConfig(true, 0.5, Priority.NORMAL),
        )
    }

    /** Re-checks location after the person answers Android's permission prompt. */
    fun permissionsChanged() {
        registerVirtual()
        sync()
    }

    /** Makes the hardware match the settings. Safe to call at any time. */
    @Synchronized fun sync() {
        for ((id, s) in hardware) {
            val cfg = rt.sensors.config(id) ?: continue
            val running = listeners[id]
            if (!cfg.enabled) {
                if (running != null) { sm.unregisterListener(running); listeners.remove(id) }
                continue
            }
            if (running != null && rates[id] == cfg.rateHz) continue
            if (running != null) sm.unregisterListener(running)
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) = rt.sensors.update(id, e.values)
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            // Read at twice the send rate, so a fresh value is ready at each send.
            val periodUs = (1_000_000 / (cfg.rateHz * 2)).toInt().coerceAtLeast(5000)
            sm.registerListener(l, s, periodUs, handler)
            listeners[id] = l
            rates[id] = cfg.rateHz
        }
        val loc = rt.sensors.config("phone.location")
        if (loc?.enabled == true) startLocation() else stopLocation()
    }

    /** Only here to ask for full tracking. The raw measurements themselves aren't used yet. */
    private val measurements = object : android.location.GnssMeasurementsEvent.Callback() {}

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            // A GPS fix wins over a rough network position while GPS is working.
            if (l.provider == LocationManager.GPS_PROVIDER) lastGpsFixMs = System.currentTimeMillis()
            else if (System.currentTimeMillis() - lastGpsFixMs < 5000) return
            val declination = android.hardware.GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble()
            rt.fc.onGps(GpsFix(l.latitude, l.longitude, l.altitude, l.speed.toDouble(), l.bearing.toDouble(), l.accuracy.toDouble(), satellites, System.currentTimeMillis()), declination,
                fromSatellites = l.provider == LocationManager.GPS_PROVIDER)
            rt.sensors.update("phone.location", doubleArrayOf(
                l.latitude, l.longitude, l.altitude, l.speed.toDouble(), l.bearing.toDouble(), l.accuracy.toDouble(), satellites.toDouble(),
            ))
        }
        @Deprecated("Required on older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellites = (0 until status.satelliteCount).count { status.usedInFix(it) }
            // The four strongest satellites: the usual measure of how good the sky view is.
            val best = (0 until status.satelliteCount).map { status.getCn0DbHz(it).toDouble() }.sortedDescending().take(4)
            rt.fc.gnssSignal = if (best.isEmpty()) null else best.average()
        }
    }

    @SuppressLint("MissingPermission") // Checked just below.
    private fun startLocation() {
        if (locationOn || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val providers = lm.getProviders(true)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (p in providers) lm.requestLocationUpdates(p, 1000L, 0f, locationListener, thread.looper)
            }
            lm.registerGnssStatusCallback(gnssCallback, handler)
            // Keep the GPS chip tracking all the time instead of duty-cycling to save power, which causes jumps and
            // dropped satellites. Costs some battery while Location is on. Android 12 and later.
            if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
                lm.registerGnssMeasurementsCallback(android.location.GnssMeasurementRequest.Builder().setFullTracking(true).build(), context.mainExecutor, measurements)
            }
            locationOn = true
        } catch (e: Exception) {
            rt.log.event("sensor", "phone", "location_error", e.javaClass.simpleName)
        }
    }

    private fun stopLocation() {
        if (!locationOn) return
        runCatching { lm.removeUpdates(locationListener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { lm.unregisterGnssMeasurementsCallback(measurements) }
        locationOn = false
    }

    private fun pollDeviceState() {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            rt.sensors.update("phone.battery", doubleArrayOf(
                b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100.0 / scale,
                b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
                b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0,
                if (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) 1.0 else 0.0,
            ))
        }
        var thermal = 0
        if (Build.VERSION.SDK_INT >= 29) {
            thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
            rt.sensors.update("phone.thermal", doubleArrayOf(thermal.toDouble()))
        }
        // A device with no battery (a TV box, or a board on mains power) reports level 0. That's not a flat battery.
        if (b != null) rt.fc.onDeviceState(
            if (!b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) null
            else b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100.0 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1),
            thermal)
    }
}
