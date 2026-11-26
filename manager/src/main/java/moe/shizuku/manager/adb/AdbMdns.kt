package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Int>
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Throwable) {
                Log.w(TAG, "discoverServices failed", e)
                running = false
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Throwable) {
                Log.w(TAG, "stopServiceDiscovery failed", e)
            }
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        if (!running) return
        try {
            nsdManager.resolveService(info, ResolveListener(this))
        } catch (e: Throwable) {
            Log.w(TAG, "resolveService failed", e)
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val port = resolvedService.port
        if (port !in 1..65535) return

        val resolvedHost = resolvedService.host ?: return
        val isLocalHost = try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { resolvedHost.hostAddress == it.hostAddress }
                } == true
        } catch (_: Throwable) {
            false
        }

        if (running && isLocalHost && verifyPortAccepting(port)) {
            serviceName = resolvedService.serviceName
            observer.onChanged(port)
        }
    }

    private fun verifyPortAccepting(port: Int): Boolean {
        if (serviceType == TLS_CONNECT) {
            return AdbClient.isAdbListener("127.0.0.1", port, 300)
        }
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 250)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryStop()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
