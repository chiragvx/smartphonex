package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

class FramesAndParsersTest {
    private val joint = ActuatorDef("a", address = 1, min = -90.0, max = 90.0)

    // Checked against the published examples for each protocol.
    @Test fun dynamixelPingMatchesPublishedExample() {
        assertEquals("FF FF FD 00 01 03 00 01 19 4E", hex(Dynamixel2Driver().ping(1)!!))
    }

    @Test fun feetechPingMatchesPublishedChecksum() {
        assertEquals("FF FF 01 02 01 FB", hex(FeetechDriver().ping(1)!!))
    }

    @Test fun lx16aMoveHasCorrectLayoutAndChecksum() {
        assertEquals("55 55 01 07 01 F4 01 E8 03 16", hex(Lx16aDriver().move(1, 500, 1000)))
    }

    @Test fun driversMapRangeToServoUnits() {
        val mid = FeetechDriver().encode(listOf(joint to 0.0), 0)
        assertEquals(2047, (mid[6].toInt() and 0xFF) or ((mid[7].toInt() and 0xFF) shl 8))
        val top = Lx16aDriver().encode(listOf(joint to 90.0), 20)
        assertEquals(1000, (top[5].toInt() and 0xFF) or ((top[6].toInt() and 0xFF) shl 8))
    }

    @Test fun driversClampOutOfRangeValues() {
        val over = Lx16aDriver().encode(listOf(joint to 999.0), 20)
        assertEquals(1000, (over[5].toInt() and 0xFF) or ((over[6].toInt() and 0xFF) shl 8))
    }

    @Test fun textDriverFillsTemplateOnly() {
        assertEquals("S1=0.000,50\n", String(TextDriver("S{address}={value},{percent}\\n").encode(listOf(joint to 0.0), 0)))
    }

    @Test fun crsfRoundTrip() {
        val out = CrsfParser().feed(SerialFrames.crsf(listOf(1500, 2000, 1000)))
        assertEquals(1, out.size)
        assertEquals(0.0, out[0].channels[0], 0.01)
        assertEquals(0.976, out[0].channels[1], 0.02)
        assertEquals(-0.976, out[0].channels[2], 0.02)
    }

    @Test fun ibusRoundTrip() {
        val out = IbusParser().feed(SerialFrames.ibus(listOf(1500, 2000, 1000)))
        assertEquals(listOf(0.0, 1.0, -1.0), out[0].channels.take(3))
    }

    @Test fun parsersSurviveNoiseAndSplitFrames() {
        val f = SerialFrames.crsf(listOf(1600))
        val p = CrsfParser()
        assertTrue(p.feed(byteArrayOf(1, 2, 3, 0xC8.toByte(), 99)).isEmpty())
        assertTrue(p.feed(f.copyOfRange(0, 10)).isEmpty())
        assertEquals(1, p.feed(f.copyOfRange(10, f.size)).size)
    }

    @Test fun corruptFrameIsDropped() {
        val f = SerialFrames.ibus(listOf(1600))
        f[5] = (f[5] + 1).toByte()
        assertTrue(IbusParser().feed(f).isEmpty())
    }

    @Test fun sbusReadsChannelsAndFailsafeFlag() {
        val f = ByteArray(25)
        f[0] = 0x0F
        // Channel 1 at 992, the centre value: low byte, then 3 bits.
        f[1] = (992 and 0xFF).toByte(); f[2] = (992 shr 8).toByte()
        f[23] = 0x08
        val out = SbusParser().feed(f)
        assertEquals(0.0, out[0].channels[0], 0.001)
        assertTrue(out[0].failsafe)
    }

    @Test fun mavlinkCrcMatchesCheckValue() {
        assertEquals(0x6F91, Mavlink.crc("123456789".toByteArray(), 0, 9, -1))
    }

    @Test fun mavlinkFramesAreWellFormed() {
        val h = Mavlink.heartbeat(1, true, 1)
        assertEquals(0xFD.toByte(), h[0])
        assertEquals(h.size, 10 + (h[1].toInt() and 0xFF) + 2)
        assertEquals(0, h[7].toInt())
        assertEquals(30, Mavlink.attitude(0, 1, 0.1f, 0f, 0f, 0f, 0f, 0f)[7].toInt())
    }

