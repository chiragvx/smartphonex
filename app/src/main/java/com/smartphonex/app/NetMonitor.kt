package com.smartphonex.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.smartphonex.core.VehicleRuntime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Records which network the phone is on, its signal and data use, and logs every change. */
class NetMonitor(context: Context, private val rt: VehicleRuntime, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val uid = Process.myUid()
    private var lastLabel = ""
    @Volatile private var override = 0
    private var displayRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            exec.execute { poll() }
            onChange()
        }
        override fun onLost(network: Network) {
            exec.execute { poll() }
        }
    }

    fun start() {
        cm.registerDefaultNetworkCallback(callback)
        exec.scheduleWithFixedDelay({ runCatching { poll() } }, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        exec.shutdownNow()
    }

    private fun registerDisplayInfo() {
        if (displayRegistered || Build.VERSION.SDK_INT < 31 || tm == null) return
        try {
            tm.registerTelephonyCallback(exec, object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    override = info.overrideNetworkType
                }
            })
            displayRegistered = true
        } catch (_: SecurityException) {
            // READ_PHONE_STATE isn't granted yet: try again on a later poll.
        }
    }

    private fun radio(): String {
        val type = try { tm?.dataNetworkType ?: 0 } catch (_: SecurityException) { return "cellular (no permission)" }
        // Non-standalone 5G reports LTE as its data type, with the 5G part only in the display info.
        if (Build.VERSION.SDK_INT >= 31 && (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)) return "5G NSA"
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "cellular"
            else -> "2G/3G ($type)"
        }
    }

    private fun poll() {
        registerDisplayInfo()
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val radio = if (transport == "cellular") radio() else ""
        val level = if (Build.VERSION.SDK_INT >= 28) runCatching { tm?.signalStrength?.level }.getOrNull() else null
        val label = if (radio.isEmpty()) transport else radio
        if (label != lastLabel) {
            rt.log.event("network", "phone", label, "network changed from ${lastLabel.ifEmpty { "start" }} to $label")
            lastLabel = label
        }
        rt.net = buildJsonObject {
            put("transport", transport)
            put("radio", radio)
            put("signal", level)
            put("txBytes", TrafficStats.getUidTxBytes(uid))
            put("rxBytes", TrafficStats.getUidRxBytes(uid))
        }
    }
}
