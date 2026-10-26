package dev.coinutxo.logic

class WalletNameException(message: String) : Exception(message)

object WalletName {
    const val MAX_LENGTH = 40

    fun normalize(raw: String): String {
        if (raw.any { it.isISOControl() }) throw WalletNameException("이름에 쓸 수 없는 문자가 있습니다.")
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) throw WalletNameException("이름을 입력하세요.")
        if (collapsed.length > MAX_LENGTH) throw WalletNameException("이름은 ${MAX_LENGTH}자까지 쓸 수 있습니다.")
        return collapsed
    }
}
