package travel.eftours.touraudio.traveler

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private var captionsOn = false
    private var captionFull = false
    private var captionSp = 18f
    private var lastState = TravelerService.State()
    private var shownCaption = ""
    private var shownInterim = false

    private var pendingJoin = false
    private var pendingNetworks = false
    private var editingNetwork = false
    private var connectedName = ""
    private val reactionHandler = Handler(Looper.getMainLooper())
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
        captionSp = prefs.getFloat("caption_font", 18f).coerceIn(MIN_CAPTION_SP, MAX_CAPTION_SP)
        findViewById<EditText>(R.id.ssidInput).setText(prefs.getString("ssid", ""))
        val storedPassword = prefs.getString("password", "").orEmpty()
            .ifBlank { TourCredentials.TOUR_PASSWORD }
        findViewById<EditText>(R.id.passwordInput).setText(storedPassword)
        prefs.edit().putString("password", storedPassword).apply()

        findViewById<Button>(R.id.joinButton).setOnClickListener { joinWifi() }
        findViewById<Button>(R.id.scanButton).setOnClickListener { scanCode() }
        findViewById<Button>(R.id.findButton).setOnClickListener { findNetworks() }
        findViewById<Button>(R.id.changeNetwork).setOnClickListener {
            editingNetwork = true
            applyScreens(lastState.wifiConnected, connectedName)
        }
        findViewById<Button>(R.id.backToSession).setOnClickListener {
            editingNetwork = false
            applyScreens(lastState.wifiConnected, connectedName)
        }
        findViewById<Button>(R.id.listenButton).setOnClickListener { toggleListen() }
        findViewById<Button>(R.id.stopListening).setOnClickListener { toggleListen() }
        findViewById<Button>(R.id.captionButton).setOnClickListener { setCaptionsVisible(true) }
        findViewById<Button>(R.id.captionHide).setOnClickListener { setCaptionsVisible(false) }
        findViewById<Button>(R.id.captionFullButton).setOnClickListener {
            captionFull = !captionFull
            render(lastState)
        }
        findViewById<Button>(R.id.captionSmaller).setOnClickListener { changeCaptionSize(-2f) }
        findViewById<Button>(R.id.captionLarger).setOnClickListener { changeCaptionSize(2f) }
        findViewById<Button>(R.id.volumeDown).setOnClickListener { bumpVolume(-10) }
        findViewById<Button>(R.id.volumeUp).setOnClickListener { bumpVolume(10) }
        wireReactions()
        findViewById<SeekBar>(R.id.volumeSlider).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.volumeValue).text = "$progress%"
                if (fromUser) service?.volume(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        applyCaptionSize()
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
        setWifiStatus("Looking for EF-TOUR networks")
        TourNetworks.find(this, TourNetworks.Band.TRAVELER) { lookup ->
            val hint = lookup.hint
            if (hint != null) {
                setWifiStatus(hint)
                return@find
            }
            if (lookup.names.size == 1) {
                selectNetwork(lookup.names.first())
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Select the network your guide named")
                    .setItems(lookup.names.toTypedArray()) { _, which ->
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
        val listening = lastState.listening
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
            val reaction = findViewById<View>(id)
            reaction.setOnClickListener {
                if (!reaction.isEnabled) return@setOnClickListener
                ContextCompat.startForegroundService(this, Intent(this, TravelerService::class.java).apply {
                    action = TravelerService.ACTION_REACT
                    putExtra(TravelerService.EXTRA_REACTION, name)
                })
                reaction.isEnabled = false
                reaction.alpha = 0.35f
                reactionHandler.postDelayed({
                    reaction.isEnabled = true
                    reaction.alpha = 1f
                }, REACTION_COOLDOWN_MS)
            }
        }
    }

    private fun setCaptionsVisible(show: Boolean) {
        captionsOn = show
        if (!show) captionFull = false
        render(lastState)
    }

    private fun changeCaptionSize(delta: Float) {
        captionSp = (captionSp + delta).coerceIn(MIN_CAPTION_SP, MAX_CAPTION_SP)
        getSharedPreferences("traveler", MODE_PRIVATE).edit().putFloat("caption_font", captionSp).apply()
        applyCaptionSize()
        shownCaption = ""
        render(lastState)
    }

    private fun applyCaptionSize() {
        findViewById<TextView>(R.id.captionText).setTextSize(TypedValue.COMPLEX_UNIT_SP, captionSp)
    }

    private fun bumpVolume(delta: Int) {
        val slider = findViewById<SeekBar>(R.id.volumeSlider)
        val next = (slider.progress + delta).coerceIn(0, 100)
        slider.progress = next
        findViewById<TextView>(R.id.volumeValue).text = "$next%"
        service?.volume(next)
    }

    private fun render(state: TravelerService.State) {
        lastState = state
        findViewById<TextView>(R.id.statusText).text = state.status
        findViewById<TextView>(R.id.batteryText).text = state.battery
        findViewById<TextView>(R.id.wifiStatus).text = state.wifi
        findViewById<Button>(R.id.listenButton).text = if (state.listening) "Stop Listening" else "Start Listening"
        findViewById<View>(R.id.waveform).visibility = if (state.receiving) View.VISIBLE else View.INVISIBLE
        val id = findViewById<TextView>(R.id.travelerId)
        if (state.travelerId > 0) {
            id.visibility = View.VISIBLE
            id.text = "Traveler ${state.travelerId}"
        }
        if (!state.listening || !state.captionsAvailable) {
            captionsOn = false
            captionFull = false
        }
        applyCaptionChrome(state)
        val network = if (state.wifiConnected) state.wifi.removePrefix("Connected to ").trim() else ""
        applyScreens(state.wifiConnected, network)
    }

    private fun applyCaptionChrome(state: TravelerService.State) {
        val offered = state.listening && state.captionsAvailable
        val showing = offered && captionsOn
        findViewById<View>(R.id.captionButton).visibility = if (offered && !showing) View.VISIBLE else View.GONE
        findViewById<View>(R.id.reactionBar).visibility = if (state.listening) View.VISIBLE else View.GONE
        val panel = findViewById<LinearLayout>(R.id.captionPanel)
        val scroll = findViewById<ScrollView>(R.id.captionScroll)
        val sessionScroll = findViewById<View>(R.id.sessionScroll)
        val controls = findViewById<View>(R.id.fullScreenControls)
        val full = showing && captionFull
        scaleReactions(full)
        val sessionParams = sessionScroll.layoutParams as LinearLayout.LayoutParams
        sessionParams.height = 0
        sessionParams.weight = if (full) 0f else 1f
        sessionScroll.layoutParams = sessionParams
        listOf(R.id.headerLogo, R.id.headerTitle, R.id.headerSubtitle).forEach { id ->
            findViewById<View>(id).visibility = if (full) View.GONE else View.VISIBLE
        }
        sessionScroll.visibility = if (full) View.GONE else View.VISIBLE
        controls.visibility = if (full) View.VISIBLE else View.GONE
        panel.visibility = if (showing) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.captionFullButton).text = if (full) "Exit full screen" else "Full screen"
        if (showing) {
            val panelParams = panel.layoutParams as LinearLayout.LayoutParams
            val scrollParams = scroll.layoutParams as LinearLayout.LayoutParams
            if (full) {
                panelParams.height = 0
                panelParams.weight = 1f
                scrollParams.height = 0
                scrollParams.weight = 1f
            } else {
                panelParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
                panelParams.weight = 0f
                scrollParams.height = captionViewportHeight()
                scrollParams.weight = 0f
            }
            panel.layoutParams = panelParams
            scroll.layoutParams = scrollParams
            showCaptionText(state, scroll)
        }
    }

    private fun scaleReactions(full: Boolean) {
        val bar = findViewById<LinearLayout>(R.id.reactionBar)
        val size = ((if (full) 26 else 34) * resources.displayMetrics.density).toInt()
        for (index in 0 until bar.childCount) {
            val column = bar.getChildAt(index) as? LinearLayout ?: continue
            val icon = column.getChildAt(0)
            val params = icon.layoutParams
            params.width = size
            params.height = size
            icon.layoutParams = params
        }
    }

    private fun captionViewportHeight(): Int {
        val text = findViewById<TextView>(R.id.captionText)
        val line = text.lineHeight.takeIf { it > 0 } ?: (text.textSize * 1.4f).toInt()
        return line * 5 + text.paddingTop + text.paddingBottom
    }

    private fun showCaptionText(state: TravelerService.State, scroll: ScrollView) {
        if (state.caption == shownCaption && state.captionInterim == shownInterim) return
        val view = findViewById<TextView>(R.id.captionText)
        val child = scroll.getChildAt(0)
        val atBottom = child == null || scroll.height == 0 ||
            scroll.scrollY + scroll.height >= child.height - 48
        view.text = captionSpannable(state)
        shownCaption = state.caption
        shownInterim = state.captionInterim
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun captionSpannable(state: TravelerService.State): CharSequence {
        if (!state.captionInterim || state.caption.isEmpty()) return state.caption
        val split = state.caption.lastIndexOf('\n')
        val start = if (split < 0) 0 else split + 1
        return SpannableString(state.caption).apply {
            setSpan(StyleSpan(Typeface.ITALIC), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(0xCCFFFFFF.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
        val hideHeader = captionFull && captionsOn && !showConnect
        listOf(R.id.headerLogo, R.id.headerTitle, R.id.headerSubtitle).forEach { id ->
            findViewById<View>(id).visibility = if (hideHeader) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val REACTION_COOLDOWN_MS = 5_000L
        const val MIN_CAPTION_SP = 16f
        const val MAX_CAPTION_SP = 34f
    }
}
