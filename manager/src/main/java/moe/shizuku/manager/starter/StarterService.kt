package moe.shizuku.manager.starter

import android.app.NotificationChannel
import android.app.NotificationManager
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
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)

        // If Shizuku is already running, stop it first so the user can switch
        // between root and ADB without manually restarting.
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            try {
                Shizuku.exit()
                // Give the server a moment to die before starting a new one.
                Thread.sleep(500)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        if (root) {
            startRoot()
        } else {
            startAdb(host!!, port)
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
        return NotifCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("Shizuku")
            .setContentText(text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
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
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                sb.append("Service is already running, nothing to do.\n")
                finishSuccess(false)
                return@launch
            }

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
                if (it.code == 0) {
                    finishSuccess()
                } else {
                    sb.append("Send this to developer may help solve the problem.\n")
                    finishError(RuntimeException("Root start failed"))
                }
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        val mode = if (port == 5555) "ADB" else "wireless adb"
        sb.append("Starting with $mode in port $port...").append('\n').append('\n')

        scope.launch {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                sb.append("Service is already running, nothing to do.\n")
                finishSuccess(false)
                return@launch
            }

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
                    success = true
                    break
                } catch (e: Throwable) {
                    lastError = e
                    e.printStackTrace()
                }
            }

            if (success) {
                finishSuccess()
            } else {
                sb.append('\n').append(Log.getStackTraceString(lastError))
                finishError(lastError ?: RuntimeException("ADB connection failed after 3 attempts"))
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "shizuku_starter"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_IS_ROOT = "moe.shizuku.manager.starter.EXTRA_IS_ROOT"
        const val EXTRA_HOST = "moe.shizuku.manager.starter.EXTRA_HOST"
        const val EXTRA_PORT = "moe.shizuku.manager.starter.EXTRA_PORT"
    }
}
