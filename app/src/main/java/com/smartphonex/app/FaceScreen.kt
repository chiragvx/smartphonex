package com.smartphonex.app

import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartphonex.core.Face
import com.smartphonex.core.Face.Expr
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** How one eye is shaped. Every field eases toward its target, so expressions blend instead of jumping. */
private data class EyeShape(val open: Float, val tilt: Float, val cut: Float, val scale: Float)

private fun shapes(e: Expr): Pair<EyeShape, EyeShape> {
    fun both(s: EyeShape) = s to s
    return when (e) {
        Expr.NEUTRAL -> both(EyeShape(1f, 0f, 0f, 1f))
        Expr.HAPPY -> both(EyeShape(0.95f, 0f, 0.6f, 1f))
        Expr.EXCITED -> both(EyeShape(1.1f, 0f, 0.5f, 1.1f))
        Expr.SCARED -> both(EyeShape(1.15f, -0.45f, 0f, 0.82f))
        Expr.CONFUSED -> EyeShape(0.8f, 0.55f, 0f, 0.95f) to EyeShape(1.08f, -0.3f, 0f, 1.06f)
        Expr.SAD -> both(EyeShape(0.75f, -0.6f, 0f, 0.95f))
        Expr.ANGRY -> both(EyeShape(0.7f, 0.7f, 0f, 1f))
        Expr.SURPRISED -> both(EyeShape(1.25f, 0f, 0f, 1.12f))
        Expr.SLEEPY -> both(EyeShape(0.18f, -0.2f, 0f, 1f))
        Expr.CURIOUS -> EyeShape(1.12f, 0f, 0f, 1.12f) to EyeShape(0.7f, 0.3f, 0f, 0.92f)
        Expr.WINK -> EyeShape(0.9f, 0f, 0.6f, 1f) to EyeShape(0.06f, 0f, 0f, 1f)
        Expr.FOCUSED -> both(EyeShape(0.62f, 0.2f, 0f, 1f))
        Expr.ALARM -> both(EyeShape(1.2f, -0.2f, 0f, 1.1f))
        Expr.LOVE, Expr.DEAD -> both(EyeShape(1f, 0f, 0f, 1f))
    }
}

/** Mouth curve, from -1 (frown) to 1 (smile). */
private fun smile(e: Expr) = when (e) {
    Expr.HAPPY, Expr.LOVE, Expr.WINK, Expr.EXCITED -> 1f
    Expr.SCARED -> -0.5f
    Expr.CONFUSED -> -0.15f
    Expr.SAD -> -0.8f
    Expr.ANGRY -> -0.4f
    Expr.ALARM, Expr.DEAD -> -0.6f
    Expr.SLEEPY, Expr.FOCUSED -> 0f
    else -> 0.25f
}

private class Animator {
    var left = EyeShape(1f, 0f, 0f, 1f)
    var right = left
    var smile = 0.25f
    var gx = 0f
    var gy = 0f
    var blink = 1f
    private var nextBlink = 0L
    private var blinkStart = -1L
    private var wanderX = 0f
    private var wanderY = 0f
    private var nextWander = 0L

    private fun ease(a: Float, b: Float, k: Float) = a + (b - a) * k
    private fun ease(a: EyeShape, b: EyeShape, k: Float) = EyeShape(ease(a.open, b.open, k), ease(a.tilt, b.tilt, k), ease(a.cut, b.cut, k), ease(a.scale, b.scale, k))

    fun step(now: Long, dtS: Float, look: Face.Look) {
        val k = (dtS * 9f).coerceAtMost(1f)
        val (l, r) = shapes(look.expr)
        left = ease(left, l, k); right = ease(right, r, k)
        smile = ease(smile, smile(look.expr), k)
        // At rest the eyes glance around now and then. When the device is moving, they follow it.
        val resting = look.gazeX == 0f && look.gazeY == 0f && look.expr != Expr.SLEEPY && look.expr != Expr.DEAD
        if (resting && now >= nextWander) {
            wanderX = if (Random.nextFloat() < 0.4f) 0f else Random.nextFloat() * 1.6f - 0.8f
            wanderY = if (Random.nextFloat() < 0.5f) 0f else Random.nextFloat() * 0.8f - 0.4f
            nextWander = now + 1200 + Random.nextLong(3500)
        }
        val tx = if (resting) wanderX else look.gazeX
        val ty = if (resting) wanderY else look.gazeY
        val g = (dtS * 14f).coerceAtMost(1f)
        gx = ease(gx, tx, g); gy = ease(gy, ty, g)
        // Blinks every few seconds, quicker when alarmed. A blink takes 160 ms.
        if (nextBlink == 0L) nextBlink = now + 2000
        if (blinkStart < 0 && now >= nextBlink && look.expr != Expr.DEAD) blinkStart = now
        if (blinkStart >= 0) {
            val p = (now - blinkStart) / 160f
            blink = if (p < 0.5f) 1f - p * 2f else (p - 0.5f) * 2f
            if (p >= 1f) {
                blink = 1f; blinkStart = -1
                nextBlink = now + if (look.alarm) 900 + Random.nextLong(900) else 2200 + Random.nextLong(4000)
            }
        }
    }
}

