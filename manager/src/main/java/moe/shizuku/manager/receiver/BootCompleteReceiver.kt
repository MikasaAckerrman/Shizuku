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
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
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
            //NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
            Shell.getCachedShell()?.close()
            return
        }

        Shell.cmd(Starter.internalCommand).exec()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStart(context: Context) {
        val cr = context.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val latch = CountDownLatch(1)
            val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port <= 0) return@AdbMdns
                try {
                    val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
                    val key = AdbKey(keystore, "shizuku")
                    val client = AdbClient("127.0.0.1", port, key)
                    client.connect()
                    client.shellCommand(Starter.internalCommand, null)
                    client.close()
                } catch (_: Exception) {
                }
                latch.countDown()
            }
            // [port] 1) TLS port from the system property (survives reboot via
            // persist.adb.tls_server.enable=1) — no Wi-Fi, no mDNS required.
            var port = -1
            try {
                port = moe.shizuku.manager.utils.EnvironmentUtils.getAdbTlsPort()
            } catch (_: Exception) {
            }
            // [port] 2) Fallback: scan /proc/net/tcp{,6} for local listeners.
            if (port !in 1..65535) {
                try {
                    val ports = HashSet<Int>()
                    for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                        java.io.File(f).forEachLine { line ->
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size >= 4 && parts[3] == "0A") {
                                val hex = parts[1].substringAfterLast(':')
                                val p2 = hex.toIntOrNull(16)
                                if (p2 != null && p2 in 30000..60999) ports.add(p2)
                            }
                        }
                    }
                    for (p2 in ports) {
                        try {
                            java.net.InetSocketAddress("127.0.0.1", p2).let {}
                            val s = java.net.Socket()
                            s.connect(java.net.InetSocketAddress("127.0.0.1", p2), 200)
                            s.close()
                            port = p2
                            break
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (port in 1..65535) {
                // [port] Retry x3 with 7s gaps: on early boot the freshly started
                // server's binder registration may hang while system_server initializes.
                // Each attempt kills the old process and starts a fresh one.
                for (attempt in 1..3) {
                    try {
                        val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
                        val key = AdbKey(keystore, "shizuku")
                        val client = AdbClient("127.0.0.1", port, key)
                        client.connect()
                        client.shellCommand(Starter.internalCommand, null)
                        client.close()
                        if (attempt < 3) {
                            Thread.sleep(7000)
                        }
                    } catch (_: Exception) {
                        try { Thread.sleep(7000) } catch (_: InterruptedException) {}
                    }
                }
            }
            // [port] 3) mDNS as the last resort (original logic)
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                adbMdns.start()
                latch.await(3, TimeUnit.SECONDS)
                adbMdns.stop()
            }
            pending.finish()
        }
    }
}
