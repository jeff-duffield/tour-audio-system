package travel.eftours.touraudio.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Nearby network names that belong to one tour router, such as EF-TOUR-001. */
object TourNetworks {
    data class Lookup(val names: List<String>, val hint: String?)

    fun find(context: Context, onResult: (Lookup) -> Unit) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 33) {
            val locationOn = app.getSystemService(LocationManager::class.java).isLocationEnabled
            if (!locationOn) {
                onResult(Lookup(emptyList(), "Turn on location so this phone can list EF-TOUR networks, or scan the QR code."))
                return
            }
        }
        val wifi = app.getSystemService(WifiManager::class.java) ?: run {
            onResult(Lookup(emptyList(), "This phone cannot scan Wi-Fi."))
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val done = booleanArrayOf(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                finish(app, wifi, this, handler, done, onResult)
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        val started = try {
            wifi.startScan()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            finish(app, wifi, receiver, handler, done, onResult)
            return
        }
        handler.postDelayed({
            finish(app, wifi, receiver, handler, done, onResult)
        }, 4000)
    }

    private fun finish(
        app: Context,
        wifi: WifiManager,
        receiver: BroadcastReceiver,
        handler: Handler,
        done: BooleanArray,
        onResult: (Lookup) -> Unit
    ) {
        if (done[0]) return
        done[0] = true
        handler.removeCallbacksAndMessages(null)
        try {
            app.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        val names = try {
            names(wifi)
        } catch (_: SecurityException) {
            onResult(Lookup(emptyList(), "Allow nearby Wi-Fi access, then try again."))
            return
        }
        val hint = if (names.isEmpty()) {
            "No EF-TOUR networks found. Scan the QR code or type the network name."
        } else {
            null
        }
        onResult(Lookup(names, hint))
    }

    private fun names(wifi: WifiManager): List<String> {
        return wifi.scanResults.mapNotNull { result ->
            val raw = if (Build.VERSION.SDK_INT >= 33) {
                result.wifiSsid?.toString()
            } else {
                @Suppress("DEPRECATION")
                result.SSID
            }
            raw?.trim()?.removeSurrounding("\"")
        }.filter { it.startsWith("EF-TOUR", ignoreCase = true) }
            .distinct()
            .sorted()
    }
}