    @Test fun lineTrackerFindsPosition() {
        val w = 40; val h = 40
        val img = ByteArray(w * h) { 200.toByte() }
        for (r in 0 until h) for (c in 28 until 34) img[r * w + c] = 10
        val (found, pos) = LineTracker.track(img, w, h, dark = true).toList()
        assertEquals(1.0, found, 0.0)
        assertTrue("position $pos", pos > 0.3)
        assertEquals(0.0, LineTracker.track(ByteArray(w * h) { 100 }, w, h, true)[0], 0.0)
    }
}

class RouterTest {
    private var now = 1000L
    private val r = InputRouter { now }

    @Test fun personAtControlsOutranksGroundStation() {
        assertTrue(r.allow(Source.GROUND, true))
        assertTrue(r.allow(Source.RC, true))
        assertFalse(r.allow(Source.GROUND, true))
        assertEquals(Source.RC, r.owner())
    }

    @Test fun idleHigherSourceDoesNotTakeControl() {
        assertTrue(r.allow(Source.GROUND, true))
        assertFalse("centred sticks mustn't override someone driving", r.allow(Source.RC, false))
        assertEquals(Source.GROUND, r.owner())
    }

    @Test fun controlIsReleasedAfterHold() {
        r.allow(Source.RC, true)
        now += 1600
        assertTrue(r.allow(Source.GROUND, true))
    }
}

class DeviceSchemaTest {
    private val good = """{"schema":"${DeviceSchema.VERSION}","name":"Test","class":"arm","hazard":"light",
      "actuators":[{"id":"j1","unit":"deg","min":-10,"max":10,"max_rate":100,"safe":{"action":"hold"},"mix":{"turn":1}}],
      "skills":[{"id":"home","type":"pose","params":{"joints":{"j1":0},"ms":100}}]}"""

    @Test fun parsesAndRoundTrips() {
        val (d, errors) = DeviceSchema.parse(parse(good))
        assertTrue(errors.toString(), errors.isEmpty())
        val (again, e2) = DeviceSchema.parse(DeviceSchema.toJson(d!!))
        assertTrue(e2.toString(), e2.isEmpty())
        assertEquals(d, again)
    }

    private fun errorsFor(edit: (String) -> String) = DeviceSchema.parse(parse(edit(good))).second.joinToString()

    @Test fun reportsUsefulErrors() {
        assertTrue(errorsFor { it.replace(DeviceSchema.VERSION, "v0") }.contains("schema"))
        assertTrue(errorsFor { it.replace("\"arm\"", "\"submarine\"") }.contains("class"))
        assertTrue(errorsFor { it.replace("\"min\":-10", "\"min\":50") }.contains("min must be below max"))
        assertTrue(errorsFor { it.replace("\"action\":\"hold\"", "\"action\":\"explode\"") }.contains("safe action"))
        assertTrue(errorsFor { it.replace("\"joints\":{\"j1\":0}", "\"joints\":{\"nope\":0}") }.contains("unknown joint"))
        assertTrue(errorsFor { it.replace("\"max_rate\":100", "\"max_rate\":0") }.contains("max_rate"))
    }

    @Test fun sharedPortWithDifferentSpeedsIsRefusedWithAdvice() {
        val s = """{"schema":"${DeviceSchema.VERSION}","name":"T","class":"arm","hazard":"light",
          "transports":[{"id":"u","type":"usb-uart","baud":115200}],
          "actuators":[{"id":"j","driver":"lx16a","transport":"u","min":-1,"max":1}],
          "inputs":[{"id":"rc","driver":"crsf","transport":"u","map":{"ch1":"turn"}}]}"""
        assertTrue(DeviceSchema.parse(parse(s)).second.joinToString().contains("second adapter"))
    }

    @Test fun importAlwaysRestartsSafetyStages() {
        val d = DeviceSchema.parse(parse(good)).first!!.copy(commissioning = Commissioning(11, mapOf("usb_pull" to "ok"), setOf("restrained"), "someone"))
        val c = DeviceSchema.forImport(d).commissioning
        assertNull(c.signedOffBy); assertTrue(c.approvals.isEmpty()); assertTrue(c.tests.isEmpty()); assertTrue(c.stage <= 4)
    }

