package dev.coinutxo.logic

object NetworkDefaults {
    const val SIGNET = "SIGNET"
    const val BITCOIN = "BITCOIN"
    const val BTCMOBICK = "BTCMOBICK"
    const val SIGNET_ESPLORA = "https://mempool.space/signet/api"
    const val MAINNET_ESPLORA = "https://blockstream.info/api"
    const val LEGACY_MAINNET_ESPLORA = "https://mempool.space/api"
    const val BTCMOBICK_ESPLORA = "https://blockchain.mobick.info/api"

    fun defaultEsplora(network: String): String = when (network) {
        BITCOIN -> MAINNET_ESPLORA
        BTCMOBICK -> BTCMOBICK_ESPLORA
        else -> SIGNET_ESPLORA
    }

    fun preferredEsplora(network: String, saved: String): String =
        if (network == BITCOIN && saved.trimEnd('/') == LEGACY_MAINNET_ESPLORA) MAINNET_ESPLORA else saved

    fun isKnownNetwork(network: String): Boolean = network == SIGNET || network == BITCOIN || network == BTCMOBICK
}
