package dev.coinutxo.logic

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EsploraProbeTest {
    @Test
    fun silentServerFailsBeforeTheReadTimeoutLapsesLong() {
        ServerSocket(0).use { server ->
            val hold = Thread {
                server.accept().use { socket ->
                    Thread.sleep(5_000)
                    socket.shutdownOutput()
                }
            }
            hold.isDaemon = true
            hold.start()
            val started = System.nanoTime()
            assertFailsWith<EsploraUnreachableException> {
                EsploraProbe.assertReachable(
                    "http://127.0.0.1:${server.localPort}",
                    connectTimeoutMs = 300,
                    readTimeoutMs = 300,
                )
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 2_000, "probe took ${elapsedMs}ms")
        }
    }
}
