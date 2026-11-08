package com.smartphonex.core

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.locks.LockSupport

/** Fault injection for the link layer and the phone itself. All of it is off by default. */
class Faults {
    @Volatile var delayMs = 0L
    @Volatile var lossPct = 0
    @Volatile var mcpDown = false
    @Volatile var linkBlockedUntil = 0L
    @Volatile var stallMs = 0L
    @Volatile var cpuLoadUntil = 0L

    fun startCpuLoad(threads: Int, seconds: Int) {
        cpuLoadUntil = System.currentTimeMillis() + seconds * 1000L
        repeat(threads) {
            Thread({
                var x = 1.0
                while (System.currentTimeMillis() < cpuLoadUntil) x = Math.sqrt(x + 1.0)
            }, "cpu-load-$it").apply { isDaemon = true; start() }
        }
    }
}

/** Fixed-rate control thread. It tolerates late ticks by using the real elapsed time as dt. */
class ControlLoop(
    private val controller: VehicleController,
    private val faults: Faults,
    private val onSample: (Telemetry) -> Unit,
    private val onTick: (Telemetry) -> Unit = {},
    private val hz: Int = 100,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::run, "control-loop").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
    }

    private fun run() {
        val period = 1_000_000_000L / hz
        val report = runCatching { Realtime.enter("control-loop", period) }.getOrNull()
        var last = System.nanoTime()
        var next = last + period
        var count = 0
        var sumMs = 0.0
        var maxMs = 0.0
        var overruns = 0L
        var windowStart = last
        var sampleEvery = 0
        while (running) {
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            val stall = faults.stallMs
            if (stall > 0) {
                faults.stallMs = 0
                Thread.sleep(stall)
            }
            val nowN = System.nanoTime()
            val dtMs = (nowN - last) / 1e6
            last = nowN
            next += period
            if (nowN - next > period * 5) next = nowN + period
            if (dtMs > 2000.0 / hz) overruns++
            count++; sumMs += dtMs; if (dtMs > maxMs) maxMs = dtMs
            if (nowN - windowStart >= 1_000_000_000L) {
                controller.setLoopStats(LoopStats(count * 1e9 / (nowN - windowStart), sumMs / count, maxMs, overruns))
                count = 0; sumMs = 0.0; maxMs = 0.0; windowStart = nowN
            }
            val t = controller.tick(dtMs / 1000.0)
            onTick(t)
            if (++sampleEvery >= hz / 10) { sampleEvery = 0; onSample(t) }
            report?.let { r -> (System.nanoTime() - nowN).takeIf { it > 0 }?.let { runCatching { r(it) } } }
        }
    }
}

/** Everything that makes up the vehicle computer, independent of Android and of any UI. */
/** How the links talk to the assistant, without knowing about HTTP or key storage. */
interface AssistantPort {
    fun configJson(): JsonObject
    fun onMessage(o: JsonObject, send: (String) -> Unit)
}

class VehicleRuntime(val dir: File) {
    val log = EventLog()
    private val simulator = SimulatedCar(System.nanoTime())
    private val preview = OutputPreview()
    val profiles = ProfileStore(File(dir, "profiles.json"))
    val params = ParamStore(File(dir, "params.json")) { profiles.activeId }
    val fc = FlightController(params, log, { profiles.active.vehicleType }).also { it.maxSpeedMps = { profiles.active.maxSpeedMps } }
    val flightLog = FlightLog(File(dir, "logs"))
    private lateinit var opsHolder: Ops
    val ops: Ops get() = opsHolder
    /** How many ground station connections are open right now. */
    val groundLinks = java.util.concurrent.atomic.AtomicInteger()
    private val rootDir = dir
    val devices = DeviceStore(File(dir, "devices"))
    val engine = DeviceEngine(log, { fc.attitudeNow })
    /** Set by the Android layer when USB serial is available. Null leaves hardware closed. */
    @Volatile var transports: TransportProvider? = null
        set(value) { field = value; engine.hardware = value?.let { HardwareLink(it, log) } }
    val setup by lazy { SetupSession(this) }
    val receiver by lazy { ReceiverBridge(this) }
    val behaviors = BehaviorRunner(
        readValue = ::behaviorValue,
        runSkill = { engine.runSkill(it) },
        skillRunning = { engine.runningSkill != null },
        stopSkill = { engine.stopSkill() },
    )
    val controller = VehicleController(preview, profiles, log, adapterFor = { kind ->
        when (kind) {
            "simulator" -> simulator
            "preview" -> preview
            "device" -> engine.also {
                val d = devices.get(profiles.activeId.removePrefix("device:"))
                it.load(d)
                receiver.start(d)
            }
            else -> null
        }
    }, fc = fc, ops = { if (::opsHolder.isInitialized) opsHolder else null }, deviceHooks = object : VehicleController.DeviceHooks {
        override fun armProblem(): String? {
            val d = engine.device?.takeIf { profiles.active.adapter == "device" } ?: return null
            if (d.commissioning.signedOffBy == null) return null
            return DeviceSchema.fitProblem(d.deviceClass, fc.gyroHz, fc.loopHz)
        }
        override fun inputs(values: Map<String, Double>, ms: Long) = values.forEach { (k, v) -> engine.setNamedInput(k, v, ms) }
        override fun runSkill(id: String) = if (profiles.active.adapter == "device") engine.runSkill(id) else "no device is selected"
        override fun stopSkill() = engine.stopSkill()
        override fun runBehavior(id: String): String? {
            val b = engine.device?.behaviors?.firstOrNull { it.id == id } ?: return "no behavior $id"
            behaviors.start(id, b.tree)
            return null
        }
        override fun stopBehavior() = behaviors.stop()
    })

