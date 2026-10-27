package travel.eftours.touraudio.spike

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Screen for the throwaway Android spike. Open spikes/android in Android Studio
 * and run it on a phone. Lock the screen after Listen or Broadcast is running.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var trustCert: CheckBox
    private lateinit var ssidInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var voiceProcessing: CheckBox
    private lateinit var status: TextView
    private lateinit var logView: TextView

    private var service: AudioSpikeService? = null
    private var bound = false
    private var pending: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isEmpty()) {
            pending?.invoke()
        } else {
            appendLog("permission denied: ${denied.joinToString()}")
        }
        pending = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as AudioSpikeService.LocalBinder).service()
            service = bound
            logView.text = ""
            bound.setListener { line -> runOnUiThread { appendLog(line) } }
            status.text = "Service connected"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.setListener(null)
            service = null
            status.text = "Service disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC

        urlInput = findViewById(R.id.urlInput)
        trustCert = findViewById(R.id.trustCert)
        ssidInput = findViewById(R.id.ssidInput)
        passwordInput = findViewById(R.id.passwordInput)
        voiceProcessing = findViewById(R.id.voiceProcessing)
        status = findViewById(R.id.status)
        logView = findViewById(R.id.logView)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        urlInput.setText(prefs.getString(KEY_URL, AudioSpikeService.DEFAULT_URL))
        ssidInput.setText(prefs.getString(KEY_SSID, ""))
        passwordInput.setText(prefs.getString(KEY_PASSWORD, ""))
        trustCert.isChecked = prefs.getBoolean(KEY_TRUST, true)
        voiceProcessing.isChecked = prefs.getBoolean(KEY_VOICE, false)

        findViewById<Button>(R.id.joinWifi).setOnClickListener {
            savePrefs()
            ensure(wifiPermissions()) { startJoin() }
        }
        findViewById<Button>(R.id.listen).setOnClickListener {
            savePrefs()
            ensure(notifyPermissions()) { startListen() }
        }
        findViewById<Button>(R.id.broadcast).setOnClickListener {
            savePrefs()
            ensure(notifyPermissions() + Manifest.permission.RECORD_AUDIO) { startBroadcast() }
        }
        findViewById<Button>(R.id.checkWifi).setOnClickListener {
            service?.logWifiCheck() ?: appendLog("start Join, Listen, or Broadcast first")
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            status.text = "Stopped"
            val running = service
            if (running != null) {
                running.stopSession()
            } else {
                stopService(Intent(this, AudioSpikeService::class.java))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, AudioSpikeService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
        }
        service = null
        super.onStop()
    }

    private fun startJoin() {
        status.text = "Joining Wi-Fi"
        ContextCompat.startForegroundService(
            this,
            AudioSpikeService.joinIntent(this, ssidInput.text.toString(), passwordInput.text.toString())
        )
    }

    private fun startListen() {
        status.text = "Listening"
        ContextCompat.startForegroundService(
            this,
            AudioSpikeService.listenIntent(this, urlInput.text.toString(), trustCert.isChecked)
        )
    }

    private fun startBroadcast() {
        status.text = "Broadcasting"
        ContextCompat.startForegroundService(
            this,
            AudioSpikeService.broadcastIntent(
                this,
                urlInput.text.toString(),
                trustCert.isChecked,
                voiceProcessing.isChecked
            )
        )
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            appendLog("battery optimization already unrestricted")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            appendLog("could not open the battery settings screen")
        }
    }

    private fun ensure(perms: Array<String>, then: () -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            then()
        } else {
            pending = then
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun notifyPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }

    private fun wifiPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_URL, urlInput.text.toString().trim())
            .putString(KEY_SSID, ssidInput.text.toString().trim())
            .putString(KEY_PASSWORD, passwordInput.text.toString())
            .putBoolean(KEY_TRUST, trustCert.isChecked)
            .putBoolean(KEY_VOICE, voiceProcessing.isChecked)
            .apply()
    }

    private fun appendLog(line: String) {
        status.text = line
        logView.append(line + "\n")
        if (logView.length() > 40_000) {
            val text = logView.text
            logView.text = text.subSequence(text.length - 20_000, text.length)
        }
    }

    companion object {
        private const val PREFS = "spike_prefs"
        private const val KEY_URL = "url"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRUST = "trust"
        private const val KEY_VOICE = "voice"
    }
}
