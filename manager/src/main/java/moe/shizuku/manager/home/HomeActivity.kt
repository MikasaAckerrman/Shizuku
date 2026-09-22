package moe.shizuku.manager.home

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AboutDialogBinding
import moe.shizuku.manager.databinding.HomeActivityBinding
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.utils.AppIconCache
import rikka.core.ktx.unsafeLazy
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku

abstract class HomeActivity : AppBarActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus()
        appsModel.load(onlyCount = true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkServerStatus()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel) }

    private var lastServiceStatus: ServiceStatus? = null
    private var lastGrantedCount: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = HomeActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        homeModel.serviceStatus.observe(this) {
            if (it.status == Status.SUCCESS) {
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                if (statusChanged(status)) {
                    adapter.updateData()
                }
                ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
            }
        }
        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                if (grantedCountChanged(it.data)) {
                    adapter.updateData()
                }
            }
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addItemSpacing(top = 2f, bottom = 2f, unit = TypedValue.COMPLEX_UNIT_DIP)
        recyclerView.addEdgeSpacing(top = 2f, bottom = 2f, left = 12f, right = 12f, unit = TypedValue.COMPLEX_UNIT_DIP)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    private fun statusChanged(status: ServiceStatus?): Boolean {
        if (status == null) return false
        val last = lastServiceStatus
        lastServiceStatus = status
        return last == null ||
                last.isRunning != status.isRunning ||
                last.uid != status.uid ||
                last.permission != status.permission ||
                last.apiVersion != status.apiVersion ||
                last.patchVersion != status.patchVersion ||
                last.seContext != status.seContext
    }

    private fun grantedCountChanged(count: Int?): Boolean {
        if (count == null) return false
        val changed = lastGrantedCount != count
        lastGrantedCount = count
        return changed
    }

    private var lastResume = 0L

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastResume > 500) {
            lastResume = now
            checkServerStatus()
        }
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                val binding = AboutDialogBinding.inflate(LayoutInflater.from(this), null, false)
                binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()
                binding.sourceCode.text = getString(
                    R.string.about_view_source_code,
                    "<b><a href=\"https://github.com/RikkaApps/Shizuku\">GitHub</a></b>"
                ).toHtml()
                binding.icon.setImageBitmap(
                    AppIconCache.getOrLoadBitmap(
                        this,
                        applicationInfo,
                        Process.myUid() / 100000,
                        resources.getDimensionPixelOffset(R.dimen.default_app_icon_size)
                    )
                )
                binding.versionName.text = packageManager.getPackageInfo(packageName, 0).versionName
                MaterialAlertDialogBuilder(this)
                    .setView(binding.root)
                    .show()
                true
            }
            R.id.action_stop -> {
                if (!Shizuku.pingBinder()) {
                    return true
                }
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.dialog_stop_message)
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        try {
                            Shizuku.exit()
                        } catch (e: Throwable) {
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}
