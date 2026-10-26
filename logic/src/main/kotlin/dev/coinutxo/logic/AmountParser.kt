package dev.coinutxo.logic

class AmountParseException(message: String) : Exception(message)

enum class AmountUnit {
    SAT,
    BTC,
}

object AmountParser {
    const val MAX_SATS: Long = 2_100_000_000_000_000L
    private const val SATS_PER_BTC: Long = 100_000_000L

    private val satSuffix = Regex("""(?i)\s*(sat|sats|사토시)\s*$""")
    private val btcSuffix = Regex("""(?i)\s*(btc|비트코인)\s*$""")

    fun parse(raw: String, unit: AmountUnit): Long {
        var text = raw.trim().replace(",", "").replace("_", "")
        if (text.isEmpty()) throw AmountParseException("금액을 입력하세요.")
        val forced = when {
            satSuffix.containsMatchIn(text) -> {
                text = text.replace(satSuffix, "").trim()
                AmountUnit.SAT
            }
            btcSuffix.containsMatchIn(text) -> {
                text = text.replace(btcSuffix, "").trim()
                AmountUnit.BTC
            }
            else -> unit
        }
        if (text.isEmpty()) throw AmountParseException("금액을 입력하세요.")
        if (text.startsWith("-") || text.startsWith("+")) {
            throw AmountParseException("음수 금액은 사용할 수 없습니다.")
        }
        return when (forced) {
            AmountUnit.SAT -> parseSats(text)
            AmountUnit.BTC -> parseBtc(text)
        }
    }

    private fun parseSats(text: String): Long {
        if (text.contains('.')) throw AmountParseException("사토시는 정수만 입력할 수 있습니다.")
        if (!text.all { it.isDigit() }) throw AmountParseException("금액을 해석할 수 없습니다.")
        if (text.length > 16) throw AmountParseException("금액이 너무 큽니다.")
        val value = text.toLongOrNull() ?: throw AmountParseException("금액이 너무 큽니다.")
        if (value == 0L) throw AmountParseException("0은 보낼 수 없습니다.")
        if (value > MAX_SATS) throw AmountParseException("금액이 공급량을 초과합니다.")
        return value
    }

    private fun parseBtc(text: String): Long {
        if (!text.matches(Regex("""\d+(\.\d+)?"""))) {
            throw AmountParseException("금액을 해석할 수 없습니다.")
        }
        val parts = text.split('.')
        if (parts[0].length > 16) throw AmountParseException("금액이 너무 큽니다.")
        val whole = parts[0].toLongOrNull() ?: throw AmountParseException("금액이 너무 큽니다.")
        val fraction = if (parts.size == 2) parts[1] else ""
        if (fraction.length > 8) throw AmountParseException("소수점 이하는 8자리까지입니다.")
        val fractionSats = if (fraction.isEmpty()) 0L else fraction.padEnd(8, '0').toLong()
        val sats = try {
            Math.addExact(Math.multiplyExact(whole, SATS_PER_BTC), fractionSats)
        } catch (_: ArithmeticException) {
            throw AmountParseException("금액이 너무 큽니다.")
        }
        if (sats == 0L) throw AmountParseException("0은 보낼 수 없습니다.")
        if (sats > MAX_SATS) throw AmountParseException("금액이 공급량을 초과합니다.")
        return sats
    }
}
