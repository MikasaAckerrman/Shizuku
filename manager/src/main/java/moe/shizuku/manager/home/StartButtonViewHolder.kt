package moe.shizuku.manager.home

import android.content.Intent
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartButtonBinding
import moe.shizuku.manager.starter.StarterActivity
import androidx.fragment.app.FragmentActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class StartButtonViewHolder(private val binding: HomeStartButtonBinding, root: View) :
    BaseViewHolder<StartButtonViewHolder.Data>(root) {

    data class Data(val isRunning: Boolean, val isRoot: Boolean)

    companion object {
        val CREATOR = Creator<Data> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartButtonBinding.inflate(inflater, outer.root, true)
            StartButtonViewHolder(inner, outer.root)
        }
    }

    init {
        binding.button1.setOnClickListener { v: View -> onStartClicked(v) }
    }

    private fun onStartClicked(v: View) {
        val context = v.context
        val method = ShizukuSettings.getPreferredStartMethod()
        when (method) {
            ShizukuSettings.START_METHOD_ROOT -> {
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                }
                context.startActivity(intent)
            }
            ShizukuSettings.START_METHOD_WIRELESS -> {
                val port = EnvironmentUtils.getAdbTcpPort()
                if (port > 0) {
                    val intent = Intent(context, StarterActivity::class.java).apply {
                        putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                        putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                        putExtra(StarterActivity.EXTRA_PORT, port)
                    }
                    context.startActivity(intent)
                } else {
                    WadbNotEnabledDialogFragment().show(
                        context.asActivity<FragmentActivity>().supportFragmentManager,
                        WadbNotEnabledDialogFragment::class.java.simpleName
                    )
                }
            }
            else -> {
                // Default: ADB on port 5555
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                    putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                    putExtra(StarterActivity.EXTRA_PORT, 5555)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onBind() {
        val running = data?.isRunning ?: false
        val root = data?.isRoot ?: false

        binding.button1.isEnabled = !running
        if (running) {
            binding.text1.text = context.getString(
                R.string.home_status_service_is_running,
                context.getString(R.string.app_name)
            )
            val mode = if (root) "root" else "shell"
            binding.text2.text = "Running as $mode"
        } else {
            binding.text1.text = context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))

            val methodName = when (ShizukuSettings.getPreferredStartMethod()) {
                ShizukuSettings.START_METHOD_ROOT -> context.getString(R.string.start_method_root)
                ShizukuSettings.START_METHOD_WIRELESS -> context.getString(R.string.start_method_wireless)
                else -> context.getString(R.string.start_method_adb_5555)
            }
            binding.text2.text = context.getString(R.string.home_start_button_summary, methodName)
        }

        binding.text2.isVisible = true
    }
}
