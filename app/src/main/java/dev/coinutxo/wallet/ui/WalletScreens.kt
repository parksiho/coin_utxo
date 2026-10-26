package dev.coinutxo.wallet.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.coinutxo.bdk.BdkKit
import dev.coinutxo.bdk.SendDraft
import dev.coinutxo.bdk.SyncSnapshot
import dev.coinutxo.logic.AmountParser
import dev.coinutxo.logic.AmountUnit
import dev.coinutxo.logic.FeeMath
import dev.coinutxo.logic.FeeQuote
import dev.coinutxo.logic.UtxoRef
import dev.coinutxo.wallet.CoinUtxoApplication
import dev.coinutxo.wallet.data.WalletMeta
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object PendingSend {
    private val chosen = HashMap<String, List<UtxoRef>>()

    @Synchronized
    fun set(walletId: String, utxos: List<UtxoRef>) {
        chosen[walletId] = utxos.toList()
    }

    @Synchronized
    fun get(walletId: String): List<UtxoRef> = chosen[walletId].orEmpty()

    @Synchronized
    fun clear(walletId: String) {
        chosen.remove(walletId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val model: WalletViewModel = viewModel(
        key = "wallet-$id",
        factory = viewModelFactory {
            initializer {
                WalletViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application, id)
            }
        },
    )
    LaunchedEffect(id) { model.open() }
    val meta = model.meta
    val snapshot = model.snapshot
    Scaffold(topBar = {
        TopAppBar(title = { Text(meta?.name ?: "지갑") }, navigationIcon = {
            TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
        })
    }) { padding ->
        if (meta == null) {
            Column(Modifier.padding(padding).padding(16.dp)) { Text("지갑을 찾을 수 없습니다.") }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${networkLabel(meta.network)} · ${kindLabel(meta.kind)}")
            Text(snapshot?.balanceSats?.let(::formatSats) ?: "잔액을 불러오는 중", style = MaterialTheme.typography.titleLarge)
            if (snapshot != null && snapshot.tipHeight > 0) {
                Text("팁 높이 ${snapshot.tipHeight}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.sync() }, enabled = !model.busy) { Text(if (model.busy) "동기화 중" else "동기화") }
                if (snapshot?.canRevealNext == true) {
                    OutlinedButton(onClick = { model.reveal() }, enabled = !model.busy) { Text("다음 주소") }
                }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("받기 주소", style = MaterialTheme.typography.titleMedium)
            val address = snapshot?.receiveAddress
            if (address.isNullOrBlank()) {
                Text("주소를 아직 만들지 못했습니다.")
            } else {
                Text(address, fontFamily = FontFamily.Monospace)
                if (model.copied) Text("주소를 복사했습니다.")
                OutlinedButton(onClick = { copyText(context, address); model.markCopied() }) { Text("주소 복사") }
                Image(bitmap = remember(address) { qrBitmap(address) }, contentDescription = "받기 주소 QR", modifier = Modifier.height(220.dp))
            }
            Text("UTXO", style = MaterialTheme.typography.titleMedium)
            Text("보낼 때는 체크한 UTXO만 입력으로 사용합니다. 선택하지 않은 코인은 거래에 넣지 않습니다.")
            if (snapshot == null) {
                CircularProgressIndicator()
            } else if (snapshot.utxos.isEmpty()) {
                Text("사용 가능한 UTXO가 없습니다. 시그넷 코인을 받거나 동기화하세요.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = model.selected.size == snapshot.utxos.size,
                        onCheckedChange = { model.toggleAll(it) },
                    )
                    Text("모두 선택 (${model.selected.size}/${snapshot.utxos.size})")
                }
                snapshot.utxos.forEach { utxo ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = utxo.id in model.selected,
                            onCheckedChange = { model.toggle(utxo.id) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(utxo.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("${formatSats(utxo.amountSats)} · ${confirmationLabel(utxo.confirmations)}")
                        }
                    }
                }
            }
            if (meta.kind != BdkKit.KIND_WATCH) {
                Button(
                    onClick = {
                        val chosen = snapshot?.utxos?.filter { it.id in model.selected }.orEmpty()
                        PendingSend.set(id, chosen)
                        nav.navigate("send/$id")
                    },
                    enabled = snapshot != null && model.selected.isNotEmpty() && !model.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("선택한 UTXO로 보내기") }
            } else {
                Text("조회 전용이라 이 기기에서 서명하거나 보낼 수 없습니다.")
            }
            OutlinedButton(onClick = { model.askDelete() }, modifier = Modifier.fillMaxWidth()) { Text("이 기기에서 삭제") }
        }
    }
    if (model.confirmDelete) {
        AlertDialog(
            onDismissRequest = { model.dismissDelete() },
            title = { Text("지갑을 삭제할까요?") },
            text = { Text("이 기기의 키와 동기화 데이터를 지웁니다. 복구 문구를 적어 두지 않았다면 코인을 되찾을 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { model.delete { nav.popBackStack() } }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { model.dismissDelete() }) { Text("취소") } },
        )
    }
}

private fun confirmationLabel(confirmations: Int): String =
    if (confirmations <= 0) "미확인" else "확인 $confirmations"

private fun copyText(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", value))
}

private fun qrBitmap(text: String, size: Int = 512): androidx.compose.ui.graphics.ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap.asImageBitmap()
}

class WalletViewModel(app: Application, private val walletId: String) : AndroidViewModel(app) {
    var meta by mutableStateOf<WalletMeta?>(null)
        private set
    var snapshot by mutableStateOf<SyncSnapshot?>(null)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var copied by mutableStateOf(false)
        private set
    var confirmDelete by mutableStateOf(false)
        private set

    private fun repository() = getApplication<CoinUtxoApplication>().repository

    fun open() {
        meta = repository().wallet(walletId)
        if (meta == null || busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val local = repository().localSnapshot(walletId)
                if (local.utxos.isNotEmpty() || local.balanceSats > 0L) {
                    snapshot = local
                }
                val synced = repository().sync(walletId)
                snapshot = synced
                meta = repository().wallet(walletId)
                selected = selected.intersect(synced.utxos.map { it.id }.toSet())
            } catch (e: Exception) {
                error = safeError(e)
            } finally {
                busy = false
            }
        }
    }

    fun sync() = open()

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
        copied = false
    }

    fun toggleAll(on: Boolean) {
        selected = if (on) snapshot?.utxos?.map { it.id }?.toSet().orEmpty() else emptySet()
    }

    fun reveal() {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                repository().revealNext(walletId)
                snapshot = repository().localSnapshot(walletId)
            } catch (e: Exception) {
                error = safeError(e)
            } finally {
                busy = false
            }
        }
    }

    fun markCopied() {
        copied = true
    }

    fun askDelete() {
        confirmDelete = true
    }

    fun dismissDelete() {
        confirmDelete = false
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                repository().delete(walletId)
                PendingSend.clear(walletId)
                onDone()
            } catch (e: Exception) {
                error = safeError(e)
                confirmDelete = false
            } finally {
                busy = false
            }
        }
    }
}