    @Test fun urdfJointsBecomeActuators() {
        val xml = """<robot name="r"><joint name="elbow" type="revolute"><limit lower="-1.57" upper="1.57" velocity="1"/></joint>
          <joint name="fixed1" type="fixed"/><joint name="wheel" type="continuous"/></robot>"""
        val d = DeviceSchema.fromUrdf(xml, "Bot")
        assertEquals(listOf("elbow", "wheel"), d.actuators.map { it.id })
        assertEquals(90.0, d.actuators[0].max, 0.1)
        assertTrue(DeviceSchema.validate(d).toString(), DeviceSchema.validate(d).isEmpty())
    }

    @Test fun hostileUrdfIsRefused() {
        val xml = """<!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><robot>&x;</robot>"""
        assertThrows(Exception::class.java) { DeviceSchema.fromUrdf(xml, "Bad") }
    }

    /** Conformance: everything that ships has to pass. */
    @Test fun everyShippedPackIsValid() {
        val packs = File("src/main/assets/packs").listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue(packs.size >= 5)
        for (f in packs) {
            val (d, errors) = DeviceSchema.parse(parse(f.readText()))
            assertTrue("${f.name}: $errors", errors.isEmpty())
            assertEquals(f.name, f.nameWithoutExtension, d!!.id)
            assertNotEquals("${f.name} is not signed off", d.commissioning.signedOffBy != null, true)
        }
    }

    @Test fun phoneFitDecidesDemandingClasses() {
        assertNotNull(DeviceSchema.fitProblem("multirotor", 200.0, 100.0))
        assertNull(DeviceSchema.fitProblem("wheeled", 0.0, 0.0))
        assertNull(DeviceSchema.fitProblem("biped", 200.0, 100.0))
    }
}

private class FakeTransport : TransportProvider {
    val sent = ArrayList<ByteArray>()
    override fun list() = listOf("fake adapter")
    override fun open(def: TransportDef) = object : Transport {
        override fun open(onBytes: (ByteArray) -> Unit) {}
        override fun write(data: ByteArray) { synchronized(sent) { sent += data } }
        override fun close() {}
    }
}

class EngineTest {
    private var now = 1000L
    private var att = Attitude(0.0, 0.0, 0.0)
    private val log = EventLog(clock = { now })
    private val engine = DeviceEngine(log, { att }, { now })
    private fun actuator(safe: String = "neutral", value: Double? = null, mix: Map<String, Double> = mapOf("forward" to 1.0)) =
        ActuatorDef("j", min = -10.0, max = 20.0, neutral = 0.0, maxRate = 100.0, safe = SafeAction(safe, value), mix = mix)
    private fun device(vararg a: ActuatorDef, skills: List<SkillDef> = emptyList(), controllers: List<ControllerDef> = emptyList(), onFall: String? = null, onLoss: String? = null) =
        DeviceDescription("d", "D", "arm", "light", actuators = a.toList(), skills = skills, controllers = controllers, onFall = onFall, onLinkLoss = onLoss)
    private fun run(throttle: Double, ms: Int, safe: Boolean = false, fail: Boolean = false) {
        repeat(ms / 10) { now += 10; engine.exchange(ActuatorOutput(throttle, 0.0, safe = safe, failsafe = fail), 0.01) }
    }
    private fun value() = engine.reportOutputs().first().value

    @Test fun mixMapsToRangeOnEachSideOfNeutral() {
        engine.load(device(actuator()))
        run(1.0, 1000); assertEquals(20.0, value(), 0.01)
        run(-1.0, 1000); assertEquals(-10.0, value(), 0.01)
    }

    @Test fun rateLimitIsEnforced() {
        engine.load(device(actuator()))
        run(1.0, 100) // 100 units/s for 0.1 s.
        assertEquals(10.0, value(), 0.5)
    }

    @Test fun safeActionsDiffer() {
        for ((action, expected) in listOf("hold" to 20.0, "neutral" to 0.0, "value" to 5.0, "limp" to 20.0)) {
            engine.load(device(actuator(action, if (action == "value") 5.0 else null)))
            run(1.0, 1000)
            run(1.0, 1000, safe = true)
            assertEquals(action, expected, value(), 0.01)
        }
    }

