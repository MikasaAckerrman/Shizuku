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
        // [fix-6] Accept USER_UNLOCKED too: on devices with a lock screen
        // (PIN/pattern/password) the credential-encrypted storage — where
        // both the launch mode and the ADB key live — is NOT readable at
        // LOCKED_BOOT_COMPLETED / BOOT_COMPLETED while the phone is still
        // locked. The receiver would read mode=UNKNOWN and silently do
        // nothing. USER_UNLOCKED fires right after the user unlocks, when
        // CE storage becomes available — that is the moment the start can
        // actually succeed. pingBinder() guards against redundant work.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action
            && Intent.ACTION_USER_UNLOCKED != intent.action) {
            return
        }

        if (UserHandleCompat.myUserId() > 0 || pingBinderSafe()) return

        // [fix-7] CE storage (where the launch mode lives) is unreadable at
        // LOCKED_BOOT_COMPLETED and at BOOT_COMPLETED while the screen is
        // locked — getLastLaunchMode() returns UNKNOWN and the start never
        // happens. Fall back to a device-encrypted mirror of the mode so
        // the server starts at LOCKED_BOOT_COMPLETED (~15-25 s from power),
        // BEFORE the user even enters their PIN.
        val mode = getLaunchModeWithDeFallback(context)
        if (mode == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // https://r.android.com/2128832
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            && (mode == LaunchMethod.ADB || mode == LaunchMethod.UNKNOWN)) {
            // [fix-7] UNKNOWN here means CE is locked but the mode may still
            // have been configured — and ADB is the only launch method that
            // can work without root surviving reboot. Attempt it; the
            // connectAndStart failure path is harmless (no dialog: our key
            // is pre-authorized in /data/misc/adb/adb_keys).
            adbStart(context)
            // Mirror the mode to DE for the next (even earlier) boot.
            runCatching {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(DE_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_DE_MODE, LaunchMethod.ADB)
                    .putBoolean(KEY_DE_MIRRORED, true)
                    .apply()
            }
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    /**
     * [fix-7] The launch mode as seen at the current boot stage: the CE value
     * when readable, otherwise the DE mirror written by a previous start.
     */
    private fun getLaunchModeWithDeFallback(context: Context): Int {
        val ceMode = ShizukuSettings.getLastLaunchMode()
        if (ceMode != LaunchMethod.UNKNOWN) return ceMode
        return runCatching {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(DE_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DE_MODE, LaunchMethod.UNKNOWN)
        }.getOrDefault(LaunchMethod.UNKNOWN)
    }

    private companion object {
        const val DE_PREFS = "boot_de"
        const val KEY_DE_MODE = "mode"
        const val KEY_DE_MIRRORED = "mirrored"

        /** [fix-8] goAsync() grants ~10 s before ANR; leave a safety margin. */
        const val BROADCAST_DEADLINE_MS = 8_000L

        /** [fix-8] mDNS discovery + connect needs ~3 s; skip if we can't afford it. */
        const val MDNS_BUDGET_MS = 4_000L

        /** [fix-10] Short read budget for scan candidates — non-ADB open ports
         *  (v2ray, local config servers) must fail in ~1.5 s, not 10 s each. */
        const val SCAN_READ_TIMEOUT_MS = 1_500
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
        // [fix-8] Hard deadline: goAsync() only grants ~10 s before the
        // system marks the receiver ANR and shows "Shizuku is not
        // responding". A full 3-attempt retry with per-port timeouts can
        // reach 60+ s. Track a wall-clock deadline and bail out before
        // the ANR window: a late start is better than a crash dialog.
        val deadline = System.currentTimeMillis() + BROADCAST_DEADLINE_MS

        // [fix-3/retry] After dispatching the start command, poll the binder:
        // the server registers asynchronously (1-3 s). Checking immediately
        // (as the previous code did) always saw "not up" and restarted the
        // server three times, killing each working instance.
        //
        // [concurrency] Check the binder BEFORE each attempt too: starter.cpp
        // SIGKILLs every shizuku_server before forking a new one, so a
        // concurrent session (or a second receiver invocation) must not
        // kill a server another session just started. This check makes the
        // ping-pong kill loop practically impossible: whoever sees the binder
        // up first returns and leaves the server alone.
        for (attempt in 1..3) {
            if (pingBinderSafe()) return // a server (any session's) is already up
            try {
                connectAndStart(cr)
            } catch (_: Exception) {
            }
            // Poll for up to 5 s — binder comes up 1-3 s after the command.
            for (i in 1..10) {
                if (pingBinderSafe()) return // server is up — done
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
                if (System.currentTimeMillis() > deadline) return // [fix-8] about to ANR
            }
            // binder still down after 5 s — next attempt (2 s extra backoff)
            if (attempt < 3) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
                if (System.currentTimeMillis() > deadline) return // [fix-8]
            }
        }

        // Last resort: mDNS (needs Wi-Fi). Enable the toggle only here —
        // the controller reset no longer matters because direct connect failed.
        // [fix-8] Skip if we are already close to the ANR deadline.
        if (System.currentTimeMillis() + MDNS_BUDGET_MS > deadline) return
        try { Settings.Global.putInt(cr, "adb_wifi_enabled", 1) } catch (_: Exception) {}
        if (runCatching { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) }.getOrDefault(0) == 1) {
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

    /** [fix-11] pingBinder must never crash the receiver: a bad receiver gets
     *  permanently disabled by the system, killing autostart forever. */
    private fun pingBinderSafe(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * [fix-1] Try EVERY candidate port in order instead of picking one and
     * skipping the rest. Previously the TLS path was dead code because the
     * default 5555 short-circuited the discovery chain.
     *
     * Discovery order (fastest / most reliable first):
     *   1. forced port (mDNS result)
     *   2. service.adb.tcp.port / persist.adb.tcp.port — vivo: this prop is
     *      NOT policed by the wireless-debug controller and survives reboot
     *      in persistent_properties (the no-Wi-Fi path)
     *   3. 5555 (conventional adbd TCP port)
     *   4. service.adb.tls.port (TLS listener)
     *   5. /proc/net/tcp{,6} scan: 5555 first, then all local listeners
     */
    /** [fix-10] Short read budget for scan candidates — non-ADB open ports
     *  (v2ray, local config servers) must fail in ~1.5 s, not 10 s each. */
    private fun connectAndStart(cr: android.content.ContentResolver, forcedPort: Int = -1) {
        val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
        val key = AdbKey(keystore, "shizuku")

        // [fix-1] Try EVERY candidate port in order instead of picking one and
        // skipping the rest. Previously the TLS path was dead code because the
        // default 5555 short-circuited the discovery chain.
        //
        // Discovery order (fastest / most reliable first):
        //   1. forced port (mDNS result)
        //   2. service.adb.tcp.port / persist.adb.tcp.port — vivo: this prop is
        //      NOT policed by the wireless-debug controller and survives reboot
        //      in persistent_properties (the no-Wi-Fi path)
        //   3. 5555 (conventional adbd TCP port)
        //   4. service.adb.tls.port (TLS listener)
        //   5. /proc/net/tcp{,6} scan: 5555 first, then all local listeners
        //
        // [fix-10] Port sources are (almost) certainly adbd → full 10 s read
        // budget. Scan candidates are merely "something is listening" — v2ray,
        // config server, adb client, anything — so give them a short read
        // budget: a wrong-but-open port fails fast instead of stalling the
        // boot receiver for 10 s per port.
        val knownPorts = ArrayList<Int>(4)
        if (forcedPort in 1..65535) knownPorts.add(forcedPort)
        runCatching { EnvironmentUtils.getAdbTcpPort() }.getOrNull()
            ?.takeIf { it in 1..65535 }?.let { knownPorts.add(it) }
        knownPorts.add(5555)
        runCatching { EnvironmentUtils.getAdbTlsPort() }.getOrNull()
            ?.takeIf { it in 1..65535 }?.let { knownPorts.add(it) }
        val scanPorts = scanProcNetTcp()

        var lastError: Exception? = null
        // Known sources first (full budget), then scan (short budget).
        for ((port, readBudgetMs) in knownPorts.map { it to AdbClient.READ_TIMEOUT_MS } +
                scanPorts.map { it to SCAN_READ_TIMEOUT_MS }) {
            if (port !in 1..65535) continue
            var client: AdbClient? = null
            try {
                client = AdbClient("127.0.0.1", port, key, readBudgetMs)
                client.connect()
                client.shellCommand(Starter.internalCommand, null)
                return // command dispatched — caller polls the binder
            } catch (e: Exception) {
                lastError = e
            } finally {
                runCatching { client?.close() }
            }
        }
        throw AdbException("no adbd listener found", lastError)
    }

    /** [fix-1] Full-range scan including 5555 (old range was 30000..60999). */
    private fun scanProcNetTcp(): List<Int> {
        val ports = HashSet<Int>()
        for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                java.io.File(f).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[3] == "0A") { // 0A = LISTEN
                        val hex = parts[1].substringAfterLast(':')
                        val p = hex.toIntOrNull(16)
                        if (p != null && p in 1..65535) ports.add(p)
                    }
                }
            } catch (_: Exception) {}
        }
        // 5555 first (conventional adbd TCP), then everything else
        val result = ArrayList<Int>(ports.size + 1)
        result.add(5555)
        result.addAll(ports.filter { it != 5555 }.sorted())
        return result
    }
}
