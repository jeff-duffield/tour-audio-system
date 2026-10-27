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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var service: GuideService? = null
    private var bound = false
    private var requestedBind = false
    private var pendingJoin = false
    private var locked = false
    private var taps = 0
    private var lastTap = 0L
    private var ccOn = false
    private var ccErrors = 0
    private var recognizer: SpeechRecognizer? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMic()) ensureService()
        if (pendingJoin) {
            pendingJoin = false
            sendJoin()
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
        findViewById<EditText>(R.id.passwordInput).setText(prefs.getString("password", ""))
        findViewById<Button>(R.id.joinButton).setOnClickListener { joinWifi() }
        findViewById<Button>(R.id.broadcastButton).setOnClickListener { toggleBroadcast() }
        findViewById<Button>(R.id.lockButton).setOnClickListener { setLocked(true) }
        findViewById<Button>(R.id.ccButton).setOnClickListener { toggleCaptions() }
        findViewById<Button>(R.id.clearReactions).setOnClickListener { service?.clearReactions() }
        findViewById<View>(R.id.lockOverlay).setOnClickListener { unlockTap() }
        if (!hasMic()) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasMic()) ensureService()
        if (ccOn) startCaptions()
    }

    override fun onStop() {
        stopCaptions()
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

    private fun joinWifi() {
        val ssid = findViewById<EditText>(R.id.ssidInput).text.toString()
        val password = findViewById<EditText>(R.id.passwordInput).text.toString()
        getSharedPreferences("guide", MODE_PRIVATE).edit()
            .putString("ssid", ssid).putString("password", password).apply()
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
        if (stop) {
            ccOn = false
            stopCaptions()
            sendCaption("", true, false)
        }
    }

    private fun toggleCaptions() {
        ccOn = !ccOn
        findViewById<Button>(R.id.ccButton).text = if (ccOn) "CC on" else "CC off"
        if (ccOn) startCaptions() else {
            stopCaptions()
            sendCaption("", true, false)
        }
    }

    private fun startCaptions() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ccOn = false
            showCaption("Captions are not available on this phone. Broadcast continues.")
            findViewById<Button>(R.id.ccButton).text = "CC off"
            return
        }
        if (recognizer != null) return
        ccErrors = 0
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                ccErrors++
                if (ccErrors >= 3) {
                    ccOn = false
                    window.decorView.post {
                        stopCaptions()
                        sendCaption("", true, false)
                        showCaption("Captions stopped. Broadcast is still on.")
                        findViewById<Button>(R.id.ccButton).text = "CC off"
                    }
                    return
                }
                if (ccOn) listen(rec)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) sendCaption(text, true)
                ccErrors = 0
                if (ccOn) listen(rec)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) sendCaption(text, false)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        showCaption("Captions on. If they fail, the broadcast keeps going.")
        listen(rec)
    }

    private fun listen(rec: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            rec.startListening(intent)
        } catch (_: Exception) {
            showCaption("Captions could not start. Broadcast is still on.")
        }
    }

    private fun stopCaptions() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun sendCaption(text: String, isFinal: Boolean, enabled: Boolean = true) {
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java).apply {
            action = GuideService.ACTION_SUBTITLE
            putExtra(GuideService.EXTRA_TEXT, text)
            putExtra(GuideService.EXTRA_FINAL, isFinal)
            putExtra(GuideService.EXTRA_ENABLED, enabled)
        })
    }

    private fun showCaption(text: String) {
        val view = findViewById<TextView>(R.id.captionStatus)
        view.visibility = View.VISIBLE
        view.text = text
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
        findViewById<View>(R.id.secondaryRow).visibility = live
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
        findViewById<TextView>(R.id.lockLabel).text = state.status
        if (!state.broadcasting && locked) setLocked(false)
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
