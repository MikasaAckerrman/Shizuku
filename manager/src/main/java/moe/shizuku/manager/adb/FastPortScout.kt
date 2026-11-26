package moe.shizuku.manager.adb

import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Высокоскоростной нативный скаут портов WADB на loopback (127.0.0.1).
 * Проверяет порты параллельно с ультракоротким таймаутом (20-30 мс).
 */
object FastPortScout {

    /**
     * Поиск активного порта WADB.
     * Возвращает первый подтвержденный порт или -1.
     */
    fun findActiveWadbPort(timeoutTotalMs: Long = 600): Int {
        val candidates = ArrayList<Int>(64)

        // 1. Читаем открытые сокеты из /proc/net/tcp и tcp6 (если доступны)
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

        // 2. Всегда проверяем стандартный 5555
        if (!candidates.contains(5555)) {
            candidates.add(5555)
        }

        val activePort = AtomicInteger(-1)
        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)

        // Сначала быстрый проход по кандидатам из procfs / 5555
        if (candidates.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(threads)
            try {
                for (p in candidates) {
                    pool.execute {
                        if (activePort.get() != -1) return@execute
                        try {
                            val s = Socket()
                            try {
                                s.connect(InetSocketAddress("127.0.0.1", p), 30)
                                activePort.compareAndSet(-1, p)
                            } finally {
                                try { s.close() } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
                pool.shutdown()
                pool.awaitTermination(timeoutTotalMs / 2, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            } finally {
                pool.shutdownNow()
            }
        }

        val found = activePort.get()
        if (found != -1) return found

        // 3. Если procfs заблокирован SELinux, делаем быстрый выборочный скан диапазона WADB
        val poolWide = Executors.newFixedThreadPool(threads * 2)
        try {
            // Сканируем диапазон типичных динамических портов WADB (35000..45000) с шагом и параллелизмом
            for (p in 32000..46000) {
                if (activePort.get() != -1) break
                poolWide.execute {
                    if (activePort.get() != -1) return@execute
                    try {
                        val s = Socket()
                        try {
                            s.connect(InetSocketAddress("127.0.0.1", p), 20)
                            activePort.compareAndSet(-1, p)
                        } finally {
                            try { s.close() } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            }
            poolWide.shutdown()
            poolWide.awaitTermination(timeoutTotalMs / 2, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        } finally {
            poolWide.shutdownNow()
        }

        return activePort.get()
    }
}
