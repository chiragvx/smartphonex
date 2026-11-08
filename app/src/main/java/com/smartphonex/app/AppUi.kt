package com.smartphonex.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.smartphonex.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs
import kotlin.math.roundToInt

private val Good = Color(0xFF3DBE6B)
private val Warn = Color(0xFFE5A823)
private val Bad = Color(0xFFE5484D)
private val Off = Color(0xFF7D8590)
private val StopRed = Color(0xFFC81E1E)

private fun Context.say(r: CommandResult, ok: String? = null) {
    if (!r.accepted) Toast.makeText(this, r.message.ifEmpty { r.code }, Toast.LENGTH_LONG).show()
    else if (ok != null) Toast.makeText(this, ok, Toast.LENGTH_SHORT).show()
}

private fun VehicleRuntime.ask(name: String, body: JsonObjectBuilder.() -> Unit = {}) = local(Command.OpsRequest(name, buildJsonObject(body)))

private fun VehicleRuntime.param(name: String) = params.get(name, profiles.active.vehicleType)

/** Changes a setting from the phone's screen, with the same checks and history as the ground station. */
private fun VehicleRuntime.setParam(name: String, value: Double): String? {
    val before = runCatching { param(name) }.getOrNull()
    return params.set(name, value).also {
        if (it == null) log.event("param", "PHONE", name, "set to $value")
        if (it == null && before != null && before != value) ops.recordParam(name, before, value)
    }
}

private fun statusColor(s: String) = when (s) {
    "ok", "pass" -> Good
    "warn" -> Warn
    "fail", "bad" -> Bad
    else -> Off
}

private fun modeLabel(m: FlightMode) = when (m) {
    FlightMode.MANUAL -> "Manual"
    FlightMode.STABILIZE -> "Stabilize"
    FlightMode.HOLD_HEADING -> "Hold heading"
    FlightMode.RETURN_HOME -> "Return home"
    FlightMode.AUTO -> "Mission"
    FlightMode.HOLD -> "Hold"
}

private fun address(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }?.hostAddress
}.getOrNull()

/** Everything one frame of the app shows. It's rebuilt ten times a second, on the phone itself. */
private class Frame(rt: VehicleRuntime, val slow: Slow?) {
    val t: Telemetry = rt.controller.latest()
    val armed = t.mode == Mode.MANUAL || t.mode == Mode.AUTO
    val fm: FlightMode = rt.fc.mode
    val profile = rt.profiles.active
    val owner = rt.controller.inputOwner
    val killed = rt.controller.killed
    val attitude = rt.fc.attitudeNow
    val test = rt.ops.tester.active()
    val mission = rt.ops.missions
}

/** The parts that are costlier to work out, refreshed twice a second. */
private class Slow(rt: VehicleRuntime) {
    val readiness = rt.ops.readiness()
    val connections = rt.ops.connections()
    val triggers = rt.ops.triggersJson().map { it.jsonObject }
    val checklist = rt.ops.checklistItems()
    val report = rt.ops.lastDiag
    val diag = rt.ops.diagRunning
    val health = rt.fc.healthMap()
    val modeProblems = FlightMode.entries.associateWith { rt.fc.modeProblem(it) }
}

@Composable
fun SmartPhoneXApp(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var contrast by remember { mutableStateOf(prefs.getBoolean("contrast", false)) }
    val scheme = if (contrast) lightColorScheme(background = Color.White, surface = Color.White, onBackground = Color.Black, onSurface = Color.Black, surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = Color.Black, primary = Color.Black)
    else darkColorScheme(background = Color(0xFF0E1116), surface = Color(0xFF161B22), surfaceVariant = Color(0xFF21262D), primary = Color(0xFF58A6FF))
    MaterialTheme(colorScheme = scheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            var rt by remember { mutableStateOf<VehicleRuntime?>(null) }
            // Follows the service's runtime, which is rebuilt after a restore.
            LaunchedEffect(Unit) { while (true) { VehicleService.runtime.let { if (it !== rt) rt = it }; delay(if (rt == null) 100 else 500) } }
            val r = rt
            if (r == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Starting...") }
            else key(r) { Root(activity, r, contrast) { contrast = it; prefs.edit().putBoolean("contrast", it).apply() } }
        }
    }
}

