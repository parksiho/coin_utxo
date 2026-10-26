package dev.coinutxo.logic

data class Bip21(
    val address: String,
    val amountSats: Long?,
    val label: String?,
)

object Bip21Parser {
    fun parse(raw: String): Bip21 {
        val text = raw.trim()
        val body = if (text.startsWith("bitcoin:", ignoreCase = true)) text.substring(8) else text
        if (body.isBlank()) throw IllegalArgumentException("주소가 없습니다.")
        val parts = body.split("?", limit = 2)
        val address = parts[0].trim()
        if (address.isEmpty()) throw IllegalArgumentException("주소가 없습니다.")
        var amount: Long? = null
        var label: String? = null
        if (parts.size == 2) {
            parts[1].split("&").forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val eq = pair.split("=", limit = 2)
                val key = java.net.URLDecoder.decode(eq[0], Charsets.UTF_8)
                val value = if (eq.size == 2) java.net.URLDecoder.decode(eq[1], Charsets.UTF_8) else ""
                when (key) {
                    "amount" -> if (value.isNotEmpty()) amount = AmountParser.parse(value, AmountUnit.BTC)
                    "label" -> label = value
                }
            }
        }
        return Bip21(address, amount, label)
    }
}
