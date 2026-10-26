package dev.coinutxo.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletNameTest {
    @Test
    fun trimsAndCollapsesSpaces() {
        assertEquals("메인 지갑", WalletName.normalize("  메인   지갑  "))
    }

    @Test
    fun rejectsBlankAndOverlongNames() {
        assertFailsWith<WalletNameException> { WalletName.normalize("   ") }
        assertFailsWith<WalletNameException> { WalletName.normalize("가".repeat(WalletName.MAX_LENGTH + 1)) }
        assertFailsWith<WalletNameException> { WalletName.normalize("첫째\n둘째") }
        assertEquals("가".repeat(WalletName.MAX_LENGTH), WalletName.normalize("가".repeat(WalletName.MAX_LENGTH)))
    }
}
