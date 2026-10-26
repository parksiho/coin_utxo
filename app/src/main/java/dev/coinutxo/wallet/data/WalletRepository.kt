package dev.coinutxo.wallet.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.coinutxo.bdk.BdkKit
import dev.coinutxo.bdk.BdkSession
import dev.coinutxo.bdk.BdkUserException
import dev.coinutxo.bdk.PublicDescriptors
import dev.coinutxo.bdk.SendDraft
import dev.coinutxo.bdk.SyncSnapshot
import dev.coinutxo.logic.BdkWorkers
import dev.coinutxo.logic.NetworkDefaults
import dev.coinutxo.logic.UtxoRef
import dev.coinutxo.logic.WalletName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

data class WalletMeta(
    val id: String,
    val name: String,
    val network: String,
    val kind: String,
    val externalDescriptor: String,
    val internalDescriptor: String?,
    val createdAt: Long,
    val lastBalanceSats: Long?,
    val balanceNetwork: String? = null,
)

data class StoredSecret(val kind: String, val value: String)

class WalletRepository(context: Context) {
    private val appContext = context.applicationContext
    private val metas = MetaStore(appContext)
    private val settings = SettingsStore(appContext)
    private val secrets = SecretStore(appContext)
    private val workers = BdkWorkers()
    private val sessions = ConcurrentHashMap<String, BdkSession>()
    private val scanning = ConcurrentHashMap.newKeySet<String>()
    private val removed = ConcurrentHashMap.newKeySet<String>()

    fun currentNetwork(): String = settings.network()

    fun esploraUrl(): String = settings.esplora()

    fun wallets(): List<WalletMeta> = metas.list()

    fun wallet(id: String): WalletMeta? = metas.list().firstOrNull { it.id == id }

    fun rename(id: String, raw: String) {
        val name = WalletName.normalize(raw)
        if (wallet(id) == null) throw BdkUserException("지갑을 찾을 수 없습니다.")
        metas.rename(id, name)
    }

    fun setNetwork(network: String) {
        settings.setNetwork(network)
    }

