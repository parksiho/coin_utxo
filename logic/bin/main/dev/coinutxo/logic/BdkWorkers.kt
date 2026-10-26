package dev.coinutxo.logic

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BdkWorkers(poolSize: Int = 4) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(poolSize) { runnable ->
        Thread(runnable, "bdk").apply { isDaemon = true }
    }
    private val walletLocks = ConcurrentHashMap<String, Any>()

    fun <T> call(block: () -> T): T = unwrap { executor.submit(Callable { block() }).get() }

    fun <T> callWithin(timeoutMs: Long, block: () -> T): T = unwrap {
        executor.submit(Callable { block() }).get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun <T> withLock(id: String, block: () -> T): T {
        val lock = walletLocks.computeIfAbsent(id) { Any() }
        synchronized(lock) { return block() }
    }

    fun <T> callWallet(id: String, block: () -> T): T = call { withLock(id, block) }

    private fun <T> unwrap(block: () -> T): T =
        try {
            block()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    override fun close() {
        executor.shutdownNow()
    }
}
