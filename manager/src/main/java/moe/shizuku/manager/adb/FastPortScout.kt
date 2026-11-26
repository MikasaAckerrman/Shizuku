package moe.shizuku.manager.adb

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Быстрый поиск активного локального порта adbd (5555 или динамический WADB).
 * Не спавнит десятки тысяч сокет-задач на произвольные порты и проверяет,
 * что слушатель отвечает по протоколу ADB (A_STLS/A_AUTH/A_CNXN), а не чужой прокси.
 */
object FastPortScout {

    fun findActiveWadbPort(timeoutTotalMs: Long = 600): Int {
        val candidates = ArrayList<Int>(32)

        for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                File(f).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[3] == "0A") { // LISTEN
                        val hex = parts[1].substringAfterLast(':')
                        val p = hex.toIntOrNull(16)
                        if (p != null && (p == 5555 || p in 30000..65535)) {
                            if (!candidates.contains(p)) candidates.add(p)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (!candidates.contains(5555)) {
            candidates.add(5555)
        }

        val activePort = AtomicInteger(-1)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for (p in candidates) {
                pool.execute {
                    if (activePort.get() != -1) return@execute
                    if (AdbClient.isAdbListener("127.0.0.1", p, 200)) {
                        activePort.compareAndSet(-1, p)
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(timeoutTotalMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        } finally {
            pool.shutdownNow()
        }

        return activePort.get()
    }

    /**
     * Быстрая проверка доступности конкретного порта по TCP (вызывать только в фоновом потоке).
     */
    fun isTcpOpen(host: String, port: Int, timeoutMs: Int = 150): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
