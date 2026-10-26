package dev.coinutxo.logic

object NetworkDefaults {
    const val SIGNET = "SIGNET"
    const val BITCOIN = "BITCOIN"
    const val SIGNET_ESPLORA = "https://mempool.space/signet/api"
    const val MAINNET_ESPLORA = "https://mempool.space/api"

    fun defaultEsplora(network: String): String = when (network) {
        BITCOIN -> MAINNET_ESPLORA
        else -> SIGNET_ESPLORA
    }

    fun isKnownNetwork(network: String): Boolean = network == SIGNET || network == BITCOIN
}
