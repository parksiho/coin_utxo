package dev.coinutxo.logic

import java.security.MessageDigest

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var value = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (char in input) {
            val index = ALPHABET.indexOf(char)
            if (index < 0) throw IllegalArgumentException("Base58 문자가 아닙니다.")
            value = value.multiply(base).add(java.math.BigInteger.valueOf(index.toLong()))
        }
        val decoded = value.toByteArray().let { bytes ->
            if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + decoded
    }

    fun encode(data: ByteArray): String {
        var value = java.math.BigInteger(1, data)
        val base = java.math.BigInteger.valueOf(58)
        val out = StringBuilder()
        while (value > java.math.BigInteger.ZERO) {
            val div = value.divideAndRemainder(base)
            out.append(ALPHABET[div[1].toInt()])
            value = div[0]
        }
        data.takeWhile { it == 0.toByte() }.forEach { _ -> out.append('1') }
        return out.reverse().toString()
    }

    fun decodeChecked(input: String): ByteArray {
        val full = decode(input)
        if (full.size < 5) throw IllegalArgumentException("Base58Check 길이가 너무 짧습니다.")
        val payload = full.copyOfRange(0, full.size - 4)
        val checksum = full.copyOfRange(full.size - 4, full.size)
        val expected = sha256(sha256(payload)).copyOfRange(0, 4)
        if (!checksum.contentEquals(expected)) throw IllegalArgumentException("Base58Check 체크섬이 올바르지 않습니다.")
        return payload
    }

    fun encodeChecked(payload: ByteArray): String {
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return encode(payload + checksum)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
