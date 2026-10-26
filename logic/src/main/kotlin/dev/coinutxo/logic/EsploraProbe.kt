package dev.coinutxo.logic

import java.net.HttpURLConnection
import java.net.URI

class EsploraUnreachableException(message: String, cause: Throwable? = null) : Exception(message, cause)

object EsploraProbe {
    fun assertReachable(baseUrl: String, connectTimeoutMs: Int = 8_000, readTimeoutMs: Int = 8_000) {
        val endpoint = baseUrl.trim().trimEnd('/') + "/blocks/tip/height"
        val connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            this.readTimeout = readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw EsploraUnreachableException("탐색 서버가 응답하지 않습니다. ($code)")
            }
        } catch (e: EsploraUnreachableException) {
            throw e
        } catch (e: Exception) {
            throw EsploraUnreachableException("탐색 서버에 연결하지 못했습니다.", e)
        } finally {
            connection.disconnect()
        }
    }
}
