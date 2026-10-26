package dev.coinutxo.logic

import java.math.BigDecimal
import java.util.Locale

object AmountFormat {
    fun btc(sats: Long, ticker: String = "BTC"): String {
        val value = BigDecimal.valueOf(sats).movePointLeft(8).stripTrailingZeros().toPlainString()
        return "$value $ticker"
    }

    fun sats(sats: Long): String {
        val grouped = "%,d".format(Locale.KOREA, sats)
        return "$grouped sats"
    }
}
