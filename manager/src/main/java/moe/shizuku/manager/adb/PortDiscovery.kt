package moe.shizuku.manager.adb

import android.content.Context
import android.os.Build
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import moe.shizuku.manager.utils.EnvironmentUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Единый модуль обнаружения порта для ручного запуска (без автозапуска на boot).
 * Объединяет быстрый локальный зонд (getAdbTcpPort / getAdbTlsPort / FastPortScout)
 * и mDNS-дискавери (_adb-tls-connect._tcp) строго в фоновом потоке.
 */
object PortDiscovery {

    class Session(
        private val context: Context,
        private val scope: CoroutineScope,
        private val onPortFound: (Int) -> Unit
    ) {
        private val delivered = AtomicBoolean(false)
        private var adbMdns: AdbMdns? = null
        private var pollJob: Job? = null

        fun start() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT, Observer { port ->
                    if (port in 1..65535) {
                        scope.launch(Dispatchers.IO) {
                            if (!delivered.get() && AdbClient.isAdbListener("127.0.0.1", port, 300)) {
                                deliver(port)
                            }
                        }
                    }
                }).apply { start() }
            }

            pollJob = scope.launch(Dispatchers.IO) {
                val direct = findDirectAdbPort()
                if (direct in 1..65535) {
                    deliver(direct)
                    return@launch
                }

                while (isActive && !delivered.get()) {
                    delay(700)
                    val tlsPort = EnvironmentUtils.getAdbTlsPort()
                    if (tlsPort in 1..65535 && AdbClient.isAdbListener("127.0.0.1", tlsPort, 250)) {
                        deliver(tlsPort)
                        return@launch
                    }
                }
            }
        }

        fun stop() {
            pollJob?.cancel()
            pollJob = null
            adbMdns?.stop()
            adbMdns = null
        }

        private fun deliver(port: Int) {
            if (delivered.compareAndSet(false, true)) {
                onPortFound(port)
            }
        }
    }

    /**
     * Быстрая проверка готового локального ADB-порта (tcp.port, 5555, tls.port, procfs) в IO-потоке.
     */
    fun findDirectAdbPort(): Int {
        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        if (tcpPort in 1..65535 && AdbClient.isAdbListener("127.0.0.1", tcpPort, 200)) {
            return tcpPort
        }
        if (AdbClient.isAdbListener("127.0.0.1", 5555, 200)) {
            return 5555
        }
        val tlsPort = EnvironmentUtils.getAdbTlsPort()
        if (tlsPort in 1..65535 && AdbClient.isAdbListener("127.0.0.1", tlsPort, 200)) {
            return tlsPort
        }
        return FastPortScout.findActiveWadbPort(350)
    }

    /**
     * Повторное обнаружение живого WADB-порта при retry-попытках в StarterService / StarterActivity.
     * Сначала проверяет системный service.adb.tls.port и /proc/net/tcp, затем при необходимости
     * выполняет короткий live mDNS-поиск (_adb-tls-connect._tcp) с проверкой isAdbListener.
     */
    fun rediscoverWadbPort(
        context: Context,
        excludePort: Int = -1,
        mdnsTimeoutMs: Long = 1500
    ): Int {
        val tlsPort = EnvironmentUtils.getAdbTlsPort()
        if (tlsPort in 1..65535 && tlsPort != excludePort && AdbClient.isAdbListener("127.0.0.1", tlsPort, 250)) {
            return tlsPort
        }

        val scoutPort = FastPortScout.findActiveWadbPort(400)
        if (scoutPort in 1..65535 && scoutPort != excludePort) {
            return scoutPort
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return -1
        }

        val found = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_CONNECT, Observer { port ->
            if (port in 1..65535 && port != excludePort) {
                if (found.compareAndSet(-1, port)) {
                    latch.countDown()
                }
            }
        })
        return try {
            mdns.start()
            latch.await(mdnsTimeoutMs, TimeUnit.MILLISECONDS)
            found.get()
        } catch (_: Throwable) {
            -1
        } finally {
            mdns.stop()
        }
    }
}
