package travel.eftours.touraudio.guide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import travel.eftours.touraudio.core.PcmCapture
import travel.eftours.touraudio.core.TourSocket
import travel.eftours.touraudio.core.TourWifi

class GuideService : Service() {
    data class State(
        val status: String = "Ready to broadcast · 0 listeners",
        val broadcasting: Boolean = false,
        val watching: Boolean = false,
        val ended: Boolean = false,
        val listeners: Int = 0,
        val level: Int = 0,
        val reactions: String = "👍 0    👎 0    ❤️ 0    😂 0    🤯 0",
        val participation: String = "0 of 0 reacted",
        val wifi: String = ""
    )

    inner class LocalBinder : Binder() {
        fun service(): GuideService = this@GuideService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: TourWifi
    private var socket: TourSocket? = null
    private var capture: PcmCapture? = null
    private var listener: ((State) -> Unit)? = null
    private var state = State()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        wifi = TourWifi(this, ::note)
        createChannel()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForeground(42, notification("Ready to broadcast"), micType())
        }
        wifi.restore()
        publish(state.copy(wifi = wifi.ssid?.let { "Holding $it" } ?: "Not joined"))
        if (sessionPrefs().getBoolean("broadcasting", false)) startBroadcast()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_JOIN -> wifi.join(
                intent.getStringExtra(EXTRA_SSID).orEmpty(),
                intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
            )
            ACTION_START -> startBroadcast()
            ACTION_STOP -> stopBroadcast()
            ACTION_SUBTITLE -> sendSubtitle(
                intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                intent.getBooleanExtra(EXTRA_FINAL, false),
                intent.getBooleanExtra(EXTRA_ENABLED, true)
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        socket?.close()
        super.onDestroy()
    }

    fun setListener(next: ((State) -> Unit)?) {
        listener = next
        next?.invoke(state)
    }

    private fun startBroadcast() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(state.copy(status = "Allow the microphone to broadcast"))
            return
        }
        startForeground(42, notification("Broadcasting"), micType())
        stopCapture()
        if (socket == null) {
            socket = TourSocket("guide", ::onJson, { }, ::note).also {
                it.connect(TourSocket.DEFAULT_URL)
            }
        }
        val mic = PcmCapture(
            voiceProcessing = false,
            onChunk = { pcm -> socket?.sendPcm(pcm) },
            onLevel = { level ->
                val percent = (level * 100).toInt()
                if (percent != state.level) publish(state.copy(level = percent))
            },
            log = ::note
        )
        capture = mic
        mic.start()
        sessionPrefs().edit().putBoolean("broadcasting", true).apply()
        acquireWakeLock()
        publish(state.copy(
            broadcasting = true,
            watching = false,
            ended = false,
            status = "Broadcasting · ${state.listeners} listeners"
        ))
    }

    private fun stopBroadcast() {
        sessionPrefs().edit().putBoolean("broadcasting", false).apply()
        stopCapture()
        socket?.close()
        socket = null
        releaseWakeLock()
        publish(state.copy(
            broadcasting = false,
            watching = false,
            level = 0,
            status = "Ready to broadcast · 0 listeners"
        ))
    }

    private fun stopCapture() {
        capture?.stop()
        capture = null
    }

    private fun enterWatch() {
        stopCapture()
        sessionPrefs().edit().putBoolean("broadcasting", false).apply()
        publish(state.copy(
            broadcasting = false,
            watching = true,
            ended = false,
            status = "Watching · ${state.listeners} listeners"
        ))
    }

    private fun sendSubtitle(text: String, isFinal: Boolean, enabled: Boolean) {
        val payload = JSONObject()
            .put("type", "subtitle")
            .put("text", text)
            .put("isFinal", isFinal)
            .put("enabled", enabled)
            .toString()
        socket?.sendJson(payload)
    }

    private fun onJson(msg: JSONObject) {
        when (msg.optString("type")) {
            "client-count" -> {
                val listeners = (msg.optInt("count") - 1).coerceAtLeast(0)
                val label = if (state.watching) "Watching" else if (state.broadcasting) "Broadcasting" else "Ready to broadcast"
                publish(state.copy(listeners = listeners, status = "$label · $listeners listeners"))
            }
            "broadcaster_taken" -> {
                val listeners = msg.optInt("listenerCount")
                publish(state.copy(listeners = listeners))
                enterWatch()
            }
            "broadcaster_ended" -> publish(state.copy(
                watching = false,
                ended = true,
                broadcasting = false,
                status = "Broadcast ended · you can start"
            ))
            "watch-status" -> {
                val listeners = msg.optInt("listenerCount")
                publish(state.copy(
                    watching = true,
                    listeners = listeners,
                    status = "Watching · $listeners listeners"
                ))
            }
            "reaction-counts" -> {
                val counts = msg.optJSONObject("counts")
                fun n(key: String) = counts?.optInt(key) ?: 0
                publish(state.copy(
                    reactions = "👍 ${n("thumbs-up")}    👎 ${n("thumbs-down")}    ❤️ ${n("heart")}    😂 ${n("laugh")}    🤯 ${n("mind-blown")}",
                    participation = "${msg.optInt("uniqueParticipants")} of ${msg.optInt("totalParticipants")} reacted"
                ))
            }
        }
    }

    fun clearReactions() {
        socket?.sendJson("""{"type":"clear-reactions"}""")
    }

    private fun publish(next: State) {
        state = next.copy(wifi = wifi.ssid?.let { "Holding $it" } ?: next.wifi)
        val callback = listener
        handler.post { callback?.invoke(state) }
    }

    private fun note(message: String) {
        if (message.startsWith("connection failed") && state.broadcasting) {
            publish(state.copy(status = "Could not reach the router"))
        }
    }

    private fun sessionPrefs() = getSharedPreferences("guide_session", MODE_PRIVATE)

    private fun notification(text: String): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Tour Audio Guide")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun micType(): Int {
        return if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "touraudio:guide")
            .also { wakeLock = it })
        if (!lock.isHeld) lock.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    companion object {
        const val ACTION_JOIN = "travel.eftours.touraudio.guide.JOIN"
        const val ACTION_START = "travel.eftours.touraudio.guide.START"
        const val ACTION_STOP = "travel.eftours.touraudio.guide.STOP"
        const val ACTION_SUBTITLE = "travel.eftours.touraudio.guide.SUBTITLE"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FINAL = "final"
        const val EXTRA_ENABLED = "enabled"
        private const val CHANNEL = "guide"
    }
}
