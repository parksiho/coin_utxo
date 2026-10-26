package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmountParserTest {
    @Test
    fun parsesSatIntegersAndSuffixes() {
        assertEquals(1_000L, AmountParser.parse("1000", AmountUnit.SAT))
        assertEquals(1_000L, AmountParser.parse("1,000", AmountUnit.SAT))
        assertEquals(1_000L, AmountParser.parse("1_000", AmountUnit.SAT))
        assertEquals(1_000L, AmountParser.parse("1000 sat", AmountUnit.BTC))
        assertEquals(2_500L, AmountParser.parse("2500사토시", AmountUnit.BTC))
    }

    @Test
    fun parsesBtcDecimals() {
        assertEquals(1L, AmountParser.parse("0.00000001", AmountUnit.BTC))
        assertEquals(50_000_000L, AmountParser.parse("0.5", AmountUnit.BTC))
        assertEquals(123_000_000L, AmountParser.parse("1.23000000", AmountUnit.BTC))
        assertEquals(120_000_000L, AmountParser.parse("1.2 BTC", AmountUnit.SAT))
        assertEquals(AmountParser.MAX_SATS, AmountParser.parse("21000000", AmountUnit.BTC))
    }

    @Test
    fun rejectsInvalidAmounts() {
        assertFailsWith<AmountParseException> { AmountParser.parse("", AmountUnit.SAT) }
        assertFailsWith<AmountParseException> { AmountParser.parse("0", AmountUnit.SAT) }
        assertFailsWith<AmountParseException> { AmountParser.parse("-1", AmountUnit.SAT) }
        assertFailsWith<AmountParseException> { AmountParser.parse("1.5", AmountUnit.SAT) }
        assertFailsWith<AmountParseException> { AmountParser.parse("1.234567891", AmountUnit.BTC) }
        assertFailsWith<AmountParseException> { AmountParser.parse("21000000.00000001", AmountUnit.BTC) }
        assertFailsWith<AmountParseException> { AmountParser.parse("1e8", AmountUnit.BTC) }
        assertFailsWith<AmountParseException> { AmountParser.parse("1.", AmountUnit.BTC) }
    }
}
