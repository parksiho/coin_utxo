package dev.coinutxo.bdk

import dev.coinutxo.logic.Bip21Parser
import dev.coinutxo.logic.CoinControl
import dev.coinutxo.logic.ExtendedPubKey
import dev.coinutxo.logic.FeeMath
import dev.coinutxo.logic.NetworkDefaults
import dev.coinutxo.logic.PsbtInputs
import dev.coinutxo.logic.UtxoRef
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorPublicKey
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Txid
import org.bitcoindevkit.Wallet
import org.bitcoindevkit.WordCount
import java.io.File

class BdkUserException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class PublicDescriptors(
    val external: String,
    val internal: String?,
)

data class ImportedSecret(
    val kind: String,
    val secret: String,
    val descriptors: PublicDescriptors,
)

data class SyncSnapshot(
    val balanceSats: Long,
    val tipHeight: Int,
    val utxos: List<UtxoRef>,
    val receiveAddress: String,
    val canRevealNext: Boolean,
)

data class SendDraft(
    val psbt: Psbt,
    val destination: String,
    val amountSats: Long,
    val feeSats: Long,
    val changeSats: Long,
    val inputIds: List<String>,
    val sendMax: Boolean,
)

class BdkSession(
    val wallet: Wallet?,
    val persister: Persister?,
    val descriptors: PublicDescriptors,
    val network: Network,
) : AutoCloseable {
    override fun close() {
        wallet?.close()
        persister?.close()
    }
}

object BdkKit {
    const val KIND_MNEMONIC = "MNEMONIC"
    const val KIND_WIF = "WIF"
    const val KIND_WATCH = "WATCH"
    const val ADDRESS_PREFIX = "address:"
    private const val SCAN_STOP_GAP = 20uL
    private const val SCAN_PARALLEL = 5uL

    fun networkOf(name: String): Network = when (name) {
        NetworkDefaults.BITCOIN -> Network.BITCOIN
        NetworkDefaults.SIGNET -> Network.SIGNET
        else -> throw BdkUserException("지원하지 않는 네트워크입니다.")
    }

    fun networkKind(network: Network): NetworkKind =
        if (network == Network.BITCOIN) NetworkKind.MAIN else NetworkKind.TEST

