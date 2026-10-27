package travel.eftours.touraudio.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Throwaway harness. Talks to the existing tour router:
 * text frames are JSON, binary frames are little-endian PCM Int16 at 16 kHz.
 * The first binary frame from this process claims the guide slot on the server.
 */
class AudioSpikeService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): AudioSpikeService = this@AudioSpikeService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<String>()
    private val stampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var listener: ((String) -> Unit)? = null
    @Volatile private var userStopped = false
    @Volatile private var sessionActive = false
    @Volatile private var mode = MODE_IDLE
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var socketOpen = false
    @Volatile private var pongSincePing = true
    @Volatile private var sessionGen = 0
    @Volatile private var socketId = 0

    private var client: OkHttpClient? = null
    private var url: String = DEFAULT_URL
    private var trustCert = true
    private var voiceProcessing = false
    private var reconnectPosted = false
    private var missedPongs = 0
    private var reconnects = 0
    private var rxChunks = 0L
    private var maxRxGapMs = 0L
    private var rxGaps = 0
    private var lastRxAt = 0L
    private var lastClientCount = -1
    private var sendFailLogged = false
    private var player: PcmPlayer? = null
    private var capture: PcmCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var logFile: File? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var joinedSsid: String? = null
    private var wifiCheck: Runnable? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        logFile = File(getExternalFilesDir(null) ?: filesDir, "spike-log.txt")
        log("spike process start ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        log("log file ${logFile?.absolutePath}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_JOIN_WIFI -> {
                if (!sessionActive) goForeground(MODE_IDLE)
                val ssid = intent.getStringExtra(EXTRA_SSID).orEmpty().trim()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                joinWifi(ssid, password)
            }
            ACTION_LISTEN, ACTION_BROADCAST -> {
                userStopped = false
                url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty().ifEmpty { DEFAULT_URL }
                trustCert = intent.getBooleanExtra(EXTRA_TRUST, true)
                voiceProcessing = intent.getBooleanExtra(EXTRA_VOICE, false)
                val next = if (intent.action == ACTION_BROADCAST) MODE_BROADCAST else MODE_LISTEN
                startSession(next)
            }
            else -> {
                if (!sessionActive && wifiCallback == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        userStopped = true
        sessionActive = false
        sessionGen++
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        releaseWifi()
        abandonFocus()
        releaseWakeLock()
        super.onDestroy()
    }

    fun setListener(next: ((String) -> Unit)?) {
        listener = next
        if (next == null) return
        val snapshot = synchronized(recent) { recent.toList() }
        snapshot.forEach { next(it) }
    }

    fun logWifiCheck() {
        log(wifiStatusLine())
    }

    fun stopSession() {
        userStopped = true
        sessionActive = false
        sessionGen++
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        releaseWifi()
        abandonFocus()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        log("stopped")
    }

    private fun startSession(nextMode: String) {
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            log("URL must start with wss:// or ws://")
            if (!sessionActive) goForeground(MODE_IDLE)
            return
        }
        sessionGen++
        val gen = sessionGen
        mode = nextMode
        sessionActive = true
        reconnectPosted = false
        reconnects = 0
        rxChunks = 0L
        rxGaps = 0
        maxRxGapMs = 0L
        lastRxAt = 0L
        missedPongs = 0
        sendFailLogged = false
        goForeground(nextMode)
        acquireWakeLock()
        requestFocus()
        stopAudio()
        if (nextMode == MODE_LISTEN) {
            player = PcmPlayer(::log).also { it.start() }
        }
        log("session $nextMode url=$url trustCert=$trustCert")
        connect(gen)
        scheduleSummary(gen)
    }

    private fun connect(gen: Int) {
        if (gen != sessionGen || userStopped || !sessionActive) return
        val id = ++socketId
        socketOpen = false
        val previous = webSocket
        webSocket = null
        previous?.cancel()
        val http = client ?: buildClient().also { client = it }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            log("bad URL: ${e.message}")
            return
        }
        log("connecting $url")
        webSocket = http.newWebSocket(request, SocketListener(gen, id))
    }

    private inner class SocketListener(private val gen: Int, private val id: Int) : WebSocketListener() {
        private fun live(): Boolean = gen == sessionGen && id == socketId && !userStopped

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!live()) return
            socketOpen = true
            missedPongs = 0
            pongSincePing = true
            sendFailLogged = false
            player?.flush()
            log("socket open")
            val format = if (mode == MODE_BROADCAST) {
                """{"type":"audio_format","role":"guide","sampleRate":16000,"chunkFrames":2048,"chunkDurationMs":128}"""
            } else {
                """{"type":"audio_format","role":"traveler","sampleRate":16000}"""
            }
            webSocket.send(format)
            if (mode == MODE_BROADCAST) startCapture()
            sendPing(gen)
            schedulePing(gen)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!live()) return
            onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!live() || mode != MODE_LISTEN) return
            onPcm(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!live()) return
            socketOpen = false
            log("socket closed $code ${reason.ifEmpty { "no reason" }}")
            scheduleReconnect(gen)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!live()) return
            socketOpen = false
            log("socket failure ${t.javaClass.simpleName}: ${t.message}")
            scheduleReconnect(gen)
        }
    }

    private fun onText(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (msg.optString("type")) {
            "client-id" -> log("client id ${msg.optInt("id")}")
            "pong" -> {
                pongSincePing = true
                missedPongs = 0
            }
            "broadcaster_taken" -> {
                log("another guide is already broadcasting (listeners=${msg.optInt("listenerCount")}). Capture stopped.")
                capture?.stop()
                capture = null
            }
            "broadcaster_ended" -> log("broadcaster ended")
            "watch-status" -> log(
                "watch mode active=${msg.optBoolean("broadcasterActive")} listeners=${msg.optInt("listenerCount")}"
            )
            "client-count" -> {
                val count = msg.optInt("count")
                if (count != lastClientCount) {
                    lastClientCount = count
                    log("client count $count")
                }
            }
            "subtitle" -> { /* guide speech; do not write it to the log */ }
        }
    }

    private fun onPcm(pcm: ByteArray) {
        val now = System.currentTimeMillis()
        if (lastRxAt != 0L) {
            val gap = now - lastRxAt
            if (gap > maxRxGapMs) maxRxGapMs = gap
            if (gap >= GAP_MS) {
                rxGaps++
                log("receive gap ${gap}ms")
            }
        }
        lastRxAt = now
        rxChunks++
        player?.enqueue(pcm)
    }

    private fun startCapture() {
        capture?.stop()
        val next = PcmCapture(voiceProcessing, ::sendPcm, ::log)
        capture = next
        next.start()
    }

    private fun sendPcm(pcm: ByteArray) {
        val socket = webSocket
        if (!socketOpen || socket == null) return
        val ok = socket.send(ByteString.of(pcm, 0, pcm.size))
        if (ok) {
            sendFailLogged = false
        } else if (!sendFailLogged) {
            sendFailLogged = true
            log("audio send rejected")
        }
    }

    private fun sendPing(gen: Int) {
        val socket = webSocket ?: return
        if (gen != sessionGen || !socketOpen) return
        pongSincePing = false
        socket.send("""{"type":"ping"}""")
        handler.postDelayed({
            if (gen != sessionGen || !sessionActive || userStopped || pongSincePing) return@postDelayed
            missedPongs++
            log("pong miss $missedPongs")
            if (missedPongs >= 2) {
                log("pong timeout, closing socket")
                webSocket?.close(1000, "pong timeout")
            }
        }, 4000)
    }

    private fun schedulePing(gen: Int) {
        handler.postDelayed({
            if (gen != sessionGen || !sessionActive || userStopped || !socketOpen) return@postDelayed
            sendPing(gen)
            schedulePing(gen)
        }, 5000)
    }

    private fun scheduleSummary(gen: Int) {
        handler.postDelayed({
            if (gen != sessionGen || !sessionActive || userStopped) return@postDelayed
            log(summaryLine())
            scheduleSummary(gen)
        }, 10_000)
    }

    private fun summaryLine(): String {
        val cap = capture
        return if (mode == MODE_BROADCAST) {
            "broadcast sent=${cap?.sentChunks ?: 0} captureGaps=${cap?.captureGaps ?: 0} maxReadGapMs=${cap?.maxReadGapMs ?: 0} reconnects=$reconnects"
        } else {
            "listen rx=$rxChunks receiveGaps=$rxGaps maxRxGapMs=$maxRxGapMs playbackGaps=${player?.playbackGaps ?: 0} reconnects=$reconnects"
        }
    }

    private fun scheduleReconnect(gen: Int) {
        if (gen != sessionGen || userStopped || !sessionActive || reconnectPosted) return
        reconnectPosted = true
        reconnects++
        handler.postDelayed({
            reconnectPosted = false
            if (gen == sessionGen && !userStopped && sessionActive) {
                log("reconnecting")
                connect(gen)
            }
        }, 1000)
    }

    private fun stopAudio() {
        capture?.stop()
        capture = null
        player?.stop()
        player = null
        socketOpen = false
        socketId++
        val previous = webSocket
        webSocket = null
        previous?.cancel()
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    private fun joinWifi(ssid: String, password: String) {
        if (ssid.isEmpty()) {
            log("Wi-Fi name is empty")
            return
        }
        if (password.length < 8) {
            log("Wi-Fi password must be at least 8 characters for WPA2")
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        releaseWifi()
        joinedSsid = ssid
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                log("joined $ssid and bound this app to it")
                scheduleWifiCheck()
                if (sessionActive) {
                    log("reconnecting audio on the tour network")
                    scheduleReconnect(sessionGen)
                }
            }

            override fun onUnavailable() {
                log("Wi-Fi join failed or the system dialog was dismissed")
            }

            override fun onLost(network: Network) {
                log("tour Wi-Fi lost")
                if (cm.boundNetworkForProcess == network) cm.bindProcessToNetwork(null)
            }
        }
        wifiCallback = callback
        log("requesting $ssid — accept the system dialog")
        cm.requestNetwork(request, callback, 30_000)
    }

    private fun scheduleWifiCheck() {
        wifiCheck?.let { handler.removeCallbacks(it) }
        val check = Runnable { log("5-min " + wifiStatusLine()) }
        wifiCheck = check
        handler.postDelayed(check, 5 * 60 * 1000L)
    }

    private fun wifiStatusLine(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.boundNetworkForProcess
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val info = caps?.transportInfo as? WifiInfo
        val raw = info?.ssid?.trim('"') ?: "unknown"
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return "wifi bound=${network != null} transportWifi=$wifi ssid=$raw expected=${joinedSsid ?: "none"}"
    }

    private fun releaseWifi() {
        wifiCheck?.let { handler.removeCallbacks(it) }
        wifiCheck = null
        val cm = getSystemService(ConnectivityManager::class.java)
        wifiCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        wifiCallback = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    private fun goForeground(kind: String) {
        val notification = notification(kind)
        if (Build.VERSION.SDK_INT >= 30) {
            val type = when (kind) {
                MODE_BROADCAST -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                MODE_LISTEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (kind != MODE_BROADCAST) {
            val type = if (kind == MODE_LISTEN) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(kind: String): Notification {
        val text = when (kind) {
            MODE_LISTEN -> "Listening — lock the screen"
            MODE_BROADCAST -> "Broadcasting — lock the screen"
            else -> "Holding tour Wi-Fi"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tour audio spike")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun requestFocus() {
        val audio = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                log("audio focus $change")
            }
            .build()
        focusRequest = request
        val result = audio.requestAudioFocus(request)
        log("audio focus request $result")
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "touraudio:spike").also {
            wakeLock = it
        }
        if (!lock.isHeld) lock.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
        if (trustCert) {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            builder.sslSocketFactory(ssl.socketFactory, trust)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun log(msg: String) {
        val line = synchronized(stampFormat) {
            "${stampFormat.format(Date())} $msg"
        }
        val callback = listener
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > 200) recent.removeFirst()
            try {
                logFile?.appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
        callback?.invoke(line)
    }

    companion object {
        const val ACTION_LISTEN = "travel.eftours.touraudio.spike.LISTEN"
        const val ACTION_BROADCAST = "travel.eftours.touraudio.spike.BROADCAST"
        const val ACTION_JOIN_WIFI = "travel.eftours.touraudio.spike.JOIN_WIFI"
        const val EXTRA_URL = "url"
        const val EXTRA_TRUST = "trust"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val DEFAULT_URL = "wss://192.168.8.1:3000"
        private const val MODE_IDLE = "idle"
        private const val MODE_LISTEN = "listen"
        private const val MODE_BROADCAST = "broadcast"
        private const val CHANNEL_ID = "spike"
        private const val NOTIFICATION_ID = 41
        private const val GAP_MS = 1000L

        fun listenIntent(context: Context, url: String, trust: Boolean): Intent {
            return Intent(context, AudioSpikeService::class.java).apply {
                action = ACTION_LISTEN
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TRUST, trust)
            }
        }

        fun broadcastIntent(context: Context, url: String, trust: Boolean, voice: Boolean): Intent {
            return Intent(context, AudioSpikeService::class.java).apply {
                action = ACTION_BROADCAST
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TRUST, trust)
                putExtra(EXTRA_VOICE, voice)
            }
        }

        fun joinIntent(context: Context, ssid: String, password: String): Intent {
            return Intent(context, AudioSpikeService::class.java).apply {
                action = ACTION_JOIN_WIFI
                putExtra(EXTRA_SSID, ssid)
                putExtra(EXTRA_PASSWORD, password)
            }
        }
    }
}
