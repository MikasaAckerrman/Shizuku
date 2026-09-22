package moe.shizuku.manager.management

import android.os.Bundle
import android.util.TypedValue
import android.view.MenuItem
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import java.util.*

class ApplicationManagementActivity : AppBarActivity() {

    private val viewModel by appsViewModel()
    private val adapter = AppsAdapter()

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) {
                runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            }
            if (!running) {
                if (!isFinishing) {
                    finish()
                }
                return@launch
            }
            setupUi()
        }
    }

    private fun setupUi() {
        val binding = AppsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    adapter.updateData(it.data)
                }
                Status.ERROR -> {
                    finish()
                    val tr = it.error
                    tr.printStackTrace()
                    Toast.makeText(this, tr.toString(), Toast.LENGTH_SHORT).show()
                }
                Status.LOADING -> {
                    // No-op
                }
            }
        }
        viewModel.load()

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, left = 24f, right = 24f, unit = TypedValue.COMPLEX_UNIT_DIP)
        recyclerView.setHasFixedSize(true)
        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    recyclerView.scrollToPosition(0)
                }
            }
        })

        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}