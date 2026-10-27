package travel.eftours.touraudio.traveler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import travel.eftours.touraudio.core.PcmPlayer
import travel.eftours.touraudio.core.TourSocket
import travel.eftours.touraudio.core.TourWifi

class TravelerService : Service() {
    data class State(
        val status: String = "Not connected",
        val listening: Boolean = false,
        val travelerId: Int = 0,
        val receiving: Boolean = false,
        val caption: String = "",
        val wifi: String = "",
        val battery: String = ""
    )

    inner class LocalBinder : Binder() {
        fun service(): TravelerService = this@TravelerService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: TourWifi
    private var socket: TourSocket? = null
    private var player: PcmPlayer? = null
    private var listener: ((State) -> Unit)? = null
    private var state = State()
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var lastAudioAt = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        wifi = TourWifi(this, ::note)
        createChannel()
        startForeground(41, notification("Tour audio"), playbackType())
        wifi.restore()
        publish(state.copy(wifi = wifi.ssid?.let { "Holding $it" } ?: "Not joined"))
        if (sessionPrefs().getBoolean("listening", false)) startListening("")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_JOIN -> wifi.join(
                intent.getStringExtra(EXTRA_SSID).orEmpty(),
                intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
            )
            ACTION_LISTEN -> startListening(intent.getStringExtra(EXTRA_URL).orEmpty())
            ACTION_STOP -> stopListening()
            ACTION_RELEASE -> {
                stopListening()
                wifi.release()
                publish(state.copy(wifi = "Not joined"))
            }
            ACTION_VOLUME -> player?.setGain((intent.getIntExtra(EXTRA_VOLUME, 80)) / 100f)
            ACTION_REACT -> socket?.sendJson(
                """{"type":"reaction","reaction":"${intent.getStringExtra(EXTRA_REACTION)}"}"""
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        socket?.close()
        socket = null
        player?.stop()
        player = null
        releaseWakeLock()
        abandonFocus()
        super.onDestroy()
    }

    fun setListener(next: ((State) -> Unit)?) {
        listener = next
        next?.invoke(state.copy(battery = batteryLabel()))
    }

    fun volume(percent: Int) {
        player?.setGain(percent / 100f)
    }

    private fun startListening(url: String) {
        stopListening()
        val play = PcmPlayer(::note)
        player = play
        play.start()
        play.setGain(0.8f)
        acquireWakeLock()
        requestFocus()
        val next = TourSocket("traveler", ::onJson, { pcm ->
            lastAudioAt = System.currentTimeMillis()
            play.enqueue(pcm)
            if (!state.receiving) publish(state.copy(receiving = true, status = "Listening"))
        }, ::note)
        socket = next
        sessionPrefs().edit().putBoolean("listening", true).apply()
        next.connect(url.ifBlank { TourSocket.DEFAULT_URL })
        publish(state.copy(listening = true, status = "Connecting"))
        watchAudio()
    }

    private fun stopListening() {
        socket?.close()
        socket = null
        player?.stop()
        player = null
        releaseWakeLock()
        abandonFocus()
        sessionPrefs().edit().putBoolean("listening", false).apply()
        publish(state.copy(listening = false, receiving = false, status = "Not connected", caption = ""))
    }

    private fun onJson(msg: JSONObject) {
        when (msg.optString("type")) {
            "client-id" -> publish(state.copy(travelerId = msg.optInt("id")))
            "subtitle" -> {
                val text = if (msg.optBoolean("enabled", true)) msg.optString("text") else ""
                publish(state.copy(caption = text))
            }
            "client-count" -> Unit
        }
    }

    private fun watchAudio() {
        handler.postDelayed({
            if (!state.listening) return@postDelayed
            val live = System.currentTimeMillis() - lastAudioAt < 1500
            if (state.receiving != live && socket?.open == true) {
                publish(state.copy(receiving = live, status = if (live) "Listening" else "Connected"))
            }
            watchAudio()
        }, 500)
    }

    private fun publish(next: State) {
        val withBattery = next.copy(battery = batteryLabel(), wifi = wifi.ssid?.let { "Holding $it" } ?: next.wifi)
        state = withBattery
        val callback = listener
        handler.post { callback?.invoke(state) }
    }

    private fun note(message: String) {
        val status = when {
            message.startsWith("joined") -> "On tour Wi-Fi"
            message.startsWith("connection failed") -> "Could not reach the router"
            message == "connected" && state.listening -> "Connected"
            message == "disconnected" && state.listening -> "Reconnecting"
            else -> state.status
        }
        publish(state.copy(status = status, wifi = message))
    }

    private fun sessionPrefs() = getSharedPreferences("traveler_session", MODE_PRIVATE)

    private fun batteryLabel(): String {
        val pct = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct > 0) "Battery $pct%" else ""
    }

    private fun notification(text: String): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Tour Audio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun playbackType(): Int {
        return if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    }

    private fun requestFocus() {
        val audio = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        focus = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "touraudio:traveler")
            .also { wakeLock = it })
        if (!lock.isHeld) lock.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    companion object {
        const val ACTION_JOIN = "travel.eftours.touraudio.traveler.JOIN"
        const val ACTION_LISTEN = "travel.eftours.touraudio.traveler.LISTEN"
        const val ACTION_STOP = "travel.eftours.touraudio.traveler.STOP"
        const val ACTION_RELEASE = "travel.eftours.touraudio.traveler.RELEASE"
        const val ACTION_VOLUME = "travel.eftours.touraudio.traveler.VOLUME"
        const val ACTION_REACT = "travel.eftours.touraudio.traveler.REACT"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_URL = "url"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_REACTION = "reaction"
        private const val CHANNEL = "traveler"
    }
}
