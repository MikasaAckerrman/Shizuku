package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartButtonBinding
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class StartButtonViewHolder(binding: HomeStartButtonBinding, root: View) :
    BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
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
        when (ShizukuSettings.getPreferredStartMethod()) {
            ShizukuSettings.START_METHOD_ROOT -> {
                context.startActivity(Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                })
            }
            ShizukuSettings.START_METHOD_WIRELESS -> {
                val port = EnvironmentUtils.getAdbTcpPort()
                if (port > 0) {
                    context.startActivity(Intent(context, StarterActivity::class.java).apply {
                        putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                        putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                        putExtra(StarterActivity.EXTRA_PORT, port)
                    })
                } else {
                    WadbNotEnabledDialogFragment().show(
                        context.asActivity<FragmentActivity>().supportFragmentManager
                    )
                }
            }
            else -> {
                context.startActivity(Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                    putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                    putExtra(StarterActivity.EXTRA_PORT, 5555)
                })
            }
        }
    }

    override fun onBind() {
        val running = Shizuku.pingBinder()
        val uid = if (running) {
            try {
                Shizuku.getUid()
            } catch (e: Throwable) {
                -1
            }
        } else -1
        val isRoot = running && uid == 0

        binding.button1.isEnabled = true

        if (running) {
            binding.text1.text = context.getString(
                R.string.home_status_service_is_running,
                context.getString(R.string.app_name)
            )
            binding.text2.text = "Running as " + if (isRoot) "root" else "shell"
        } else {
            binding.text1.text = context.getString(
                R.string.home_status_service_not_running,
                context.getString(R.string.app_name)
            )

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