    fun setEsplora(url: String) {
        val trimmed = url.trim().trimEnd('/')
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw IllegalArgumentException("탐색 서버는 http 또는 https 주소여야 합니다.")
        }
        settings.setEsplora(trimmed)
    }

    suspend fun newMnemonic(): List<CharArray> = bdk {
        BdkKit.newMnemonicWords().map { it.toCharArray() }
    }

    suspend fun saveMnemonic(words: List<CharArray>): String = bdk {
        val joined = words.joinToString(" ") { String(it) }
        try {
            val canonical = BdkKit.canonicalMnemonic(joined)
            val parts = canonical.split(" ")
            if (parts.size != 12) throw BdkUserException("새 지갑은 12개 단어를 사용합니다.")
            val networkName = settings.network()
            val network = BdkKit.networkOf(networkName)
            val descriptors = BdkKit.descriptorsForMnemonic(canonical, network)
            persistWallet(BdkKit.KIND_MNEMONIC, canonical, descriptors, networkName)
        } finally {
            // The joined string is discarded with this frame. Char arrays are wiped by the caller.
        }
    }

    suspend fun importSecret(text: String): String = bdk {
        val networkName = settings.network()
        val imported = BdkKit.importSecret(text, BdkKit.networkOf(networkName))
        persistWallet(imported.kind, imported.secret, imported.descriptors, networkName)
    }

    suspend fun importWatch(text: String): String = bdk {
        val networkName = settings.network()
        val descriptors = BdkKit.importWatch(text, BdkKit.networkOf(networkName))
        val id = newId(BdkKit.KIND_WATCH, networkName)
        metas.add(
            WalletMeta(
                id = id,
                name = defaultName(BdkKit.KIND_WATCH, networkName),
                network = networkName,
                kind = BdkKit.KIND_WATCH,
                externalDescriptor = descriptors.external,
                internalDescriptor = descriptors.internal,
                createdAt = System.currentTimeMillis(),
                lastBalanceSats = null,
            ),
        )
        id
    }

    suspend fun sync(id: String): SyncSnapshot {
        val session = bdkWallet(id) {
            if (id in removed) throw CancellationException()
            if (!scanning.add(id)) throw BdkUserException(ALREADY_SYNCING)
            try {
                session(requireMeta(id))
            } catch (e: Exception) {
                scanning.remove(id)
                throw e
            }
        }
        val url = esploraFor(requireMeta(id))
        return try {
            bdkWithin(SYNC_TIMEOUT_MS) {
                val snapshot = try {
                    BdkKit.sync(session, url)
                } catch (e: Exception) {
                    finishScan(id)
                    throw e
                }
                workers.withLock(id) {
                    if (id !in removed) metas.updateBalance(id, snapshot.balanceSats, operatingNetwork(requireMeta(id).network))
                    scanning.remove(id)
                    if (id in removed) cleanupLocked(id)
                }
                snapshot
            }
        } catch (e: TimeoutException) {
            throw BdkUserException("탐색 서버 응답이 너무 오래 걸립니다.", e)
        }
    }

    suspend fun localSnapshot(id: String): SyncSnapshot = bdkWallet(id) {
        val meta = requireMeta(id)
        BdkKit.snapshot(session(meta), 0)
    }

    suspend fun revealNext(id: String): String = bdkWallet(id) {
        BdkKit.revealNext(session(requireMeta(id)))
    }

    suspend fun buildSend(
        id: String,
        available: List<UtxoRef>,
        chosenIds: Set<String>,
        destination: String,
        amountSats: Long?,
        feeRate: Long,
    ): SendDraft = bdkWallet(id) {
        val meta = requireMeta(id)
        if (meta.kind == BdkKit.KIND_WATCH) throw BdkUserException("조회 전용 지갑은 보낼 수 없습니다.")
        BdkKit.buildSend(
            session = session(meta),
            available = available,
            chosenIds = chosenIds,
            destination = destination,
            amountSats = amountSats,
            feeRateSatPerVb = feeRate,
        )
    }

    suspend fun signAndBroadcast(id: String, draft: SendDraft): String = bdkWallet(id) {
        val meta = requireMeta(id)
        val secret = secrets.read(id) ?: throw BdkUserException("이 기기에 서명 키가 없습니다.")
        if (secret.kind != meta.kind) throw BdkUserException("저장된 키 종류가 지갑과 맞지 않습니다.")
        BdkKit.sign(draft, secret.kind, secret.value, BdkKit.networkOf(operatingNetwork(meta.network)))
        BdkKit.broadcast(esploraFor(meta), draft.psbt)
    }

    suspend fun delete(id: String) = bdkWallet(id) {
        removed.add(id)
        secrets.delete(id)
        metas.delete(id)
        if (id !in scanning) cleanupLocked(id)
    }

    fun hasSecret(id: String): Boolean = secrets.read(id) != null

    private fun persistWallet(
        kind: String,
        secret: String,
        descriptors: PublicDescriptors,
        networkName: String,
    ): String {
        val id = newId(kind, networkName)
        if (kind == BdkKit.KIND_MNEMONIC) secrets.putMnemonic(id, secret) else secrets.putWif(id, secret)
        metas.add(
            WalletMeta(
                id = id,
                name = defaultName(kind, networkName),
                network = networkName,
                kind = kind,
                externalDescriptor = descriptors.external,
                internalDescriptor = descriptors.internal,
                createdAt = System.currentTimeMillis(),
                lastBalanceSats = null,
            ),
        )
        return id
    }

    fun operatingNetwork(stored: String): String {
        val active = settings.network()
        val mainStyle = stored == NetworkDefaults.BITCOIN || stored == NetworkDefaults.BTCMOBICK
        val activeMainStyle = active == NetworkDefaults.BITCOIN || active == NetworkDefaults.BTCMOBICK
        return if (mainStyle && activeMainStyle) active else stored
    }

    fun balanceOnActiveChain(meta: WalletMeta): Long? {
        val recorded = meta.balanceNetwork ?: meta.network
        return if (recorded == operatingNetwork(meta.network)) meta.lastBalanceSats else null
    }

    private fun session(meta: WalletMeta): BdkSession {
        val chain = operatingNetwork(meta.network)
        val cached = sessions[meta.id]
        if (cached != null) {
            if (cached.chain == chain || meta.id in scanning) return cached
            cached.close()
            sessions.remove(meta.id)
        }
        val opened = BdkKit.open(
            PublicDescriptors(meta.externalDescriptor, meta.internalDescriptor),
            BdkKit.networkOf(chain),
            dbPath(meta.id),
            BdkKit.genesisHex(chain),
            chain,
        )
        sessions[meta.id] = opened
        return opened
    }

    private fun esploraFor(meta: WalletMeta): String {
        val chain = operatingNetwork(meta.network)
        return if (settings.network() == chain) settings.esplora() else NetworkDefaults.defaultEsplora(chain)
    }

    private fun dbPath(id: String): String {
        val dir = File(appContext.filesDir, "wallets/$id").apply { mkdirs() }
        return File(dir, "bdk.sqlite").absolutePath
    }

    private fun requireMeta(id: String): WalletMeta =
        metas.list().firstOrNull { it.id == id } ?: throw BdkUserException("지갑을 찾을 수 없습니다.")

    private fun newId(kind: String, networkName: String): String = UUID.randomUUID().toString()

    private fun defaultName(kind: String, networkName: String): String {
        val index = metas.list().size + 1
        val prefix = when (kind) {
            BdkKit.KIND_WATCH -> "조회"
            else -> when (networkName) {
                NetworkDefaults.BITCOIN -> "BTC"
                NetworkDefaults.BTCMOBICK -> "BTCMobick"
                else -> "BTC 시그넷"
            }
        }
        return "$prefix 지갑 $index"
    }

    private fun finishScan(id: String) {
        workers.withLock(id) {
            scanning.remove(id)
            if (id in removed) cleanupLocked(id)
        }
    }

    private fun cleanupLocked(id: String) {
        sessions.remove(id)?.close()
        File(appContext.filesDir, "wallets/$id").deleteRecursively()
        removed.remove(id)
    }

    private suspend fun <T> bdk(block: () -> T): T = withContext(Dispatchers.IO) { workers.call(block) }

    private suspend fun <T> bdkWithin(timeoutMs: Long, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            workers.callWithin(timeoutMs, block)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("동기화가 중단되었습니다.", e)
        }
    }

    private suspend fun <T> bdkWallet(id: String, block: () -> T): T =
        withContext(Dispatchers.IO) { workers.callWallet(id, block) }

    companion object {
        const val ALREADY_SYNCING = "이미 동기화 중입니다."
        private const val SYNC_TIMEOUT_MS = 20_000L
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("coin_utxo_settings", Context.MODE_PRIVATE)

    fun network(): String = prefs.getString(KEY_NETWORK, NetworkDefaults.SIGNET) ?: NetworkDefaults.SIGNET

    fun esplora(): String {
        val saved = prefs.getString(KEY_ESPLORA, null) ?: return NetworkDefaults.defaultEsplora(network())
        return NetworkDefaults.preferredEsplora(network(), saved)
    }

    fun setNetwork(network: String) {
        prefs.edit()
            .putString(KEY_NETWORK, network)
            .putString(KEY_ESPLORA, NetworkDefaults.defaultEsplora(network))
            .commit()
    }

    fun setEsplora(url: String) {
        prefs.edit().putString(KEY_ESPLORA, url).commit()
    }

    companion object {
        private const val KEY_NETWORK = "network"
        private const val KEY_ESPLORA = "esplora"
    }
}

