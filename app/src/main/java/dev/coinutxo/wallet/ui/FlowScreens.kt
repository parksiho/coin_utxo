package dev.coinutxo.wallet.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.coinutxo.logic.NetworkDefaults
import dev.coinutxo.wallet.CoinUtxoApplication
import dev.coinutxo.wallet.data.WalletMeta
import kotlinx.coroutines.launch

private fun AndroidViewModel.repo() = getApplication<CoinUtxoApplication>().repository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, model: HomeViewModel = viewModel()) {
    val state = model.state
    LaunchedEffect(Unit) { model.refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text("코인 UTXO") }, actions = {
        TextButton(onClick = { nav.navigate("settings") }) { Text("설정") }
    }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("감사를 받지 않은 소프트웨어입니다. 기본 네트워크는 시그넷이며, 메인넷은 설정에서만 켤 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
            Text("현재 네트워크: ${networkLabel(state.network)}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { nav.navigate("create/backup") }, modifier = Modifier.fillMaxWidth()) { Text("새 지갑 만들기") }
            OutlinedButton(onClick = { nav.navigate("import") }, modifier = Modifier.fillMaxWidth()) { Text("개인키 가져오기") }
            OutlinedButton(onClick = { nav.navigate("watch") }, modifier = Modifier.fillMaxWidth()) { Text("주소·xpub 조회") }
            if (state.wallets.isEmpty()) {
                Text("아직 지갑이 없습니다.")
            }
            state.wallets.forEach { wallet ->
                Card(onClick = { nav.navigate("wallet/${wallet.id}") }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(wallet.name, style = MaterialTheme.typography.titleMedium)
                        Text("${networkLabel(wallet.network)} · ${kindLabel(wallet.kind)}")
                        Text(wallet.lastBalanceSats?.let(::formatSats) ?: "아직 동기화하지 않음")
                    }
                }
            }
        }
    }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(HomeState(NetworkDefaults.SIGNET, emptyList()))
        private set

    fun refresh() {
        val repository = repo()
        state = HomeState(repository.currentNetwork(), repository.wallets())
    }
}

