package travel.eftours.touraudio.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

/**
 * Holds the tour Wi-Fi for the life of the app process.
 * The network is requested without an internet requirement, because the
 * router often has no uplink. Android drops that request if the callback
 * is released, so audio start/stop must not release it.
 */
class TourWifi(
    context: Context,
    private val log: (String) -> Unit
) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var available = false

    val ssid: String? get() = prefs.getString(KEY_SSID, null)

    fun restore() {
        val name = ssid ?: return
        val password = prefs.getString(KEY_PASSWORD, null) ?: return
        if (callback != null) return
        request(name, password)
    }

    fun join(ssid: String, password: String) {
        val name = ssid.trim()
        if (name.isEmpty()) {
            log("Wi-Fi name is empty")
            return
        }
        if (password.length < 8) {
            log("Wi-Fi password must be at least 8 characters")
            return
        }
        prefs.edit().putString(KEY_SSID, name).putString(KEY_PASSWORD, password).apply()
        if (callback != null && available && this.ssid == name) {
            log("already holding $name")
            return
        }
        request(name, password)
    }

    fun release() {
        prefs.edit().remove(KEY_SSID).remove(KEY_PASSWORD).apply()
        unregister()
        log("released tour Wi-Fi")
    }

    private fun request(ssid: String, password: String) {
        unregister()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available = true
                cm.bindProcessToNetwork(network)
                log("joined $ssid")
            }

            override fun onUnavailable() {
                available = false
                log("Wi-Fi join failed or the dialog was dismissed")
            }

            override fun onLost(network: Network) {
                available = false
                log("tour Wi-Fi lost")
                if (cm.boundNetworkForProcess == network) cm.bindProcessToNetwork(null)
            }
        }
        callback = next
        log("requesting $ssid — accept the system dialog")
        try {
            cm.requestNetwork(request, next)
        } catch (e: SecurityException) {
            callback = null
            available = false
            log("Wi-Fi permission is required before joining")
        }
    }

    private fun unregister() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        available = false
    }

    companion object {
        private const val PREFS = "tour_wifi"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
    }
}
