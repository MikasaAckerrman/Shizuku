package moe.shizuku.manager.adb

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AdbPairingTutorialActivityBinding
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.compatibility.DeviceCompatibility

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialActivity : AppBarActivity() {

    private lateinit var binding: AdbPairingTutorialActivityBinding

    private var notificationEnabled: Boolean = false
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this

        binding = AdbPairingTutorialActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        notificationEnabled = isNotificationEnabled()

        if (notificationEnabled) {
            startPairingService()
        }

        binding.apply {
            syncNotificationEnabled()

            if (DeviceCompatibility.isMiui()) {
                miui.isVisible = true
            }

            wifiCallback = EnvironmentUtils.registerWifiListener(context) { wifiOk ->
                runOnUiThread {
                    developerOptions.isEnabled = wifiOk
                    developerOptions.alpha = if (wifiOk) 1.0f else 0.4f
                }
            }

            developerOptions.setOnClickListener {
                if (!EnvironmentUtils.isWifiConnected(context)) {
                    return@setOnClickListener
                }

                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            }

            notificationOptions.setOnClickListener {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EnvironmentUtils.unregisterWifiListener(this, wifiCallback)
        wifiCallback = null
    }

    private fun syncNotificationEnabled() {
        binding.apply {
            step1.isVisible = notificationEnabled
            step2.isVisible = notificationEnabled
            step3.isVisible = notificationEnabled
            network.isVisible = notificationEnabled
            notification.isVisible = notificationEnabled
            notificationDisabled.isGone = notificationEnabled
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val context = this

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel)
        return nm.areNotificationsEnabled() &&
                (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    override fun onResume() {
        super.onResume()

        val wifiOk = EnvironmentUtils.isWifiConnected(this)
        binding.developerOptions.isEnabled = wifiOk
        binding.developerOptions.alpha = if (wifiOk) 1.0f else 0.4f

        val newNotificationEnabled = isNotificationEnabled()
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled
            syncNotificationEnabled()

            if (newNotificationEnabled) {
                startPairingService()
            }
        }
    }

    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(this, "OP_START_FOREGROUND is denied. What are you doing?", Toast.LENGTH_LONG).show()
                }
                startService(intent)
            }
        }
    }
}