    @Test fun skillsCannotExceedLimits() {
        val s = SkillDef("big", "pose", parse("""{"joints":{"j":999},"ms":10}"""))
        engine.load(device(actuator(mix = emptyMap()), skills = listOf(s)))
        assertNull(engine.runSkill("big"))
        run(0.0, 1000)
        assertEquals(20.0, value(), 0.01)
    }

    @Test fun sequencePlaysAndFinishes() {
        val s = SkillDef("s", "sequence", parse("""{"steps":[{"joints":{"j":10},"ms":200},{"joints":{"j":0},"ms":200}],"loops":1}"""))
        engine.load(device(actuator(mix = emptyMap()), skills = listOf(s)))
        engine.runSkill("s")
        run(0.0, 150)
        assertTrue(value() > 2.0)
        run(0.0, 400)
        assertNull(engine.runningSkill)
        assertEquals(0.0, value(), 1.0)
    }

    @Test fun safeStateOverridesSkillUntilFailsafeSkill() {
        val sit = SkillDef("sit", "pose", parse("""{"joints":{"j":15},"ms":100}"""))
        engine.load(device(actuator(mix = emptyMap()), skills = listOf(sit), onLoss = "sit"))
        run(0.0, 50, safe = true, fail = true)
        assertEquals("sit", engine.runningSkill)
        run(0.0, 500, safe = true, fail = true)
        assertEquals(15.0, value(), 0.5)
    }

    @Test fun fallDetectionStopsSkillAndRunsResponse() {
        val walk = SkillDef("walk", "sequence", parse("""{"steps":[{"joints":{"j":10},"ms":10000}]}"""))
        val down = SkillDef("down", "pose", parse("""{"joints":{"j":-5},"ms":100}"""))
        engine.load(device(actuator(mix = emptyMap()), skills = listOf(walk, down), controllers = listOf(ControllerDef("f", "fall_detect", mapOf("limit" to 45.0))), onFall = "down"))
        engine.runSkill("walk")
        run(0.0, 50)
        att = Attitude(60.0, 0.0, 0.0)
        run(0.0, 20)
        assertEquals("down", engine.runningSkill)
    }

    @Test fun balanceCorrectsWithinItsLimit() {
        val bal = ControllerDef("b", "balance", mapOf("limit" to 5.0), parse("""{"pitch":{"j":-1.0}}"""))
        engine.load(device(actuator(mix = emptyMap()), controllers = listOf(bal)))
        att = Attitude(0.0, 30.0, 0.0)
        run(0.0, 500)
        assertEquals(-5.0, value(), 0.1)
    }

    @Test fun gaitsBuildForEveryPattern() {
        assertEquals(6, Gaits.build(parse("""{"pattern":"biped_static","joints":{"left_knee":"a"}}"""))!!.size)
        assertEquals(6, Gaits.build(parse("""{"pattern":"hexapod_tripod","joints":{"l1_lift":"a"}}"""))!!.size)
        assertNull(Gaits.build(parse("""{"pattern":"moonwalk"}""")))
    }

    // --- Hardware must stay closed.

    private fun hw(signedOff: Boolean): FakeTransport {
        val fake = FakeTransport()
        engine.hardware = HardwareLink(fake, log)
        val t = TransportDef("bus", "usb-uart")
        val a = ActuatorDef("j", "lx16a", "bus", 1, min = -10.0, max = 10.0, maxRate = 100.0, mix = mapOf("forward" to 1.0))
        val b = ActuatorDef("k", "lx16a", "bus", 2, min = -10.0, max = 10.0, maxRate = 100.0)
        engine.load(DeviceDescription("d", "D", "arm", "light", transports = listOf(t), actuators = listOf(a, b),
            commissioning = Commissioning(signedOffBy = if (signedOff) "person" else null)))
        return fake
    }

    @Test fun unsignedDeviceSendsNothingToHardware() {
        val fake = hw(false)
        run(1.0, 300)
        Thread.sleep(80)
        assertEquals(0, synchronized(fake.sent) { fake.sent.size })
    }

    @Test fun commissioningMoveTouchesOnlyThatJoint() {
        val fake = hw(false)
        engine.commissionMove("k", 1.0, 200)
        run(0.0, 100)
        Thread.sleep(80)
        val sent = synchronized(fake.sent) { fake.sent.toList() }
        assertTrue(sent.isNotEmpty())
        assertTrue("only address 2", sent.all { it[2] == 2.toByte() && it.size == 10 })
    }

