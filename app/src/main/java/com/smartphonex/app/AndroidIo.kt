package com.smartphonex.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
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

/**
 * USB-UART adapters, through the usb-serial-for-android library. Not yet tried with a real adapter.
 * Phone USB-C, OTG adapter or cable, USB-UART adapter, then its TX, RX and ground pins to the device.
 */
class AndroidTransports(private val context: Context) : TransportProvider {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val inUse = HashSet<String>()
    @Volatile var lastPermission: String? = null; private set

    init {
        // The answer to a permission request. The next write retries by itself once it's allowed.
        val filter = android.content.IntentFilter(PERMISSION)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                lastPermission = if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "allowed" else "refused"
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    }

    override fun list(): List<String> = UsbSerialProber.getDefaultProber().findAllDrivers(usb).map {
        val d = it.device
        val note = if (it is Cp21xxSerialDriver) ", an original CP2102 can't do 420000 or 100000 baud: use FTDI or CP2102N for CRSF and SBUS" else ""
        "${d.productName ?: "USB serial"} (vendor ${"%04x".format(d.vendorId)}, product ${"%04x".format(d.productId)}, ${it.javaClass.simpleName}$note)"
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
            // Only FTDI chips can invert the line themselves (set once with FT_PROG). Others need an inverter.
            if (def.inverted && driver !is FtdiSerialDriver) throw IOException("an inverted line (SBUS) needs an FTDI adapter set to invert with FT_PROG, or a hardware inverter")
            if (!usb.hasPermission(driver.device)) {
                // Mutable, so Android can fill in whether it was allowed. Explicit, so only this app receives it.
                val flags = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(context, 0, Intent(PERMISSION).setPackage(context.packageName), flags)
                usb.requestPermission(driver.device, pi)
                throw IOException("allow USB access on the phone. Tick \"use by default\" so it isn't asked again")
            }
            val connection = usb.openDevice(driver.device) ?: throw IOException("couldn't open the USB adapter")
            val port = driver.ports[0]
            val name = driver.device.deviceName
            inUse += name
            object : Transport {
                private var io: SerialInputOutputManager? = null
                @Volatile private var failure: Exception? = null
                override fun open(onBytes: (ByteArray) -> Unit) {
                    try {
                        port.open(connection)
                        val parity = when (def.parity) { "even" -> UsbSerialPort.PARITY_EVEN; "odd" -> UsbSerialPort.PARITY_ODD; else -> UsbSerialPort.PARITY_NONE }
                        val stop = if (def.stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1
                        port.setParameters(def.baud, def.dataBits, stop, parity)
                        // FTDI holds received bytes for up to 16 ms by default. Servo replies and telemetry want them now.
                        if (port is FtdiSerialDriver.FtdiSerialPort) runCatching { port.setLatencyTimer(1) }
                    } catch (e: UnsupportedOperationException) {
                        close(); throw IOException("this adapter can't do ${def.baud} baud ${def.dataBits}${def.parity.first().uppercaseChar()}${def.stopBits}: ${e.message}")
                    } catch (e: IOException) {
                        close(); throw e
                    }
                    io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                        override fun onNewData(data: ByteArray) = onBytes(data)
                        // Unplugged or failed: remember it, so the next write fails and the output shows the error.
                        override fun onRunError(e: Exception) { failure = e }
                    }).also { it.start() }
                }
                override fun write(data: ByteArray) {
                    failure?.let { throw IOException("the USB adapter stopped: ${it.message ?: it.javaClass.simpleName}") }
                    port.write(data, WRITE_TIMEOUT_MS)
                }
                override fun close() {
                    runCatching { io?.stop() }
                    runCatching { port.close() }
                    synchronized(inUse) { inUse -= name }
                }
            }
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 50
        const val PERMISSION = "com.smartphonex.USB_PERMISSION"
    }
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
