package travel.eftours.touraudio.traveler

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import travel.eftours.touraudio.core.TourCredentials
import travel.eftours.touraudio.core.TourCredentialsStore
import travel.eftours.touraudio.core.TourNetworks

class MainActivity : AppCompatActivity() {
    private var service: TravelerService? = null
    private var bound = false
    private var locked = false
    private var captionsOn = false
    private var lastState = TravelerService.State()
    private var taps = 0
    private var lastTap = 0L

    private var pendingJoin = false
    private var pendingNetworks = false
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val join = pendingJoin
        val networks = pendingNetworks
        pendingJoin = false
        pendingNetworks = false
        when {
            join -> sendJoin()
            networks -> showNetworks()
            else -> startListening()
        }
    }
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScan() else setWifiStatus("Allow the camera to scan a tour code")
    }
    private val qrScan = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val credentials = TourCredentials.parse(contents)
        if (credentials == null) {
            setWifiStatus("That code is not a tour join code")
        } else {
            applyCredentials(credentials)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TravelerService.LocalBinder).service()
            bound = true
            service?.setListener { render(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("traveler", MODE_PRIVATE)
        findViewById<EditText>(R.id.ssidInput).setText(prefs.getString("ssid", ""))
        findViewById<EditText>(R.id.passwordInput).setText(prefs.getString("password", ""))

        findViewById<Button>(R.id.joinButton).setOnClickListener { joinWifi() }
        findViewById<Button>(R.id.scanButton).setOnClickListener { scanCode() }
        findViewById<Button>(R.id.findButton).setOnClickListener { findNetworks() }
        findViewById<Button>(R.id.listenButton).setOnClickListener { toggleListen() }
        findViewById<Button>(R.id.lockButton).setOnClickListener { setLocked(true) }
        findViewById<Button>(R.id.captionButton).setOnClickListener {
            captionsOn = !captionsOn
            findViewById<Button>(R.id.captionButton).text = if (captionsOn) "Hide captions" else "Show captions"
            render(lastState)
        }
        findViewById<View>(R.id.lockOverlay).setOnClickListener { unlockTap() }
        wireReactions()
        findViewById<SeekBar>(R.id.volumeSlider).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.volumeValue).text = "$progress%"
                if (fromUser) service?.volume(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.startForegroundService(this, Intent(this, TravelerService::class.java))
        bindService(Intent(this, TravelerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.setListener(null)
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    private fun joinWifi() {
        val existing = TourCredentialsStore(this).last()
        applyCredentials(
            TourCredentials(
                ssid = findViewById<EditText>(R.id.ssidInput).text.toString().trim(),
                password = findViewById<EditText>(R.id.passwordInput).text.toString(),
                host = existing?.host ?: TourCredentials.DEFAULT_HOST,
                port = existing?.port ?: TourCredentials.DEFAULT_PORT
            ),
            connect = true
        )
    }

    private fun scanCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScan()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScan() {
        qrScan.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the tour QR code")
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
    }

    private fun findNetworks() {
        val needed = wifiPermissions()
        if (needed.isEmpty()) showNetworks() else {
            pendingNetworks = true
            permissions.launch(needed)
        }
    }

    private fun showNetworks() {
        setWifiStatus("Looking for EF-TOUR networks")
        TourNetworks.find(this) { lookup ->
            if (lookup.hint != null) {
                setWifiStatus(lookup.hint)
                return@find
            }
            if (lookup.names.size == 1) {
                selectNetwork(lookup.names.first())
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Tour networks")
                    .setItems(lookup.names.toTypedArray()) { _, which ->
                        selectNetwork(lookup.names[which])
                    }
                    .show()
            }
        }
    }

    private fun selectNetwork(ssid: String) {
        val saved = TourCredentialsStore(this).passwordFor(ssid)
        findViewById<EditText>(R.id.ssidInput).setText(ssid)
        if (saved != null) {
            findViewById<EditText>(R.id.passwordInput).setText(saved)
            joinWifi()
        } else {
            setWifiStatus("Enter the password for $ssid, or scan its QR code")
        }
    }

    private fun applyCredentials(credentials: TourCredentials, connect: Boolean = true) {
        findViewById<EditText>(R.id.ssidInput).setText(credentials.ssid)
        findViewById<EditText>(R.id.passwordInput).setText(credentials.password)
        getSharedPreferences("traveler", MODE_PRIVATE).edit()
            .putString("ssid", credentials.ssid)
            .putString("password", credentials.password)
            .apply()
        TourCredentialsStore(this).save(credentials)
        if (!connect) return
        val needed = wifiPermissions()
        if (needed.isEmpty()) sendJoin() else {
            pendingJoin = true
            permissions.launch(needed)
        }
    }

    private fun setWifiStatus(text: String) {
        findViewById<TextView>(R.id.wifiStatus).text = text
    }

    private fun sendJoin() {
        val ssid = findViewById<EditText>(R.id.ssidInput).text.toString()
        val password = findViewById<EditText>(R.id.passwordInput).text.toString()
        ContextCompat.startForegroundService(this, Intent(this, TravelerService::class.java).apply {
            action = TravelerService.ACTION_JOIN
            putExtra(TravelerService.EXTRA_SSID, ssid)
            putExtra(TravelerService.EXTRA_PASSWORD, password)
        })
    }

    private fun wifiPermissions(): Array<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.toTypedArray()
    }

    private fun toggleListen() {
        val listening = findViewById<Button>(R.id.listenButton).text == "Stop Listening"
        if (listening) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TravelerService::class.java).setAction(TravelerService.ACTION_STOP)
            )
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) startListening() else {
            pendingJoin = false
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun startListening() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, TravelerService::class.java).setAction(TravelerService.ACTION_LISTEN)
        )
    }

    private fun wireReactions() {
        val map = listOf(
            R.id.reactUp to "thumbs-up",
            R.id.reactDown to "thumbs-down",
            R.id.reactHeart to "heart",
            R.id.reactLaugh to "laugh",
            R.id.reactMind to "mind-blown"
        )
        map.forEach { (id, name) ->
            findViewById<View>(id).setOnClickListener {
                ContextCompat.startForegroundService(this, Intent(this, TravelerService::class.java).apply {
                    action = TravelerService.ACTION_REACT
                    putExtra(TravelerService.EXTRA_REACTION, name)
                })
            }
        }
    }

    private fun render(state: TravelerService.State) {
        lastState = state
        findViewById<TextView>(R.id.statusText).text = state.status
        findViewById<TextView>(R.id.batteryText).text = state.battery
        findViewById<TextView>(R.id.wifiStatus).text = state.wifi
        findViewById<Button>(R.id.listenButton).text = if (state.listening) "Stop Listening" else "Start Listening"
        findViewById<Button>(R.id.lockButton).visibility = if (state.listening) View.VISIBLE else View.GONE
        findViewById<View>(R.id.waveform).visibility = if (state.receiving) View.VISIBLE else View.INVISIBLE
        val id = findViewById<TextView>(R.id.travelerId)
        if (state.travelerId > 0) {
            id.visibility = View.VISIBLE
            id.text = "Traveler ${state.travelerId}"
        }
        val caption = findViewById<TextView>(R.id.captionText)
        if (!captionsOn || state.caption.isBlank()) {
            caption.visibility = View.GONE
        } else {
            caption.visibility = View.VISIBLE
            caption.text = state.caption
        }
        findViewById<TextView>(R.id.lockLabel).text = if (state.receiving) "Audio playing" else state.status
        if (!state.listening && locked) setLocked(false)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        findViewById<View>(R.id.lockOverlay).visibility = if (value) View.VISIBLE else View.GONE
        taps = 0
    }

    private fun unlockTap() {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 700) taps + 1 else 1
        lastTap = now
        findViewById<TextView>(R.id.lockHint).text = when (taps) {
            1 -> "Tap 2 more times"
            2 -> "Tap 1 more time"
            else -> "Tap 3× to unlock"
        }
        if (taps >= 3) setLocked(false)
    }
}
