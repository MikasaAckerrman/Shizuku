package moe.shizuku.manager.adb

import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Высокоскоростной нативный скаут портов WADB на loopback (127.0.0.1).
 * Проверяет порты параллельно с ультракоротким таймаутом (15-30мс).
 */
object FastPortScout {

    /**
     * Поиск активного порта WADB.
     * Возвращает первый подтвержденный порт или -1.
     */
    fun findActiveWadbPort(timeoutTotalMs: Long = 600): Int {
        val candidates = ArrayList<Int>(16)

        // 1. Читаем открытые сокеты из /proc/net/tcp и tcp6
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
            } catch (_: Throwable) {}
        }

        if (candidates.isEmpty()) {
            return -1
        }

        // 2. Быстрая параллельная проверка подключения
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(4))
        val activePort = java.util.concurrent.atomic.AtomicInteger(-1)

        try {
            for (p in candidates) {
                pool.execute {
                    if (activePort.get() != -1) return@execute
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress("127.0.0.1", p), 40)
                            activePort.compareAndSet(-1, p)
                        }
                    } catch (_: Throwable) {}
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
}