@Composable
private fun Root(activity: MainActivity, rt: VehicleRuntime, contrast: Boolean, setContrast: (Boolean) -> Unit) {
    val prefs = remember { activity.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var frame by remember { mutableStateOf(Frame(rt, Slow(rt))) }
    // Five times a second is plenty for people to read, and halves the drawing work. Nothing is rebuilt while out of sight.
    val visible by activity.visible
    LaunchedEffect(rt, visible) {
        var n = 0
        var slow = frame.slow
        while (isActive && visible) {
            delay(200)
            if (++n % 3 == 0) slow = Slow(rt)
            frame = Frame(rt, slow)
        }
    }
    // Armed from this screen, a person is here, so it keeps saying so on every tab. Leaving the app or
    // leaving MANUAL for any reason ends it. Re-arming is always a separate, deliberate act.
    val driving by activity.drivingAllowed
    LaunchedEffect(driving) {
        while (driving && isActive) {
            val p = rt.profiles.active
            if (rt.controller.latest().mode != Mode.MANUAL) { activity.drivingAllowed.value = false; break }
            rt.local(Command.Drive(PhoneSticks.thr.value.toDouble() * p.maxThrottle, PhoneSticks.str.value.toDouble() * p.maxSteering, PhoneSticks.pit.value.toDouble(), PhoneSticks.rol.value.toDouble()))
            delay(50)
        }
    }
    var tab by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf(!prefs.getBoolean("guided", false)) }
    var faceShown by remember { mutableStateOf(false) }
    val f = frame
    // The face comes up after the screen is left alone, or when someone asks it to show an expression. Never while
    // driving from this screen or running an output test, which need the controls in view.
    val faceMode = Face.mode(rt.param("FACE_MODE"))
    val busy = driving || f.test != null || locked || guide
    // A ground station or AI can show or hide it too, even with the face set to off. Only commands newer than this screen count.
    var faceCmdSeen by remember { mutableStateOf(rt.ops.faceCommand?.second) }
    LaunchedEffect(faceMode, busy) {
        if (busy) faceShown = false
        while (isActive) {
            val quiet = (android.os.SystemClock.uptimeMillis() - activity.lastInput) / 1000.0
            val c = rt.ops.faceCommand
            if (c != null && c.second != faceCmdSeen) {
                faceCmdSeen = c.second
                if (!busy) faceShown = c.first
                // Hidden by request: start the idle wait again, so it doesn't pop straight back up.
                if (!c.first) activity.lastInput = android.os.SystemClock.uptimeMillis()
            } else if (!busy && faceMode != Face.Mode.OFF && !faceShown) {
                val asked = rt.ops.faceRequest?.let { it.second > System.currentTimeMillis() } == true
                if (quiet >= Face.idleSeconds(faceMode, rt.param("FACE_IDLE_S")) || (asked && quiet >= 3)) faceShown = true
            }
            val req = rt.ops.faceRequest?.takeIf { it.second > System.currentTimeMillis() }
            rt.ops.faceStatus = buildJsonObject {
                put("shown", faceShown)
                put("mode", faceMode.name.lowercase())
                put("requested", req?.first?.name?.lowercase())
                put("held", req?.second == Long.MAX_VALUE)
                when {
                    driving -> put("blocked", "driving from the phone's screen")
                    f.test != null -> put("blocked", "an output test is running")
                    locked -> put("blocked", "touch is locked")
                    guide -> put("blocked", "the first-run guide is open")
                }
            }
            delay(300)
        }
    }
    // While the face shows, it's all that's drawn: the controls underneath aren't rebuilt.
    if (!faceShown) Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar(rt, f)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val page = web
            if (page != null) GroundWeb(rt, page) { web = null }
            // Each tab has its own scroll position, so Home always opens with arming in view.
            else key(tab) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (tab) {
                        0 -> HomeTab(activity, rt, f)
                        1 -> ConnectedTab(rt, f)
                        2 -> TrimTab(rt, f)
                        3 -> TestTab(rt, f)
                        else -> MoreTab(activity, rt, f, contrast, setContrast, { locked = true }, { web = it }, { guide = true }, { faceShown = true })
                    }
                }
            }
        }
        Button(
            onClick = { activity.drivingAllowed.value = false; rt.local(Command.Stop) },
            colors = ButtonDefaults.buttonColors(containerColor = StopRed, contentColor = Color.White),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text("STOP", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        if (web == null) NavigationBar {
            listOf("Home" to Icons.Filled.Home, "Connected" to Icons.Filled.Share, "Trim" to Icons.Filled.Build, "Test" to Icons.Filled.CheckCircle, "More" to Icons.Filled.MoreVert).forEachIndexed { i, (name, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, name) }, label = { Text(name) })
            }
        }
    }
    if (locked) TouchLock { locked = false }
    if (guide) FirstRunGuide { guide = false; prefs.edit().putBoolean("guided", true).apply() }
    if (faceShown) {
        val quiet = (android.os.SystemClock.uptimeMillis() - activity.lastInput) / 1000.0
        val look = Face.look(Face.Input(
            f.killed, f.t.mode, f.t.failsafeReason, f.t.steering, f.t.throttle, f.mission.state == MissionEngine.State.RUNNING,
            rt.ops.faceRequest, System.currentTimeMillis(), quiet, f.slow?.health?.get("battery") == "bad", f.slow?.health?.get("heat") == "bad",
        ))
        FaceScreen(activity, look, rt.param("FACE_STYLE").toInt(), rt.param("FACE_HUE").toFloat(), rt.param("FACE_BRIGHT").toFloat(),
            onWake = { faceShown = false })
    }
}

