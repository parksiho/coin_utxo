package dev.coinutxo.logic

class FeeMathException(message: String) : Exception(message)

data class FeeQuote(
    val inputSats: Long,
    val amountSats: Long,
    val feeSats: Long,
    val changeSats: Long,
    val outputCount: Int,
    val sendMax: Boolean,
)

/**
 * Native segwit (P2WPKH) size model in weight units:
 * overhead 10.5 vB, input 68 vB, output 31 vB.
 * Dust threshold matches Bitcoin Core's default relay dust for P2WPKH (294 sats).
 */
object FeeMath {
    const val DUST_SATS: Long = 294L
    const val MIN_FEE_RATE: Long = 1L

    fun weightUnits(inputs: Int, outputs: Int): Long {
        if (inputs <= 0 || outputs <= 0) {
            throw FeeMathException("입력과 출력이 있어야 크기를 계산할 수 있습니다.")
        }
        return 42L + 272L * inputs.toLong() + 124L * outputs.toLong()
    }

    fun feeFor(inputs: Int, outputs: Int, satPerVb: Long): Long {
        if (satPerVb < MIN_FEE_RATE) throw FeeMathException("수수료율은 1 sat/vB 이상이어야 합니다.")
        val weight = weightUnits(inputs, outputs)
        val product = try {
            Math.multiplyExact(weight, satPerVb)
        } catch (_: ArithmeticException) {
            throw FeeMathException("수수료가 너무 큽니다.")
        }
        return (product + 3L) / 4L
    }

    fun quote(inputSats: List<Long>, amountSats: Long?, feeRate: Long): FeeQuote {
        if (inputSats.isEmpty()) throw FeeMathException("선택한 UTXO가 없습니다.")
        if (feeRate < MIN_FEE_RATE) throw FeeMathException("수수료율은 1 sat/vB 이상이어야 합니다.")
        val inputSum = inputSats.fold(0L) { acc, value ->
            try {
                Math.addExact(acc, value)
            } catch (_: ArithmeticException) {
                throw FeeMathException("입력 금액이 너무 큽니다.")
            }
        }
        val inputs = inputSats.size
        if (amountSats == null) {
            val fee = feeFor(inputs, 1, feeRate)
            val amount = inputSum - fee
            if (amount <= 0L) throw FeeMathException("수수료가 선택한 금액 이상입니다.")
            if (amount < DUST_SATS) {
                throw FeeMathException("선택한 금액이 수수료와 더스트(294 sats)를 내기엔 부족합니다.")
            }
            return FeeQuote(inputSum, amount, fee, 0L, 1, true)
        }
        if (amountSats <= 0L) throw FeeMathException("0은 보낼 수 없습니다.")
        if (amountSats < DUST_SATS) throw FeeMathException("받는 금액이 더스트(294 sats)보다 작습니다.")
        val feeWithChange = feeFor(inputs, 2, feeRate)
        val change = inputSum - amountSats - feeWithChange
        if (change >= DUST_SATS) {
            return FeeQuote(inputSum, amountSats, feeWithChange, change, 2, false)
        }
        val feeNoChange = inputSum - amountSats
        val minFee = feeFor(inputs, 1, feeRate)
        if (feeNoChange < minFee) {
            throw FeeMathException("선택한 UTXO로 금액과 수수료를 낼 수 없습니다.")
        }
        return FeeQuote(inputSum, amountSats, feeNoChange, 0L, 1, false)
    }
}