/**
 * The phone's face. A tap anywhere closes it and shows the controls, with STOP, again. The face has no buttons of its
 * own, so a tap can't do anything else by accident.
 */
@Composable
fun FaceScreen(
    activity: MainActivity, look: Face.Look, style: Int, hue: Float, brightness: Float,
    onWake: () -> Unit,
) {
    // Dimmer screen and no system bars while the face shows. Both are put back when it goes.
    DisposableEffect(brightness) {
        val w = activity.window
        val before = w.attributes.screenBrightness
        w.attributes = w.attributes.apply { screenBrightness = brightness }
        if (Build.VERSION.SDK_INT >= 30) w.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            w.attributes = w.attributes.apply { screenBrightness = if (before >= 0) before else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            if (Build.VERSION.SDK_INT >= 30) w.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }
    val anim = remember { Animator() }
    var frame by remember { mutableStateOf(0L) }
    val lookNow by rememberUpdatedState(look)
    // 30 frames a second on a timer: smooth enough for a face. Waiting on every screen refresh instead would wake the
    // main thread 120 times a second on a fast phone screen, for frames that are thrown away.
    LaunchedEffect(Unit) {
        var last = android.os.SystemClock.uptimeMillis()
        while (true) {
            kotlinx.coroutines.delay(33)
            val ms = android.os.SystemClock.uptimeMillis()
            anim.step(System.currentTimeMillis(), ((ms - last) / 1000f).coerceIn(0f, 0.1f), lookNow)
            last = ms
            frame = ms
        }
    }
    val base = if (look.alarm) Color(0xFFFF3B30) else Color.hsv(hue.coerceIn(0f, 360f), 0.75f, 1f)
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { onWake() } }) {
        Canvas(Modifier.fillMaxSize()) {
            frame // Redraw on every animation frame.
            val shake = when (look.expr) {
                Expr.ALARM -> sin(frame / 25.0).toFloat() * size.minDimension * 0.006f
                Expr.SCARED -> sin(frame / 18.0).toFloat() * size.minDimension * 0.003f
                else -> 0f
            }
            when (style) {
                2 -> visor(anim, look, base, frame)
                else -> {
                    eyes(anim, look, base, shake)
                    if (style == 1) mouth(anim, look, base)
                }
            }
        }
        look.label?.let { Text(it, color = base, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) }
    }
}

private fun DrawScope.eyes(a: Animator, look: Face.Look, color: Color, shake: Float) {
    // Sized to the screen: big on a portrait phone, and still fits when the phone is mounted sideways.
    val unit = min(size.width, size.height * 1.4f)
    val ew = unit * 0.24f
    val eh = min(ew * 1.3f, size.height * 0.36f)
    val gap = unit * 0.12f
    val bounce = if (look.expr == Expr.EXCITED) abs(sin(System.currentTimeMillis() / 140.0).toFloat()) * eh * 0.08f else 0f
    val cy = size.height * 0.42f + a.gy * eh * 0.28f - bounce
    val cx = size.width / 2 + a.gx * ew * 0.35f + shake
    for ((side, shape) in listOf(-1 to a.left, 1 to a.right)) {
        val x = cx + side * (gap + ew / 2)
        when (look.expr) {
            Expr.DEAD -> { cross(Offset(x, cy), ew * 0.45f, color); continue }
            Expr.LOVE -> { heart(Offset(x, cy), ew * 0.55f * (1f + 0.06f * sin(System.currentTimeMillis() / 160.0).toFloat()), color); continue }
            else -> {}
        }
        val w = ew * shape.scale * (1f - abs(a.gx) * 0.08f)
        val h = (eh * shape.scale * shape.open * a.blink).coerceAtLeast(eh * 0.05f)
        val top = cy - h / 2
        drawRoundRect(color, Offset(x - w / 2, top), Size(w, h), CornerRadius(min(w, h) * 0.32f))
        // Lids, cut in the background colour. Positive tilt lowers the inner corner (angry), negative the outer (sad).
        if (abs(shape.tilt) > 0.02f && h > eh * 0.1f) {
            val drop = abs(shape.tilt) * h * 0.55f
            val inner = x - side * w / 2
            val outer = x + side * w / 2
            val lowX = if (shape.tilt > 0) inner else outer
            val highX = if (shape.tilt > 0) outer else inner
            val m = w * 0.1f
            val lx = lowX + if (lowX < highX) -m else m
            val hx = highX + if (highX > lowX) m else -m
            drawPath(Path().apply { moveTo(lx, top + drop); lineTo(hx, top - 1); lineTo(hx, top - eh); lineTo(lx, top - eh); close() }, Color.Black)
        }
        // A happy eye is cut from below by a curve, leaving an upturned arc.
        if (shape.cut > 0.02f) {
            // The oval's top edge leaves an arch: the deeper the cut, the thinner the smiling eye.
            drawOval(Color.Black, Offset(x - w * 0.8f, top + h * (1f - shape.cut * 0.95f)), Size(w * 1.6f, h * 1.3f))
        }
    }
}

