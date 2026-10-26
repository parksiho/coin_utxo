package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class AmountFormatTest {
    @Test
    fun formatsBtcWithoutTrailingZeros() {
        assertEquals("0 BTC", AmountFormat.btc(0))
        assertEquals("0.00000001 BTC", AmountFormat.btc(1))
        assertEquals("0.5 BTC", AmountFormat.btc(50_000_000))
        assertEquals("1.23 BTC", AmountFormat.btc(123_000_000))
    }

    @Test
    fun formatsGroupedSats() {
        assertEquals("0 sats", AmountFormat.sats(0))
        assertEquals("1,000 sats", AmountFormat.sats(1_000))
    }
}
