package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartRootButtonBinding
import moe.shizuku.manager.starter.StarterService
import moe.shizuku.manager.starter.StarterState
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class StartRootViewHolder(private val binding: HomeStartRootButtonBinding, root: View) :
    BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartRootButtonBinding.inflate(inflater, outer.root, true)
            StartRootViewHolder(inner, outer.root)
        }
    }

    init {
        binding.button.setOnClickListener { v: View -> onStartClicked(v) }
    }

    private fun onStartClicked(v: View) {
        if (StarterState.isStarting.value == true) return
        StarterState.setStarting(true)
        v.context.startService(Intent(v.context, StarterService::class.java).apply {
            putExtra(StarterService.EXTRA_IS_ROOT, true)
        })
    }

    override fun onBind() {
        val running = Shizuku.pingBinder()
        val starting = StarterState.isStarting.value == true
        binding.button.isEnabled = !running && !starting
        binding.button.alpha = if (binding.button.isEnabled) 1.0f else 0.5f
    }
}
