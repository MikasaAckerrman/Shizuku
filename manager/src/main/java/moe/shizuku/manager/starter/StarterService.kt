package moe.shizuku.manager.starter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat as NotifCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

/**
 * Foreground service that actually performs the Shizuku start. This keeps the
 * start alive even when the user swipes the app away from recents.
 */
class StarterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sb = StringBuilder()
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Shizuku is starting...", true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        StarterState.setStarting(true)

        val root = intent.getBooleanExtra(EXTRA_IS_ROOT, true)
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 0)

        // [fix] Do NOT stop the server here. Shizuku.exit() is a
        // synchronous binder call and Thread.sleep() would block the MAIN
        // thread (jank/ANR risk). Worse, it raced with the start paths:
        // if the server was slow to die, the "already running" fast path
        // in startRoot()/startAdb() could report success WITHOUT starting
        // anything, leaving Shizuku dead. Stopping (for a mode switch) now
        // happens inside the coroutines, off the main thread.
        if (root) {
            startRoot()
        } else {
            startAdb(host, port)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shizuku starter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the status while Shizuku is starting"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
        val builder = NotifCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("Shizuku")
            .setContentText(text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
        if (!ongoing && text.contains("failed")) {
            // [fix] "tap for details" now really opens the details: the
            // error notification carries the same intent the service
            // launches directly. Flag_IMMUTABLE is required on API 31+.
            val intent = Intent(this, StarterActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_SILENT, false)
                putExtra(StarterActivity.EXTRA_OUTPUT, sb.toString())
            }
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pi)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, ongoing: Boolean = false) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text, ongoing))
    }

    private fun finishSuccess(showToast: Boolean = true) {
        StarterState.setStarting(false)
        updateNotification("Shizuku started", false)
        scope.launch(Dispatchers.Main) {
            if (showToast) {
                Toast.makeText(this@StarterService, "Shizuku started", Toast.LENGTH_SHORT).show()
            }
            kotlinx.coroutines.delay(1500)
            stopSelf()
        }
    }

    private fun finishError(throwable: Throwable) {
        StarterState.setStarting(false)
        updateNotification("Shizuku failed - tap for details", false)
        val intent = Intent(this, StarterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_SILENT, false)
            putExtra(StarterActivity.EXTRA_OUTPUT, sb.toString())
        }
        startActivity(intent)
        stopSelf()
    }

    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n')

        scope.launch {
            // [fix] Any unexpected failure must reach finishError():
            // otherwise StarterState stays "starting" forever and both
            // start buttons remain disabled until the app restarts.
            try {
                // [fix] If a REACHABLE server is running, stop it first:
                // the user pressed "Start with root" to switch modes (or
                // to force a restart). An UNREACHABLE server (a zombie
                // that survived a soft reboot) is left alone here — the
                // native starter kills it by name.
                stopExistingServerIfRunning()

                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    sb.append("Can't open root shell, try again...\n")
                    if (!Shell.getShell().isRoot) {
                        sb.append("Still not :(\n")
                        finishError(RuntimeException("Root start failed"))
                        return@launch
                    }
                }

            Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')
                }
            }).submit {
                val shellCode = it.code
                // [fix] submit() callbacks run on the main thread; the
                // verification polls with sleeps, so run it on IO.
                scope.launch(Dispatchers.IO) {
                    if (shellCode == 0 && verifyServerStarted(sb)) {
                        finishSuccess()
                    } else {
                        sb.append("Send this to developer may help solve the problem.\n")
                        finishError(RuntimeException("Root start failed"))
                    }
                }
            }
            } catch (t: Throwable) {
                // [fix] unexpected failure — must release StarterState.
                finishError(t)
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        val mode = if (port == 5555) "ADB" else "wireless adb"
        sb.append("Starting with $mode in port $port...").append('\n').append('\n')

        scope.launch {
            // [fix] Any unexpected failure must reach finishError():
            // otherwise StarterState stays "starting" forever and both
            // start buttons remain disabled until the app restarts.
            try {
                // [fix] If a REACHABLE server is running, stop it first
                // (the user pressed Start to switch modes or to force a
                // restart). An UNREACHABLE server — a zombie that survived
                // a soft reboot — is handled by the native starter, which
                // kills it by name; a ROOT-owned zombie (which the shell
                // starter cannot SIGKILL) gets the root-shell fallback
                // below.
                stopExistingServerIfRunning()

                val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append(Log.getStackTraceString(e))
                finishError(AdbKeyException(e))
                return@launch
            }

            var lastError: Throwable? = null
            var success = false
            for (attempt in 0 until 3) {
                if (attempt > 0) {
                    sb.append("\n[retry ADB connection attempt ${attempt + 1}/3]\n")
                    Thread.sleep(200)
                }
                try {
                    AdbClient(host, port, key).use { client ->
                        client.connect()
                        client.shellCommand(Starter.internalCommand) {
                            sb.append(String(it))
                        }
                    }
                    // [fix] The ADB session completing does NOT guarantee the
                    // server is alive. Verify before reporting success, but
                    // tolerate the case where the binder has not been
                    // delivered to us yet (server may finish publishing right
                    // after the shell command returns, or the delivery retry
                    // may still be pending after a soft reboot).
                    success = verifyServerStarted(sb)
                    if (success) break
                } catch (e: Throwable) {
                    lastError = e
                    e.printStackTrace()
                }

                // [fix] A server started as ROOT cannot be killed by the
                // ADB (shell) starter — SIGKILL fails with EPERM and every
                // retry would hit the same wall. If a root shell is
                // available, stop the zombie ourselves and retry the ADB
                // start; otherwise tell the user to use "Start with root".
                if (!success && sb.indexOf(ROOT_ZOMBIE_MARKER) >= 0) {
                    sb.append('\n')
                        .append("[the running server was started as root, stopping it via root...]\n")
                    if (killServerViaRoot()) {
                        sb.append("[stopped, retrying]\n")
                        continue
                    } else {
                        sb.append("[no root access: use \"Start with root\" to restart the server]\n")
                        break
                    }
                }
            }

            if (success) {
                finishSuccess()
            } else {
                sb.append('\n').append(Log.getStackTraceString(lastError))
                sb.append("\nServer did not confirm it is running.\n")
                finishError(lastError ?: RuntimeException("ADB connection failed after 3 attempts"))
            }
            } catch (t: Throwable) {
                // [fix] unexpected failure — must release StarterState.
                finishError(t)
            }
        }
    }

    /**
     * Verify the server is really running after a start attempt.
     *
     * Success is confirmed by EITHER:
     * - the native starter reporting "exit with 0" (the ready file appeared
     *   and the server published its binder), OR
     * - the binder being reachable from this app (pingBinder).
     *
     * We do NOT fail fast: the shell command may return before the starter
     * finishes waiting for the ready file, and binder delivery to this app
     * may lag (server delivers via content provider, with retries). Poll
     * briefly before concluding failure.
     */
    private fun verifyServerStarted(output: StringBuilder): Boolean {
        // Fast path: binder already alive.
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true

        // Fast path: starter already reported success in its output.
        val marker = "info: shizuku_starter exit with 0"
        if (output.contains(marker)) return true

        // Poll: server may still be publishing / delivering binder.
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(VERIFY_POLL_MS)
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
        }
        // Final verdict from the marker (output may have arrived late).
        return output.contains(marker)
    }

    /**
     * Stop the running server if it is currently REACHABLE (binder alive).
     *
     * This covers the mode-switch case: the user pressed Start while a
     * working server of another mode is running. A server we cannot reach
     * (a zombie after a soft reboot — its binder delivery is dead even
     * though the process survives) is NOT handled here: the native starter
     * kills it by name, or the root-shell fallback does.
     */
    private fun stopExistingServerIfRunning(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        sb.append("Service is already running, stopping it first...\n")
        try {
            Shizuku.exit()
        } catch (e: Throwable) {
            // The server may have died between the ping and the exit.
            e.printStackTrace()
        }
        // Wait (bounded) for the server to actually go down so the new
        // start is clean; the server exits asynchronously.
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
            try {
                Thread.sleep(STOP_POLL_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
        }
        return true
    }

    /**
     * Kill a running shizuku_server through a ROOT shell. Used when the
     * server was started as root and the ADB (shell) starter cannot
     * SIGKILL it (EPERM). Returns false when root is not available.
     */
    private fun killServerViaRoot(): Boolean {
        return try {
            if (!Shell.getShell().isRoot) return false
            val pid = Shell.cmd("pidof -s shizuku_server").exec()
                .out.firstOrNull()?.trim().orEmpty()
            if (pid.isEmpty()) {
                true // already gone
            } else {
                Shell.cmd("kill -9 $pid").exec().isSuccess
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    companion object {
        private const val CHANNEL_ID = "shizuku_starter"
        private const val NOTIFICATION_ID = 1

        // [fix] How long to poll for binder/marker before declaring a
        // failed start. The native starter itself waits up to 15s for the
        // ready file, so the poll window here only needs to cover binder
        // delivery lag (usually well under 5s).
        private const val VERIFY_TIMEOUT_MS = 8000L
        private const val VERIFY_POLL_MS = 250L

        // Marker printed by the native starter when SIGKILL fails with
        // EPERM — the running server was started as a different (root)
        // user, and a shell-uid starter cannot kill it.
        private const val ROOT_ZOMBIE_MARKER = "fatal: can't kill"

        // How long to wait for the server to die after Shizuku.exit().
        private const val STOP_TIMEOUT_MS = 2000L
        private const val STOP_POLL_MS = 100L

        const val EXTRA_IS_ROOT = "moe.shizuku.manager.starter.EXTRA_IS_ROOT"
        const val EXTRA_HOST = "moe.shizuku.manager.starter.EXTRA_HOST"
        const val EXTRA_PORT = "moe.shizuku.manager.starter.EXTRA_PORT"
    }
}
