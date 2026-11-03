package travel.eftours.touraudio.guide

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var service: GuideService? = null
    private var bound = false
    private var requestedBind = false
    private var pendingJoin = false
    private var pendingNetworks = false
    private var editingNetwork = false
    private var onNetwork = false
    private var connectedName = ""

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMic()) ensureService()
        val join = pendingJoin
        val networks = pendingNetworks
        pendingJoin = false
        pendingNetworks = false
        if (join) sendJoin()
        if (networks) showNetworks()
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
            service = (binder as GuideService.LocalBinder).service()
            bound = true
            service?.setListener { render(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            requestedBind = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("guide", MODE_PRIVATE)
        findViewById<EditText>(R.id.ssidInput).setText(prefs.getString("ssid", ""))
        val storedPassword = prefs.getString("password", "").orEmpty()
            .ifBlank { TourCredentials.TOUR_PASSWORD }
        findViewById<EditText>(R.id.passwordInput).setText(storedPassword)
        prefs.edit().putString("password", storedPassword).apply()
        findViewById<Button>(R.id.joinButton).setOnClickListener { joinWifi() }
        findViewById<Button>(R.id.scanButton).setOnClickListener { scanCode() }
        findViewById<Button>(R.id.showCodeButton).setOnClickListener { showCode() }
        findViewById<Button>(R.id.findButton).setOnClickListener { findNetworks() }
        findViewById<Button>(R.id.changeNetwork).setOnClickListener {
            editingNetwork = true
            applyScreens(onNetwork, connectedName)
        }
        findViewById<Button>(R.id.backToSession).setOnClickListener {
            editingNetwork = false
            applyScreens(onNetwork, connectedName)
        }
        findViewById<Button>(R.id.broadcastButton).setOnClickListener { toggleBroadcast() }
        findViewById<Button>(R.id.ccButton).setOnClickListener { toggleCaptions() }
        findViewById<Button>(R.id.clearReactions).setOnClickListener { service?.clearReactions() }
        findViewById<SeekBar>(R.id.sensitivitySlider).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress.coerceIn(1, 10)
                    findViewById<TextView>(R.id.sensitivityValue).text =
                        if (value <= 3) "Low" else if (value >= 8) "High" else "Medium"
                    if (fromUser) setSensitivity(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        if (!hasMic()) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasMic()) ensureService()
    }

    override fun onStop() {
        service?.setListener(null)
        if (bound) unbindService(connection)
        bound = false
        requestedBind = false
        super.onStop()
    }

    private fun ensureService() {
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java))
        if (!bound && !requestedBind) {
            requestedBind = true
            bindService(Intent(this, GuideService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun hasMic(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun savedPassword(ssid: String): String {
        val typed = findViewById<EditText>(R.id.passwordInput).text.toString()
        if (typed.isNotBlank()) return typed
        return TourCredentialsStore(this).passwordFor(ssid)
            ?: TourCredentials.knownPassword(ssid)
            ?: ""
    }

    private fun joinWifi() {
        val existing = TourCredentialsStore(this).last()
        applyCredentials(
            TourCredentials(
                ssid = findViewById<EditText>(R.id.ssidInput).text.toString().trim(),
                password = savedPassword(findViewById<EditText>(R.id.ssidInput).text.toString().trim()),
                host = existing?.host ?: TourCredentials.DEFAULT_HOST,
                port = existing?.port ?: TourCredentials.DEFAULT_PORT
            )
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

    private fun showCode() {
        val guideSsid = connectedName.ifBlank {
            setWifiStatus("Join the guide network before showing a traveler code")
            return
        }
        val travelerSsid = TourNetworks.travelerSsid(guideSsid) ?: run {
            setWifiStatus("Join an EF-TOUR-GUIDE network before showing a traveler code")
            return
        }
        val existing = TourCredentialsStore(this).last()
        val password = TourCredentialsStore(this).passwordFor(guideSsid)
            ?: TourCredentials.knownPassword(guideSsid)
            ?: TourCredentials.TOUR_PASSWORD
        val credentials = TourCredentials(
            travelerSsid,
            password,
            existing?.host ?: TourCredentials.DEFAULT_HOST,
            existing?.port ?: TourCredentials.DEFAULT_PORT
        )
        val bitmap = try {
            TourQr.bitmap(credentials.encode())
        } catch (_: Exception) {
            setWifiStatus("Could not make a QR code")
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(48, 36, 48, 36)
        }
        panel.addView(TextView(this).apply {
            text = travelerSsid
            setTextColor(android.graphics.Color.BLACK)
            textSize = 22f
            gravity = android.view.Gravity.CENTER
        })
        panel.addView(TextView(this).apply {
            text = "Have travelers scan this with the Tour Audio app. It joins $travelerSsid, not the guide network."
            setTextColor(0xFF333333.toInt())
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16)
        })
        panel.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        AlertDialog.Builder(this)
            .setTitle("Travelers join $travelerSsid")
            .setView(panel)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun findNetworks() {
        val needed = wifiPermissions().toMutableList()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) showNetworks() else {
            pendingNetworks = true
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun showNetworks() {
        setWifiStatus("Looking for EF-TOUR-GUIDE networks")
        TourNetworks.find(this, TourNetworks.Band.GUIDE) { lookup ->
            val hint = lookup.hint
            if (hint != null) {
                setWifiStatus(hint)
                return@find
            }
            if (lookup.names.size == 1) {
                selectNetwork(lookup.names.first())
            } else {
                val labels = lookup.names.map { name ->
                    val travelers = TourNetworks.travelerSsid(name)
                    if (travelers == null) name else "$name — travelers join $travelers"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Which guide network?")
                    .setItems(labels) { _, which ->
                        selectNetwork(lookup.names[which])
                    }
                    .show()
            }
        }
    }

    private fun selectNetwork(ssid: String) {
        val saved = TourCredentialsStore(this).passwordFor(ssid) ?: TourCredentials.knownPassword(ssid)
        findViewById<EditText>(R.id.ssidInput).setText(ssid)
        if (saved != null) {
            findViewById<EditText>(R.id.passwordInput).setText(saved)
            joinWifi()
        } else {
            setWifiStatus("Enter the password for $ssid, or scan its QR code")
        }
    }

    private fun applyCredentials(credentials: TourCredentials) {
        findViewById<EditText>(R.id.ssidInput).setText(credentials.ssid)
        findViewById<EditText>(R.id.passwordInput).setText(credentials.password)
        getSharedPreferences("guide", MODE_PRIVATE).edit()
            .putString("ssid", credentials.ssid)
            .putString("password", credentials.password)
            .apply()
        TourCredentialsStore(this).save(credentials)
        val needed = wifiPermissions().toMutableList()
        if (!hasMic()) needed += Manifest.permission.RECORD_AUDIO
        if (needed.isEmpty()) {
            ensureService()
            sendJoin()
        } else {
            pendingJoin = true
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun setWifiStatus(text: String) {
        findViewById<TextView>(R.id.wifiStatus).text = text
    }

    private fun sendJoin() {
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java).apply {
            action = GuideService.ACTION_JOIN
            putExtra(GuideService.EXTRA_SSID, findViewById<EditText>(R.id.ssidInput).text.toString())
            putExtra(GuideService.EXTRA_PASSWORD, findViewById<EditText>(R.id.passwordInput).text.toString())
        })
    }

    private fun wifiPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed
    }

    private fun toggleBroadcast() {
        val button = findViewById<Button>(R.id.broadcastButton)
        if (!hasMic()) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        ensureService()
        val stop = button.text == "Stop Broadcasting"
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java).setAction(
            if (stop) GuideService.ACTION_STOP else GuideService.ACTION_START
        ))
    }

    private fun toggleCaptions() {
        ensureService()
        val turnOn = findViewById<Button>(R.id.ccButton).text != "Turn off CC"
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java).apply {
            action = GuideService.ACTION_CAPTIONS
            putExtra(GuideService.EXTRA_ON, turnOn)
        })
    }

    private fun setSensitivity(value: Int) {
        ensureService()
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java).apply {
            action = GuideService.ACTION_SENSITIVITY
            putExtra(GuideService.EXTRA_SENSITIVITY, value)
        })
    }

    private fun showCount(id: Int, count: Int) {
        val view = findViewById<TextView>(id)
        view.text = count.toString()
        view.setTextColor(if (count > 0) 0xFFFFD0EA.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun render(state: GuideService.State) {
        findViewById<TextView>(R.id.statusText).text = state.status
        findViewById<TextView>(R.id.wifiStatus).text = state.wifi
        val banner = findViewById<TextView>(R.id.watchBanner)
        when {
            state.watching -> {
                banner.visibility = View.VISIBLE
                banner.text = "Another guide is broadcasting. You are in watch-only mode."
            }
            state.ended -> {
                banner.visibility = View.VISIBLE
                banner.text = "Broadcast has ended. You can start broadcasting."
            }
            else -> banner.visibility = View.GONE
        }
        val button = findViewById<Button>(R.id.broadcastButton)
        button.text = if (state.broadcasting) "Stop Broadcasting" else "Start Broadcasting"
        button.isEnabled = !state.watching
        val live = if (state.broadcasting) View.VISIBLE else View.GONE
        findViewById<View>(R.id.ccButton).visibility = live
        findViewById<View>(R.id.sensitivityRow).visibility = live
        findViewById<View>(R.id.reactionDashboard).visibility = live
        findViewById<ProgressBar>(R.id.micLevel).apply {
            visibility = live
            progress = state.level
        }
        showCount(R.id.countThumbsUp, state.thumbsUp)
        showCount(R.id.countThumbsDown, state.thumbsDown)
        showCount(R.id.countHeart, state.heart)
        showCount(R.id.countLaugh, state.laugh)
        showCount(R.id.countMind, state.mindBlown)
        findViewById<TextView>(R.id.reactionParticipation).text = state.participation
        findViewById<Button>(R.id.ccButton).text =
            if (state.captionsOn) "Turn off CC" else "Turn on CC"
        val caption = findViewById<TextView>(R.id.captionStatus)
        caption.visibility = View.VISIBLE
        caption.text = state.captionStatus
        findViewById<SeekBar>(R.id.sensitivitySlider).progress = state.sensitivity
        findViewById<TextView>(R.id.sensitivityValue).text =
            if (state.sensitivity <= 3) "Low" else if (state.sensitivity >= 8) "High" else "Medium"
        val travelerSsid = if (state.wifiConnected) TourNetworks.travelerSsid(state.ssid) else null
        val hint = findViewById<TextView>(R.id.travelerHint)
        val code = findViewById<Button>(R.id.showCodeButton)
        if (travelerSsid != null) {
            hint.visibility = View.VISIBLE
            hint.text = "Tell travelers to select $travelerSsid"
            code.visibility = View.VISIBLE
        } else {
            hint.visibility = View.GONE
            code.visibility = View.GONE
        }
        onNetwork = state.wifiConnected
        val network = if (state.wifiConnected) state.ssid.ifBlank { state.wifi.removePrefix("Connected to ").trim() } else ""
        applyScreens(state.wifiConnected, network)
    }

    private fun applyScreens(connected: Boolean, name: String) {
        if (!connected) {
            editingNetwork = false
            connectedName = ""
        } else if (name.isNotBlank() && name != connectedName) {
            editingNetwork = false
            connectedName = name
        }
        val showConnect = !connected || editingNetwork
        findViewById<View>(R.id.sessionPanel).visibility = if (showConnect) View.GONE else View.VISIBLE
        findViewById<View>(R.id.connectPanel).visibility = if (showConnect) View.VISIBLE else View.GONE
        findViewById<View>(R.id.backToSession).visibility = if (showConnect && connected) View.VISIBLE else View.GONE
    }
}
