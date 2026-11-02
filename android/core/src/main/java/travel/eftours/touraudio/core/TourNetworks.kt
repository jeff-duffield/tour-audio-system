package travel.eftours.touraudio.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Nearby network names for one role: travelers use EF-TOUR-001, guides use EF-TOUR-GUIDE. */
object TourNetworks {
    enum class Band { TRAVELER, GUIDE }

    data class Lookup(val names: List<String>, val hint: String?)

    fun matches(ssid: String, band: Band): Boolean {
        val name = ssid.trim()
        return when (band) {
            Band.TRAVELER -> TRAVELER_NAME.matches(name)
            Band.GUIDE -> GUIDE_NAME.matches(name)
        }
    }

    fun find(context: Context, band: Band, onResult: (Lookup) -> Unit) {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java) ?: run {
            onResult(Lookup(emptyList(), "This phone cannot scan Wi-Fi."))
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val done = booleanArrayOf(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                val found = try {
                    matching(wifi, band)
                } catch (_: SecurityException) {
                    finish(app, wifi, this, handler, done, band, onResult)
                    return
                }
                if (found.isNotEmpty() || updated) {
                    finish(app, wifi, this, handler, done, band, onResult)
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        try {
            wifi.startScan()
        } catch (_: SecurityException) {
            finish(app, wifi, receiver, handler, done, band, onResult)
            return
        }
        handler.postDelayed({
            finish(app, wifi, receiver, handler, done, band, onResult)
        }, 5000)
    }

    private fun finish(
        app: Context,
        wifi: WifiManager,
        receiver: BroadcastReceiver,
        handler: Handler,
        done: BooleanArray,
        band: Band,
        onResult: (Lookup) -> Unit
    ) {
        if (done[0]) return
        done[0] = true
        handler.removeCallbacksAndMessages(null)
        try {
            app.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        val seen = try {
            seenNames(wifi)
        } catch (_: SecurityException) {
            onResult(Lookup(emptyList(), "Allow nearby devices and location, then try again."))
            return
        }
        val names = seen.filter { matches(it, band) }.distinct().sorted()
        val hint = when {
            names.isNotEmpty() -> null
            !locationOn(app) -> "Turn location on. Android hides Wi-Fi names until location is on."
            seen.isEmpty() -> "No Wi-Fi names came back. Allow nearby devices and location, then try again."
            else -> {
                val label = if (band == Band.GUIDE) "EF-TOUR-GUIDE" else "EF-TOUR"
                "No $label network in this scan. Nearby: ${seen.take(4).joinToString()}."
            }
        }
        onResult(Lookup(names, hint))
    }

    private fun matching(wifi: WifiManager, band: Band): List<String> {
        return seenNames(wifi).filter { matches(it, band) }
    }

    private fun seenNames(wifi: WifiManager): List<String> {
        return wifi.scanResults.mapNotNull { networkName(it) }.distinct().sorted()
    }

    private fun networkName(result: ScanResult): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val modern = result.wifiSsid
            if (modern != null) {
                val utf8 = readable(modern.utf8Text)
                if (utf8 != null) return utf8
                val printed = readable(modern.toString())
                if (printed != null) return printed
            }
        }
        @Suppress("DEPRECATION")
        return readable(result.SSID)
    }

    private fun readable(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var name = raw.trim().filter { it.code >= 32 }
        if (name.length >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length - 1).trim()
        }
        if (name.isBlank() || name.equals("<unknown ssid>", ignoreCase = true)) return null
        return name
    }

    private fun locationOn(context: Context): Boolean {
        return context.getSystemService(LocationManager::class.java).isLocationEnabled
    }

    private val TRAVELER_NAME = Regex("^EF-TOUR-\\d+$", RegexOption.IGNORE_CASE)
    private val GUIDE_NAME = Regex("^EF-TOUR-GUIDE(-\\d+)?$", RegexOption.IGNORE_CASE)
}
