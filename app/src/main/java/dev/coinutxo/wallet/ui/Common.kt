package dev.coinutxo.wallet.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import dev.coinutxo.bdk.BdkKit
import dev.coinutxo.logic.NetworkDefaults
import java.math.BigDecimal
import java.util.Locale

private val Orange = Color(0xFFF7931A)
private val Ink = Color(0xFF1C140C)
private val Paper = Color(0xFFFFF8F0)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4B00),
    onPrimary = Color.White,
    secondary = Color(0xFF5C4636),
    background = Paper,
    surface = Color.White,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Ink,
    secondary = Color(0xFFE7C7A1),
    background = Color(0xFF140E09),
    surface = Color(0xFF24180F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun CoinUtxoTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography().run {
            copy(bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif))
        },
        content = content,
    )
}

private object SecureWindow {
    private var depth = 0

    fun push(activity: Activity) {
        depth += 1
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun pop(activity: Activity) {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
fun SecureScreenEffect() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        if (activity != null) SecureWindow.push(activity)
        onDispose {
            if (activity != null) SecureWindow.pop(activity)
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

fun formatSats(sats: Long): String {
    val grouped = "%,d".format(Locale.KOREA, sats)
    val btc = BigDecimal.valueOf(sats).movePointLeft(8).stripTrailingZeros().toPlainString()
    return "$grouped sats · $btc BTC"
}

fun networkLabel(network: String): String = when (network) {
    NetworkDefaults.BITCOIN -> "메인넷"
    else -> "시그넷"
}

fun kindLabel(kind: String): String = when (kind) {
    BdkKit.KIND_MNEMONIC -> "니모닉"
    BdkKit.KIND_WIF -> "WIF"
    else -> "조회 전용"
}

fun safeError(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    val lower = message.lowercase()
    if (message.isEmpty() || message.length > 240) return "요청에 실패했습니다."
    if (listOf("xprv", "tprv", "yprv", "zprv", "mnemonic", "wif").any { lower.contains(it) }) {
        return "요청에 실패했습니다."
    }
    return message
}

fun explorerTxUrl(network: String, txid: String): String =
    if (network == NetworkDefaults.BITCOIN) "https://mempool.space/tx/$txid" else "https://mempool.space/signet/tx/$txid"
