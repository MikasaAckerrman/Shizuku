package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartRootButtonBinding
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.starter.StarterService
import moe.shizuku.manager.starter.StarterState
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartRootViewHolder(private val binding: HomeStartRootButtonBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartRootButtonBinding.inflate(inflater, outer.root, true)
            StartRootViewHolder(inner, outer.root)
        }
    }

    private var stateObserver: Observer<Boolean>? = null

    init {
        binding.rootStart.setOnClickListener { v: View ->
            if (StarterState.isStarting.value == true) return@setOnClickListener
            StarterState.setStarting(true)
            v.context.startService(Intent(v.context, StarterService::class.java).apply {
                putExtra(StarterService.EXTRA_IS_ROOT, true)
            })
        }
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                stateObserver?.let { StarterState.isStarting.removeObserver(it) }
                stateObserver = null
            }
        })
    }

    override fun onBind() {
        updateButtonState()

        stateObserver?.let { StarterState.isStarting.removeObserver(it) }
        stateObserver = Observer { updateButtonState() }
        StarterState.isStarting.observeForever(stateObserver!!)
    }

    private fun updateButtonState() {
        val running = data.isRunning
        val activeIsRoot = running && data.uid == 0
        val starting = StarterState.isStarting.value == true
        // Root button works if Shizuku is not running or running via a non-root method.
        val enabled = (!running || !activeIsRoot) && !starting
        binding.rootStart.isEnabled = enabled
        binding.rootStart.alpha = if (enabled) 1.0f else 0.5f
    }
}