package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemProperties
import java.io.File
import java.net.NetworkInterface

object EnvironmentUtils {

    @JvmStatic
    fun isWatch(context: Context): Boolean {
        return (context.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_WATCH)
    }

    @JvmStatic
    fun isWifiConnected(context: Context): Boolean {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val networks = cm.allNetworks
                    for (network in networks) {
                        val caps = cm.getNetworkCapabilities(network)
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return true
                        }
                    }
                    val active = cm.activeNetwork
                    val activeCaps = if (active != null) cm.getNetworkCapabilities(active) else null
                    if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // Fallback via network interfaces in case ConnectivityManager is restricted or delayed
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (intf.isUp && !intf.isLoopback) {
                        val name = intf.name.lowercase()
                        if (name.startsWith("wlan")) {
                            val addrs = intf.inetAddresses
                            if (addrs != null && addrs.hasMoreElements()) {
                                return true
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return false
    }

    @JvmStatic
    fun registerWifiListener(
        context: Context,
        onWifiChanged: (Boolean) -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            onWifiChanged(isWifiConnected(context))
            return null
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifiChanged(isWifiConnected(context))
            }

            override fun onLost(network: Network) {
                onWifiChanged(isWifiConnected(context))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onWifiChanged(isWifiConnected(context))
            }
        }

        return try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, callback)
            onWifiChanged(isWifiConnected(context))
            callback
        } catch (_: Throwable) {
            onWifiChanged(isWifiConnected(context))
            null
        }
    }

    @JvmStatic
    fun unregisterWifiListener(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }

    fun isRooted(): Boolean {
        return System.getenv("PATH")?.split(File.pathSeparatorChar)?.find { File("$it/su").exists() } != null
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        return port
    }

    fun getAdbTlsPort(): Int {
        return SystemProperties.getInt("service.adb.tls.port", -1)
    }
}