@Composable
private fun TopBar(rt: VehicleRuntime, f: Frame) {
    val camLive = VehicleService.cameraStreamer?.isLive == true
    val locLive = rt.sensors.config("phone.location")?.enabled == true
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.profile.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            val (label, color) = when {
                f.killed -> "KILLED" to Bad
                f.t.mode == Mode.FAILSAFE -> "FAILSAFE" to Bad
                f.armed -> "ARMED" to Warn
                else -> "DISARMED" to Good
            }
            Text(label, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Text("${modeLabel(f.fm)}  ·  In control: ${f.owner?.name?.lowercase() ?: "nobody"}" + (f.t.failsafeReason?.let { "  ·  $it" } ?: ""), fontSize = 13.sp)
        if (camLive || locLive) Text(listOfNotNull("CAMERA LIVE".takeIf { camLive }, "LOCATION SHARED".takeIf { locLive }).joinToString("  ·  "), color = Bad, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun Card(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(12.dp).clip(CircleShape).background(c))

@Composable
private fun StatusRow(label: String, status: String, detail: String, fix: String = "") {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(top = 5.dp, end = 8.dp)) { Dot(statusColor(status)) }
        Column {
            Text(label, fontWeight = FontWeight.Medium)
            if (detail.isNotEmpty()) Text(detail, fontSize = 13.sp)
            if (fix.isNotEmpty()) Text(fix, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// --- Home.

@Composable
private fun HomeTab(activity: MainActivity, rt: VehicleRuntime, f: Frame) {
    val ctx = LocalContext.current
    val slow = f.slow ?: return
    Card {
        val fails = slow.readiness.filter { it.status == "fail" }
        val warns = slow.readiness.filter { it.status == "warn" }
        if (f.armed) {
            OutlinedButton(onClick = { ctx.say(rt.local(Command.SetMode(Mode.DISARMED))) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Disarm", fontSize = 18.sp) }
        } else {
            if (fails.isEmpty()) SlideToArm {
                val r = rt.local(Command.SetMode(Mode.MANUAL))
                ctx.say(r, "Armed")
                if (r.accepted) activity.drivingAllowed.value = true
            }
            else fails.forEach { StatusRow(it.label, it.status, it.detail, it.fix) }
        }
        warns.forEach { StatusRow(it.label, it.status, it.detail, it.fix) }
    }
    Card("Mode") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(FlightMode.MANUAL, FlightMode.STABILIZE, FlightMode.HOLD_HEADING, FlightMode.RETURN_HOME, FlightMode.HOLD).forEach { m ->
                val problem = slow.modeProblems[m]
                FilterChip(selected = f.fm == m, onClick = { ctx.say(rt.local(Command.SetFlightMode(m))) }, label = { Text(modeLabel(m)) }, enabled = problem == null || f.fm == m)
            }
        }
        slow.modeProblems[FlightMode.STABILIZE]?.let { Text("Assisted modes: $it", fontSize = 13.sp) }
    }
    if (f.mission.mission != null) MissionCard(rt, f)
    Card("Health") {
        val labels = listOf("gyro" to "Gyro", "accel" to "Accel", "compass" to "Compass", "baro" to "Barometer", "gps" to "GPS", "attitude" to "Attitude", "battery" to "Battery", "heat" to "Heat", "timing" to "Timing")
        labels.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (id, name) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Dot(when (slow.health[id]) { "ok" -> Good; "bad" -> Bad; else -> Off })
                        Spacer(Modifier.width(6.dp))
                        Text(name, fontSize = 13.sp)
                    }
                }
            }
        }
        Text("Roll %.1f°   Pitch %.1f°   Heading %.0f°".format(f.attitude.roll, f.attitude.pitch, f.attitude.yaw), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text("Loop %.0f Hz, slowest step %.1f ms".format(f.t.loop.hz, f.t.loop.dtMaxMs), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(rt.fc.batteryText(), fontSize = 13.sp)
    }
    if (slow.triggers.isNotEmpty()) TriggersCard(rt, slow.triggers)
    DriveCard(activity, rt, f)
    KillCard(rt)
}

@Composable
private fun MissionCard(rt: VehicleRuntime, f: Frame) {
    val ctx = LocalContext.current
    val m = f.mission
    Card("Mission: ${m.mission?.name}") {
        Text("${m.state.name.lowercase()}, point ${(m.index + 1).coerceAtMost(m.mission?.items?.size ?: 0)} of ${m.mission?.items?.size}" + (m.lastDist?.let { ", %.0f m to go".format(it) } ?: ""))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (m.state) {
                MissionEngine.State.RUNNING -> {
                    Button(onClick = { ctx.say(rt.ask("mission_pause")) }) { Text("Pause") }
                    OutlinedButton(onClick = { ctx.say(rt.ask("mission_skip")) }) { Text("Skip") }
                    OutlinedButton(onClick = { ctx.say(rt.ask("mission_abort")) }) { Text("Abort") }
                }
                MissionEngine.State.PAUSED -> {
                    Button(onClick = { ctx.say(rt.ask("mission_resume")) }) { Text("Resume") }
                    OutlinedButton(onClick = { ctx.say(rt.ask("mission_abort")) }) { Text("Abort") }
                }
                else -> HoldButton("Hold to start mission", 1200, Modifier.fillMaxWidth()) { ctx.say(rt.ask("mission_start"), "Mission started") }
            }
        }
    }
}

@Composable
private fun TriggersCard(rt: VehicleRuntime, triggers: List<JsonObject>) {
    val ctx = LocalContext.current
    Card("Triggers") {
        triggers.forEach { t ->
            val id = t["id"]!!.jsonPrimitive.content
            val label = t["label"]!!.jsonPrimitive.content
            val danger = t["danger"]?.jsonPrimitive?.boolean == true
            if (danger) HoldButton("Hold: $label", 1500, Modifier.fillMaxWidth(), Bad) {
                val r = rt.ask("trigger_prepare") { put("id", id) }
                ctx.say(if (r.accepted) rt.ask("trigger_fire") { put("id", id) } else r, "$label fired")
            } else OutlinedButton(onClick = { ctx.say(rt.ask("trigger_fire") { put("id", id) }, "$label fired") }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}

/** Drive from the screen. Sliders spring back, so letting go means zero. */
private object PhoneSticks {
    val thr = mutableStateOf(0f)
    val str = mutableStateOf(0f)
    val pit = mutableStateOf(0f)
    val rol = mutableStateOf(0f)
}

@Composable
private fun DriveCard(activity: MainActivity, rt: VehicleRuntime, f: Frame) {
    var driving by activity.drivingAllowed
    var thr by PhoneSticks.thr
    var str by PhoneSticks.str
    var pit by PhoneSticks.pit
    var rol by PhoneSticks.rol
    val surfaces = f.profile.channels.any { it.pitch != 0.0 || it.roll != 0.0 }
    Card("Drive from this screen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (f.armed) "Use the sliders below" else "Arm first", modifier = Modifier.weight(1f))
            Switch(checked = driving, enabled = f.t.mode == Mode.MANUAL, onCheckedChange = { driving = it })
        }
        if (driving) {
            Text("Throttle"); Slider(thr, { thr = it }, valueRange = -1f..1f, onValueChangeFinished = { thr = 0f })
            Text("Steering"); Slider(str, { str = it }, valueRange = -1f..1f, onValueChangeFinished = { str = 0f })
            if (surfaces) {
                Text("Pitch"); Slider(pit, { pit = it }, valueRange = -1f..1f, onValueChangeFinished = { pit = 0f })
                Text("Roll"); Slider(rol, { rol = it }, valueRange = -1f..1f, onValueChangeFinished = { rol = 0f })
            }
        }
    }
}

@Composable
private fun KillCard(rt: VehicleRuntime) {
    val ctx = LocalContext.current
    Card("Kill") {
        Text("Cuts every output at once, with no glide or slow-down. Use Stop first unless something is going badly wrong.", fontSize = 13.sp)
        HoldButton("Hold 2 s to kill", 2000, Modifier.fillMaxWidth(), Bad) { ctx.say(rt.local(Command.Kill), "All outputs off") }
    }
}

@Composable
private fun SlideToArm(onArm: () -> Unit) {
    var width by remember { mutableStateOf(1) }
    var x by remember { mutableStateOf(0f) }
    val thumb = 64.dp
    val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumb.toPx() }
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceVariant).onSizeChanged { width = it.width }) {
        Text("Slide to arm", modifier = Modifier.align(Alignment.Center), fontSize = 18.sp)
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), 0) }.size(thumb).clip(CircleShape).background(Warn)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            if (x >= width - thumbPx - 8) onArm()
                            x = 0f
                        },
                        onDragCancel = { x = 0f },
                    ) { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(0f, (width - thumbPx).coerceAtLeast(0f))
                    }
                },
        )
    }
}

