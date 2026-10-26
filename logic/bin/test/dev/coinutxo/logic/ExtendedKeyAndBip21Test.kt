package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtendedKeyAndBip21Test {
    @Test
    fun convertsZpubToXpubAndReadsDepth() {
        val payload = ByteArray(78)
        payload[0] = 0x04
        payload[1] = 0xb2.toByte()
        payload[2] = 0x47
        payload[3] = 0x46
        payload[4] = 3
        payload[45] = 0x02
        val text = Base58.encodeChecked(payload)
        val decoded = ExtendedPubKey.decode(text)
        assertEquals(3, decoded.depth)
        assertEquals(true, decoded.mainnet)
        assertEquals("xpub", decoded.standardForm.take(4))
        val roundTrip = ExtendedPubKey.decode(decoded.standardForm)
        assertEquals(decoded.standardForm, roundTrip.standardForm)
    }

    @Test
    fun convertsVpubToTpub() {
        val payload = ByteArray(78)
        payload[0] = 0x04
        payload[1] = 0x5f
        payload[2] = 0x1c
        payload[3] = 0xf6.toByte()
        payload[4] = 3
        payload[45] = 0x02
        val decoded = ExtendedPubKey.decode(Base58.encodeChecked(payload))
        assertEquals(false, decoded.mainnet)
        assertEquals("tpub", decoded.standardForm.take(4))
        assertNull(ExtendedPubKey.decodeOrNull("not-a-key"))
    }

    @Test
    fun parsesBip21() {
        val parsed = Bip21Parser.parse("bitcoin:tb1qexample?amount=0.5&label=faucet%20return")
        assertEquals("tb1qexample", parsed.address)
        assertEquals(50_000_000L, parsed.amountSats)
        assertEquals("faucet return", parsed.label)
        assertEquals("tb1qexample", Bip21Parser.parse("tb1qexample").address)
    }
}
