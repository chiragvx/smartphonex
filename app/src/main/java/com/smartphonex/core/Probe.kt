package com.smartphonex.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Packet loss measured with ICMP from the phone. The WebSocket link runs over TCP, which hides
 * loss by retransmitting, so the link alone can only show it as extra delay.
 */
object Probe {
    private val LOSS = Regex("""([\d.]+)% packet loss""")
    private val RTT = Regex("""= ([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+) ms""")

    fun parse(output: String, host: String, sent: Int): JsonObject {
        val loss = LOSS.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
        val rtt = RTT.find(output)?.groupValues
        return buildJsonObject {
            put("type", "probe")
            put("host", host)
            put("sent", sent)
            put("lossPct", loss)
            put("rttMinMs", rtt?.get(1)?.toDoubleOrNull())
            put("rttAvgMs", rtt?.get(2)?.toDoubleOrNull())
            put("rttMaxMs", rtt?.get(3)?.toDoubleOrNull())
            put("jitterMs", rtt?.get(4)?.toDoubleOrNull())
            put("ok", loss != null)
        }
    }

    fun run(host: String = "1.1.1.1", count: Int = 50): JsonObject = try {
        val p = ProcessBuilder("/system/bin/ping", "-c", "$count", "-i", "0.2", "-W", "2", host).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
        parse(out, host, count)
    } catch (e: Exception) {
        buildJsonObject { put("type", "probe"); put("ok", false); put("error", e.javaClass.simpleName) }
    }
}