/** Fires once after being held this long. Letting go early does nothing. */
@Composable
private fun HoldButton(label: String, ms: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, onHeld: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    val held by rememberUpdatedState(onHeld)
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, color, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val start = System.currentTimeMillis()
                    var fired = false
                    kotlinx.coroutines.coroutineScope {
                        val job = launchHold(ms, start, { progress = it }) { fired = true; held() }
                        tryAwaitRelease()
                        job.cancel()
                    }
                    progress = 0f
                    if (fired) Unit
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).align(Alignment.CenterStart).background(color.copy(alpha = 0.35f)))
        Text(label, fontWeight = FontWeight.Medium)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchHold(ms: Long, start: Long, progress: (Float) -> Unit, done: () -> Unit) = launch {
    while (isActive) {
        val p = (System.currentTimeMillis() - start).toFloat() / ms
        progress(p.coerceAtMost(1f))
        if (p >= 1f) { done(); break }
        delay(30)
    }
}

@Composable
private fun TouchLock(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
            Text("Touch locked", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("This screen ignores touches, so vibration or spray can't press anything. Stop still works from the ground station, receiver and gamepad.", color = Color.White)
            HoldButton("Hold 2 s to unlock", 2000, Modifier.width(240.dp), Color.White, onUnlock)
        }
    }
}

