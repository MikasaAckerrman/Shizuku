package moe.shizuku.manager.home

import android.os.Trace
import android.content.pm.PackageManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku

class HomeViewModel : ViewModel() {

    private val _serviceStatus = MutableLiveData<Resource<ServiceStatus>>()
    val serviceStatus = _serviceStatus as LiveData<Resource<ServiceStatus>>

    private suspend fun load(): ServiceStatus = withContext(Dispatchers.IO) {
        Trace.beginSection("HomeViewModel.load")
        try {
            if (!Shizuku.pingBinder()) {
                return@withContext ServiceStatus()
            }

            // Run independent binder calls in parallel to reduce latency.
            val uid = async { runCatching { Shizuku.getUid() }.getOrDefault(-1) }
            val apiVersion = async { runCatching { Shizuku.getVersion() }.getOrDefault(-1) }
            val patchVersion = async {
                runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1).let { if (it < 0) 0 else it }
            }
            val permissionTest = async {
                Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
            }

            val u = uid.await()
            val v = apiVersion.await()
            val p = patchVersion.await()
            val perm = permissionTest.await()

            val seContext = if (v >= 6) {
                try {
                    Shizuku.getSELinuxContext()
                } catch (tr: Throwable) {
                    LOGGER.w(tr, "getSELinuxContext")
                    null
                }
            } else null

            // Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
            // Run a random remote transaction here, report no permission as not running
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, BuildConfig.APPLICATION_ID, 0)

            ServiceStatus(u, v, p, seContext, perm)
        } finally {
            Trace.endSection()
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = load()
                _serviceStatus.postValue(Resource.success(status))
            } catch (e: CancellationException) {

            } catch (e: Throwable) {
                _serviceStatus.postValue(Resource.error(e, ServiceStatus()))
            }
        }
    }
}