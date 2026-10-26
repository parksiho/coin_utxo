package dev.coinutxo.wallet

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.coinutxo.wallet.data.WalletRepository
import dev.coinutxo.wallet.ui.AppNav
import dev.coinutxo.wallet.ui.CoinUtxoTheme

class CoinUtxoApplication : Application() {
    lateinit var repository: WalletRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = WalletRepository(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoinUtxoTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppNav(rememberNavController())
                }
            }
        }
    }
}
