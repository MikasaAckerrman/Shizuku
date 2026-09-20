package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // https://r.android.com/2128832
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            && ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            adbStart(context)
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    private fun rootStart(context: Context) {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            return
        }

        Shell.cmd(Starter.internalCommand).exec()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStart(context: Context) {
        val cr = context.contentResolver
        // [fix-3] DO NOT put adb_wifi_enabled=1 here: on vivo OriginOS this wakes
        // the wireless-debugging controller which force-resets persist.adb.tls_server.enable
        // within ~30s. Only enable it later, if (and only if) we fall back to mDNS.
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                adbStartInner(context, cr)
            } finally {
                pending.finish()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStartInner(context: Context, cr: android.content.ContentResolver) {
        // [fix-2] Stop as soon as the binder is actually up — never blindly
        // restart a working server.
        for (attempt in 1..3) {
            try {
                connectAndStart(cr)
            } catch (_: Exception) {
            }
            if (Shizuku.pingBinder()) return // server is up — done
            try { Thread.sleep(7000) } catch (_: InterruptedException) {}
        }

        // Last resort: mDNS (needs Wi-Fi). Enable the toggle only here —
        // the controller reset no longer matters because direct connect failed.
        try { Settings.Global.putInt(cr, "adb_wifi_enabled", 1) } catch (_: Exception) {}
        if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
            val latch = CountDownLatch(1)
            val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port <= 0) return@AdbMdns
                try {
                    connectAndStart(cr, port)
                } catch (_: Exception) {
                }
                latch.countDown()
            }
            adbMdns.start()
            latch.await(3, TimeUnit.SECONDS)
            adbMdns.stop()
        }
    }

    /**
     * Discover a usable local adbd listener and run Starter.internalCommand over it.
     * Discovery order (fastest / most reliable first):
     *   1. service.adb.tcp.port / persist.adb.tcp.port / 5555 — vivo: this prop
     *      is NOT policed by the wireless-debug controller and survives reboot
     *      in persistent_properties (the no-Wi-Fi path)
     *   2. service.adb.tls.port (TLS listener)
     *   3. /proc/net/tcp{,6} scan incl. 5555 (fix-1: range was 30000..60999)
     */
    private fun connectAndStart(cr: android.content.ContentResolver, forcedPort: Int = -1) {
        var port = forcedPort
        if (port !in 1..65535) {
            port = EnvironmentUtils.getAdbTcpPort() // service. or persist.adb.tcp.port
            if (port !in 1..65535) port = 5555       // conventional adbd TCP port
        }
        if (port !in 1..65535) {
            port = EnvironmentUtils.getAdbTlsPort()
        }
        // [fix-1] scan /proc/net/tcp{,6}: include 5555 and full 1..65535 range
        if (port !in 1..65535) {
            val ports = HashSet<Int>()
            for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                try {
                    java.io.File(f).forEachLine { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 4 && parts[3] == "0A") {
                            val hex = parts[1].substringAfterLast(':')
                            val p2 = hex.toIntOrNull(16)
                            if (p2 != null) ports.add(p2)
                        }
                    }
                } catch (_: Exception) {}
            }
            // prefer 5555, then any other local listener that answers
            for (p2 in sequenceOf(5555) + ports.filter { it != 5555 }.sorted()) {
                try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress("127.0.0.1", p2), 200)
                    s.close()
                    port = p2
                    break
                } catch (_: Exception) {}
            }
        }
        if (port in 1..65535) {
            val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
            val key = AdbKey(keystore, "shizuku")
            val client = AdbClient("127.0.0.1", port, key)
            client.connect()
            client.shellCommand(Starter.internalCommand, null)
            client.close()
        } else {
            throw AdbException("no adbd listener found")
        }
    }
}
