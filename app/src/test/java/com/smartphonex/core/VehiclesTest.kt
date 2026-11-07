package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test

class VehiclesTest {
    private fun preset(id: String) = Presets.all.first { it.id == id }
    private fun mix(id: String, t: Double = 0.0, y: Double = 0.0, p: Double = 0.0, r: Double = 0.0, safe: Boolean = false) =
        Mixer.mix(preset(id).channels, ActuatorOutput(t, y, p, r), safe).associate { it.name to it }

    @Test fun carMapsThrottleAndSteering() {
        val m = mix("car", t = 0.5, y = -1.0)
        assertEquals(1750, m["Motor"]!!.pulseUs)
        assertEquals(1000, m["Steering"]!!.pulseUs)
    }

    @Test fun tankTurnsByDrivingSidesApart() {
        val m = mix("skid", t = 0.0, y = 0.5)
        assertEquals(-0.5, m["Left motor"]!!.value, 1e-9)
        assertEquals(0.5, m["Right motor"]!!.value, 1e-9)
    }

    @Test fun planeSeparatesAllAxes() {
        val m = mix("plane", t = 0.5, y = 0.2, p = -0.4, r = 0.6)
        assertEquals(1500, m["Motor"]!!.pulseUs)
        assertEquals(0.6, m["Aileron"]!!.value, 1e-9)
        assertEquals(-0.4, m["Elevator"]!!.value, 1e-9)
        assertEquals(0.2, m["Rudder"]!!.value, 1e-9)
    }

    @Test fun forwardOnlyMotorCannotReverse() {
        assertEquals(1000, mix("plane", t = -1.0)["Motor"]!!.pulseUs)
    }

    @Test fun elevonsMixPitchAndRoll() {
        val m = mix("wing", p = 0.5, r = 0.5)
        assertEquals(0.7, m["Left elevon"]!!.value, 1e-9)
        assertEquals(0.0, m["Right elevon"]!!.value, 1e-9)
    }

    @Test fun safeStateIgnoresThePilot() {
        val m = mix("plane", t = 1.0, y = 1.0, p = 1.0, r = 1.0, safe = true)
        assertEquals(1000, m["Motor"]!!.pulseUs)
        for (c in listOf("Aileron", "Elevator", "Rudder")) assertEquals(1500, m[c]!!.pulseUs)
    }

    @Test fun everyPresetIsValid() {
        for (p in Presets.all) assertNull(p.id, Mixer.validate(p.channels))
    }

    @Test fun aircraftAreNotHandedToTheAiByDefault() {
        for (id in listOf("plane", "wing")) {
            assertFalse(preset(id).aiMayArm)
            assertFalse(preset(id).missionsAllowed)
        }
    }

    @Test fun invalidChannelsRefused() {
        assertNotNull(Mixer.validate(emptyList()))
        assertNotNull(Mixer.validate(List(17) { Channel("c$it") }))
        assertNotNull(Mixer.validate(listOf(Channel("a"), Channel("a"))))
        assertNotNull(Mixer.validate(listOf(Channel("a", throttle = 9.0))))
        assertNotNull(Mixer.validate(listOf(Channel("a", failsafe = Double.NaN))))
        assertNotNull(Mixer.validate(listOf(Channel("a", ChannelKind.MOTOR_FORWARD, failsafe = -0.5))))
    }

    @Test fun crcMatchesPublishedCheckValue() {
        assertEquals(0xBC, SerialFrames.crc8DvbS2("123456789".toByteArray()))
    }

    @Test fun crsfFrameHasCorrectShapeAndValues() {
        val f = SerialFrames.crsf(listOf(1500, 2000))
        assertEquals(26, f.size)
        assertEquals(0xC8.toByte(), f[0])
        assertEquals(24.toByte(), f[1])
        assertEquals(0x16.toByte(), f[2])
        val ch0 = (f[3].toInt() and 0xFF) or ((f[4].toInt() and 0x07) shl 8)
        val ch1 = ((f[4].toInt() and 0xF8) ushr 3) or ((f[5].toInt() and 0x3F) shl 5)
        assertEquals(992, ch0)
        assertEquals(1792, ch1)
        assertEquals(SerialFrames.crc8DvbS2(f, 2, 25), f[25].toInt() and 0xFF)
    }

