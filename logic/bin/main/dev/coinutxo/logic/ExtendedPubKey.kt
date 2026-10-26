package dev.coinutxo.logic

data class DecodedExtendedKey(
    val depth: Int,
    val standardForm: String,
    val mainnet: Boolean,
)

object ExtendedPubKey {
    private val versions = mapOf(
        "0488b21e" to Pair(true, "0488b21e"),
        "049d7cb2" to Pair(true, "0488b21e"),
        "04b24746" to Pair(true, "0488b21e"),
        "043587cf" to Pair(false, "043587cf"),
        "044a5262" to Pair(false, "043587cf"),
        "045f1cf6" to Pair(false, "043587cf"),
    )

    fun decodeOrNull(text: String): DecodedExtendedKey? = try {
        decode(text)
    } catch (_: Exception) {
        null
    }

    fun decode(text: String): DecodedExtendedKey {
        val payload = Base58.decodeChecked(text.trim())
        if (payload.size != 78) throw IllegalArgumentException("확장 키 길이가 올바르지 않습니다.")
        val version = payload.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
        val mapped = versions[version] ?: throw IllegalArgumentException("지원하지 않는 확장 공개키입니다.")
        val rewritten = hexToBytes(mapped.second) + payload.copyOfRange(4, payload.size)
        return DecodedExtendedKey(
            depth = payload[4].toInt() and 0xff,
            standardForm = Base58.encodeChecked(rewritten),
            mainnet = mapped.first,
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