    init {
        opsHolder = Ops(this, rootDir)
        fc.missions = opsHolder.missions
        engine.trimFor = { opsHolder.trims.get(it) }
        engine.overrideFor = { opsHolder.overrideFor(it) }
    }

    /** Values a behavior may read. */
    private fun behaviorValue(name: String): Double? {
        val p = name.split(":")
        return when (p[0]) {
            "attitude" -> fc.attitudeNow.let { a -> when (p.getOrNull(1)) { "roll" -> a.roll; "pitch" -> a.pitch; "heading" -> a.yaw; else -> null } }
            "sensor" -> sensors.valueOf(p.getOrNull(1) ?: "", p.getOrNull(2)?.toIntOrNull() ?: 0)
            "output" -> controller.latest().outputs.firstOrNull { it.name == p.getOrNull(1) }?.value
            else -> null
        }
    }

    /** Makes a device selectable alongside the vehicle presets. Hazard level decides the AI's freedom. */
    fun registerDevice(d: DeviceDescription) {
        val aiAllowed = d.aiAutonomy != "none" && d.hazard != "heavy" && d.hazard != "flying"
        profiles.addProfile(VehicleProfile(
            "device:${d.id}", d.name, maxThrottle = 1.0, maxSteering = 1.0, vehicleType = if (d.deviceClass == "fixed-wing") "plane" else d.deviceClass, adapter = "device",
            channels = d.actuators.take(16).map { a ->
                Channel(a.id, ChannelKind.SERVO, a.mix["forward"] ?: 0.0, a.mix["turn"] ?: 0.0, a.mix["pitch"] ?: 0.0, a.mix["roll"] ?: 0.0)
            }.map { c -> c.copy(throttle = c.throttle.coerceIn(-2.0, 2.0), yaw = c.yaw.coerceIn(-2.0, 2.0)) },
            missionsAllowed = false, aiMayArm = aiAllowed,
        ))
    }
    val sensors = SensorHub(File(dir, "sensors.json"))
    /** Sets the MAVLink telemetry destination. Returns an error message, or null. */
    @Volatile var mavlink: ((String) -> String?)? = null
    /** The built-in setup assistant, set by the Android layer. */
    @Volatile var assistant: AssistantPort? = null
    /** Set by the Android layer. Null where no camera service runs, such as in unit tests. */
    @Volatile var camera: CameraAccess? = null
    private val sensorListeners = java.util.concurrent.CopyOnWriteArrayList<(SensorPayload) -> Unit>()
    @Volatile private var sensorThreadRunning = false
    @Volatile private var started = false
    val faults = Faults()
    val token: String = loadToken(File(dir, "token.txt"))
    val pairing = Pairing(token)
    /** Latest network and relay state, set by the Android layer and read by the links. */
    @Volatile var net: JsonObject = JsonObject(emptyMap())
    @Volatile var relay: JsonObject = JsonObject(emptyMap())
    private val history = ArrayDeque<Telemetry>()
    private val loop = ControlLoop(controller, faults, ::addSample, onTick = ::logTick)

    /** Runs after every control step. Queueing a row never waits for the disk. */
    private fun logTick(t: Telemetry) {
        val armed = t.mode == Mode.MANUAL || t.mode == Mode.AUTO
        val want = armed || params.get("LOG_ALWAYS") >= 1.0
        if (want && !flightLog.running) flightLog.start(System.currentTimeMillis())
        if (!want && flightLog.running) flightLog.stop()
        if (flightLog.running) flightLog.add(fc.logRow())
        ops.tick(t)
    }
    private var localSeq = 0L

    @Synchronized private fun addSample(t: Telemetry) {
        history.addLast(t)
        if (history.size > HISTORY_SAMPLES) history.removeFirst()
    }

    @Synchronized fun history(last: Int = Int.MAX_VALUE): List<Telemetry> = history.toList().takeLast(last)

    fun exportCsv(name: String): String? = when (name) {
        "telemetry.csv" -> Codec.telemetryCsv(history())
        "commands.csv" -> Codec.logCsv(log.allCommands())
        "events.csv" -> Codec.logCsv(log.allEvents())
        "sensors.csv" -> sensors.csv()
        "flight.csv" -> flightLog.latestCsv()
        "reports.json" -> ops.reportsJson().toString()
        else -> null
    }

    fun addSensorListener(l: (SensorPayload) -> Unit) { sensorListeners += l }
    fun removeSensorListener(l: (SensorPayload) -> Unit) { sensorListeners -= l }

    fun start() {
        log.event("system", "controller", "start", "control loop started")
        loop.start()
        devices.all().forEach(::registerDevice)
        Thread({
            while (sensorThreadRunning || !started) {
                runCatching { behaviors.tick() }
                Thread.sleep(50)
            }
        }, "behaviors").apply { isDaemon = true; start() }
        started = true
        // Separate from the control loop, so sensors can never delay it.
        sensorThreadRunning = true
        Thread({
            while (sensorThreadRunning) {
                sensors.tick()?.let { p -> sensorListeners.forEach { it(p) } }
                Thread.sleep(50)
            }
        }, "sensor-scheduler").apply { isDaemon = true; start() }
    }

    fun stop() {
        sensorThreadRunning = false
        flightLog.stop()
        loop.stop()
        controller.currentAdapter.safeStop()
    }

    /** Commands from the phone's own screen. They need no network at all. */
    fun local(c: Command) = controller.submit(Envelope(Source.LOCAL, ++localSeq, null, c))

    private fun loadToken(f: File): String {
        if (f.exists()) f.readText().trim().takeIf { it.length >= 16 }?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        f.writeText(t)
        return t
    }

    private companion object { const val HISTORY_SAMPLES = 18000 } // 30 minutes at 10 Hz
}
