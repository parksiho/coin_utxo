package dev.coinutxo.logic

object PsbtInputs {
    fun outpointIds(serialized: String): List<String> {
        val bytes = decodeSerialized(serialized.trim())
        if (bytes.size < 5 ||
            bytes[0] != 0x70.toByte() ||
            bytes[1] != 0x73.toByte() ||
            bytes[2] != 0x62.toByte() ||
            bytes[3] != 0x74.toByte() ||
            bytes[4] != 0xff.toByte()
        ) {
            throw IllegalArgumentException("PSBT 매직이 올바르지 않습니다.")
        }
        var index = 5
        while (index < bytes.size) {
            val keyLen = readCompact(bytes, index)
            index = keyLen.second
            if (keyLen.first == 0L) break
            if (index + keyLen.first > bytes.size) throw IllegalArgumentException("PSBT 키가 잘렸습니다.")
            val key = bytes.copyOfRange(index, index + keyLen.first.toInt())
            index += keyLen.first.toInt()
            val valueLen = readCompact(bytes, index)
            index = valueLen.second
            if (index + valueLen.first > bytes.size) throw IllegalArgumentException("PSBT 값이 잘렸습니다.")
            val value = bytes.copyOfRange(index, index + valueLen.first.toInt())
            index += valueLen.first.toInt()
            if (key.isNotEmpty() && key[0] == 0.toByte()) {
                return parseTxInputs(value)
            }
        }
        throw IllegalArgumentException("PSBT에 서명 전 거래가 없습니다.")
    }

    private fun decodeSerialized(text: String): ByteArray {
        if (text.startsWith("70736274")) return hexDecode(text)
        return try {
            java.util.Base64.getMimeDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            hexDecode(text)
        }
    }

    private fun parseTxInputs(tx: ByteArray): List<String> {
        if (tx.size < 10) throw IllegalArgumentException("서명 전 거래가 너무 짧습니다.")
        var index = 4
        if (tx.size > 6 && tx[4] == 0.toByte() && tx[5] != 0.toByte()) {
            index = 6
        }
        val count = readCompact(tx, index)
        index = count.second
        val ids = ArrayList<String>(count.first.toInt())
        repeat(count.first.toInt()) {
            if (index + 36 > tx.size) throw IllegalArgumentException("입력이 잘렸습니다.")
            val txid = tx.copyOfRange(index, index + 32).reversedArray().joinToString("") { "%02x".format(it) }
            val vout = (tx[index + 32].toInt() and 0xff) or
                ((tx[index + 33].toInt() and 0xff) shl 8) or
                ((tx[index + 34].toInt() and 0xff) shl 16) or
                ((tx[index + 35].toInt() and 0xff) shl 24)
            index += 36
            val scriptLen = readCompact(tx, index)
            index = scriptLen.second + scriptLen.first.toInt()
            index += 4
            ids += "$txid:$vout"
        }
        return ids
    }

    private fun readCompact(bytes: ByteArray, index: Int): Pair<Long, Int> {
        if (index >= bytes.size) throw IllegalArgumentException("잘린 길이 값입니다.")
        val flag = bytes[index].toInt() and 0xff
        return when {
            flag < 0xfd -> flag.toLong() to index + 1
            flag == 0xfd -> {
                if (index + 3 > bytes.size) throw IllegalArgumentException("잘린 길이 값입니다.")
                val value = (bytes[index + 1].toInt() and 0xff) or ((bytes[index + 2].toInt() and 0xff) shl 8)
                value.toLong() to index + 3
            }
            flag == 0xfe -> {
                if (index + 5 > bytes.size) throw IllegalArgumentException("잘린 길이 값입니다.")
                val value = (bytes[index + 1].toLong() and 0xff) or
                    ((bytes[index + 2].toLong() and 0xff) shl 8) or
                    ((bytes[index + 3].toLong() and 0xff) shl 16) or
                    ((bytes[index + 4].toLong() and 0xff) shl 24)
                value to index + 5
            }
            else -> throw IllegalArgumentException("지원하지 않는 길이 값입니다.")
        }
    }

    private fun hexDecode(text: String): ByteArray {
        val clean = text.trim()
        if (clean.length % 2 != 0) throw IllegalArgumentException("16진수 길이가 홀수입니다.")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
