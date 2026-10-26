package dev.coinutxo.logic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BdkWorkersTest {
    @Test
    fun independentWorkIsNotBlockedByWalletOperation() {
        val workers = BdkWorkers(poolSize = 2)
        val syncStarted = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val syncFinished = CountDownLatch(1)
        Thread {
            workers.callWallet("wallet") {
                syncStarted.countDown()
                assertTrue(releaseSync.await(5, TimeUnit.SECONDS))
            }
            syncFinished.countDown()
        }.start()

        assertTrue(syncStarted.await(5, TimeUnit.SECONDS))
        val mnemonicFinished = AtomicBoolean(false)
        workers.call { mnemonicFinished.set(true) }
        assertTrue(mnemonicFinished.get())
        releaseSync.countDown()
        assertTrue(syncFinished.await(5, TimeUnit.SECONDS))
        workers.close()
    }

    @Test
    fun callRethrowsTheOriginalException() {
        val workers = BdkWorkers(poolSize = 1)
        val error = assertFailsWith<IllegalStateException> {
            workers.call { throw IllegalStateException("원본") }
        }
        assertEquals("원본", error.message)
        workers.close()
    }

    @Test
    fun timedOutWorkDoesNotBlockIndependentWork() {
        val workers = BdkWorkers(poolSize = 2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
        Thread {
            try {
                workers.callWithin(300) {
                    started.countDown()
                    release.await()
                }
            } catch (_: java.util.concurrent.TimeoutException) {
                timedOut.set(true)
            }
        }.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val finished = AtomicBoolean(false)
        val began = System.nanoTime()
        workers.call { finished.set(true) }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        assertTrue(finished.get())
        assertTrue(elapsedMs < 1_000, "independent work waited ${elapsedMs}ms")
        val deadline = System.currentTimeMillis() + 2_000
        while (!timedOut.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(timedOut.get())
        release.countDown()
        workers.close()
    }
}
