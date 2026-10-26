package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeeMathTest {
    @Test
    fun weightAndFeeCeil() {
        assertEquals(562L, FeeMath.weightUnits(1, 2))
        assertEquals(141L, FeeMath.feeFor(1, 2, 1))
        assertEquals(438L, FeeMath.weightUnits(1, 1))
        assertEquals(110L, FeeMath.feeFor(1, 1, 1))
        assertEquals(834L, FeeMath.weightUnits(2, 2))
        assertEquals(209L, FeeMath.feeFor(2, 2, 1))
        assertEquals(417L, FeeMath.feeFor(2, 2, 2))
    }

    @Test
    fun partialSendKeepsChangeAboveDust() {
        val quote = FeeMath.quote(listOf(100_000L), 50_000L, 1)
        assertEquals(141L, quote.feeSats)
        assertEquals(49_859L, quote.changeSats)
        assertEquals(2, quote.outputCount)
        assertEquals(false, quote.sendMax)
    }

    @Test
    fun dustChangeIsFoldedIntoFee() {
        val kept = FeeMath.quote(listOf(100_000L), 99_565L, 1)
        assertEquals(294L, kept.changeSats)
        assertEquals(141L, kept.feeSats)
        assertEquals(2, kept.outputCount)

        val folded = FeeMath.quote(listOf(100_000L), 99_566L, 1)
        assertEquals(0L, folded.changeSats)
        assertEquals(434L, folded.feeSats)
        assertEquals(1, folded.outputCount)
        assertEquals(99_566L, folded.amountSats)
    }

    @Test
    fun sendMaxAndInsufficientFunds() {
        val max = FeeMath.quote(listOf(10_000L), null, 1)
        assertEquals(110L, max.feeSats)
        assertEquals(9_890L, max.amountSats)
        assertEquals(0L, max.changeSats)
        assertEquals(true, max.sendMax)

        val two = FeeMath.quote(listOf(40_000L, 40_000L), null, 1)
        assertEquals(178L, two.feeSats)
        assertEquals(79_822L, two.amountSats)

        assertFailsWith<FeeMathException> { FeeMath.quote(listOf(200L), null, 1) }
        assertFailsWith<FeeMathException> { FeeMath.quote(listOf(1_000L), 900L, 1) }
        assertFailsWith<FeeMathException> { FeeMath.quote(listOf(100_000L), 100L, 1) }
        assertFailsWith<FeeMathException> { FeeMath.quote(listOf(100_000L), 50_000L, 0) }
        assertFailsWith<FeeMathException> { FeeMath.quote(emptyList(), 50_000L, 1) }
    }
}
