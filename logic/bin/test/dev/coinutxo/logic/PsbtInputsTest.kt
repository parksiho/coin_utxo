package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class PsbtInputsTest {
    @Test
    fun readsUnsignedTxInputsFromPsbt() {
        val tx = byteArrayOf(
            0x02, 0x00, 0x00, 0x00,
            0x01,
            *ByteArray(32) { 0x11 },
            0x02, 0x00, 0x00, 0x00,
            0x00,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x01,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val psbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()) +
            byteArrayOf(0x01, 0x00) +
            byteArrayOf(tx.size.toByte()) +
            tx +
            byteArrayOf(0x00)
        val encoded = java.util.Base64.getEncoder().encodeToString(psbt)
        val expectedTxid = "11".repeat(32)
        assertEquals(listOf("$expectedTxid:2"), PsbtInputs.outpointIds(encoded))
    }
}