data class HomeState(val network: String, val wallets: List<WalletMeta>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, model: SettingsViewModel = viewModel()) {
    val state = model.state
    var url by remember(state.esplora) { mutableStateOf(state.esplora) }
    Scaffold(topBar = { TopAppBar(title = { Text("설정") }, navigationIcon = {
        TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이 앱은 감사를 받지 않았습니다. 복구 문구를 잃어버리면 코인을 되찾을 수 없습니다.")
            Text("네트워크", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.useSignet() }, enabled = state.network != NetworkDefaults.SIGNET) { Text("시그넷") }
                Button(onClick = { model.askMainnet() }, enabled = state.network != NetworkDefaults.BITCOIN) { Text("메인넷") }
            }
            Text("사용 중: ${networkLabel(state.network)}. 이미 만든 지갑의 네트워크는 바뀌지 않습니다.")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Esplora 서버") },
                singleLine = true,
            )
            Button(onClick = { model.saveEsplora(url) }) { Text("서버 저장") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (state.confirmMainnet) {
        AlertDialog(
            onDismissRequest = { model.dismissMainnet() },
            title = { Text("메인넷을 사용할까요?") },
            text = {
                Text("메인넷은 실제 비트코인입니다. 이 소프트웨어는 감사를 받지 않았으며, 잘못된 거래로 코인을 잃을 수 있습니다. 개발자는 그 손실에 책임지지 않습니다. 복구 문구를 오프라인에 적고, 잃어도 되는 금액만 사용하세요. 탐색 서버도 메인넷 기본값으로 바뀝니다.")
            },
            confirmButton = { TextButton(onClick = { model.confirmMainnet() }) { Text("메인넷 사용") } },
            dismissButton = { TextButton(onClick = { model.dismissMainnet() }) { Text("취소") } },
        )
    }
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(read())
        private set

    fun askMainnet() {
        state = state.copy(confirmMainnet = true, error = null, message = null)
    }

    fun dismissMainnet() {
        state = state.copy(confirmMainnet = false)
    }

    fun confirmMainnet() {
        repo().setNetwork(NetworkDefaults.BITCOIN)
        state = read().copy(message = "메인넷으로 바꿨습니다.")
    }

    fun useSignet() {
        repo().setNetwork(NetworkDefaults.SIGNET)
        state = read().copy(message = "시그넷으로 바꿨습니다.")
    }

    fun saveEsplora(url: String) {
        try {
            repo().setEsplora(url)
            state = read().copy(message = "탐색 서버를 저장했습니다.")
        } catch (e: Exception) {
            state = state.copy(error = safeError(e), message = null)
        }
    }

    private fun read(): SettingsState {
        val repository = repo()
        return SettingsState(repository.currentNetwork(), repository.esploraUrl(), false, null, null)
    }
}

data class SettingsState(
    val network: String,
    val esplora: String,
    val confirmMainnet: Boolean,
    val message: String?,
    val error: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBackupScreen(nav: NavHostController, model: CreateWalletViewModel = viewModel()) {
    SecureScreenEffect()
    val state = model.state
    Scaffold(topBar = { TopAppBar(title = { Text("복구 문구") }, navigationIcon = {
        TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
    }) }) { padding ->
        when (state) {
            CreateState.Loading -> Column(Modifier.padding(padding).padding(24.dp)) { CircularProgressIndicator() }
            is CreateState.Failed -> Column(Modifier.padding(padding).padding(16.dp)) { Text(state.message, color = MaterialTheme.colorScheme.error) }
            is CreateState.Ready -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("12개 단어를 종이에 적으세요. 이 화면에는 복사 버튼이 없습니다. 문구를 잃으면 지갑을 복구할 수 없습니다.")
                state.words.forEachIndexed { index, word ->
                    Text("${index + 1}. $word", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { nav.navigate("create/confirm") }, modifier = Modifier.fillMaxWidth()) {
                    Text("단어를 적었습니다")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateConfirmScreen(nav: NavHostController, model: CreateWalletViewModel = viewModel()) {
    SecureScreenEffect()
    val state = model.state
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ready = state as? CreateState.Ready
    Scaffold(topBar = { TopAppBar(title = { Text("단어 확인") }, navigationIcon = {
        TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ready == null) {
                Text("복구 문구를 다시 만들어 주세요.")
                return@Column
            }
            Text("적어 둔 문구에서 두 단어를 입력하세요. 맞아야 이 기기에 저장됩니다.")
            OutlinedTextField(
                value = first,
                onValueChange = { first = it },
                label = { Text("${ready.picks[0] + 1}번째 단어") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = second,
                onValueChange = { second = it },
                label = { Text("${ready.picks[1] + 1}번째 단어") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ready.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    model.confirm(first, second) { id ->
                        nav.navigate("wallet/$id") { popUpTo("home") }
                    }
                },
                enabled = !ready.saving && first.isNotBlank() && second.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ready.saving) "저장 중" else "지갑 저장") }
        }
    }
}

sealed interface CreateState {
    data object Loading : CreateState
    data class Failed(val message: String) : CreateState
    data class Ready(
        val words: List<String>,
        val picks: List<Int>,
        val error: String? = null,
        val saving: Boolean = false,
    ) : CreateState
}

class CreateWalletViewModel(app: Application) : AndroidViewModel(app) {
    private var secret: List<CharArray>? = null
    var state by mutableStateOf<CreateState>(CreateState.Loading)
        private set

    init {
        viewModelScope.launch {
            try {
                val generated = repo().newMnemonic()
                secret = generated
                val picks = generated.indices.shuffled().take(2).sorted()
                state = CreateState.Ready(generated.map { String(it) }, picks)
            } catch (e: Exception) {
                state = CreateState.Failed(safeError(e))
            }
        }
    }

    fun confirm(first: String, second: String, onSaved: (String) -> Unit) {
        val words = secret ?: return
        val ready = state as? CreateState.Ready ?: return
        val ok = matches(words, ready.picks[0], first) && matches(words, ready.picks[1], second)
        if (!ok) {
            state = ready.copy(error = "입력한 단어가 복구 문구와 일치하지 않습니다.")
            return
        }
        state = ready.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val id = repo().saveMnemonic(words)
                onSaved(id)
            } catch (e: Exception) {
                state = ready.copy(saving = false, error = safeError(e))
            }
        }
    }

    private fun matches(words: List<CharArray>, index: Int, entry: String): Boolean =
        words.getOrNull(index)?.concatToString()?.equals(entry.trim(), ignoreCase = true) == true

    override fun onCleared() {
        secret?.forEach { it.fill('\u0000') }
        secret = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavHostController, model: ImportViewModel = viewModel()) {
    SecureScreenEffect()
    var text by remember { mutableStateOf("") }
    val handle = nav.currentBackStackEntry?.savedStateHandle
    val scanned = handle?.get<String>(SCAN_RESULT)
    LaunchedEffect(scanned) {
        if (!scanned.isNullOrEmpty()) {
            text = scanned
            handle.remove<String>(SCAN_RESULT)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("가져오기") }, navigationIcon = {
        TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("WIF 또는 BIP39 니모닉을 붙여 넣거나 QR로 읽습니다. 니모닉은 BIP84 네이티브 세그윗 지갑이 됩니다. 키는 키스토어로 암호화되어 저장됩니다.")
            Text("네트워크: ${networkLabel(model.network)}")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text("니모닉 또는 WIF") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedButton(onClick = { nav.navigate("scan?secure=1") }, modifier = Modifier.fillMaxWidth()) { Text("QR 스캔") }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { model.import(text) { id -> nav.navigate("wallet/$id") { popUpTo("home") } } },
                enabled = !model.busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (model.busy) "가져오는 중" else "지갑 저장") }
        }
    }
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    var network by mutableStateOf(repo().currentNetwork())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun import(text: String, onSaved: (String) -> Unit) {
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val id = repo().importSecret(text)
                onSaved(id)
            } catch (e: Exception) {
                error = safeError(e)
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(nav: NavHostController, model: WatchViewModel = viewModel()) {
    var text by remember { mutableStateOf("") }
    val handle = nav.currentBackStackEntry?.savedStateHandle
    val scanned = handle?.get<String>(SCAN_RESULT)
    LaunchedEffect(scanned) {
        if (!scanned.isNullOrEmpty()) {
            text = scanned
            handle.remove<String>(SCAN_RESULT)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("조회") }, navigationIcon = {
        TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("주소, BIP84 계정 xpub/zpub/vpub, 또는 공개 디스크립터를 읽습니다. 개인키는 저장하지 않습니다.")
            Text("네트워크: ${networkLabel(model.network)}")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                label = { Text("주소, xpub, 디스크립터") },
            )
            OutlinedButton(onClick = { nav.navigate("scan?secure=0") }, modifier = Modifier.fillMaxWidth()) { Text("QR 스캔") }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { model.watch(text) { id -> nav.navigate("wallet/$id") { popUpTo("home") } } },
                enabled = !model.busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (model.busy) "저장 중" else "조회 지갑 저장") }
        }
    }
}

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    var network by mutableStateOf(repo().currentNetwork())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun watch(text: String, onSaved: (String) -> Unit) {
        busy = true
        error = null
        viewModelScope.launch {
            try {
                onSaved(repo().importWatch(text))
            } catch (e: Exception) {
                error = safeError(e)
            } finally {
                busy = false
            }
        }
    }
}