    @Test fun ibusFrameHasCorrectShapeAndChecksum() {
        val f = SerialFrames.ibus(listOf(1234))
        assertEquals(32, f.size)
        assertEquals(0x20.toByte(), f[0]); assertEquals(0x40.toByte(), f[1])
        assertEquals(1234, (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8))
        var sum = 0
        for (i in 0 until 30) sum += f[i].toInt() and 0xFF
        assertEquals(0xFFFF - sum, (f[30].toInt() and 0xFF) or ((f[31].toInt() and 0xFF) shl 8))
    }

    // --- Controller with the output preview ---
    private var now = 1_000_000L
    private fun controller(profile: String): VehicleController {
        val store = ProfileStore(null)
        store.select(profile)
        val preview = OutputPreview()
        return VehicleController(preview, store, EventLog(clock = { now }), clock = { now }, adapterFor = { preview })
    }
    private fun VehicleController.send(c: Command, s: Source = Source.GROUND) = submit(Envelope(s, null, null, c))
    private fun VehicleController.step(ms: Int): Telemetry { var t = latest(); repeat(ms / 20) { now += 20; t = tick(0.02) }; return t }

    @Test fun previewShowsPilotInputOnChannels() {
        val c = controller("plane")
        c.send(Command.SetMode(Mode.MANUAL))
        c.send(Command.Drive(0.5, 0.0, pitch = 0.5, roll = -0.5))
        val t = c.step(40)
        val out = t.outputs.associate { it.name to it.pulseUs }
        assertEquals(1500, out["Motor"])
        assertEquals(1750, out["Elevator"])
        assertEquals(1250, out["Aileron"])
        assertTrue(t.frameHex.startsWith("C8 18 16"))
    }

    @Test fun linkLossMakesAPlaneSafe() {
        val c = controller("plane")
        c.send(Command.SetMode(Mode.MANUAL))
        c.send(Command.Drive(1.0, 1.0, 1.0, 1.0))
        assertEquals(2000, c.step(40).outputs.first { it.name == "Motor" }.pulseUs)
        val t = c.step(1000)
        assertEquals(Mode.FAILSAFE, t.mode)
        assertEquals(listOf(1000, 1500, 1500, 1500), t.outputs.map { it.pulseUs })
    }

    @Test fun aiCannotArmAPlaneOrRunMissionsOnIt() {
        val c = controller("plane")
        assertEquals("not_permitted", c.send(Command.SetMode(Mode.AUTO), Source.AI).code)
        assertEquals("unsupported", c.send(Command.RunMission("square")).code)
    }

    @Test fun missionWithoutSpeedSensorDoesNotWindUp() {
        val c = controller("car")
        c.send(Command.SetMode(Mode.AUTO), Source.AI)
        assertTrue(c.send(Command.RunMission("straight_and_stop"), Source.AI).accepted)
        val t = c.step(2500)
        assertEquals(1.5 * 0.25, t.throttle, 1e-6)
    }

    @Test fun holdSpeedRefusedWithoutSpeedSensor() {
        val c = controller("car")
        c.send(Command.SetMode(Mode.AUTO), Source.AI)
        assertEquals("unsupported", c.send(Command.SetSpeed(1.0, 1000), Source.AI).code)
    }

    @Test fun aiCannotChangeOutputs() {
        assertEquals("not_permitted", controller("car").send(Command.SetChannels(listOf(Channel("x")), null), Source.AI).code)
    }

    @Test fun outputsLockedWhileArmed() {
        val c = controller("custom")
        c.send(Command.SetMode(Mode.MANUAL))
        assertEquals("not_permitted", c.send(Command.SetChannels(listOf(Channel("x")), null)).code)
    }

    @Test fun customChannelsApplyAndPersistInProfile() {
        val c = controller("custom")
        assertTrue(c.send(Command.SetChannels(listOf(Channel("Winch", throttle = 0.5)), "ibus")).accepted)
        assertEquals(listOf("Winch"), c.step(40).outputs.map { it.name })
        assertTrue(c.latest().frameHex.startsWith("20 40"))
    }
}

class SensorHubTest {
    private var now = 1_000_000L
    private val hub = SensorHub(null, clock = { now })
    private fun add(id: String, priority: Int, rate: Double = 20.0, private: Boolean = false, reason: String? = null) =
        hub.register(SensorInfo(id, id, "motion", "u", listOf("x"), isPrivate = private, unavailableReason = reason), SensorConfig(true, rate, priority))

    private fun feed(vararg ids: String) { ids.forEach { hub.update(it, doubleArrayOf(1.0)) } }

