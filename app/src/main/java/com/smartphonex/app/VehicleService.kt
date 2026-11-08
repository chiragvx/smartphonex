package com.smartphonex.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.smartphonex.core.AppData
import com.smartphonex.core.Realtime
import com.smartphonex.core.DeviceSchema
import com.smartphonex.core.VehicleRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Keeps the vehicle computer alive with the screen off or the app in the background. */
class VehicleService : Service() {
    private var server: GroundServer? = null
    private var relay: RelayClient? = null
    private var net: NetMonitor? = null
    private var sensors: AndroidSensors? = null
    private var camera: CameraStreamer? = null
    private var mavlink: MavlinkBridge? = null
    private var recorder: VideoRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var diagnostics: Diagnostics? = null
    private var updates: Updates? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartphonex:control").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            // Low latency keeps Wi-Fi from sleeping between beacons, which otherwise holds incoming commands for ~100 ms.
            // Android only honours it while the app is in front with the screen on, which is why the app keeps the screen on.
            .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "smartphonex:link").apply { acquire() }
        Realtime.enter = Priority.forApp(this)
        // Heat: tell the flight controller and shed camera frames before Android throttles the CPU. Never the control loop.
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val pm = getSystemService(PowerManager::class.java)
            pm.addThermalStatusListener(mainExecutor) { status ->
                CameraStreamer.shed = when { status >= 4 -> 0.25; status >= 3 -> 0.5; else -> 1.0 }
                runtime?.log?.event("app", "thermal", if (status >= 3) "problem" else "ok", "Android thermal status $status" + if (status >= 3) ": camera frames reduced" else "")
            }
        }
        Diagnostics.installCrashHandler(filesDir)
        startAll()
        instance = this
    }

    private fun startAll() {
        // Before anything reads a file: apply a staged restore, then bring saved files up to this app's data version.
        val data = AppData.prepare(filesDir, BuildConfig.VERSION_NAME)
        val rt = VehicleRuntime(filesDir)
        runtime = rt
        rt.log.event("app", "data", if (data.problem == null) "ok" else "problem",
            (if (data.restored) "restored a backup. " else "") + "data version ${data.from}" + (if (data.to != data.from) " to ${data.to}" else "") + (data.problem?.let { ": $it" } ?: ""))
        rt.start()
        val diag = Diagnostics(this, rt).also { diagnostics = it; it.recordExits() }
        val upd = Updates(rt).also { updates = it; it.start() }
        rt.ops.appChecks = listOf({ diag.check() }, { upd.check(true) }, { Priority.backgroundCheck(this) })
        rt.transports = AndroidTransports(this)
        rt.assistant = Assistant(rt, filesDir)
        seedPacks(rt)
        val mav = MavlinkBridge(rt, filesDir).also { mavlink = it; it.start() }
        rt.mavlink = mav::setHost
        val cam = CameraStreamer(this, rt).also { camera = it; cameraStreamer = it }
        rt.camera = cam
        rt.ops.geotag = { f, g ->
            android.media.ExifInterface(f.absolutePath).apply {
                fun dms(v: Double): String {
                    val a = Math.abs(v)
                    val d = a.toInt()
                    val m = ((a - d) * 60).toInt()
                    val sec = Math.round((a - d - m / 60.0) * 3600 * 1000)
                    return "$d/1,$m/1,$sec/1000"
                }
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, dms(g.lat))
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, if (g.lat < 0) "S" else "N")
                setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, dms(g.lon))
                setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, if (g.lon < 0) "W" else "E")
                setAttribute(android.media.ExifInterface.TAG_GPS_ALTITUDE, "${Math.round(Math.abs(g.altM) * 10)}/10")
                setAttribute(android.media.ExifInterface.TAG_GPS_ALTITUDE_REF, if (g.altM < 0) "1" else "0")
                saveAttributes()
            }
        }
        recorder = VideoRecorder(rt, cam).also { it.start() }
        rt.fc.nowNs = android.os.SystemClock::elapsedRealtimeNanos
        val phoneSensors = AndroidSensors(this, rt).also { sensors = it }
        rt.sensors.onConfigChanged = {
            phoneSensors.sync()
            cam.reconcile()
        }
        phoneSensors.start()
        val client = RelayClient(rt, filesDir, cam).also { relay = it; relayClient = it }
        client.start()
        net = NetMonitor(this, rt, client::networkChanged).also { it.start() }
        server = GroundServer(rt, client, cam, { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }, ::restartSoon, diag, upd).also { it.start() }
    }

    private fun stopAll() {
        updates?.stop()
        recorder?.stop()
        server?.stop()
        net?.stop()
        relay?.stop()
        relayClient = null
        sensors?.stop()
        mavlink?.stop()
        camera?.stop()
        cameraStreamer = null
        runtime?.stop()
        runtime = null
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var restarting = false

    /** Rebuilds everything from the saved files, after the reply that asked for it has been sent. Used to finish a restore. */
    fun restartSoon() {
        if (restarting) return
        restarting = true
        main.postDelayed({
            runCatching { stopAll() }
            Thread.sleep(300)
            startAll()
            restarting = false
        }, 800)
    }

    /** Example devices that ship with the app. They start at preview, like any imported pack. */
    private fun seedPacks(rt: VehicleRuntime) {
        val names = runCatching { assets.list("packs") }.getOrNull() ?: return
        for (n in names) runCatching {
            val text = assets.open("packs/$n").use { it.readBytes().decodeToString() }
            val parsed = DeviceSchema.parse(Json.parseToJsonElement(text).jsonObject).first ?: return@runCatching
            if (rt.devices.get(parsed.id) == null) {
                val fresh = DeviceSchema.forImport(parsed)
                rt.devices.put(fresh)
                rt.registerDevice(fresh)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        stopAll()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /** Called after the person answers Android's permission prompts. */
    fun permissionsChanged() {
        goForeground()
        sensors?.permissionsChanged()
        camera?.reconcile()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val channel = "vehicle"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "Vehicle controller", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, channel)
            .setContentTitle("SmartPhoneX vehicle controller")
            .setContentText("Control loop and ground station running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT < 34) {
            startForeground(1, n)
            return
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(1, n, types)
        } catch (e: RuntimeException) {
            // Android refuses camera and location types when restarting in the background, as after
            // being killed. Vehicle control must still come back, so run without them for now.
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    companion object {
        @Volatile var runtime: VehicleRuntime? = null
        @Volatile var relayClient: RelayClient? = null
        @Volatile var cameraStreamer: CameraStreamer? = null
        @Volatile var instance: VehicleService? = null
    }
}
