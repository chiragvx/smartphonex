package com.smartphonex.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.smartphonex.core.Ops

/**
 * Getting time-critical threads the CPU they need. Android has no real-time scheduling for apps, so this is the best
 * an app can do: the highest priority it's allowed, and, from Android 12, a hint session that tells the system the
 * thread's period and real work time, so it keeps it on a fast core at a high enough clock.
 */
object Priority {
    /** What each time-critical thread got, for the phone report. */
    val status = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun forApp(ctx: Context): (String, Long) -> ((Long) -> Unit)? = { name, periodNs ->
        val raised = runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); true }.getOrDefault(false)
        val report = if (Build.VERSION.SDK_INT >= 31) Hints.session(ctx, periodNs) else null
        status[name] = "priority ${if (raised) Process.getThreadPriority(Process.myTid()) else "unchanged"}, hint session ${if (report != null) "on" else "off"}"
        report
    }

    @RequiresApi(31)
    private object Hints {
        fun session(ctx: Context, periodNs: Long): ((Long) -> Unit)? {
            val m = ctx.getSystemService(PerformanceHintManager::class.java) ?: return null
            val s = runCatching { m.createHintSession(intArrayOf(Process.myTid()), periodNs) }.getOrNull() ?: return null
            return { work -> s.reportActualWorkDuration(work) }
        }
    }

    /** Whether Android may pause or stop the app to save battery. */
    fun ignoringBatteryLimits(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    fun backgroundCheck(ctx: Context): Ops.Check = if (ignoringBatteryLimits(ctx))
        Ops.Check("background", "Running in the background", "ok", "Battery saving won't pause the app")
    else Ops.Check("background", "Running in the background", "warn", "Android may pause or stop the app to save battery",
        "On the phone: More, In the field, Let it run in the background" + if (isXiaomi()) ". On Xiaomi, also turn on Autostart" else "")

    fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)

    /** Asks Android to stop limiting the app. Falls back to the battery settings list if the direct request isn't available. */
    fun requestNoBatteryLimits(ctx: Context) {
        @Suppress("BatteryLife")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        runCatching { ctx.startActivity(direct) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    /** Xiaomi's own Autostart switch, which its system checks on top of Android's. Best effort: the screen's name varies. */
    fun openXiaomiAutostart(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        true
    }.getOrDefault(false)
}