@Composable
private fun FirstRunGuide(onDone: () -> Unit) {
    val steps = listOf(
        "Pick your device" to "Open More and choose what this phone controls. With nothing attached, outputs are only previewed, so it's safe to explore.",
        "See what's connected" to "Connected lists everything linked to the phone, with how to fix anything that's wrong.",
        "Trim" to "Trim centers each output and sets level for how the phone is mounted.",
        "Test, then arm" to "Test checks that everything works. Then slide to arm on Home. The red STOP is always on screen.",
    )
    var i by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color(0xEE000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${i + 1} of ${steps.size}", color = Color.White)
            Text(steps[i].first, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(steps[i].second, color = Color.White, fontSize = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (i == steps.lastIndex) onDone() else i++ }) { Text(if (i == steps.lastIndex) "Got it" else "Next") }
                if (i < steps.lastIndex) TextButton(onClick = onDone) { Text("Skip", color = Color.White) }
            }
        }
    }
}

// --- Connected.

@Composable
private fun ConnectedTab(rt: VehicleRuntime, f: Frame) {
    val slow = f.slow ?: return
    val ip = address()
    Card("Pair a browser") {
        val code = rt.pairing.currentCode()
        val locked = rt.pairing.lockedSeconds()
        val url = "http://${ip ?: "phone-ip"}:8080"
        Text(url, fontFamily = FontFamily.Monospace)
        if (locked > 0) Text("Locked for $locked s after wrong codes", color = Bad)
        else {
            Text(code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            val qr = remember(url, code) { qrBitmap("$url/#pair=$code") }
            if (ip != null && qr != null) {
                Image(qr.asImageBitmap(), "QR code to pair a browser", Modifier.size(200.dp).align(Alignment.CenterHorizontally).background(Color.White).padding(8.dp))
                Text("Scan it on the same network, or type the address and code.", fontSize = 13.sp)
            }
        }
    }
    Card {
        slow.connections.forEach { c -> StatusRow(c.label, c.status, c.detail, c.fix) }
    }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

// --- Trim.

@Composable
private fun TrimTab(rt: VehicleRuntime, f: Frame) {
    val hardware = rt.engine.device?.takeIf { f.profile.adapter == "device" }?.commissioning?.signedOffBy != null
    Text(
        when {
            !hardware -> "Preview only: no hardware moves. Values show what it would receive."
            f.armed -> "Live: this moves the real output. Small steps only."
            else -> "Saved now. It moves the real output once armed, or while testing."
        },
        fontWeight = FontWeight.Medium,
    )
    rt.ops.outputNames().forEach { TrimRow(rt, f, it) }
    LevelCard(rt, f)
}

@Composable
private fun TrimRow(rt: VehicleRuntime, f: Frame, name: String) {
    val ctx = LocalContext.current
    val cur = rt.ops.trims.get(name)
    val out = f.t.outputs.firstOrNull { it.name == name }
    var open by remember(name) { mutableStateOf(false) }
    fun set(body: JsonObjectBuilder.() -> Unit) = ctx.say(rt.ask("trim_set") { put("output", name); body() })
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(out?.let { "%+.2f  %d µs".format(it.value, it.pulseUs) } ?: "--", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val step = if (f.armed) 0.01 else 0.005
            OutlinedButton(onClick = { set { put("trim", cur.trim - step) } }) { Text("-") }
            Text("%+.3f".format(cur.trim), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 20.sp)
            OutlinedButton(onClick = { set { put("trim", cur.trim + step) } }) { Text("+") }
        }
        if (!f.armed) {
            var live by remember(name, cur.trim) { mutableStateOf(cur.trim.toFloat()) }
            Slider(live, { live = it }, valueRange = -0.3f..0.3f, onValueChangeFinished = { set { put("trim", Math.round(live * 1000) / 1000.0) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { set { put("trim", 0.0) } }) { Text("Center") }
            OutlinedButton(onClick = { ctx.say(rt.ask("trim_undo") { put("output", name) }) }) { Text("Undo") }
            TextButton(onClick = { open = !open }) { Text(if (open) "Less" else "More") }
        }
        if (open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reverse", modifier = Modifier.weight(1f))
                Switch(checked = cur.reverse, enabled = !f.armed, onCheckedChange = { v -> set { put("reverse", v) } })
            }
            var lo by remember(name, cur.lo) { mutableStateOf(cur.lo.toFloat()) }
            var hi by remember(name, cur.hi) { mutableStateOf(cur.hi.toFloat()) }
            Text("Low limit %.2f".format(lo))
            Slider(lo, { lo = it }, valueRange = -1f..0f, enabled = !f.armed, onValueChangeFinished = { set { put("lo", Math.round(lo * 100) / 100.0) } })
            Text("High limit %.2f".format(hi))
            Slider(hi, { hi = it }, valueRange = 0f..1f, enabled = !f.armed, onValueChangeFinished = { set { put("hi", Math.round(hi * 100) / 100.0) } })
        }
    }
}

@Composable
private fun LevelCard(rt: VehicleRuntime, f: Frame) {
    val ctx = LocalContext.current
    Card("Level and mounting") {
        Horizon(f.attitude.roll, f.attitude.pitch, Modifier.fillMaxWidth().height(160.dp))
        Text("Roll %.1f°   Pitch %.1f°".format(f.attitude.roll, f.attitude.pitch), fontFamily = FontFamily.Monospace)
        Button(onClick = {
            val err = rt.fc.setLevel()
            Toast.makeText(ctx, err ?: "Level saved", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Set level") }
        Text("How the phone is mounted", fontWeight = FontWeight.Medium)
        val cur = rt.params.get("MOUNT_TYPE").toInt()
        listOf("▭  Flat, screen up", "▯  Upright, camera forward", "▱  Sideways, camera forward", "▬  Flat, screen down").forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !f.armed) { rt.params.set("MOUNT_TYPE", i.toDouble()) }) {
                RadioButton(selected = cur == i, enabled = !f.armed, onClick = { rt.params.set("MOUNT_TYPE", i.toDouble()) })
                Text(label)
            }
        }
    }
}

@Composable
private fun Horizon(rollDeg: Double, pitchDeg: Double, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp))) {
        val c = center
        val shift = (pitchDeg / 45.0 * size.height / 2).toFloat()
        val a = Math.toRadians(-rollDeg)
        val big = size.width + size.height
        val nx = -Math.sin(a).toFloat()
        val ny = Math.cos(a).toFloat()
        val mid = Offset(c.x + nx * shift, c.y + ny * shift)
        drawRect(Color(0xFF3A79B7), size = size)
        val path = androidx.compose.ui.graphics.Path().apply {
            val dx = ny; val dy = -nx
            moveTo(mid.x - dx * big, mid.y - dy * big)
            lineTo(mid.x + dx * big, mid.y + dy * big)
            lineTo(mid.x + dx * big + nx * big, mid.y + dy * big + ny * big)
            lineTo(mid.x - dx * big + nx * big, mid.y - dy * big + ny * big)
            close()
        }
        drawPath(path, Color(0xFF7A5230))
        drawLine(Color.Yellow, Offset(c.x - 50, c.y), Offset(c.x - 12, c.y), 5f)
        drawLine(Color.Yellow, Offset(c.x + 12, c.y), Offset(c.x + 50, c.y), 5f)
        drawRect(Color.Yellow, Offset(c.x - 3, c.y - 3), Size(6f, 6f))
    }
}

