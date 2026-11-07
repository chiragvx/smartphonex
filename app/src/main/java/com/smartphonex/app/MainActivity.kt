package com.smartphonex.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.smartphonex.core.Command
import com.smartphonex.core.Envelope
import com.smartphonex.core.Mode
import com.smartphonex.core.Source
import com.smartphonex.core.VehicleRuntime

/**
 * The phone's own app. It talks straight to the runtime, so it works with no network, no browser and
 * no AI. The screens are in AppUi.kt. This class keeps what belongs to Android: permissions, links,
 * dialogs and the gamepad.
 */
class MainActivity : ComponentActivity() {
    private var approvalShowing: String? = null
    private var padForward = 0.0
    private var padTurn = 0.0
    private var padPitch = 0.0
    private var padRoll = 0.0
    private var padSeen = false
    private var padSeq = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            VehicleService.runtime?.let { rt ->
                showApprovals(rt)
                drivePad(rt)
            }
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With the screen off the control loop keeps running, but the network link gets much worse.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Camera and location stay off until an operator enables them. READ_PHONE_STATE is only used
        // to label the network as 4G or 5G in measurements.
        val wanted = mutableListOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.CAMERA",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += "android.permission.POST_NOTIFICATIONS"
        requestPermissions(wanted.toTypedArray(), 1)
        startForegroundService(Intent(this, VehicleService::class.java))
        setContent { SmartPhoneXApp(this) }
        handleEnrollLink()
    }

    @Deprecated("Still the simplest way to hear back from requestPermissions")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        VehicleService.instance?.permissionsChanged()
    }

    private fun pad(c: Command) = VehicleService.runtime?.controller?.submit(Envelope(Source.GAMEPAD, ++padSeq, null, c))

    private fun drivePad(rt: VehicleRuntime) {
        if (!padSeen || rt.controller.latest().mode != Mode.MANUAL) return
        val p = rt.profiles.active
        pad(Command.Drive(padForward.coerceIn(-1.0, 1.0) * p.maxThrottle, padTurn.coerceIn(-1.0, 1.0) * p.maxSteering, padPitch.coerceIn(-1.0, 1.0), padRoll.coerceIn(-1.0, 1.0)))
    }

    private fun padDevice(id: Int) { VehicleService.runtime?.ops?.gamepad = InputDevice.getDevice(id)?.name ?: "Gamepad" }

    /** A connected gamepad acts as a person at the controls. It only works while this screen is open. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return super.onGenericMotionEvent(e)
        padSeen = true
        padDevice(e.deviceId)
        padForward = -e.getAxisValue(MotionEvent.AXIS_Y).toDouble()
        padTurn = -e.getAxisValue(MotionEvent.AXIS_X).toDouble()
        padPitch = -e.getAxisValue(MotionEvent.AXIS_RZ).toDouble()
        padRoll = -e.getAxisValue(MotionEvent.AXIS_Z).toDouble()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return super.onKeyDown(keyCode, event)
        padSeen = true
        padDevice(event.deviceId)
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B -> { pad(Command.Stop); return true }
            KeyEvent.KEYCODE_BUTTON_A -> { pad(Command.SetMode(Mode.MANUAL)); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Anyone holding the phone can answer setup requests here, as well as in the ground station. */
    private fun showApprovals(rt: VehicleRuntime) {
        val a = rt.setup.pendingList.firstOrNull() ?: return
        if (approvalShowing == a.id) return
        approvalShowing = a.id
        AlertDialog.Builder(this)
            .setTitle("Setup needs your OK")
            .setMessage(a.detail)
            .setPositiveButton("Approve") { _, _ -> rt.setup.approve(a.id, true, "phone"); approvalShowing = null }
            .setNegativeButton("Refuse") { _, _ -> rt.setup.approve(a.id, false, "phone"); approvalShowing = null }
            .setCancelable(false)
            .show()
    }

    fun chooseVehicle() {
        val rt = VehicleService.runtime ?: return
        val list = rt.profiles.list()
        AlertDialog.Builder(this)
            .setTitle("Vehicle")
            .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                val r = rt.local(Command.SelectProfile(list[i].id))
                if (!r.accepted) Toast.makeText(this, "Disarm first: tap STOP.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEnrollLink()
    }

    /**
     * A scanned QR code opens the app with a link. Any web page could send such a link, so
     * nothing changes until the person holding the phone confirms the server's name.
     */
    private fun handleEnrollLink() {
        val uri = intent?.data ?: return
        intent.data = null // Handle each link once, not again after a screen rotation.
        if (uri.scheme != "smartphonex" || uri.host != "enroll") return
        val relayUrl = uri.getQueryParameter("relay")
        val code = uri.getQueryParameter("code")
        val host = relayUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        if (host == null || code == null) {
            Toast.makeText(this, "That QR code isn't valid.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Connect this vehicle?")
            .setMessage("Anyone signed in to this server can drive this vehicle:\n\n$host\n\nOnly continue if you just scanned this code yourself.")
            .setPositiveButton("Connect") { _, _ -> enroll(relayUrl, code, host) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enroll(relayUrl: String, code: String, host: String) {
        Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
        Thread {
            var client = VehicleService.relayClient
            var waited = 0
            while (client == null && waited < 50) { // The service may still be starting.
                Thread.sleep(100)
                waited++
                client = VehicleService.relayClient
            }
            val error = client?.enroll(relayUrl, code) ?: if (client == null) "The vehicle service isn't running." else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(if (error == null) "Linked" else "Not connected")
                    .setMessage(error ?: "This phone is linked to $host and will keep reconnecting by itself.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh); drivingAllowed.value = false }

    /** Leaving the app ends driving from the screen, as nothing is holding the sticks. */
    val drivingAllowed = androidx.compose.runtime.mutableStateOf(false)
}