private fun DrawScope.mouth(a: Animator, look: Face.Look, color: Color) {
    val unit = min(size.width, size.height * 1.4f)
    val w = unit * 0.32f
    val cx = size.width / 2 + a.gx * unit * 0.03f
    val y = size.height * 0.66f
    val stroke = Stroke(unit * 0.022f, cap = StrokeCap.Round)
    if (look.expr == Expr.SURPRISED || look.expr == Expr.ALARM || look.expr == Expr.SCARED) {
        drawOval(color, Offset(cx - unit * 0.04f, y - unit * 0.05f), Size(unit * 0.08f, unit * 0.1f), style = stroke)
        return
    }
    val bend = a.smile * unit * 0.07f
    drawPath(Path().apply { moveTo(cx - w / 2, y); quadraticBezierTo(cx, y + bend * 2, cx + w / 2, y) }, color, style = stroke)
}

private fun DrawScope.visor(a: Animator, look: Face.Look, color: Color, frame: Long) {
    val w = size.width * 0.82f
    val h = min(size.width, size.height) * 0.14f
    val left = (size.width - w) / 2
    val top = size.height * 0.4f - h / 2
    drawRoundRect(color.copy(alpha = 0.18f), Offset(left, top), Size(w, h), CornerRadius(h / 2))
    if (look.expr == Expr.DEAD) {
        drawLine(color.copy(alpha = 0.5f), Offset(left + h, top + h / 2), Offset(left + w - h, top + h / 2), h * 0.12f)
        return
    }
    val speed = when (look.expr) { Expr.ALARM -> 180.0; Expr.SCARED, Expr.EXCITED -> 300.0; Expr.SLEEPY -> 2400.0; else -> 900.0 }
    val resting = look.gazeX == 0f
    val pos = if (resting) sin(frame / speed).toFloat() else a.gx
    val open = (a.left.open * a.blink).coerceIn(0.08f, 1.2f)
    val dotW = h * if (look.expr in setOf(Expr.HAPPY, Expr.LOVE, Expr.EXCITED)) 1.8f else 1.2f
    val x = size.width / 2 + pos * (w / 2 - dotW)
    val dh = h * 0.7f * open
    for (i in 3 downTo 1) drawRoundRect(color.copy(alpha = 0.12f * i), Offset(x - dotW / 2 - i * h * 0.15f, top + (h - dh) / 2 - i * h * 0.05f), Size(dotW + i * h * 0.3f, dh + i * h * 0.1f), CornerRadius(h / 2))
    drawRoundRect(color, Offset(x - dotW / 2, top + (h - dh) / 2), Size(dotW, dh), CornerRadius(dh / 2))
}

private fun DrawScope.cross(c: Offset, r: Float, color: Color) {
    val s = r * 0.32f
    drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), s, StrokeCap.Round)
    drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), s, StrokeCap.Round)
}

private fun DrawScope.heart(c: Offset, r: Float, color: Color) {
    drawPath(Path().apply {
        moveTo(c.x, c.y + r * 0.9f)
        cubicTo(c.x - r * 1.6f, c.y - r * 0.1f, c.x - r * 0.9f, c.y - r * 1.3f, c.x, c.y - r * 0.45f)
        cubicTo(c.x + r * 0.9f, c.y - r * 1.3f, c.x + r * 1.6f, c.y - r * 0.1f, c.x, c.y + r * 0.9f)
        close()
    }, color)
}