// --- Test.

@Composable
private fun TestTab(rt: VehicleRuntime, f: Frame) {
    val ctx = LocalContext.current
    val slow = f.slow ?: return
    // Leaving this page ends any test, whatever was happening.
    DisposableEffect(Unit) { onDispose { rt.ask("test_stop") } }
    Card("Ready to arm?") {
        slow.readiness.forEach { StatusRow(it.label, it.status, it.detail, it.fix) }
    }
    Card("Checklist") {
        val done = rt.ops.opsJson(true)["checklist"]!!.jsonArray.map { it.jsonObject }
        done.forEach { c ->
            val item = c["item"]!!.jsonPrimitive.content
            val ticked = c["done"]!!.jsonPrimitive.boolean
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ticked, { v -> rt.ask("checklist_set") { put("item", item); put("done", v) } })
                Text(item)
            }
        }
    }
    Card("Automatic tests") {
        val running = slow.diag
        Text("Sensors, GPS, timing, stop and link-loss. No output moves, and the phone is held still for them. It takes a few seconds.", fontSize = 13.sp)
        Button(enabled = running == null && !f.armed, onClick = { ctx.say(rt.ask("diagnostics_run")) }, modifier = Modifier.fillMaxWidth()) { Text(running?.let { "Testing: $it" } ?: "Run tests") }
        slow.report?.let { r ->
            val passed = r["passed"]?.jsonPrimitive?.boolean == true
            Text((if (passed) "Passed" else "Failed") + ", " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r["t"]!!.jsonPrimitive.long)), color = if (passed) Good else Bad, fontWeight = FontWeight.Bold)
            r["results"]?.jsonArray?.forEach { x ->
                val o = x.jsonObject
                StatusRow(o["test"]!!.jsonPrimitive.content, o["status"]!!.jsonPrimitive.content, o["detail"]!!.jsonPrimitive.content)
            }
        }
    }
    OutputTests(rt, f)
}

