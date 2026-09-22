package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartButtonBinding
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.starter.StarterService
import moe.shizuku.manager.starter.StarterState
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartButtonViewHolder(private val binding: HomeStartButtonBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartButtonBinding.inflate(inflater, outer.root, true)
            StartButtonViewHolder(inner, outer.root)
        }
    }

    private var stateObserver: Observer<Boolean>? = null

    init {
        binding.button1.setOnClickListener { v: View -> onStartClicked(v) }
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                stateObserver?.let { StarterState.isStarting.removeObserver(it) }
                stateObserver = null
            }
        })
    }

    private fun onStartClicked(v: View) {
        if (StarterState.isStarting.value == true) return
        StarterState.setStarting(true)
        val context = v.context
        when (ShizukuSettings.getPreferredStartMethod()) {
            ShizukuSettings.START_METHOD_ROOT -> {
                context.startService(Intent(context, StarterService::class.java).apply {
                    putExtra(StarterService.EXTRA_IS_ROOT, true)
                })
            }
            ShizukuSettings.START_METHOD_WIRELESS -> {
                val port = EnvironmentUtils.getAdbTcpPort()
                if (port > 0) {
                    context.startService(Intent(context, StarterService::class.java).apply {
                        putExtra(StarterService.EXTRA_IS_ROOT, false)
                        putExtra(StarterService.EXTRA_HOST, "127.0.0.1")
                        putExtra(StarterService.EXTRA_PORT, port)
                    })
                } else {
                    WadbNotEnabledDialogFragment().show(
                        context.asActivity<FragmentActivity>().supportFragmentManager
                    )
                }
            }
            else -> {
                context.startService(Intent(context, StarterService::class.java).apply {
                    putExtra(StarterService.EXTRA_IS_ROOT, false)
                    putExtra(StarterService.EXTRA_HOST, "127.0.0.1")
                    putExtra(StarterService.EXTRA_PORT, 5555)
                })
            }
        }
    }

    override fun onBind() {
        updateButtonState()

        stateObserver?.let { StarterState.isStarting.removeObserver(it) }
        stateObserver = Observer { updateButtonState() }
        StarterState.isStarting.observeForever(stateObserver!!)

        val running = data.isRunning
        if (!running) {
            val methodName = when (ShizukuSettings.getPreferredStartMethod()) {
                ShizukuSettings.START_METHOD_ROOT -> context.getString(R.string.start_method_root)
                ShizukuSettings.START_METHOD_WIRELESS -> context.getString(R.string.start_method_wireless)
                else -> context.getString(R.string.start_method_adb_5555)
            }
            binding.text2.text = context.getString(R.string.home_start_button_summary, methodName)
            binding.text2.isVisible = true
        } else {
            binding.text2.isVisible = false
        }
    }

    private fun updateButtonState() {
        val running = data.isRunning
        val activeIsRoot = running && data.uid == 0
        val starting = StarterState.isStarting.value == true

        val configuredIsRoot = ShizukuSettings.getPreferredStartMethod() == ShizukuSettings.START_METHOD_ROOT
        val configuredIsAdbOrWireless = !configuredIsRoot

        // Enabled if not running, or if the configured method is not the one currently active.
        // This lets the user switch from ADB to root or vice versa without manual restart.
        val configuredMethodActive = (configuredIsRoot && activeIsRoot) ||
                (configuredIsAdbOrWireless && running && !activeIsRoot)
        val enabled = !running || !configuredMethodActive

        binding.button1.isEnabled = enabled && !starting
        binding.button1.alpha = if (binding.button1.isEnabled) 1.0f else 0.5f
    }
}