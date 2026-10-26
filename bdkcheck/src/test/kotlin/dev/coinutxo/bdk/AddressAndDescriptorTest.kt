package dev.coinutxo.bdk

import dev.coinutxo.logic.Base58
import org.bitcoindevkit.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressAndDescriptorTest {
    @Test
    fun validatesAddressesForSignetAndMainnet() {
        val signet = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        val mainnet = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        assertEquals(signet, BdkKit.validateAddress(signet, Network.SIGNET))
        assertEquals(signet, BdkKit.validateAddress("bitcoin:$signet?amount=0.001", Network.SIGNET))
        assertEquals(mainnet, BdkKit.validateAddress(mainnet, Network.BITCOIN))
        assertFailsWith<BdkUserException> { BdkKit.validateAddress(mainnet, Network.SIGNET) }
        assertFailsWith<BdkUserException> { BdkKit.validateAddress(signet, Network.BITCOIN) }
        assertFailsWith<BdkUserException> { BdkKit.validateAddress("not-an-address", Network.SIGNET) }
        assertFailsWith<BdkUserException> { BdkKit.validateAddress("", Network.SIGNET) }
        assertFailsWith<BdkUserException> {
            BdkKit.validateAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", Network.SIGNET)
        }
        BdkKit.validateAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", Network.BITCOIN)
    }

    @Test
    fun mnemonicBecomesBip84PublicDescriptors() {
        val words = BdkKit.newMnemonicWords()
        assertEquals(12, words.size)
        val first = BdkKit.descriptorsForMnemonic(words.joinToString(" "), Network.SIGNET)
        val second = BdkKit.descriptorsForMnemonic(words.joinToString(" "), Network.SIGNET)
        assertEquals(first, second)
        assertTrue(first.external.startsWith("wpkh("))
        assertTrue(first.external.contains("84'"))
        assertTrue(first.internal!!.contains("84'"))
        assertFalse(first.external.contains("prv", ignoreCase = true))
        words.forEach { word -> assertFalse(first.external.contains(word)) }
        val session = BdkKit.open(first, Network.SIGNET, kotlin.io.path.createTempDirectory("bdk-desc").resolve("w.sqlite").toString())
        try {
            val address = BdkKit.snapshot(session, 0).receiveAddress
            assertTrue(address.startsWith("tb1q"), address)
        } finally {
            session.close()
        }
    }

    @Test
    fun wifImportIsSingleKeyAndPublic() {
        val secret = ByteArray(32) { 0x11 }
        val payload = byteArrayOf(0xef.toByte()) + secret + byteArrayOf(0x01)
        val wif = Base58.encodeChecked(payload)
        val imported = BdkKit.importSecret(wif, Network.SIGNET)
        assertEquals(BdkKit.KIND_WIF, imported.kind)
        assertEquals(null, imported.descriptors.internal)
        assertFalse(imported.descriptors.external.contains(wif))
        assertFalse(imported.descriptors.external.contains("prv", ignoreCase = true))
        val session = BdkKit.open(
            imported.descriptors,
            Network.SIGNET,
            kotlin.io.path.createTempDirectory("bdk-wif").resolve("w.sqlite").toString(),
        )
        try {
            val address = BdkKit.snapshot(session, 0).receiveAddress
            assertTrue(address.startsWith("tb1q"), address)
            assertEquals(false, BdkKit.snapshot(session, 0).canRevealNext)
        } finally {
            session.close()
        }
    }

    @Test
    fun watchRejectsSecretsAndAcceptsPublicDescriptor() {
        val words = BdkKit.newMnemonicWords().joinToString(" ")
        val descriptors = BdkKit.descriptorsForMnemonic(words, Network.SIGNET)
        assertFailsWith<BdkUserException> { BdkKit.importWatch(words, Network.SIGNET) }
        val watched = BdkKit.importWatch(descriptors.external + "\n" , Network.SIGNET)
        assertTrue(watched.external.contains("wpkh("))
        val address = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        val fromAddress = BdkKit.importWatch(address, Network.SIGNET)
        assertTrue(fromAddress.external.contains(address))
        assertEquals(null, fromAddress.internal)
    }
}