@Composable
private fun OutputTests(rt: VehicleRuntime, f: Frame) {
    var propsOff by remember { mutableStateOf(false) }
    Card("Output tests") {
        Text(if (f.armed) "Disarm to test outputs." else "Hold a button to run that output. Letting go stops it at once. Restrain anything that can move.", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(propsOff, { propsOff = it })
            Text("Props are off, or wheels off the ground")
        }
        f.test?.let { (o, v) -> Text("Running: $o at %+.2f".format(v), color = Warn, fontWeight = FontWeight.Bold) }
        val device = rt.engine.device?.takeIf { f.profile.adapter == "device" }
        rt.ops.outputNames().forEach { name ->
            val channel = f.profile.channels.firstOrNull { it.name == name }.takeIf { device == null }
            val motor = channel != null && channel.kind != ChannelKind.SERVO
            val limit = if (motor) rt.params.get("TEST_MAX_MOTOR") else rt.params.get("TEST_MAX_SERVO")
            var size by remember(name) { mutableStateOf((limit / 2).toFloat()) }
            Text(name, fontWeight = FontWeight.Medium)
            Text("Size %.2f (limit %.2f)".format(size, limit), fontSize = 13.sp)
            Slider(size, { size = it }, valueRange = 0f..limit.toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (channel?.kind != ChannelKind.MOTOR_FORWARD) RunWhileHeld("Hold: reverse", Modifier.weight(1f), rt, name, -size.toDouble(), propsOff, !f.armed)
                RunWhileHeld("Hold: forward", Modifier.weight(1f), rt, name, size.toDouble(), propsOff, !f.armed)
            }
        }
    }
}