    @Test fun onlyDiscoveredSensorsExist() {
        assertTrue(hub.catalog().isEmpty())
        assertNull(hub.tick())
        assertEquals("unknown sensor", hub.configure("nope", true, null, null))
    }

    @Test fun disabledSensorIsNotSent() {
        add("a", 1); add("b", 1); feed("a", "b")
        hub.configure("b", false, null, null)
        val p = hub.tick()!!.full
        assertTrue(p.contains("\"a\"")); assertFalse(p.contains("\"b\""))
    }

    @Test fun rateIsRespected() {
        add("a", 1, rate = 2.0); feed("a")
        assertNotNull(hub.tick())
        now += 100; feed("a"); assertNull(hub.tick())
        now += 401; feed("a"); assertNotNull(hub.tick())
    }

    @Test fun staleReadingNotResent() {
        add("a", 1); feed("a"); assertNotNull(hub.tick())
        now += 1000; assertNull(hub.tick())
    }

    @Test fun overBudgetDropsLowPriorityFirst() {
        add("low", Priority.LOW); add("high", Priority.HIGH)
        hub.setBudget(1)
        var high = 0; var low = 0
        repeat(200) {
            now += 50
            feed("low", "high")
            hub.tick()?.full?.let { if (it.contains("\"high\"")) high++; if (it.contains("\"low\"")) low++ }
        }
        assertTrue("high $high, low $low", high > low)
        assertTrue(high > 0)
    }

    @Test fun unlimitedBudgetDropsNothing() {
        add("low", Priority.LOW); add("high", Priority.HIGH)
        repeat(50) { now += 50; feed("low", "high"); val p = hub.tick()!!.full; assertTrue(p.contains("low") && p.contains("high")) }
    }

    @Test fun privateSensorsAreLeftOutOfThePublicStream() {
        add("gps", 1, private = true); add("imu", 1); feed("gps", "imu")
        val p = hub.tick()!!
        assertTrue(p.full.contains("gps")); assertFalse(p.public.contains("gps")); assertTrue(p.public.contains("imu"))
        assertTrue(hub.catalogJson(false).none { it.toString().contains("gps") })
        assertFalse(hub.readings(false).containsKey("gps"))
        assertTrue(hub.readings(true).containsKey("gps"))
    }

    @Test fun unavailableSensorCannotBeEnabled() {
        add("hr", 1, reason = "needs permission")
        assertEquals(false, hub.config("hr")!!.enabled)
        assertEquals("needs permission", hub.configure("hr", true, null, null))
    }

    @Test fun badSettingsRefused() {
        add("a", 1)
        assertNotNull(hub.configure("a", null, 500.0, null))
        assertNotNull(hub.configure("a", null, null, 9))
        assertNotNull(hub.setBudget(-1))
        assertNotNull(hub.setCamera(CameraConfig(fps = 500.0)))
    }

    @Test fun videoYieldsToBudgetAndCanBeHighPriority() {
        assertTrue("unlimited", hub.takeVideo(50_000, Priority.LOW))
        hub.setBudget(100) // 12500 bytes a second.
        now += 2000; hub.tick()
        assertFalse("larger than a second of budget", hub.takeVideo(50_000, Priority.HIGH))
        assertFalse("low must leave the reserve", hub.takeVideo(10_000, Priority.LOW))
        assertTrue("high may use it", hub.takeVideo(10_000, Priority.HIGH))
    }

    @Test fun settingsSurviveRestartButCameraStartsOff() {
        val f = java.io.File.createTempFile("sensors", ".json"); f.delete()
        val a = SensorHub(f, clock = { now })
        a.register(SensorInfo("imu", "imu", "motion", "", listOf("x")), SensorConfig(true, 5.0, 2))
        a.configure("imu", false, 7.0, 1)
        a.setBudget(250)
        a.setCamera(CameraConfig(enabled = true, cameraId = "1", width = 640, height = 480))
        a.setAiPrivateAccess(true)
        val b = SensorHub(f, clock = { now })
        b.register(SensorInfo("imu", "imu", "motion", "", listOf("x")), SensorConfig(true, 5.0, 2))
        assertEquals(SensorConfig(false, 7.0, 1), b.config("imu"))
        assertEquals(250, b.budgetKbps)
        assertEquals("1", b.camera.cameraId)
        assertEquals(640, b.camera.width)
        assertFalse("camera must be turned on again on purpose", b.camera.enabled)
        assertTrue(b.aiPrivateAccess)
        f.delete()
    }
}
