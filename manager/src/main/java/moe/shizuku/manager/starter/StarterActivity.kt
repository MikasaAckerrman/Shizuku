package moe.shizuku.manager.starter

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View
import android.widget.Toast
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLProtocolException

private class NotRootedException : Exception()

class StarterActivity : AppBarActivity() {

    private val viewModel by viewModels {
        ViewModel(
            this,
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

    private val silent by lazy { intent.getBooleanExtra(EXTRA_SILENT, false) }
    private var binding: StarterActivityBinding? = null
    private val successHandled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        if (silent) {
            setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        }
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        if (silent) {
            supportActionBar?.hide()
        }

        val providedOutput = intent.getStringExtra(EXTRA_OUTPUT)
        if (providedOutput != null) {
            // Started from StarterService on error: just display the output.
            binding = StarterActivityBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
            binding?.text1?.text = providedOutput
            return
        }

        // Silent mode: empty view until an error forces us to show the terminal.
        if (silent) {
            setContentView(View(this))
        } else {
            binding = StarterActivityBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
        }

        // Fallback watchdog: even if output parsing misses the success marker,
        // finish as soon as the binder responds.
        lifecycleScope.launch(Dispatchers.IO) {
            repeat(150) { // 150 x 100 ms = 15 s max (post-reboot systems can be slow)
                delay(100)
                if (isFinishing) return@launch
                if (viewModel.output.value?.status == Status.ERROR) return@launch
                if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    withContext(Dispatchers.Main) {
                        viewModel.appendOutput("Shizuku successfully started")
                        finishWithSuccess(silent)
                    }
                    return@launch
                }
            }
        }

        viewModel.output.observe(this) {
            val output = it.data!!.trim()
            if (output.contains("info: shizuku_starter exit with 0")) {
                viewModel.appendOutput("Shizuku successfully started")
                finishWithSuccess(silent)
                return@observe
            }

            if (it.status == Status.ERROR) {
                if (silent && binding == null) {
                    binding = StarterActivityBinding.inflate(layoutInflater)
                    setContentView(binding!!.root)
                }

                var message = 0
                when (it.error) {
                    is AdbKeyException -> {
                        message = R.string.adb_error_key_store
                    }
                    is NotRootedException -> {
                        message = R.string.start_with_root_failed
                    }
                    is ConnectException -> {
                        message = R.string.cannot_connect_port
                    }
                    is SSLProtocolException -> {
                        message = R.string.adb_pair_required
                    }
                }

                if (message != 0) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            binding?.text1?.text = output
        }
    }

    private fun finishWithSuccess(showToast: Boolean) {
        if (successHandled.getAndSet(true)) return
        if (showToast) {
            Toast.makeText(this, "Shizuku started", Toast.LENGTH_SHORT).show()
        }
        if (!isFinishing) {
            finish()
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
        const val EXTRA_SILENT = "$EXTRA.SILENT"
        const val EXTRA_OUTPUT = "$EXTRA.OUTPUT"
    }
}

private class ViewModel(context: Context, root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    init {
        try {
            if (root) {
                startRoot()
            } else {
                startAdb(host!!, port)
            }
        } catch (e: Throwable) {
            postResult(e)
        }
    }

    fun appendOutput(line: String) {
        sb.appendLine(line)
        postResult()
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }

    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n')
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            // [fix-12] Same guard as startAdb: never kill a healthy server.
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                sb.append('\n').append("Service is already running, nothing to do.")
                postResult()
                return@launch
            }

            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                sb.append('\n').append("Can't open root shell, try again...").append('\n')

                postResult()
                if (!Shell.getShell().isRoot) {
                    sb.append('\n').append("Still not :(").append('\n')
                    postResult(NotRootedException())
                    return@launch
                }
            }

            Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')
                    postResult()
                }
            }).submit {
                if (it.code != 0) {
                    sb.append('\n').append("Send this to developer may help solve the problem.")
                    postResult()
                }
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        val mode = if (port == 5555) "ADB" else "wireless adb"
        sb.append("Starting with $mode in port $port...").append('\n').append('\n')
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            // [fix-12] Don't kill a healthy server: the starter binary
            // SIGKILLs every shizuku_server process before forking a new one,
            // so pressing "Start" while the service is already up would
            // disconnect all active clients for no reason.
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                sb.append('\n').append("Service is already running, nothing to do.")
                postResult()
                return@launch
            }

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(e))

                postResult(AdbKeyException(e))
                return@launch
            }

            var lastError: Throwable? = null
            var success = false
            for (attempt in 0 until 3) {
                if (attempt > 0) {
                    sb.append("\n[retry ADB connection attempt ${attempt + 1}/3]\n")
                    postResult()
                    Thread.sleep(200)
                }
                try {
                    AdbClient(host, port, key).use { client ->
                        client.connect()
                        client.shellCommand(Starter.internalCommand) {
                            sb.append(String(it))
                            postResult()
                        }
                    }
                    success = true
                    break
                } catch (e: Throwable) {
                    lastError = e
                    e.printStackTrace()
                }
            }

            if (!success) {
                sb.append('\n').append(Log.getStackTraceString(lastError))
                postResult(lastError ?: RuntimeException("ADB connection failed after 3 attempts"))
            }
        }
    }
}
