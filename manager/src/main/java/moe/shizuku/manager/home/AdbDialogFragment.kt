package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.PortDiscovery
import moe.shizuku.manager.databinding.AdbDialogBinding
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.R)
class AdbDialogFragment : DialogFragment() {

    private lateinit var binding: AdbDialogBinding
    private var discoverySession: PortDiscovery.Session? = null
    private val starting = AtomicBoolean(false)
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbDialogBinding.inflate(layoutInflater)

        val tcpPort = EnvironmentUtils.getAdbTcpPort()

        val builder = MaterialAlertDialogBuilder(context).apply {
            setTitle(R.string.dialog_adb_discovery)
            setView(binding.root)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.development_settings, null)

            if (tcpPort != -1) {
                setNeutralButton("$tcpPort", null)
            }
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        EnvironmentUtils.unregisterWifiListener(requireContext(), wifiCallback)
        wifiCallback = null
        discoverySession?.stop()
        discoverySession = null
    }

    private fun onDialogShow(dialog: AlertDialog) {
        val context = dialog.context
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val cr = context.contentResolver
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            } catch (_: Throwable) {
            }
        }

        val devButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val updateDevButton: (Boolean) -> Unit = { wifiOk ->
            devButton.post {
                devButton.isEnabled = wifiOk
                devButton.alpha = if (wifiOk) 1.0f else 0.4f
            }
        }
        wifiCallback = EnvironmentUtils.registerWifiListener(context, updateDevButton)

        devButton.setOnClickListener {
            if (!EnvironmentUtils.isWifiConnected(it.context)) return@setOnClickListener

            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            try {
                it.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val target = PortDiscovery.findDirectAdbPort()
                if (target in 1..65535) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) startAndDismiss(target)
                    }
                }
            }
        }

        discoverySession = PortDiscovery.Session(context, lifecycleScope) { foundPort ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (isAdded) startAndDismiss(foundPort)
            }
        }.apply { start() }
    }

    private fun startAndDismiss(port: Int) {
        if (!starting.compareAndSet(false, true)) return
        val host = "127.0.0.1"
        val intent = Intent(requireContext(), StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_HOST, host)
            putExtra(StarterActivity.EXTRA_PORT, port)
        }
        requireContext().startActivity(intent)

        dismissAllowingStateLoss()
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }
}
