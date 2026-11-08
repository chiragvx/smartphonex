package com.smartphonex.core

import kotlin.math.abs

/**
 * The local authority. Every command path goes through submit(); only tick() touches the
 * adapter. Nothing here depends on a network, a browser or an AI being present.
 */
class VehicleController(
    initialAdapter: VehicleAdapter,
    private val profiles: ProfileStore,
    private val log: EventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Finds the adapter a profile asks for. Null keeps the current one. */
    private val adapterFor: (String) -> VehicleAdapter? = { null },
    /** The autopilot. Null in tests of the plain command path. */
    private val fc: FlightController? = null,
    /** Lets the device layer refuse arming and handle device commands. */
    private val deviceHooks: DeviceHooks? = null,
    private val ops: () -> Ops? = { null },
) {
    @Volatile var killed = false; private set
    private var lastMovedMs = 0L
    private var drillRunning = false

    /** Kill: everything off now. Only a person's deliberate arming clears it. */
    private fun kill(source: Source): CommandResult {
        if (source == Source.AI || source == Source.BEHAVIOR) return CommandResult.reject("not_permitted", "only a person can kill")
        killed = true
        deviceHooks?.stopBehavior()
        deviceHooks?.stopSkill()
        ops()?.missions?.abort()
        aiLocked = true
        failsafe("kill from $source")
        return CommandResult.OK
    }

    /** Starts AUTO for a mission that a person, or an allowed AI, has asked for. */
    fun startAuto(source: Source): CommandResult = synchronized(lock) {
        if (source == Source.AI && (aiLocked || !profiles.active.aiMayArm)) return CommandResult.reject("not_permitted", "AI can't start this")
        if (mode == Mode.FAILSAFE && source == Source.AI) return CommandResult.reject("not_permitted", "failsafe needs a human to clear it")
        if (source != Source.AI) { aiLocked = false; killed = false }
        ops()?.tester?.stop()
        enterMode(Mode.AUTO, "mission from $source")
        lastMovedMs = clock()
        CommandResult.OK
    }

    /** Test: press stop while armed in preview, and time how long outputs take to reach safe. */
    fun stopDrill(): Long? {
        synchronized(lock) {
            if (mode != Mode.DISARMED) return null
            drillRunning = true
            adapter.let { (it as? DeviceEngine)?.muted = true }
            manualThrottle = 0.0
            mode = Mode.MANUAL
            humanLastMs = clock()
        }
        Thread.sleep(150)
        val t0 = System.nanoTime()
        synchronized(lock) { enterMode(Mode.DISARMED, "stop test") }
        var result: Long? = null
        repeat(300) {
            if (result == null) {
                Thread.sleep(10)
                val l = synchronized(lock) { lastOutput }
                if (l.safe) result = (System.nanoTime() - t0) / 1_000_000
            }
        }
        synchronized(lock) { (adapter as? DeviceEngine)?.muted = false; drillRunning = false }
        return result
    }

    /** Test: arm in preview and send nothing, as if the link died. Returns how long the real logic took. */
    fun failsafeDrill(): Pair<Long?, String> {
        val wasLocked: Boolean
        synchronized(lock) {
            if (mode != Mode.DISARMED) return null to "Disarm first"
            wasLocked = aiLocked
            drillRunning = true
            (adapter as? DeviceEngine)?.muted = true
            mode = Mode.MANUAL
            humanLastMs = clock()
        }
        val t0 = System.nanoTime()
        var took: Long? = null
        var ended = ""
        repeat(300) {
            if (took == null) {
                Thread.sleep(10)
                synchronized(lock) { if (mode != Mode.MANUAL) { took = (System.nanoTime() - t0) / 1_000_000; ended = mode.name } }
            }
        }
        Thread.sleep(100)
        synchronized(lock) {
            ops()?.missions?.abort()
            fc?.setModeForce(FlightMode.MANUAL)
            enterMode(Mode.DISARMED, "failsafe test finished")
            aiLocked = wasLocked
            (adapter as? DeviceEngine)?.muted = false
            drillRunning = false
        }
        val limit = profiles.active.commandTimeoutMs
        val t = took ?: return null to "No reaction within 3 s"
        return t to "Reacted in $t ms (limit ${limit} ms), going to $ended"
    }
    interface DeviceHooks {
        fun armProblem(): String?
        fun inputs(values: Map<String, Double>, ms: Long)
        fun runSkill(id: String): String?
        fun stopSkill()
        fun runBehavior(id: String): String?
        fun stopBehavior()
    }
    private val router = InputRouter(clock)
    val inputOwner: Source? get() = router.owner()
    private var tickCount = 0L
    private var lastWould: List<Int> = emptyList()
    private val fcActive: Boolean get() = fc != null && adapter !is SimulatedCar
    private val lock = Any()
    @Volatile private var adapter: VehicleAdapter = adapterFor(profiles.active.adapter) ?: initialAdapter
    val currentAdapter: VehicleAdapter get() = adapter
    private var manualPitch = 0.0
    private var manualRoll = 0.0
    private var mode = Mode.DISARMED
    private var failsafeReason: String? = null
    /** Set when a human stops the car or a failsafe fires: the AI may not re-arm until a human does. */
    private var aiLocked = false
    private val lastSeq = HashMap<Source, Long>()

    private var humanLastMs = 0L
    private var manualThrottle = 0.0
    private var manualSteering = 0.0

    private var aiThrottle = 0.0
    private var aiThrottleUntil = 0L
    private var aiSteering = 0.0
    private var aiSteeringUntil = 0L
    private var aiSpeed = 0.0
    private var aiSpeedUntil = 0L

    private var missionName: String? = null
    private var missionSteps: List<MissionStep> = emptyList()
    private var missionIndex = 0
    private var missionStepStart = 0L

    private val pid = Pid()
    private var lastOutput = ActuatorOutput(0.0, 0.0)
    private var lastFrame = ""
    private var lastTarget: Double? = null
    private var sensors = SensorFrame(null, null, null, null, null, null, false)
    private var obstacleHold = false
    private var wasCollided = false

    private var accepted = 0L
    private var rejected = 0L
    private var procNanos = 0L
    private var procCount = 0L
    private var loop = LoopStats(0.0, 0.0, 0.0, 0)

    val capabilities: List<String> get() = adapter.capabilities
    val adapterId: String get() = adapter.id

    fun setLoopStats(s: LoopStats) = synchronized(lock) { loop = s }

    fun submit(env: Envelope): CommandResult = synchronized(lock) {
        val start = System.nanoTime()
        val r = evaluate(env)
        procNanos += System.nanoTime() - start
        procCount++
        record(env.source, env.command.toString(), r)
        r
    }

    /** Record a command that failed to parse, so malformed input is counted and logged too. */
    fun recordMalformed(source: Source, detail: String) = synchronized(lock) {
        record(source, "malformed", CommandResult.reject("malformed", detail))
    }

    private fun record(source: Source, text: String, r: CommandResult) {
        if (r.accepted) accepted++ else rejected++
        log.command("command", source.name, r.code, text)
        // A source streaming at full rate while someone else has control isn't worth an event each time.
        if (r.code != "not_owner" && (!r.accepted || !text.startsWith("Drive("))) {
            log.event(if (r.accepted) "command" else "rejected", source.name, r.code, if (r.accepted) text else "$text: ${r.message}")
        }
    }

    private fun evaluate(env: Envelope): CommandResult {
        val now = clock()
        val cmd = env.command
        // A stop is always safe, so a late or repeated stop is honoured instead of being rejected.
        if (cmd is Command.Stop) {
            env.seq?.let { lastSeq[env.source] = maxOf(lastSeq[env.source] ?: Long.MIN_VALUE, it) }
            return applyStop(env.source)
        }
        if (cmd is Command.Kill) {
            env.seq?.let { lastSeq[env.source] = maxOf(lastSeq[env.source] ?: Long.MIN_VALUE, it) }
            return kill(env.source)
        }
        val p = profiles.active
        env.seq?.let { seq ->
            val prev = lastSeq[env.source]
            if (prev != null && seq <= prev) return CommandResult.reject("duplicate", "seq $seq <= $prev")
        }
        env.sentAtMs?.let { sent ->
            val age = now - sent
            if (age > p.staleCommandMs) return CommandResult.reject("stale", "age ${age}ms")
            if (age < -p.staleCommandMs) return CommandResult.reject("stale", "timestamp ${-age}ms in the future")
        }
        env.seq?.let { lastSeq[env.source] = it }
        return when (cmd) {
            is Command.Drive -> drive(env.source, cmd, p, now)
            is Command.SetThrottle -> aiAxis(env.source) {
                if (abs(cmd.value) > p.maxThrottle) return@aiAxis outOfRange("throttle", p.maxThrottle)
                val lease = lease(cmd.durationMs, p) ?: return@aiAxis badLease(p)
                aiThrottle = cmd.value; aiThrottleUntil = now + lease; aiSpeedUntil = 0
                CommandResult.OK
            }
            is Command.SetSteering -> aiAxis(env.source) {
                if (abs(cmd.value) > p.maxSteering) return@aiAxis outOfRange("steering", p.maxSteering)
                val lease = lease(cmd.durationMs, p) ?: return@aiAxis badLease(p)
                aiSteering = cmd.value; aiSteeringUntil = now + lease
                CommandResult.OK
            }
            is Command.SetSpeed -> aiAxis(env.source) {
                if ("speed" !in adapter.capabilities) return@aiAxis CommandResult.reject("unsupported", "vehicle has no speed sensor")
                if (abs(cmd.mps) > p.maxSpeedMps) return@aiAxis outOfRange("speed", p.maxSpeedMps)
                val lease = lease(cmd.durationMs, p) ?: return@aiAxis badLease(p)
                aiSpeed = cmd.mps; aiSpeedUntil = now + lease; aiThrottleUntil = 0
                CommandResult.OK
            }
            is Command.SetMode -> setMode(env.source, cmd.mode, p, now)
            is Command.ConfigurePid -> configurePid(env.source, cmd, p)
            is Command.UpdateProfile -> updateProfile(env.source, cmd, p)
            is Command.SelectProfile -> selectProfile(env.source, cmd)
            is Command.SetChannels -> setChannels(env.source, cmd, p)
            is Command.OpsRequest -> ops()?.handle(env.source, cmd.name, cmd.args, mode, p) ?: CommandResult.reject("unsupported", "not available")
            Command.Kill -> error("handled above")
            is Command.SetFlightMode -> setFlightMode(env.source, cmd.mode, p)
            is Command.SetNamedInputs -> deviceCommand(env.source) { cmd.inputs.values.firstOrNull { abs(it) > 1.0 }?.let { "inputs must be -1 to 1" } ?: run { deviceHooks?.inputs(cmd.inputs, cmd.durationMs.coerceIn(1, 10000)); null } }
            is Command.RunSkill -> deviceCommand(env.source) { deviceHooks?.runSkill(cmd.id) }
            Command.StopSkill -> { deviceHooks?.stopSkill(); CommandResult.OK }
            is Command.RunBehavior -> deviceCommand(env.source) { deviceHooks?.runBehavior(cmd.id) }
            Command.StopBehavior -> { deviceHooks?.stopBehavior(); CommandResult.OK }
            is Command.RunMission -> runMission(env.source, cmd, now)
            Command.Stop -> error("handled above")
        }
    }

    private fun outOfRange(what: String, limit: Double) =
        CommandResult.reject("out_of_range", "$what exceeds configured limit +/-$limit")
    private fun badLease(p: VehicleProfile) =
        CommandResult.reject("out_of_range", "durationMs must be 1..${p.maxAiLeaseMs}")
    private fun lease(ms: Long, p: VehicleProfile) = ms.takeIf { it in 1..p.maxAiLeaseMs }

    private inline fun aiAxis(source: Source, body: () -> CommandResult): CommandResult {
        if (source != Source.AI) return CommandResult.reject("not_permitted", "timed commands are for the AI path; humans use drive")
        if (mode != Mode.AUTO) return CommandResult.reject("not_permitted", "vehicle is in $mode, not AUTO")
        if (missionName != null) return CommandResult.reject("not_permitted", "mission $missionName is running")
        return body()
    }

    private fun drive(source: Source, c: Command.Drive, p: VehicleProfile, now: Long): CommandResult {
        if (source == Source.AI) return CommandResult.reject("not_permitted", "AI may not use the manual drive path")
        if (drillRunning) return CommandResult.reject("not_permitted", "a test is running")
        if (abs(c.throttle) > p.maxThrottle) return outOfRange("throttle", p.maxThrottle)
        if (abs(c.steering) > p.maxSteering) return outOfRange("steering", p.maxSteering)
        if (abs(c.pitch) > 1.0) return outOfRange("pitch", 1.0)
        if (abs(c.roll) > 1.0) return outOfRange("roll", 1.0)
        val moved = abs(c.throttle) > STICK_DEADBAND || abs(c.steering) > STICK_DEADBAND || abs(c.pitch) > STICK_DEADBAND || abs(c.roll) > STICK_DEADBAND
        if (!router.allow(source, moved)) return CommandResult.reject("not_owner", "${router.owner()} has control")
        if (moved) lastMovedMs = now
        when (mode) {
            Mode.DISARMED, Mode.FAILSAFE -> return CommandResult.reject("not_permitted", "vehicle is $mode")
            // A human driving during AUTO takes over immediately.
            Mode.AUTO -> {
                enterMode(Mode.MANUAL, "human override"); aiLocked = true
                fc?.let { f -> if (f.mode == FlightMode.AUTO || f.mode == FlightMode.HOLD) { ops()?.missions?.pause(); f.setModeForce(FlightMode.MANUAL) } }
            }
            Mode.MANUAL -> {}
        }
        manualThrottle = c.throttle
        manualSteering = c.steering
        manualPitch = c.pitch
        manualRoll = c.roll
        humanLastMs = now
        return CommandResult.OK
    }

    private fun applyStop(source: Source): CommandResult {
        // A failsafe is already stopped, and stays latched until a human explicitly re-arms.
        if (mode != Mode.FAILSAFE) enterMode(Mode.DISARMED, "stop from $source")
        ops()?.missions?.abort()
        // The AI cannot undo a human's stop.
        if (source != Source.AI && source != Source.BEHAVIOR) aiLocked = true
        deviceHooks?.stopBehavior()
        deviceHooks?.stopSkill()
        return CommandResult.OK
    }

    private fun setMode(source: Source, target: Mode, p: VehicleProfile, now: Long): CommandResult {
        if (target == Mode.FAILSAFE) return CommandResult.reject("not_permitted", "FAILSAFE cannot be requested")
        if (drillRunning && target != Mode.DISARMED) return CommandResult.reject("not_permitted", "a test is running")
        // Disarming a flying plane cuts its motor and controls. STOP remains the deliberate way to do that.
        if (target == Mode.DISARMED && (mode == Mode.MANUAL || mode == Mode.AUTO) && fcActive)
            fc?.flyingReason()?.let { return CommandResult.reject("not_permitted", "the plane looks airborne ($it). Land first, or press STOP to cut the motor") }
        if (target != Mode.DISARMED && fcActive) fc?.armProblem()?.let { return CommandResult.reject("not_permitted", it) }
        if (target != Mode.DISARMED) deviceHooks?.armProblem()?.let { return CommandResult.reject("not_permitted", it) }
        if (source == Source.AI) {
            if (mode == Mode.FAILSAFE) return CommandResult.reject("not_permitted", "failsafe needs a human to clear it")
            if (target == Mode.MANUAL) return CommandResult.reject("not_permitted", "AI may not select MANUAL")
            if (target == Mode.AUTO) {
                if (mode == Mode.MANUAL) return CommandResult.reject("not_permitted", "a human has control")
                if (aiLocked) return CommandResult.reject("not_permitted", "a human stop is latched; a human must re-arm")
                if (!p.aiMayArm) return CommandResult.reject("not_permitted", "this profile does not allow the AI to arm")
            }
        } else if (target != Mode.DISARMED) {
            // Any explicit human arming clears the latches.
            aiLocked = false
        }
        if (target == Mode.MANUAL) humanLastMs = now
        if (target != Mode.DISARMED) { ops()?.armProblem()?.let { return CommandResult.reject("not_permitted", it) }; if (source != Source.AI) killed = false; lastMovedMs = now; ops()?.tester?.stop() }
        enterMode(target, "set_mode from $source")
        return CommandResult.OK
    }

    private fun bounded(name: String, v: Double?, lo: Double, hi: Double): CommandResult? =
        if (v != null && v !in lo..hi) CommandResult.reject("out_of_range", "$name must be in $lo..$hi") else null

    private fun configurePid(source: Source, c: Command.ConfigurePid, p: VehicleProfile): CommandResult {
        bounded("kp", c.kp, 0.0, 5.0)?.let { return it }
        bounded("ki", c.ki, 0.0, 5.0)?.let { return it }
        bounded("kd", c.kd, 0.0, 2.0)?.let { return it }
        bounded("ff", c.ff, 0.0, 2.0)?.let { return it }
        if (source == Source.AI && mode == Mode.MANUAL) return CommandResult.reject("not_permitted", "a human has control")
        profiles.updateActive(p.copy(kp = c.kp ?: p.kp, ki = c.ki ?: p.ki, kd = c.kd ?: p.kd, ff = c.ff ?: p.ff))
        pid.reset()
        return CommandResult.OK
    }

    private fun updateProfile(source: Source, c: Command.UpdateProfile, p0: VehicleProfile): CommandResult {
        if (source == Source.AI) return CommandResult.reject("not_permitted", "AI may not change safety limits")
        var p = p0
        for ((k, v) in c.fields) {
            val err = when (k) {
                "maxThrottle" -> bounded(k, v, 0.0, 1.0).also { p = p.copy(maxThrottle = v) }
                "maxSteering" -> bounded(k, v, 0.0, 1.0).also { p = p.copy(maxSteering = v) }
                "maxSpeedMps" -> bounded(k, v, 0.0, 20.0).also { p = p.copy(maxSpeedMps = v) }
                "kp" -> bounded(k, v, 0.0, 5.0).also { p = p.copy(kp = v) }
                "ki" -> bounded(k, v, 0.0, 5.0).also { p = p.copy(ki = v) }
                "kd" -> bounded(k, v, 0.0, 2.0).also { p = p.copy(kd = v) }
                "ff" -> bounded(k, v, 0.0, 2.0).also { p = p.copy(ff = v) }
                "commandTimeoutMs" -> bounded(k, v, 100.0, 5000.0).also { p = p.copy(commandTimeoutMs = v.toLong()) }
                "staleCommandMs" -> bounded(k, v, 100.0, 10000.0).also { p = p.copy(staleCommandMs = v.toLong()) }
                "maxAiLeaseMs" -> bounded(k, v, 100.0, 60000.0).also { p = p.copy(maxAiLeaseMs = v.toLong()) }
                "obstacleStopM" -> bounded(k, v, 0.0, 5.0).also { p = p.copy(obstacleStopM = v) }
                "aiMayArm" -> bounded(k, v, 0.0, 1.0).also { p = p.copy(aiMayArm = v != 0.0) }
                else -> CommandResult.reject("out_of_range", "unknown field $k")
            }
            if (err != null) return err
        }
        profiles.updateActive(p)
        pid.reset()
        return CommandResult.OK
    }

    private fun selectProfile(source: Source, c: Command.SelectProfile): CommandResult {
        if (source == Source.AI) return CommandResult.reject("not_permitted", "AI may not change profiles")
        if (mode != Mode.DISARMED) return CommandResult.reject("not_permitted", "disarm before changing profile")
        if (!profiles.select(c.id)) return CommandResult.reject("out_of_range", "unknown profile ${c.id}")
        adapterFor(profiles.active.adapter)?.let { next ->
            if (next !== adapter) {
                adapter.safeStop()
                adapter = next
                sensors = SensorFrame(null, null, null, null, null, null, false)
                wasCollided = false
            }
        }
        pid.reset()
        return CommandResult.OK
    }

    private inline fun deviceCommand(source: Source, run: () -> String?): CommandResult {
        if (deviceHooks == null) return CommandResult.reject("unsupported", "no device layer")
        if (source == Source.AI && mode != Mode.AUTO) return CommandResult.reject("not_permitted", "vehicle is in $mode, not AUTO")
        if (mode != Mode.MANUAL && mode != Mode.AUTO) return CommandResult.reject("not_permitted", "vehicle is $mode")
        run()?.let { return CommandResult.reject("not_permitted", it) }
        return CommandResult.OK
    }

    private fun setFlightMode(source: Source, m: FlightMode, p: VehicleProfile): CommandResult {
        val f = fc ?: return CommandResult.reject("unsupported", "no flight controller")
        if (!fcActive) return CommandResult.reject("unsupported", "the simulator doesn't use the flight controller")
        if (source == Source.AI) {
            if (!p.aiMayArm) return CommandResult.reject("not_permitted", "this vehicle does not allow the AI to change modes")
            if (mode == Mode.MANUAL) return CommandResult.reject("not_permitted", "a human has control")
            if (mode == Mode.FAILSAFE) return CommandResult.reject("not_permitted", "failsafe needs a human to clear it")
        }
        f.setMode(m)?.let { return CommandResult.reject("not_permitted", it) }
        return CommandResult.OK
    }

    private fun setChannels(source: Source, c: Command.SetChannels, p: VehicleProfile): CommandResult {
        if (source == Source.AI) return CommandResult.reject("not_permitted", "AI may not change vehicle outputs")
        if (mode != Mode.DISARMED) return CommandResult.reject("not_permitted", "disarm before changing outputs")
        Mixer.validate(c.channels)?.let { return CommandResult.reject("out_of_range", it) }
        if (c.frameFormat != null && c.frameFormat !in listOf("crsf", "ibus")) return CommandResult.reject("out_of_range", "unknown frame format")
        profiles.updateActive(p.copy(channels = c.channels, frameFormat = c.frameFormat ?: p.frameFormat))
        return CommandResult.OK
    }

    private fun runMission(source: Source, c: Command.RunMission, now: Long): CommandResult {
        val steps = Missions.all[c.name] ?: return CommandResult.reject("out_of_range", "unknown mission ${c.name}")
        if (!profiles.active.missionsAllowed) return CommandResult.reject("unsupported", "missions aren't available for this vehicle type")
        if (source == Source.AI && mode != Mode.AUTO) return CommandResult.reject("not_permitted", "vehicle is in $mode, not AUTO")
        if (source != Source.AI) {
            if (mode == Mode.FAILSAFE) return CommandResult.reject("not_permitted", "clear the failsafe first")
            aiLocked = false
            if (mode != Mode.AUTO) enterMode(Mode.AUTO, "mission from $source")
        }
        missionName = c.name; missionSteps = steps; missionIndex = 0; missionStepStart = now
        pid.reset()
        log.event("mission", source.name, "start", c.name)
        return CommandResult.OK
    }

    private fun clearActivity() {
        manualThrottle = 0.0; manualSteering = 0.0; manualPitch = 0.0; manualRoll = 0.0
        aiThrottleUntil = 0; aiSteeringUntil = 0; aiSpeedUntil = 0
        missionName = null; missionSteps = emptyList(); missionIndex = 0
        pid.reset()
    }

    private fun enterMode(m: Mode, why: String) {
        if (m != Mode.FAILSAFE) failsafeReason = null
        clearActivity()
        if (m == Mode.DISARMED || m == Mode.FAILSAFE) fc?.let { f -> if (f.mode == FlightMode.AUTO || f.mode == FlightMode.HOLD) f.setModeForce(FlightMode.MANUAL) }
        if (mode != m) log.event("mode", "controller", m.name, "${mode.name} -> ${m.name}: $why")
        mode = m
    }

    private fun failsafe(reason: String) {
        failsafeReason = reason
        aiLocked = true
        enterMode(Mode.FAILSAFE, reason)
        // A deliberate test shouldn't raise the alarm on every screen.
        log.event(if (drillRunning) "test" else "failsafe", "controller", "activated", reason)
    }

    /** Drive measured speed to zero, then release the output so the car doesn't creep. */
    private fun brake(speed: Double, p: VehicleProfile): Double =
        if (abs(speed) < 0.05) 0.0 else (-speed).coerceIn(-p.maxThrottle, p.maxThrottle)

    fun tick(dtSec: Double): Telemetry = synchronized(lock) {
        val now = clock()
        val p = profiles.active
        if (mode == Mode.MANUAL && now - humanLastMs > p.commandTimeoutMs) {
            if (fcActive && fc!!.returnHomeOnLinkLoss()) {
                // The autopilot takes the vehicle home. A human can take over again at any time.
                log.event("failsafe", "controller", "return_home", "manual command link lost: returning home")
                aiLocked = true
                enterMode(Mode.AUTO, "link lost, returning home")
            } else {
                failsafe("manual command link lost")
            }
        }
        if (fcActive) fc!!.takeFailsafeRequest()?.let { if (mode == Mode.MANUAL || mode == Mode.AUTO) failsafe(it) }

        val speed = sensors.speedMps ?: 0.0
        var target: Double? = null
        var steering = 0.0
        var pitch = 0.0
        var roll = 0.0
        var throttle: Double

        when (mode) {
            Mode.DISARMED, Mode.FAILSAFE -> throttle = brake(speed, p)
            Mode.MANUAL -> { throttle = manualThrottle; steering = manualSteering; pitch = manualPitch; roll = manualRoll }
            Mode.AUTO -> {
                advanceMission(now)
                val step = missionSteps.getOrNull(missionIndex)
                when {
                    step != null -> { target = step.speed; steering = step.steering }
                    now < aiSpeedUntil -> target = aiSpeed
                }
                if (step == null && now < aiSteeringUntil) steering = aiSteering
                val t = target
                throttle = when {
                    // No speed sensor means no feedback: a PID would wind up to full power, so use feed-forward only.
                    t != null && sensors.speedMps == null -> (t * p.ff).coerceIn(-p.maxThrottle, p.maxThrottle)
                    t != null -> pid.update(t, speed, dtSec, p, p.maxThrottle)
                    now < aiThrottleUntil -> aiThrottle
                    else -> { pid.reset(); brake(speed, p) }
                }
            }
        }

        // Local obstacle guard: reacts in one tick, however slow the AI or the network is.
        val range = sensors.rangeM
        val stopDistance = p.obstacleStopM + maxOf(speed, 0.0) * 0.6
        val hold = range != null && range < stopDistance && throttle > 0 && mode == Mode.AUTO
        if (hold) throttle = brake(speed, p)
        if (hold && !obstacleHold) log.event("obstacle", "controller", "hold", "range ${"%.2f".format(range)}m")
        obstacleHold = hold

        val requested = ActuatorOutput(throttle.coerceIn(-p.maxThrottle, p.maxThrottle), steering.coerceIn(-p.maxSteering, p.maxSteering), pitch, roll)
        val armed = mode == Mode.MANUAL || mode == Mode.AUTO
        // The autopilot runs even when disarmed, so the ground station can show what it would command.
        val axes = if (fcActive) fc!!.process(requested, armed, dtSec).let {
            it.copy(throttle = it.throttle.coerceIn(-p.maxThrottle, p.maxThrottle), steering = it.steering.coerceIn(-p.maxSteering, p.maxSteering))
        } else { fc?.bypass(); requested }
        val safe = !armed && abs(speed) < 0.05
        val o = ops()
        val channels = shape(Mixer.mix(p.channels, axes, safe), p, safe, o)
        val out = axes.copy(channels = channels, safe = safe, failsafe = mode == Mode.FAILSAFE, killed = killed)
        if (fc?.takeMissionStop() == true && armed) enterMode(Mode.DISARMED, "mission complete")
        val idleLimit = fc?.params?.get("AUTO_DISARM", p.vehicleType) ?: 0.0
        val missionRunning = o?.missions?.state == MissionEngine.State.RUNNING
        if (mode == Mode.MANUAL && !drillRunning && !missionRunning && idleLimit > 0 && now - lastMovedMs > idleLimit * 1000) {
            log.event("mode", "controller", "auto_disarm", "disarmed after ${idleLimit.toInt()} s idle")
            enterMode(Mode.DISARMED, "idle")
        }
        if (tickCount++ % 10 == 0L) {
            lastFrame = SerialFrames.hex(SerialFrames.encode(p.frameFormat, channels.map { it.pulseUs }))
            lastWould = Mixer.mix(p.channels, axes, false).map { it.pulseUs }
        }
        // While armed it's commanding exactly this. The slower preview is only for when it's disarmed.
        if (armed) lastWould = channels.map { it.pulseUs }
        sensors = adapter.exchange(out, dtSec)
        val reported = adapter.reportOutputs()
        lastOutput = if (reported != null) out.copy(channels = reported) else out
        if (reported != null) lastWould = reported.map { it.pulseUs }
        lastTarget = target

        if (sensors.collided && !wasCollided) {
            log.event("collision", "vehicle", "collided", "contact while in $mode")
            if (mode == Mode.MANUAL || mode == Mode.AUTO) failsafe("collision")
        }
        wasCollided = sensors.collided
        snapshot(now)
    }

    private fun advanceMission(now: Long) {
        if (missionName == null) return
        val step = missionSteps.getOrNull(missionIndex)
        if (step != null && now - missionStepStart >= step.durationMs) {
            missionIndex++
            missionStepStart = now
        }
        if (missionIndex >= missionSteps.size) {
            log.event("mission", "controller", "complete", missionName ?: "")
            missionName = null; missionSteps = emptyList(); missionIndex = 0
        }
    }

    /** Simulator reset: only when the vehicle is not being driven. */
    fun resetVehicle(): CommandResult = synchronized(lock) {
        if (mode != Mode.DISARMED && mode != Mode.FAILSAFE) return CommandResult.reject("not_permitted", "disarm first")
        adapter.reset()
        sensors = SensorFrame(null, null, null, null, null, null, false)
        wasCollided = false
        log.event("sim", "controller", "reset", "vehicle reset")
        CommandResult.OK
    }

    private fun snapshot(now: Long) = Telemetry(
        now, mode, profiles.activeId, lastOutput.throttle, lastOutput.steering, lastTarget, sensors,
        missionName, failsafeReason, obstacleHold, aiLocked, loop, accepted, rejected,
        if (procCount == 0L) 0.0 else procNanos.toDouble() / procCount / 1000.0,
        lastOutput.channels, lastFrame, lastWould,
    )

    fun latest(): Telemetry = synchronized(lock) { snapshot(clock()) }

    /** Applies each output's trim, then any test or trigger override. Kill shows outputs as off. */
    private fun shape(list: List<ChannelOutput>, p: VehicleProfile, safe: Boolean, o: Ops?): List<ChannelOutput> {
        if (killed) return list.map { it.copy(value = 0.0, pulseUs = 0) }
        if (o == null) return list
        return list.mapIndexed { i, c ->
            val def = p.channels.getOrNull(i) ?: return@mapIndexed c
            val motor = def.kind != ChannelKind.SERVO
            val ov = o.overrideFor(c.name)
            val trimmed = o.trims.get(c.name)
            val v = when {
                ov != null -> ov
                safe && motor -> c.value
                else -> if (def.kind == ChannelKind.MOTOR_FORWARD) trimmed.apply(c.value, 0.0, 1.0) else trimmed.apply(c.value)
            }
            c.copy(value = v, pulseUs = Mixer.pulseUs(def.kind, v))
        }
    }

    private companion object { const val STICK_DEADBAND = 0.05 }
}
