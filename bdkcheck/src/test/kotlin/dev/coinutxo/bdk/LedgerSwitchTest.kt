package dev.coinutxo.bdk

import org.bitcoindevkit.Network
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerSwitchTest {
    @Test
    fun sameDescriptorKeepsItsAddressWhenTheLedgerChanges() {
        val words = BdkKit.newMnemonicWords().joinToString(" ")
        val descriptors = BdkKit.descriptorsForMnemonic(words, Network.BITCOIN)
        val db = Files.createTempDirectory("ledger-switch").resolve("w.sqlite").toString()

        val bitcoin = BdkKit.open(descriptors, Network.BITCOIN, db, BdkKit.BITCOIN_GENESIS)
        val onBitcoin = BdkKit.snapshot(bitcoin, 0).receiveAddress
        val bitcoinGenesis = bitcoin.wallet!!.latestCheckpoint().hash.toString()
        bitcoin.close()

        val mobick = BdkKit.open(descriptors, Network.BITCOIN, db, BdkKit.MOBICK_GENESIS)
        val onMobick = BdkKit.snapshot(mobick, 0).receiveAddress
        val mobickGenesis = mobick.wallet!!.latestCheckpoint().hash.toString()
        mobick.close()

        val again = BdkKit.open(descriptors, Network.BITCOIN, db, BdkKit.BITCOIN_GENESIS)
        val back = BdkKit.snapshot(again, 0).receiveAddress
        again.close()

        assertEquals(onBitcoin, onMobick)
        assertEquals(onBitcoin, back)
        assertEquals(BdkKit.BITCOIN_GENESIS, bitcoinGenesis)
        assertEquals(BdkKit.MOBICK_GENESIS, mobickGenesis)
    }

    @Test
    fun bitcoinGenesisMatchesTheWalletCreatedWithoutAnOverride() {
        val words = BdkKit.newMnemonicWords().joinToString(" ")
        val descriptors = BdkKit.descriptorsForMnemonic(words, Network.BITCOIN)
        val db = Files.createTempDirectory("btc-genesis").resolve("w.sqlite").toString()
        val created = BdkKit.open(descriptors, Network.BITCOIN, db, null)
        val defaultGenesis = created.wallet!!.latestCheckpoint().hash.toString()
        created.close()
        val pinned = BdkKit.open(descriptors, Network.BITCOIN, db, BdkKit.BITCOIN_GENESIS)
        val pinnedGenesis = pinned.wallet!!.latestCheckpoint().hash.toString()
        pinned.close()
        assertEquals(defaultGenesis, BdkKit.BITCOIN_GENESIS)
        assertEquals(defaultGenesis, pinnedGenesis)
    }
}
