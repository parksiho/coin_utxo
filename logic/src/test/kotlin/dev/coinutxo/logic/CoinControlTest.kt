package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoinControlTest {
    private fun utxo(txid: String, vout: Int, amount: Long) =
        UtxoRef(txid, vout, amount, confirmations = 1)

    @Test
    fun selectionNeverExpandsBeyondChosenSet() {
        val available = listOf(
            utxo("aa", 0, 1_000),
            utxo("bb", 1, 2_000),
            utxo("cc", 0, 3_000),
        )
        val chosen = setOf("aa:0", "cc:0")
        val selected = CoinControl.selectedOnly(available, chosen)
        assertEquals(listOf("aa:0", "cc:0"), selected.map { it.id })
        assertTrue(selected.none { it.txid == "bb" })
        assertEquals(chosen, selected.map { it.id }.toSet())
        CoinControl.assertNotExpanded(chosen, selected.map { it.id })
    }

    @Test
    fun rejectsUnknownOrEmptySelectionAndExtras() {
        val available = listOf(utxo("aa", 0, 1_000))
        assertFailsWith<CoinControlException> { CoinControl.selectedOnly(available, emptySet()) }
        assertFailsWith<CoinControlException> { CoinControl.selectedOnly(available, setOf("zz:0")) }
        assertFailsWith<CoinControlException> {
            CoinControl.assertNotExpanded(setOf("aa:0"), listOf("aa:0", "bb:1"))
        }
        CoinControl.assertNotExpanded(setOf("AA:0"), listOf("aa:0"))
    }
}
