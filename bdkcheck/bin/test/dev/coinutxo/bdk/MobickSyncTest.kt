package dev.coinutxo.bdk

import org.bitcoindevkit.Network
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class MobickSyncTest {
    @Test
    fun emptyMobickWalletScansItsOwnChain() {
        val words = BdkKit.newMnemonicWords().joinToString(" ")
        val descriptors = BdkKit.descriptorsForMnemonic(words, Network.BITCOIN)
        val dir = Files.createTempDirectory("mobick-sync")
        val db = dir.resolve("w.sqlite").toString()
        val session = BdkKit.open(descriptors, Network.BITCOIN, db, BdkKit.MOBICK_GENESIS)
        try {
            val snapshot = BdkKit.sync(session, "https://blockchain.mobick.info/api")
            assertTrue(snapshot.tipHeight > 60_000, "tip ${snapshot.tipHeight}")
            assertTrue(snapshot.receiveAddress.startsWith("bc1"))
        } finally {
            session.close()
        }
    }

    @Test
    fun walletCreatedOnBitcoinGenesisCanBeReboundToMobick() {
        val words = BdkKit.newMnemonicWords().joinToString(" ")
        val descriptors = BdkKit.descriptorsForMnemonic(words, Network.BITCOIN)
        val db = Files.createTempDirectory("mobick-migrate").resolve("w.sqlite").toString()
        BdkKit.open(descriptors, Network.BITCOIN, db, null).close()
        val rebound = BdkKit.open(descriptors, Network.BITCOIN, db, BdkKit.MOBICK_GENESIS)
        try {
            val snapshot = BdkKit.sync(rebound, "https://blockchain.mobick.info/api")
            assertTrue(snapshot.tipHeight > 60_000, "migrated tip ${snapshot.tipHeight}")
        } finally {
            rebound.close()
        }
    }
}
