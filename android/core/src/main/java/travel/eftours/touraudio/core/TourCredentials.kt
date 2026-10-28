package travel.eftours.touraudio.core

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** One tour router: a single network name, its password, and the audio address. */
data class TourCredentials(
    val ssid: String,
    val password: String,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT
) {
    val audioUrl: String get() = "wss://$host:$port"

    fun encode(): String {
        return "eftour://join?ssid=${Uri.encode(ssid)}" +
            "&password=${Uri.encode(password)}" +
            "&host=${Uri.encode(host)}" +
            "&port=$port"
    }

    companion object {
        const val DEFAULT_HOST = "192.168.8.1"
        const val DEFAULT_PORT = 3000

        fun parse(raw: String): TourCredentials? {
            val text = raw.trim()
            return when {
                text.startsWith("WIFI:", ignoreCase = true) -> parseWifi(text)
                text.startsWith("eftour://", ignoreCase = true) -> parseUri(text)
                text.startsWith("{") -> parseJson(text)
                else -> null
            }
        }

        private fun parseWifi(raw: String): TourCredentials? {
            val body = raw.substring(5)
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    current.append(body[i + 1])
                    i += 2
                    continue
                }
                if (c == ';') {
                    fields.add(current.toString())
                    current.clear()
                } else {
                    current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) fields.add(current.toString())
            var ssid = ""
            var password = ""
            for (field in fields) {
                val colon = field.indexOf(':')
                if (colon <= 0) continue
                val key = field.substring(0, colon).uppercase()
                val value = field.substring(colon + 1)
                when (key) {
                    "S" -> ssid = value
                    "P" -> password = value
                }
            }
            if (ssid.isBlank()) return null
            return TourCredentials(ssid.trim(), password)
        }

        private fun parseUri(raw: String): TourCredentials? {
            val uri = Uri.parse(raw)
            val ssid = uri.getQueryParameter("ssid")?.trim().orEmpty()
            if (ssid.isBlank()) return null
            val password = uri.getQueryParameter("password").orEmpty()
            val host = uri.getQueryParameter("host")?.trim().orEmpty().ifBlank { DEFAULT_HOST }
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_PORT
            return TourCredentials(ssid, password, host, port)
        }

        private fun parseJson(raw: String): TourCredentials? {
            val obj = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            val ssid = obj.optString("ssid").trim()
            if (ssid.isBlank()) return null
            val host = obj.optString("host").trim().ifBlank { DEFAULT_HOST }
            val port = obj.optInt("port", DEFAULT_PORT)
            return TourCredentials(ssid, obj.optString("password"), host, port)
        }
    }
}

/** Remembers the last tour and a password for each network name. */
class TourCredentialsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(credentials: TourCredentials) {
        val known = passwords()
        known.put(credentials.ssid, credentials.password)
        prefs.edit()
            .putString(KEY_SSID, credentials.ssid)
            .putString(KEY_PASSWORD, credentials.password)
            .putString(KEY_HOST, credentials.host)
            .putInt(KEY_PORT, credentials.port)
            .putString(KEY_PASSWORDS, known.toString())
            .apply()
    }

    fun last(): TourCredentials? {
        val ssid = prefs.getString(KEY_SSID, null)?.trim().orEmpty()
        if (ssid.isBlank()) return null
        return TourCredentials(
            ssid,
            prefs.getString(KEY_PASSWORD, "").orEmpty(),
            prefs.getString(KEY_HOST, TourCredentials.DEFAULT_HOST) ?: TourCredentials.DEFAULT_HOST,
            prefs.getInt(KEY_PORT, TourCredentials.DEFAULT_PORT)
        )
    }

    fun passwordFor(ssid: String): String? {
        val saved = passwords().optString(ssid)
        return saved.ifBlank { null }
    }

    fun audioUrl(): String = last()?.audioUrl ?: TourCredentials(
        "",
        ""
    ).audioUrl

    private fun passwords(): JSONObject {
        val raw = prefs.getString(KEY_PASSWORDS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    companion object {
        private const val PREFS = "tour_join"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PASSWORDS = "passwords"
    }
}