    @Test fun signedOffDeviceReachesHardware() {
        val fake = hw(true)
        run(1.0, 200)
        Thread.sleep(80)
        assertTrue(synchronized(fake.sent) { fake.sent.size } > 0)
    }
}

class BehaviorTest {
    private var now = 0L
    private val ran = ArrayList<String>()
    private var running = false
    private var battery = 50.0
    private fun runner() = BehaviorRunner({ if (it == "sensor:phone.battery:0") battery else null }, { ran += it; running = true; null }, { running }, { running = false }, { now })
    private val skills = setOf("go", "dock")
    private fun tree(s: String) = parse(s)

    @Test fun validatorRefusesBadTrees() {
        assertTrue(BehaviorTree.validate(tree("""{"node":"shell","cmd":"rm"}"""), skills).isNotEmpty())
        assertTrue(BehaviorTree.validate(tree("""{"node":"skill","id":"nope"}"""), skills).isNotEmpty())
        assertTrue(BehaviorTree.validate(tree("""{"node":"wait","ms":99999999}"""), skills).isNotEmpty())
        assertTrue(BehaviorTree.validate(tree("""{"node":"repeat","times":9e9,"child":{"node":"wait","ms":1}}"""), skills).isNotEmpty())
        var deep = """{"node":"wait","ms":1}"""
        repeat(20) { deep = """{"node":"repeat","times":1,"child":$deep}""" }
        assertTrue(BehaviorTree.validate(tree(deep), skills).toString().contains("deeper"))
    }

    @Test fun sequenceWaitsForSkills() {
        val r = runner()
        r.start("t", tree("""{"node":"sequence","children":[{"node":"skill","id":"go"},{"node":"wait","ms":100},{"node":"skill","id":"dock"}]}"""))
        assertTrue(r.tick())
        assertEquals(listOf("go"), ran)
        running = false
        assertTrue(r.tick())
        now += 200
        assertTrue(r.tick())
        assertEquals(listOf("go", "dock"), ran)
        running = false
        assertFalse(r.tick())
    }

    @Test fun selectorFallsBackOnFailedCondition() {
        val r = runner()
        battery = 5.0
        r.start("t", tree("""{"node":"selector","children":[{"node":"sequence","children":[{"node":"condition","value":"sensor:phone.battery:0","op":">","compare":20},{"node":"skill","id":"go"}]},{"node":"skill","id":"dock"}]}"""))
        r.tick()
        assertEquals(listOf("dock"), ran)
    }

    @Test fun unknownValueFailsSafely() {
        val r = runner()
        r.start("t", tree("""{"node":"condition","value":"sensor:none:0","op":">","compare":0}"""))
        assertFalse(r.tick())
    }

    @Test fun repeatIsBoundedAndDoesNotSpinInOneTick() {
        val r = runner()
        r.start("t", tree("""{"node":"repeat","times":3,"child":{"node":"wait","ms":0}}"""))
        var ticks = 0
        while (r.tick()) { ticks++; assertTrue(ticks < 10) }
        assertTrue(ticks >= 2)
    }

    @Test fun stopEndsItAndItsSkill() {
        val r = runner()
        r.start("t", tree("""{"node":"skill","id":"go"}"""))
        r.tick()
        r.stop()
        assertFalse(running)
        assertNull(r.runningId)
    }
}

class SetupTest {
    private val dir = File.createTempFile("setup", "").also { it.delete(); it.mkdirs() }
    private val rt = VehicleRuntime(dir)
    private val s get() = rt.setup
    private val deviceJson = parse("""{"name":"Arm","class":"arm","hazard":"light",
      "transports":[{"id":"bus","type":"usb-uart"}],
      "actuators":[{"id":"j1","driver":"lx16a","transport":"bus","address":1,"unit":"deg","min":-40,"max":40,"max_rate":60,"safe":{"action":"hold"}}]}""")

    private fun call(name: String, vararg a: Pair<String, JsonElement>) = s.call(name, JsonObject(a.toMap()))
    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.boolean
    private fun JsonObject.code() = this["code"]!!.jsonPrimitive.content
    private fun str(v: String) = JsonPrimitive(v)
    private fun approveAll() = s.pendingList.forEach { s.approve(it.id, true, "test person") }

