package travel.eftours.touraudio.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * One WebSocket to the existing router. Binary frames are PCM.
 * Text frames are the current JSON protocol, including audio_format on open.
 */
class TourSocket(
    private val role: String,
    private val onJson: (JSONObject) -> Unit,
    private val onPcm: (ByteArray) -> Unit,
    private val log: (String) -> Unit
) {
    @Volatile var open: Boolean = false
        private set

    private var url = DEFAULT_URL
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var generation = 0
    private var socketId = 0
    private var running = false
    private var reconnectPosted = false
    private var pongSincePing = true
    private var missedPongs = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun connect(url: String) {
        this.url = url.ifBlank { DEFAULT_URL }
        running = true
        generation++
        openSocket(generation)
    }

    fun close() {
        running = false
        generation++
        open = false
        handler.removeCallbacksAndMessages(null)
        socket?.cancel()
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    fun sendJson(text: String) {
        if (open) socket?.send(text)
    }

    fun sendPcm(pcm: ByteArray) {
        if (open) socket?.send(pcm.toByteString())
    }

    private fun openSocket(gen: Int) {
        if (!running || gen != generation) return
        val id = ++socketId
        open = false
        val previous = socket
        socket = null
        previous?.cancel()
        val http = client ?: httpClient().also { client = it }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            log("bad audio address")
            return
        }
        log("connecting")
        socket = http.newWebSocket(request, Listener(gen, id))
    }

    private inner class Listener(private val gen: Int, private val id: Int) : WebSocketListener() {
        private fun live() = running && gen == generation && id == socketId

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!live()) return
            open = true
            missedPongs = 0
            pongSincePing = true
            val format = if (role == "guide") {
                """{"type":"audio_format","role":"guide","sampleRate":16000,"chunkFrames":2048,"chunkDurationMs":128}"""
            } else {
                """{"type":"audio_format","role":"traveler","sampleRate":16000}"""
            }
            webSocket.send(format)
            schedulePing(gen)
            log("connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!live()) return
            val msg = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            if (msg.optString("type") == "pong") {
                pongSincePing = true
                missedPongs = 0
            }
            onJson(msg)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (!live()) return
            onPcm(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!live()) return
            open = false
            log("disconnected")
            scheduleReconnect(gen)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!live()) return
            open = false
            log("connection failed: ${t.message}")
            scheduleReconnect(gen)
        }
    }

    private fun schedulePing(gen: Int) {
        handler.postDelayed({
            if (!running || gen != generation || !open) return@postDelayed
            pongSincePing = false
            socket?.send("""{"type":"ping"}""")
            handler.postDelayed({
                if (!running || gen != generation || pongSincePing) return@postDelayed
                missedPongs++
                if (missedPongs >= 2) socket?.close(1000, "pong timeout")
            }, 4000)
            schedulePing(gen)
        }, 5000)
    }

    private fun scheduleReconnect(gen: Int) {
        if (!running || gen != generation || reconnectPosted) return
        reconnectPosted = true
        handler.postDelayed({
            reconnectPosted = false
            if (running && gen == generation) openSocket(gen)
        }, 1000)
    }

    private fun httpClient(): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    companion object {
        const val DEFAULT_URL = "wss://192.168.8.1:3000"
    }
}