data class SendPreview(
    val destination: String,
    val amountSats: Long,
    val feeSats: Long,
    val changeSats: Long,
    val inputIds: List<String>,
    val sendMax: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val model: SendViewModel = viewModel(
        key = "send-$id",
        factory = viewModelFactory {
            initializer {
                SendViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application, id)
            }
        },
    )
    val handle = nav.currentBackStackEntry?.savedStateHandle
    val scanned = handle?.get<String>(SCAN_RESULT)
    LaunchedEffect(scanned) {
        if (!scanned.isNullOrEmpty()) {
            model.updateDestination(scanned)
            handle.remove<String>(SCAN_RESULT)
        }
    }
    val quote = model.liveQuote()
    Scaffold(topBar = {
        TopAppBar(title = { Text("보내기") }, navigationIcon = {
            TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${networkLabel(model.network)} · 선택한 ${model.utxos.size}개 UTXO만 사용합니다.")
            if (model.utxos.isEmpty()) {
                Text("선택된 UTXO가 없습니다. 지갑 화면에서 코인을 고른 뒤 다시 들어오세요.")
                return@Column
            }
            model.utxos.forEach { utxo ->
                Text("${utxo.id} · ${formatSats(utxo.amountSats)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("합계 ${formatSats(model.utxos.sumOf { it.amountSats })}")
            OutlinedTextField(
                value = model.destination,
                onValueChange = { model.updateDestination(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("받는 주소") },
                minLines = 2,
            )
            OutlinedButton(onClick = { nav.navigate("scan?secure=0") }, modifier = Modifier.fillMaxWidth()) { Text("주소 QR 스캔") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("전액 보내기")
                Switch(checked = model.sendMax, onCheckedChange = { model.updateSendMax(it) })
            }
            if (!model.sendMax) {
                OutlinedTextField(
                    value = model.amountText,
                    onValueChange = { model.updateAmount(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (model.unit == AmountUnit.SAT) "금액 (사토시)" else "금액 (BTC)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.updateUnit(AmountUnit.SAT) }) { Text("사토시") }
                    OutlinedButton(onClick = { model.updateUnit(AmountUnit.BTC) }) { Text("BTC") }
                }
            }
            OutlinedTextField(
                value = model.feeText,
                onValueChange = { model.updateFee(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("수수료율 (sat/vB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            when (quote) {
                null -> Text("금액과 수수료율을 입력하면 예상 수수료와 거스름을 보여 줍니다.")
                is QuoteResult.Ok -> {
                    Text("예상 금액 ${formatSats(quote.quote.amountSats)}")
                    Text("예상 수수료 ${formatSats(quote.quote.feeSats)} · 거스름 ${formatSats(quote.quote.changeSats)}")
                    Text("출력 ${quote.quote.outputCount}개. 거스름이 294 sats보다 작으면 수수료에 포함합니다.")
                }
                is QuoteResult.Bad -> Text(quote.message, color = MaterialTheme.colorScheme.error)
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { model.build() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "계산 중" else "정확한 수수료 계산")
            }
            model.preview?.let { preview ->
                Text("서명 전 확인", style = MaterialTheme.typography.titleMedium)
                Text("받는 주소 ${preview.destination}", fontFamily = FontFamily.Monospace)
                Text("보낼 금액 ${formatSats(preview.amountSats)}")
                Text("수수료 ${formatSats(preview.feeSats)}")
                Text("거스름 ${formatSats(preview.changeSats)}")
                Text(if (preview.sendMax) "전액 보내기" else "지정 금액")
                Text("입력 ${preview.inputIds.size}개 (선택한 코인만)")
                preview.inputIds.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { model.askConfirm() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("서명하고 방송")
                }
            }
            model.txid?.let { txid ->
                Text("방송했습니다", style = MaterialTheme.typography.titleMedium)
                Text(txid, fontFamily = FontFamily.Monospace)
                Text(explorerTxUrl(model.network, txid))
            }
        }
    }
    val preview = model.preview
    if (model.confirm && preview != null) {
        AlertDialog(
            onDismissRequest = { model.dismissConfirm() },
            title = { Text("이 거래를 방송할까요?") },
            text = {
                Text(
                    "금액 ${formatSats(preview.amountSats)}\n수수료 ${formatSats(preview.feeSats)}\n거스름 ${formatSats(preview.changeSats)}\n입력 ${preview.inputIds.joinToString()}\n${preview.destination}",
                )
            },
            confirmButton = { TextButton(onClick = { model.broadcast() }) { Text("방송") } },
            dismissButton = { TextButton(onClick = { model.dismissConfirm() }) { Text("취소") } },
        )
    }
}

sealed interface QuoteResult {
    data class Ok(val quote: FeeQuote) : QuoteResult
    data class Bad(val message: String) : QuoteResult
}

class SendViewModel(app: Application, private val walletId: String) : AndroidViewModel(app) {
    val utxos: List<UtxoRef> = PendingSend.get(walletId)
    val network: String = getApplication<CoinUtxoApplication>().repository.wallet(walletId)?.network ?: "signet"

    var destination by mutableStateOf("")
        private set
    var amountText by mutableStateOf("")
        private set
    var unit by mutableStateOf(AmountUnit.SAT)
        private set
    var sendMax by mutableStateOf(false)
        private set
    var feeText by mutableStateOf("1")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var preview by mutableStateOf<SendPreview?>(null)
        private set
    var confirm by mutableStateOf(false)
        private set
    var txid by mutableStateOf<String?>(null)
        private set

    private var draft: SendDraft? = null

    private fun repository() = getApplication<CoinUtxoApplication>().repository

    fun updateDestination(value: String) {
        destination = value.trim()
        invalidate()
    }

    fun updateAmount(value: String) {
        amountText = value
        invalidate()
    }

    fun updateUnit(value: AmountUnit) {
        unit = value
        invalidate()
    }

    fun updateSendMax(value: Boolean) {
        sendMax = value
        invalidate()
    }

    fun updateFee(value: String) {
        feeText = value.filter { it.isDigit() }.take(5)
        invalidate()
    }

    private fun invalidate() {
        draft = null
        preview = null
        confirm = false
        error = null
    }

    fun liveQuote(): QuoteResult? {
        if (utxos.isEmpty()) return null
        val rate = feeText.toLongOrNull()
        if (rate == null) return QuoteResult.Bad("수수료율은 정수여야 합니다.")
        val amount = if (sendMax) {
            null
        } else {
            if (amountText.isBlank()) return null
            try {
                AmountParser.parse(amountText, unit)
            } catch (e: Exception) {
                return QuoteResult.Bad(safeError(e))
            }
        }
        return try {
            QuoteResult.Ok(FeeMath.quote(utxos.map { it.amountSats }, amount, rate))
        } catch (e: Exception) {
            QuoteResult.Bad(safeError(e))
        }
    }

    fun build() {
        if (destination.isBlank()) {
            error = "받는 주소를 입력하세요."
            return
        }
        val amount: Long? = if (sendMax) {
            null
        } else {
            try {
                AmountParser.parse(amountText, unit)
            } catch (e: Exception) {
                error = safeError(e)
                return
            }
        }
        val rate = feeText.toLongOrNull()
        if (rate == null) {
            error = "수수료율은 정수여야 합니다."
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val built = repository().buildSend(
                    id = walletId,
                    available = utxos,
                    chosenIds = utxos.map { it.id }.toSet(),
                    destination = destination,
                    amountSats = amount,
                    feeRate = rate,
                )
                draft = built
                preview = SendPreview(
                    destination = built.destination,
                    amountSats = built.amountSats,
                    feeSats = built.feeSats,
                    changeSats = built.changeSats,
                    inputIds = built.inputIds,
                    sendMax = built.sendMax,
                )
            } catch (e: Exception) {
                draft = null
                preview = null
                error = safeError(e)
            } finally {
                busy = false
            }
        }
    }

    fun askConfirm() {
        if (draft == null) return
        confirm = true
    }

    fun dismissConfirm() {
        confirm = false
    }

    fun broadcast() {
        val current = draft ?: return
        confirm = false
        viewModelScope.launch {
            busy = true
            error = null
            try {
                txid = repository().signAndBroadcast(walletId, current)
                PendingSend.clear(walletId)
                draft = null
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
fun ScanScreen(nav: NavHostController, secure: Boolean) {
    if (secure) SecureScreenEffect()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    var message by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (secure) "비밀 QR" else "QR 스캔") },
            navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("뒤로") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!granted) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("QR을 읽으려면 카메라 권한이 필요합니다.")
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("권한 요청") }
                }
            } else {
                Text(
                    if (secure) "개인키가 화면에 잠시 보일 수 있습니다. 이 화면은 캡처가 차단됩니다." else "주소 또는 xpub QR을 비추세요.",
                    modifier = Modifier.padding(16.dp),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                CameraQrPreview(
                    modifier = Modifier.fillMaxSize(),
                    onResult = { raw ->
                        nav.previousBackStackEntry?.savedStateHandle?.set(SCAN_RESULT, raw.trim())
                        nav.popBackStack()
                    },
                    onError = { message = it },
                )
            }
        }
    }
}

@Composable
private fun CameraQrPreview(
    modifier: Modifier,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val delivered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        onDispose {
            executor.shutdown()
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = runCatching { future.get() }.getOrElse {
                    onError("카메라를 열지 못했습니다.")
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val scanner = BarcodeScanning.getClient()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val media = imageProxy.image
                    if (media == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener(mainExecutor) { codes ->
                            val raw = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                                ?: codes.firstOrNull()?.rawValue
                            if (!raw.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                                onResult(raw)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { onError("카메라를 열지 못했습니다.") }
            }, mainExecutor)
            previewView
        },
    )
}