    private fun toStage(n: Int) {
        call("setup_begin", "description" to str("an arm"))
        assertTrue(call("setup_propose_device", "device" to deviceJson).ok())
        if (n >= 4) assertTrue(call("setup_advance").ok())
        if (n >= 5) { call("setup_request_confirmation", "kind" to str("preview_ok"), "detail" to str("looks right")); approveAll(); assertTrue(call("setup_advance").ok()) }
        if (n >= 6) { call("setup_request_confirmation", "kind" to str("restrained"), "detail" to str("restrained")); approveAll(); assertTrue(call("setup_advance").ok()) }
    }

    @Test fun invalidDeviceComesBackWithFixableErrors() {
        val r = call("setup_propose_device", "device" to parse("""{"name":"X","class":"boat?"}"""))
        assertEquals("invalid", r.code())
        assertTrue(r["errors"].toString().contains("class"))
    }

    @Test fun stagesCannotBeSkipped() {
        assertEquals("not_ready", call("setup_advance").code())
        toStage(4)
        assertEquals("not_ready", call("setup_advance").code())
        assertEquals("not_permitted", call("setup_sign_off").code())
    }

    @Test fun noMotionBeforeStageSix() {
        toStage(5)
        assertEquals("not_permitted", call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(1.0)).code())
    }

    @Test fun firstMotionNeedsAPerson() {
        toStage(6)
        val r = call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(1.0))
        assertEquals("waiting_for_person", r.code())
        assertEquals(1, s.pendingList.size)
        approveAll()
        assertTrue(call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(1.0)).ok())
    }

    @Test fun firstMovesAreSmall() {
        toStage(6)
        call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(1.0)); approveAll()
        assertEquals("too_large", call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(30.0)).code())
    }

    @Test fun refusalMeansNo() {
        toStage(6)
        call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(1.0))
        s.pendingList.forEach { s.approve(it.id, false, "test person") }
        assertEquals("waiting_for_person", call("setup_move_actuator", "id" to str("j1"), "value" to JsonPrimitive(1.0)).code())
    }

    @Test fun noToolCanApproveOrSignOff() {
        // Reserved kinds can't be requested through the general tool, and no tool approves anything.
        toStage(4)
        assertEquals("not_permitted", call("setup_request_confirmation", "kind" to str("sign_off"), "detail" to str("x")).code())
        assertEquals("not_permitted", call("setup_request_confirmation", "kind" to str("first_motion:j1"), "detail" to str("x")).code())
        assertTrue(s.toolNames.none { it.contains("approve") })
    }

    private fun toSignOffStage() {
        toStage(6)
        call("setup_record_test", "test" to str("direction:j1"), "result" to str("clockwise, as expected"))
        assertTrue(call("setup_advance").ok())
        call("setup_request_confirmation", "kind" to str("limits_checked"), "detail" to str("checked")); approveAll()
        assertTrue(call("setup_advance").ok())
        assertEquals("not_ready", call("setup_advance").code())
        for (t in SetupSession.REQUIRED_TESTS) call("setup_record_test", "test" to str(t), "result" to str("stopped"))
        repeat(3) { assertTrue(call("setup_advance").ok()) }
    }

    @Test fun fullPathEndsWithAPersonSigningOff() {
        toSignOffStage()
        assertEquals("waiting_for_person", call("setup_sign_off").code())
        assertNull(rt.devices.get("arm")!!.commissioning.signedOffBy)
        approveAll()
        assertEquals("test person", rt.devices.get("arm")!!.commissioning.signedOffBy)
    }

    @Test fun llmCannotLowerHazardOrWidenLimitsByResubmitting() {
        toStage(4)
        val raised = JsonObject(deviceJson + ("hazard" to str("heavy")))
        assertTrue(call("setup_propose_device", "device" to raised).ok())
        assertEquals("heavy", rt.devices.get("arm")!!.hazard)
        assertTrue(call("setup_propose_device", "device" to deviceJson).ok())
        assertEquals("lowering waits for a person", "heavy", rt.devices.get("arm")!!.hazard)
        approveAll()
        assertEquals("light", rt.devices.get("arm")!!.hazard)

        toSignOffStage()
        val wider = parse(deviceJson.toString().replace("\"max\":40", "\"max\":90"))
        assertEquals("not_permitted", call("setup_propose_device", "device" to wider).code())
        val r = call("setup_set_limits", "id" to str("j1"), "max" to JsonPrimitive(90.0))
        assertEquals("waiting_for_person", r.code())
    }

    @Test fun rollbackRestoresSettings() {
        toSignOffStage()
        call("setup_set_limits", "id" to str("j1"), "max" to JsonPrimitive(30.0))
        assertEquals(30.0, rt.devices.get("arm")!!.actuators[0].max, 0.0)
        assertTrue(call("setup_rollback").ok())
        assertEquals(40.0, rt.devices.get("arm")!!.actuators[0].max, 0.0)
    }

    @Test fun aiRunToolsNeedAuto() {
        toStage(4)
        assertEquals("not_armed", call("device_run_skill", "id" to str("x")).code())
        assertEquals("not_armed", call("device_set_inputs", "inputs" to parse("""{"forward":1}""")).code())
    }

    @Test fun heavyDevicesGetNoAiControl() {
        toStage(4)
        call("setup_propose_device", "device" to JsonObject(deviceJson + ("hazard" to str("heavy"))))
        assertFalse(rt.profiles.active.aiMayArm)
        val c = rt.controller.submit(Envelope(Source.AI, null, null, Command.SetMode(Mode.AUTO)))
        assertFalse(c.accepted)
    }

    @Test fun deviceRunsThroughTheNormalController() {
        call("setup_begin", "description" to str("x"))
        call("setup_propose_device", "device" to parse("""{"name":"Pan","class":"fixed","hazard":"toy","actuators":[{"id":"pan","unit":"deg","min":-90,"max":90,"max_rate":1000,"mix":{"turn":1}}]}"""))
        assertEquals("device:pan", rt.profiles.activeId)
        rt.controller.submit(Envelope(Source.GROUND, null, null, Command.SetMode(Mode.MANUAL)))
        rt.controller.submit(Envelope(Source.GROUND, null, null, Command.Drive(0.0, 0.5)))
        val t = rt.controller.tick(0.1)
        assertEquals("pan", t.outputs[0].name)
        assertEquals(45.0, t.outputs[0].value, 1.0)
        rt.controller.submit(Envelope(Source.LOCAL, null, null, Command.Stop))
    }

    @Test fun importedDeviceCanBeCommissioned() {
        val pack = DeviceSchema.forImport(DeviceSchema.parse(JsonObject(deviceJson + ("schema" to str(DeviceSchema.VERSION)))).first!!.copy(commissioning = Commissioning(stage = 4)))
        rt.devices.put(pack)
        rt.registerDevice(pack)
        rt.controller.submit(Envelope(Source.GROUND, null, null, Command.SelectProfile("device:arm")))
        assertEquals("setup picks up the selected device", 4, call("setup_status")["stage"]!!.jsonPrimitive.int)
        call("setup_request_confirmation", "kind" to str("preview_ok"), "detail" to str("ok")); approveAll()
        assertTrue(call("setup_advance").ok())
    }

    @Test fun setupResumesAfterRestart() {
        toStage(5)
        val after = VehicleRuntime(dir)
        after.devices.all().forEach(after::registerDevice)
        after.controller.submit(Envelope(Source.GROUND, null, null, Command.SelectProfile("device:arm")))
        assertEquals(5, after.setup.call("setup_status", JsonObject(emptyMap()))["stage"]!!.jsonPrimitive.int)
    }

    @Test fun startingNewKeepsTheOldDevice() {
        toStage(5)
        val r = call("setup_begin", "description" to str("something else"))
        assertTrue(r["message"]!!.jsonPrimitive.content.contains("saved at stage 5"))
        assertEquals(5, rt.devices.get("arm")!!.commissioning.stage)
        assertEquals(1, call("setup_status")["stage"]!!.jsonPrimitive.int)
    }

    @Test fun llmsCanReachSetupThroughMcp() {
        val mcp = McpHandler(rt)
        val list = mcp.handle(parse("""{"id":1,"method":"tools/list"}"""))!!["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = list.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("setup_status", "setup_propose_device", "device_run_skill", "vehicle_stop")))
        assertTrue(names.all { Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it) })
        val r = mcp.callForApi("setup_status", JsonObject(emptyMap()))
        assertTrue(r["content"].toString().contains("Describe"))
    }
}
