package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkDefaultsTest {
    @Test
    fun mobickUsesItsOwnExplorer() {
        assertEquals("https://blockchain.mobick.info/api", NetworkDefaults.defaultEsplora(NetworkDefaults.BTCMOBICK))
        assertTrue(NetworkDefaults.isKnownNetwork(NetworkDefaults.BTCMOBICK))
    }

    @Test
    fun bitcoinUsesBlockstreamInsteadOfTheSlowMempoolDefault() {
        assertEquals("https://blockstream.info/api", NetworkDefaults.defaultEsplora(NetworkDefaults.BITCOIN))
        assertEquals(
            "https://blockstream.info/api",
            NetworkDefaults.preferredEsplora(NetworkDefaults.BITCOIN, "https://mempool.space/api"),
        )
    }
}