class MetaStore(context: Context) {
    private val prefs = context.getSharedPreferences("coin_utxo_wallets", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<WalletMeta> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    WalletMeta(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        network = item.getString("network"),
                        kind = item.getString("kind"),
                        externalDescriptor = item.getString("external"),
                        internalDescriptor = item.optString("internal").ifEmpty { null },
                        createdAt = item.getLong("createdAt"),
                        lastBalanceSats = if (item.has("balance") && !item.isNull("balance")) item.getLong("balance") else null,
                        balanceNetwork = item.optString("balanceNetwork").ifEmpty { null },
                    ),
                )
            }
        }
    }

    @Synchronized
    fun add(meta: WalletMeta) {
        val next = list() + meta
        write(next)
    }

    @Synchronized
    fun updateBalance(id: String, balance: Long, chain: String) {
        write(list().map { if (it.id == id) it.copy(lastBalanceSats = balance, balanceNetwork = chain) else it })
    }

    @Synchronized
    fun rename(id: String, name: String) {
        write(list().map { if (it.id == id) it.copy(name = name) else it })
    }

    @Synchronized
    fun delete(id: String) {
        write(list().filterNot { it.id == id })
    }

    private fun write(items: List<WalletMeta>) {
        val array = JSONArray()
        items.forEach { meta ->
            array.put(
                JSONObject()
                    .put("id", meta.id)
                    .put("name", meta.name)
                    .put("network", meta.network)
                    .put("kind", meta.kind)
                    .put("external", meta.externalDescriptor)
                    .put("internal", meta.internalDescriptor ?: JSONObject.NULL)
                    .put("createdAt", meta.createdAt)
                    .put("balance", meta.lastBalanceSats ?: JSONObject.NULL)
                    .put("balanceNetwork", meta.balanceNetwork ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    companion object {
        private const val KEY = "wallets"
    }
}

class SecretStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "coin_utxo_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putMnemonic(id: String, mnemonic: String) {
        prefs.edit().putString(id, "m\n$mnemonic").commit()
    }

    fun putWif(id: String, wif: String) {
        prefs.edit().putString(id, "w\n$wif").commit()
    }

    fun read(id: String): StoredSecret? {
        val raw = prefs.getString(id, null) ?: return null
        return when {
            raw.startsWith("m\n") -> StoredSecret(BdkKit.KIND_MNEMONIC, raw.substring(2))
            raw.startsWith("w\n") -> StoredSecret(BdkKit.KIND_WIF, raw.substring(2))
            else -> null
        }
    }

    fun delete(id: String) {
        prefs.edit().remove(id).commit()
    }
}