    fun newMnemonicWords(): List<String> {
        val mnemonic = Mnemonic(WordCount.WORDS12)
        return mnemonic.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    fun descriptorsForMnemonic(words: String, network: Network): PublicDescriptors {
        val mnemonic = Mnemonic.fromString(normalizeMnemonic(words))
        val kind = networkKind(network)
        val secret = DescriptorSecretKey(kind, mnemonic, "")
        val external = Descriptor.newBip84(secret, KeychainKind.EXTERNAL, kind).asPublic().toString()
        val internal = Descriptor.newBip84(secret, KeychainKind.INTERNAL, kind).asPublic().toString()
        assertPublic(external)
        assertPublic(internal)
        return PublicDescriptors(external, internal)
    }

    fun canonicalMnemonic(words: String): String = Mnemonic.fromString(normalizeMnemonic(words)).toString()

    fun descriptorsForWif(wif: String, network: Network): PublicDescriptors {
        val trimmed = wif.trim()
        val descriptor = try {
            Descriptor("wpkh($trimmed)", networkKind(network))
        } catch (e: Exception) {
            throw BdkUserException("WIF를 해석할 수 없습니다.", e)
        }
        val external = descriptor.asPublic().toString()
        assertPublic(external)
        if (external.contains(trimmed)) {
            throw BdkUserException("공개 디스크립터에 비밀 키가 남아 있습니다.")
        }
        return PublicDescriptors(external, null)
    }

    fun importSecret(text: String, network: Network): ImportedSecret {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw BdkUserException("가져올 키를 입력하세요.")
        val words = trimmed.split(Regex("\\s+"))
        if (words.size in setOf(12, 15, 18, 21, 24) && words.all { it.all { ch -> ch.isLetter() } }) {
            val canonical = try {
                canonicalMnemonic(trimmed)
            } catch (e: Exception) {
                throw BdkUserException("올바른 BIP39 니모닉이 아닙니다.", e)
            }
            return ImportedSecret(
                KIND_MNEMONIC,
                canonical,
                descriptorsForMnemonic(canonical, network),
            )
        }
        if (looksLikeWif(trimmed)) {
            return ImportedSecret(KIND_WIF, trimmed, descriptorsForWif(trimmed, network))
        }
        throw BdkUserException("니모닉 또는 WIF만 가져올 수 있습니다.")
    }

    fun importWatch(text: String, network: Network): PublicDescriptors {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw BdkUserException("주소, xpub 또는 디스크립터를 입력하세요.")
        if (containsPrivateMarker(trimmed) || looksLikeMnemonic(trimmed) || looksLikeWif(trimmed)) {
            throw BdkUserException("개인키는 조회 화면에 저장하지 않습니다. 가져오기 화면을 사용하세요.")
        }
        val kind = networkKind(network)
        if (trimmed.contains("(")) {
            val descriptor = try {
                Descriptor(trimmed, kind)
            } catch (e: Exception) {
                throw BdkUserException("디스크립터를 해석할 수 없습니다.", e)
            }
            val parts = if (descriptor.isMultipath()) descriptor.toSingleDescriptors() else listOf(descriptor)
            val publics = parts.map { it.asPublic().toString() }
            publics.forEach(::assertPublic)
            return when {
                publics.size >= 2 -> PublicDescriptors(publics[0], publics[1])
                else -> PublicDescriptors(publics[0], null)
            }
        }
        val extended = parseExtendedKey(trimmed)
        if (extended != null) return extendedWatch(extended, network)
        val address = validateAddress(trimmed, network)
        return PublicDescriptors(ADDRESS_PREFIX + address, null)
    }

    fun validateAddress(text: String, network: Network): String {
        val candidate = try {
            Bip21Parser.parse(text).address
        } catch (_: Exception) {
            text.trim()
        }
        if (candidate.isEmpty()) throw BdkUserException("주소를 입력하세요.")
        try {
            val address = Address(candidate, network)
            if (!address.isValidForNetwork(network)) {
                throw BdkUserException("이 네트워크에서 사용할 수 없는 주소입니다.")
            }
            return address.toString()
        } catch (e: BdkUserException) {
            throw e
        } catch (_: Exception) {
            throw BdkUserException("주소를 해석할 수 없습니다.")
        }
    }

    fun singleAddress(descriptors: PublicDescriptors): String? =
        descriptors.external.removePrefix(ADDRESS_PREFIX).takeIf { descriptors.external.startsWith(ADDRESS_PREFIX) }

    fun open(descriptors: PublicDescriptors, network: Network, sqlitePath: String): BdkSession {
        if (singleAddress(descriptors) != null) {
            return BdkSession(null, null, descriptors, network)
        }
        val kind = networkKind(network)
        val external = Descriptor(descriptors.external, kind)
        val internal = descriptors.internal?.let { Descriptor(it, kind) }
        val file = File(sqlitePath)
        if (file.exists()) {
            try {
                val persister = Persister.newSqlite(sqlitePath)
                val wallet = if (internal == null) {
                    Wallet.loadSingle(external, persister, 25u)
                } else {
                    Wallet.load(external, internal, persister, 25u)
                }
                return BdkSession(wallet, persister, descriptors, network)
            } catch (_: Exception) {
                file.delete()
            }
        }
        file.parentFile?.mkdirs()
        val persister = Persister.newSqlite(sqlitePath)
        val wallet = if (internal == null) {
            Wallet.createSingle(external, network, persister, 25u)
        } else {
            Wallet(external, internal, network, persister, 25u)
        }
        wallet.persist(persister)
        return BdkSession(wallet, persister, descriptors, network)
    }

    fun sync(session: BdkSession, esploraUrl: String): SyncSnapshot {
        val watched = singleAddress(session.descriptors)
        if (watched != null) return syncSingleAddress(session, esploraUrl, watched)
        val wallet = session.wallet ?: throw BdkUserException("지갑을 열지 못했습니다.")
        val persister = session.persister ?: throw BdkUserException("지갑을 열지 못했습니다.")
        val client = EsploraClient(esploraUrl, null)
        try {
            val seenChain = wallet.latestCheckpoint().height > 0u
            val update = if (seenChain) {
                client.sync(wallet.startSyncWithRevealedSpks().build(), SCAN_PARALLEL)
            } else {
                client.fullScan(wallet.startFullScan().build(), SCAN_STOP_GAP, SCAN_PARALLEL)
            }
            wallet.applyUpdate(update)
            wallet.persist(persister)
            val tip = client.getHeight().toInt()
            return snapshot(session, tip)
        } catch (e: BdkUserException) {
            throw e
        } catch (e: Exception) {
            throw BdkUserException("체인 데이터를 가져오지 못했습니다.", e)
        } finally {
            client.close()
        }
    }

    private fun syncSingleAddress(session: BdkSession, esploraUrl: String, watched: String): SyncSnapshot {
        val address = Address(watched, session.network)
        val wanted = address.scriptPubkey().toBytes()
        val client = EsploraClient(esploraUrl, null)
        try {
            val tip = client.getHeight().toInt()
            val seen = linkedSetOf<String>()
            var after: org.bitcoindevkit.Txid? = null
            val utxos = mutableListOf<UtxoRef>()
            repeat(8) {
                val page = client.getAddressTxs(address, after)
                if (page.isEmpty()) return@repeat
                var fresh = 0
                for (meta in page) {
                    val txid = meta.txid.toString().lowercase()
                    if (!seen.add(txid)) continue
                    fresh++
                    val tx = client.getTx(meta.txid) ?: continue
                    tx.output().forEachIndexed { index, output ->
                        if (!output.scriptPubkey.toBytes().contentEquals(wanted)) return@forEachIndexed
                        val status = client.getOutputStatus(meta.txid, index.toULong())
                        if (status?.spent == true) return@forEachIndexed
                        utxos += UtxoRef(
                            txid = txid,
                            vout = index,
                            amountSats = output.value.toSat().toLong(),
                            confirmations = confirmations(meta.status, tip),
                        )
                    }
                }
                if (fresh == 0) return@repeat
                after = page.last().txid
            }
            utxos.sortWith(compareByDescending<UtxoRef> { it.confirmations }.thenByDescending { it.amountSats })
            return SyncSnapshot(
                balanceSats = utxos.sumOf { it.amountSats },
                tipHeight = tip,
                utxos = utxos,
                receiveAddress = address.toString(),
                canRevealNext = false,
            )
        } catch (e: BdkUserException) {
            throw e
        } catch (e: Exception) {
            throw BdkUserException("주소 잔액을 가져오지 못했습니다.", e)
        } finally {
            client.close()
        }
    }

    fun snapshot(session: BdkSession, tipHeight: Int): SyncSnapshot {
        val watched = singleAddress(session.descriptors)
        if (watched != null) {
            return SyncSnapshot(0L, tipHeight, emptyList(), watched, false)
        }
        val wallet = session.wallet ?: throw BdkUserException("지갑을 열지 못했습니다.")
        val balance = wallet.balance().total.toSat().toLong()
        val utxos = wallet.listUnspent()
            .filter { !it.isSpent }
            .map { output ->
                UtxoRef(
                    txid = output.outpoint.txid.toString().lowercase(),
                    vout = output.outpoint.vout.toInt(),
                    amountSats = output.txout.value.toSat().toLong(),
                    confirmations = confirmations(output.chainPosition, tipHeight),
                )
            }
            .sortedWith(compareByDescending<UtxoRef> { it.confirmations }.thenByDescending { it.amountSats })
        return SyncSnapshot(
            balanceSats = balance,
            tipHeight = tipHeight,
            utxos = utxos,
            receiveAddress = receiveAddress(session),
            canRevealNext = canReveal(session),
        )
    }

    fun revealNext(session: BdkSession): String {
        if (!canReveal(session)) throw BdkUserException("이 지갑은 주소를 더 만들 수 없습니다.")
        val wallet = session.wallet ?: throw BdkUserException("이 지갑은 주소를 더 만들 수 없습니다.")
        val persister = session.persister ?: throw BdkUserException("이 지갑은 주소를 더 만들 수 없습니다.")
        val info = wallet.revealNextAddress(KeychainKind.EXTERNAL)
        wallet.persist(persister)
        return info.address.toString()
    }

    fun buildSend(
        session: BdkSession,
        available: List<UtxoRef>,
        chosenIds: Set<String>,
        destination: String,
        amountSats: Long?,
        feeRateSatPerVb: Long,
    ): SendDraft {
        if (session.wallet == null) throw BdkUserException("조회 전용 주소는 보낼 수 없습니다.")
        if (feeRateSatPerVb < 1L || feeRateSatPerVb > 10_000L) {
            throw BdkUserException("수수료율은 1에서 10000 sat/vB 사이여야 합니다.")
        }
        val selected = try {
            CoinControl.selectedOnly(available, chosenIds)
        } catch (e: Exception) {
            throw BdkUserException(e.message ?: "UTXO를 선택하세요.", e)
        }
        val address = try {
            Address(validateAddress(destination, session.network), session.network)
        } catch (e: BdkUserException) {
            throw e
        } catch (e: Exception) {
            throw BdkUserException("받는 주소를 해석할 수 없습니다.", e)
        }
        val outpoints = selected.map { utxo ->
            OutPoint(Txid.fromString(utxo.txid), utxo.vout.toUInt())
        }
        var builder = TxBuilder()
            .manuallySelectedOnly()
            .addUtxos(outpoints)
            .feeRate(FeeRate.fromSatPerVb(feeRateSatPerVb.toULong()))
        builder = if (amountSats == null) {
            builder.drainWallet().drainTo(address.scriptPubkey())
        } else {
            if (amountSats < FeeMath.DUST_SATS) {
                throw BdkUserException("받는 금액이 더스트(294 sats)보다 작습니다.")
            }
            builder.addRecipient(address.scriptPubkey(), Amount.fromSat(amountSats.toULong()))
        }
        val psbt = try {
            builder.finish(session.wallet)
        } catch (e: Exception) {
            throw BdkUserException("선택한 UTXO만으로는 거래를 만들 수 없습니다.", e)
        }
        val inputIds = try {
            PsbtInputs.outpointIds(psbt.serialize())
        } catch (e: Exception) {
            throw BdkUserException("만든 거래의 입력을 확인하지 못했습니다.", e)
        }
        try {
            CoinControl.assertNotExpanded(selected.map { it.id }.toSet(), inputIds)
        } catch (e: Exception) {
            throw BdkUserException(e.message ?: "선택하지 않은 UTXO가 포함되었습니다.", e)
        }
        if (inputIds.isEmpty()) throw BdkUserException("거래에 입력이 없습니다.")
        val used = selected.filter { utxo -> inputIds.any { it.equals(utxo.id, ignoreCase = true) } }
        if (used.size != inputIds.distinct().size) {
            throw BdkUserException("선택한 UTXO 밖의 입력이 포함되었습니다.")
        }
        val inputSum = used.sumOf { it.amountSats }
        val fee = psbt.fee().toLong()
        val sendMax = amountSats == null
        val amount = if (sendMax) inputSum - fee else amountSats
        val change = inputSum - amount - fee
        if (amount <= 0L || change < 0L) throw BdkUserException("수수료를 내고 나면 보낼 금액이 없습니다.")
        if (amount < FeeMath.DUST_SATS) throw BdkUserException("받는 금액이 더스트보다 작습니다.")
        if (change in 1 until FeeMath.DUST_SATS) {
            throw BdkUserException("거스름이 더스트보다 작아 거래를 진행하지 않습니다.")
        }
        return SendDraft(
            psbt = psbt,
            destination = address.toString(),
            amountSats = amount,
            feeSats = fee,
            changeSats = change,
            inputIds = inputIds,
            sendMax = sendMax,
        )
    }

    fun sign(draft: SendDraft, secretKind: String, secret: String, network: Network) {
        val persister = Persister.newInMemory()
        val signer = try {
            when (secretKind) {
                KIND_MNEMONIC -> {
                    val kind = networkKind(network)
                    val mnemonic = Mnemonic.fromString(secret)
                    val key = DescriptorSecretKey(kind, mnemonic, "")
                    val external = Descriptor.newBip84(key, KeychainKind.EXTERNAL, kind)
                    val internal = Descriptor.newBip84(key, KeychainKind.INTERNAL, kind)
                    Wallet(external, internal, network, persister, 25u)
                }
                KIND_WIF -> {
                    val descriptor = Descriptor("wpkh($secret)", networkKind(network))
                    Wallet.createSingle(descriptor, network, persister, 25u)
                }
                else -> throw BdkUserException("조회 전용 지갑은 서명할 수 없습니다.")
            }
        } catch (e: BdkUserException) {
            persister.close()
            throw e
        } catch (e: Exception) {
            persister.close()
            throw BdkUserException("서명 키를 열지 못했습니다.", e)
        }
        try {
            val signed = signer.sign(draft.psbt, signOptions())
            if (!signed) throw BdkUserException("서명이 적용되지 않았습니다.")
        } catch (e: BdkUserException) {
            throw e
        } catch (e: Exception) {
            throw BdkUserException("기기에서 서명하지 못했습니다.", e)
        } finally {
            signer.close()
            persister.close()
        }
    }

    fun broadcast(esploraUrl: String, psbt: Psbt): String {
        val tx: Transaction = try {
            psbt.extractTx()
        } catch (e: Exception) {
            throw BdkUserException("서명한 거래를 추출하지 못했습니다.", e)
        }
        val client = EsploraClient(esploraUrl, null)
        try {
            client.broadcast(tx)
        } catch (e: Exception) {
            throw BdkUserException("거래를 전파하지 못했습니다.", e)
        } finally {
            client.close()
        }
        return tx.computeTxid().toString()
    }

    private fun signOptions(): SignOptions = SignOptions(
        trustWitnessUtxo = true,
        assumeHeight = null,
        allowAllSighashes = false,
        tryFinalize = true,
        signWithTapInternalKey = false,
        allowGrinding = true,
    )

    private fun receiveAddress(session: BdkSession): String {
        singleAddress(session.descriptors)?.let { return it }
        val wallet = session.wallet ?: throw BdkUserException("지갑을 열지 못했습니다.")
        val descriptor = Descriptor(session.descriptors.external, networkKind(session.network))
        return if (descriptor.hasWildcard()) {
            wallet.nextUnusedAddress(KeychainKind.EXTERNAL).address.toString()
        } else {
            descriptor.deriveAddress(0u, session.network).toString()
        }
    }

    private fun canReveal(session: BdkSession): Boolean {
        if (singleAddress(session.descriptors) != null || session.wallet == null) return false
        return Descriptor(session.descriptors.external, networkKind(session.network)).hasWildcard()
    }

    private fun confirmations(status: org.bitcoindevkit.TxStatus, tipHeight: Int): Int {
        if (!status.confirmed) return 0
        val height = status.blockHeight?.toInt() ?: return 1
        if (tipHeight <= 0) return 1
        return (tipHeight - height + 1).coerceAtLeast(1)
    }

    private fun confirmations(position: ChainPosition, tipHeight: Int): Int = when (position) {
        is ChainPosition.Confirmed -> {
            val height = position.confirmationBlockTime.blockId.height.toInt()
            if (tipHeight <= 0) 1 else (tipHeight - height + 1).coerceAtLeast(1)
        }
        is ChainPosition.Unconfirmed -> 0
    }

    private fun extendedWatch(text: String, network: Network): PublicDescriptors {
        var body = text.trim()
        body = body.replace(Regex("""/(0|1)?/\*$"""), "")
        val origin = Regex("""^\[([0-9a-fA-F]{8})(?:/[0-9h']+)?](.+)$""").matchEntire(body)
        val fingerprint = origin?.groupValues?.get(1)
        val keyText = origin?.groupValues?.get(2) ?: body
        val decoded = ExtendedPubKey.decodeOrNull(keyText)
            ?: throw BdkUserException("확장 공개키를 해석할 수 없습니다.")
        val mainnet = network == Network.BITCOIN
        if (decoded.mainnet != mainnet) {
            throw BdkUserException("이 확장 공개키는 현재 네트워크와 맞지 않습니다.")
        }
        if (decoded.depth != 3) {
            throw BdkUserException("BIP84 계정 공개키(depth 3) 또는 전체 디스크립터를 사용하세요.")
        }
        val kind = networkKind(network)
        val key = try {
            DescriptorPublicKey.fromString(decoded.standardForm)
        } catch (e: Exception) {
            throw BdkUserException("확장 공개키를 열지 못했습니다.", e)
        }
        val fp = fingerprint ?: "00000000"
        val external = try {
            Descriptor.newBip84Public(key, fp, KeychainKind.EXTERNAL, kind).asPublic().toString()
        } catch (e: Exception) {
            throw BdkUserException("BIP84 디스크립터를 만들지 못했습니다.", e)
        }
        val internal = Descriptor.newBip84Public(key, fp, KeychainKind.INTERNAL, kind).asPublic().toString()
        assertPublic(external)
        assertPublic(internal)
        return PublicDescriptors(external, internal)
    }

    private fun parseExtendedKey(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.contains("(") || trimmed.contains(" ")) return null
        val body = trimmed.replace(Regex("""/(0|1)?/\*$"""), "")
        val key = Regex("""^\[([0-9a-fA-F]{8})(?:/[0-9h']+)?](.+)$""").matchEntire(body)?.groupValues?.get(2)
            ?: body
        return if (ExtendedPubKey.decodeOrNull(key) != null) trimmed else null
    }

    private fun looksLikeMnemonic(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+"))
        return words.size in setOf(12, 15, 18, 21, 24) && words.all { it.all { ch -> ch.isLetter() } }
    }

    private fun looksLikeWif(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 51..52) return false
        return trimmed.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" }
    }

    private fun containsPrivateMarker(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("xprv", "tprv", "yprv", "zprv", "uprv", "vprv").any { lower.contains(it) }
    }

    private fun normalizeMnemonic(words: String): String =
        words.trim().split(Regex("\\s+")).joinToString(" ")

    private fun assertPublic(descriptor: String) {
        val lower = descriptor.lowercase()
        if (listOf("xprv", "tprv", "yprv", "zprv", "uprv", "vprv").any { lower.contains(it) }) {
            throw BdkUserException("공개 디스크립터에 비밀 키가 포함되어 있습니다.")
        }
    }
}