/** Sends a heartbeat while pressed. If this app dies mid-press, the phone stops the test by itself. */
@Composable
private fun RunWhileHeld(label: String, modifier: Modifier, rt: VehicleRuntime, output: String, value: Double, confirmed: Boolean, enabled: Boolean) {
    val ctx = LocalContext.current
    val v by rememberUpdatedState(value)
    val conf by rememberUpdatedState(confirmed)
    val on by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.height(64.dp).clip(RoundedCornerShape(8.dp)).background(if (pressed) Warn else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(output) {
                detectTapGestures(onPress = {
                    if (!on) return@detectTapGestures
                    pressed = true
                    kotlinx.coroutines.coroutineScope {
                        val job = launch {
                            while (isActive) {
                                val r = rt.ask("test_output") { put("output", output); put("value", v); put("confirmed", conf) }
                                if (!r.accepted) { ctx.say(r); break }
                                delay(100)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    }
                    rt.ask("test_stop")
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontWeight = FontWeight.Medium, color = if (pressed) Color.Black else Color.Unspecified) }
}

// --- More.

@Composable
private fun MoreTab(activity: MainActivity, rt: VehicleRuntime, f: Frame, contrast: Boolean, setContrast: (Boolean) -> Unit, lock: () -> Unit, openWeb: (String) -> Unit, guide: () -> Unit, showFace: () -> Unit) {
    Card("Device") {
        Text(f.profile.name + if (rt.controller.currentAdapter is SimulatedCar) " (simulator)" else "")
        Button(onClick = { activity.chooseVehicle() }, modifier = Modifier.fillMaxWidth()) { Text("Choose device") }
    }
    Card("Full ground station") {
        Text("Planning, review and setup open here, with the same screens a browser gets.", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("plan" to "Plan", "review" to "Review", "setup" to "Setup", "operate" to "Operate").forEach { (id, name) ->
                OutlinedButton(onClick = { openWeb(id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text(name, fontSize = 13.sp) }
            }
        }
    }
    Card("In the field") {
        Button(onClick = lock, modifier = Modifier.fillMaxWidth()) { Text("Lock touch") }
        if (!Priority.ignoringBatteryLimits(activity)) {
            Text("Android may pause or stop the app to save battery, which would cut control.", fontSize = 13.sp, color = Warn)
            OutlinedButton(onClick = { Priority.requestNoBatteryLimits(activity) }, modifier = Modifier.fillMaxWidth()) { Text("Let it run in the background") }
        }
        if (Priority.isXiaomi()) OutlinedButton(onClick = {
            if (!Priority.openXiaomiAutostart(activity)) Toast.makeText(activity, "Open Settings, Apps, SmartPhoneX, and turn on Autostart", Toast.LENGTH_LONG).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Xiaomi: turn on Autostart") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("High contrast for sunlight", modifier = Modifier.weight(1f))
            Switch(checked = contrast, onCheckedChange = setContrast)
        }
        TextButton(onClick = guide) { Text("Show the guide again") }
    }
    FaceCard(rt, showFace)
    CameraCard(rt)
    Card("Sensors") {
        rt.sensors.catalog().forEach { (info, cfg) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.name)
                    Text("${info.group}, %.0f Hz".format(cfg.rateHz), fontSize = 12.sp)
                }
                Switch(checked = cfg.enabled, onCheckedChange = { v -> rt.sensors.configure(info.id, v, null, null) })
            }
        }
    }
    Text("Build ${BuildConfigInfo.version(activity)}", fontSize = 12.sp)
}

@Composable
private fun Choice(options: List<String>, selected: Int, pick: (Int) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
    options.forEachIndexed { i, name ->
        if (i == selected) Button(onClick = { pick(i) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text(name, fontSize = 13.sp) }
        else OutlinedButton(onClick = { pick(i) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text(name, fontSize = 13.sp) }
    }
}

@Composable
private fun FaceCard(rt: VehicleRuntime, showFace: () -> Unit) {
    val ctx = LocalContext.current
    // Read on every frame, so a change from the ground station shows here too.
    val mode = rt.param("FACE_MODE").toInt()
    val style = rt.param("FACE_STYLE").toInt()
    val hue = rt.param("FACE_HUE")
    fun set(name: String, v: Double) { rt.setParam(name, v)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
    Card("Face") {
        Text("A face on this screen, for robots. It shows how the device is doing. Tap it to get the controls and STOP back.", fontSize = 13.sp)
        Choice(listOf("Off", "When idle", "Robot"), mode) { set("FACE_MODE", it.toDouble()) }
        if (mode == 1) Text("Comes up after ${rt.param("FACE_IDLE_S").toInt()} s untouched. Change it in Setup, Settings, Face.", fontSize = 12.sp)
        if (mode == 2) Text("The face is the main screen. It comes back a few seconds after you use the controls.", fontSize = 12.sp)
        Choice(listOf("Eyes", "Eyes and mouth", "Visor"), style) { set("FACE_STYLE", it.toDouble()) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Red is kept for alarms.
            listOf(190.0, 140.0, 45.0, 280.0, 215.0).forEach { h ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.hsv(h.toFloat(), 0.75f, 1f))
                    .border(if (abs(h - hue) < 1) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { set("FACE_HUE", h) })
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = showFace) { Text("Show now") }
        }
    }
}

@Composable
private fun CameraCard(rt: VehicleRuntime) {
    val cam = rt.sensors.camera
    val list = remember { rt.camera?.catalog()?.map { it.jsonObject } ?: emptyList() }
    Card("Camera") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rt.camera?.status() ?: "unavailable", modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = cam.enabled, onCheckedChange = { v -> rt.sensors.setCamera(cam.copy(enabled = v)) })
        }
        list.forEach { c ->
            val id = c["id"]!!.jsonPrimitive.content
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { rt.sensors.setCamera(cam.copy(cameraId = id)) }) {
                RadioButton(selected = cam.cameraId == id, onClick = { rt.sensors.setCamera(cam.copy(cameraId = id)) })
                Text(c["name"]?.jsonPrimitive?.content ?: id, fontSize = 13.sp)
            }
        }
    }
}

private object BuildConfigInfo {
    @Suppress("UNUSED_PARAMETER")
    fun version(c: Context): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) ", debug" else ""
}

/** The web ground station, inside the app. The native STOP stays on screen above it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GroundWeb(rt: VehicleRuntime, page: String, close: () -> Unit) {
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = close) { Text("Back to the app") }
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl("http://127.0.0.1:8080/?inapp=1#token=${rt.token}&page=$page")
            }
        }, onRelease = { it.destroy() })
    }
}
