package dev.coinutxo.logic

class CoinControlException(message: String) : Exception(message)

data class UtxoRef(
    val txid: String,
    val vout: Int,
    val amountSats: Long,
    val confirmations: Int,
) {
    val id: String get() = "$txid:$vout"
}

object CoinControl {
    /**
     * Returns exactly the chosen UTXOs, in wallet order.
     * The result never contains an id outside [chosenIds].
     */
    fun selectedOnly(available: List<UtxoRef>, chosenIds: Set<String>): List<UtxoRef> {
        if (chosenIds.isEmpty()) throw CoinControlException("UTXO를 하나 이상 선택하세요.")
        val wanted = chosenIds.map { it.lowercase() }.toSet()
        val known = available.associateBy { it.id.lowercase() }
        val unknown = wanted.filter { it !in known }
        if (unknown.isNotEmpty()) throw CoinControlException("선택한 UTXO를 찾을 수 없습니다.")
        val selected = available.filter { it.id.lowercase() in wanted }
        val selectedIds = selected.map { it.id.lowercase() }.toSet()
        check(selectedIds == wanted) { "선택 집합이 확장되었습니다." }
        check(selected.size == wanted.size) { "선택 집합이 확장되었습니다." }
        return selected
    }

    fun assertNotExpanded(allowed: Set<String>, used: Collection<String>) {
        val allowedNorm = allowed.map { it.lowercase() }.toSet()
        val extras = used.map { it.lowercase() }.filter { it !in allowedNorm }
        if (extras.isNotEmpty()) {
            throw CoinControlException("선택하지 않은 UTXO가 거래에 포함되었습니다.")
        }
    }
}
