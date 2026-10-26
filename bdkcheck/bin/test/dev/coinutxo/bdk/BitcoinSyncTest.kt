package dev.coinutxo.bdk

import dev.coinutxo.logic.NetworkDefaults
import org.bitcoindevkit.Network
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class BitcoinSyncTest {
    @Test
    fun emptyBitcoinWalletScansBlockstream() {
        val words = BdkKit.newMnemonicWords().joinToString(" ")
        val descriptors = BdkKit.descriptorsForMnemonic(words, Network.BITCOIN)
        val db = Files.createTempDirectory("btc-sync").resolve("w.sqlite").toString()
        val session = BdkKit.open(descriptors, Network.BITCOIN, db, null)
        try {
            val snapshot = BdkKit.sync(session, NetworkDefaults.MAINNET_ESPLORA)
            assertTrue(snapshot.tipHeight > 800_000, "tip ${snapshot.tipHeight}")
            assertTrue(snapshot.receiveAddress.startsWith("bc1"))
        } finally {
            session.close()
        }
    }
}
