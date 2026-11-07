package com.smartphonex.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.smartphonex.core.Mavlink
import com.smartphonex.core.Mode
import com.smartphonex.core.TcpTransport
import com.smartphonex.core.Transport
import com.smartphonex.core.TransportDef
import com.smartphonex.core.TransportProvider
import com.smartphonex.core.VehicleRuntime
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** USB-UART adapters, through the widely used usb-serial library. Not yet tried with a real adapter. */
class AndroidTransports(private val context: Context) : TransportProvider {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val inUse = HashSet<String>()

    override fun list(): List<String> = UsbSerialProber.getDefaultProber().findAllDrivers(usb).map {
        val d = it.device
        "${d.productName ?: "USB serial"} (vendor ${"%04x".format(d.vendorId)}, product ${"%04x".format(d.productId)}, ${it.javaClass.simpleName})"
    }

    override fun open(def: TransportDef): Transport {
        if (def.type == "tcp") return TcpTransport(def.host!!, def.port!!)
        if (def.type != "usb-uart") throw IOException("transport type ${def.type} has no hardware")
        return synchronized(inUse) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
            if (drivers.isEmpty()) throw IOException("no USB serial adapter is connected")
            // With a hub, adapters are taken in order, unless the description names one by its port number.
            val driver = def.port?.let { drivers.getOrNull(it) } ?: drivers.firstOrNull { it.device.deviceName !in inUse }
                ?: throw IOException("all USB serial adapters are in use")
            if (!usb.hasPermission(driver.device)) {
                val pi = PendingIntent.getBroadcast(context, 0, Intent("com.smartphonex.USB_PERMISSION").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
                usb.requestPermission(driver.device, pi)
                throw IOException("allow USB access on the phone, then try again")
            }
            val connection = usb.openDevice(driver.device) ?: throw IOException("couldn't open the USB adapter")
            val port = driver.ports[0]
            val name = driver.device.deviceName
            inUse += name
            object : Transport {
                private var io: SerialInputOutputManager? = null
                override fun open(onBytes: (ByteArray) -> Unit) {
                    port.open(connection)
                    port.setParameters(def.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                        override fun onNewData(data: ByteArray) = onBytes(data)
                        override fun onRunError(e: Exception) {}
                    }).also { it.start() }
                }
                override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)
                override fun close() {
                    runCatching { io?.stop() }
                    runCatching { port.close() }
                    synchronized(inUse) { inUse -= name }
                }
            }
        }
    }

    private companion object { const val WRITE_TIMEOUT_MS = 50 }
}

/**
 * Sends read-only MAVLink telemetry to a tool like QGroundControl on the local network. Off until
 * given an address. Nothing is accepted back, and it's kept to private addresses as it's unencrypted.
 */
class MavlinkBridge(private val rt: VehicleRuntime, dir: File) {
    private val file = File(dir, "mavlink-host.txt")
    @Volatile private var host: String? = runCatching { file.readText().trim().ifEmpty { null } }.getOrNull()
    @Volatile private var running = true

    fun setHost(h: String): String? {
        val clean = h.trim()
        if (clean.isNotEmpty() && !Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)[\d.]+$""").matches(clean)) return "use a private network address, such as 192.168.1.20"
        host = clean.ifEmpty { null }
        file.writeText(clean)
        return null
    }

    fun start() {
        Thread({
            val socket = DatagramSocket()
            var seq = 0
            var tick = 0
            val t0 = System.currentTimeMillis()
            while (running) {
                Thread.sleep(100)
                val h = host ?: continue
                runCatching {
                    val addr = InetAddress.getByName(h)
                    fun send(b: ByteArray) = socket.send(DatagramPacket(b, b.size, addr, 14550))
                    val a = rt.fc.attitudeNow
                    val ms = (System.currentTimeMillis() - t0).toInt()
                    send(Mavlink.attitude(seq++ and 0xFF, ms, Math.toRadians(a.roll).toFloat(), Math.toRadians(a.pitch).toFloat(), Math.toRadians(a.yaw).toFloat(), 0f, 0f, 0f))
                    if (tick++ % 10 == 0) {
                        val t = rt.controller.latest()
                        send(Mavlink.heartbeat(seq++ and 0xFF, t.mode == Mode.MANUAL || t.mode == Mode.AUTO, if (rt.profiles.active.vehicleType == "plane") 1 else 10))
                        // Position goes out only while the operator has Location on.
                        rt.fc.gpsNow?.takeIf { rt.sensors.config("phone.location")?.enabled == true }?.let { g ->
                            send(Mavlink.globalPosition(seq++ and 0xFF, ms, g.lat, g.lon, g.altM, a.yaw))
                        }
                    }
                }
            }
        }, "mavlink").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }
}
