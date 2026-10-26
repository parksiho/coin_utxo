package dev.coinutxo.bdk

import dev.coinutxo.logic.CoinControl
import dev.coinutxo.logic.PsbtInputs
import dev.coinutxo.logic.UtxoRef
import org.bitcoindevkit.Anchor
import org.bitcoindevkit.BlockId
import org.bitcoindevkit.ChangeSet
import org.bitcoindevkit.ConfirmationBlockTime
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.HashableOutPoint
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Persistence
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxGraphChangeSet
import org.bitcoindevkit.TxOut
import org.bitcoindevkit.Txid
import org.bitcoindevkit.Wallet
import org.bitcoindevkit.WordCount
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoinSelectionBdkTest {
    @Test
    fun manuallySelectedInputsNeverExpand() {
        val funded = fundTwoUtxos()
        val wallet = funded.wallet
        try {
            val snapshot = BdkKit.snapshot(
                BdkSession(wallet, funded.persister, funded.descriptors, Network.SIGNET),
                tipHeight = 100,
            )
            assertEquals(2, snapshot.utxos.size, snapshot.utxos.toString())
            val small = snapshot.utxos.minBy { it.amountSats }
            val large = snapshot.utxos.maxBy { it.amountSats }
            val session = BdkSession(wallet, funded.persister, funded.descriptors, Network.SIGNET)

            assertFailsWith<BdkUserException> {
                BdkKit.buildSend(
                    session = session,
                    available = snapshot.utxos,
                    chosenIds = setOf(small.id),
                    destination = snapshot.receiveAddress,
                    amountSats = large.amountSats,
                    feeRateSatPerVb = 1,
                )
            }

            val draft = BdkKit.buildSend(
                session = session,
                available = snapshot.utxos,
                chosenIds = setOf(small.id),
                destination = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
                amountSats = 10_000L,
                feeRateSatPerVb = 1,
            )
            CoinControl.assertNotExpanded(setOf(small.id), draft.inputIds)
            assertEquals(listOf(small.id), draft.inputIds.map { it.lowercase() })
            assertTrue(draft.feeSats > 0)
            assertTrue(draft.changeSats == 0L || draft.changeSats >= 294L)
            assertEquals(small.amountSats, draft.amountSats + draft.feeSats + draft.changeSats)

            val both = BdkKit.buildSend(
                session = session,
                available = snapshot.utxos,
                chosenIds = snapshot.utxos.map { it.id }.toSet(),
                destination = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
                amountSats = null,
                feeRateSatPerVb = 2,
            )
            CoinControl.assertNotExpanded(snapshot.utxos.map { it.id }.toSet(), both.inputIds)
            assertEquals(snapshot.utxos.map { it.id }.toSet(), both.inputIds.map { it.lowercase() }.toSet())
            assertEquals(0L, both.changeSats)
            assertEquals(snapshot.utxos.sumOf { it.amountSats }, both.amountSats + both.feeSats)

            val signedPersister = Persister.newInMemory()
            val signer = Wallet(
                funded.privateExternal,
                funded.privateInternal,
                Network.SIGNET,
                signedPersister,
                25u,
            )
            try {
                assertTrue(signer.sign(draft.psbt, org.bitcoindevkit.SignOptions(true, null, false, true, false, true)))
                val tx = draft.psbt.extractTx()
                val extracted = tx.input().map { input ->
                    "${input.previousOutput.txid.toString().lowercase()}:${input.previousOutput.vout.toInt()}"
                }
                assertEquals(draft.inputIds.map { it.lowercase() }, extracted.map { it.lowercase() })
                assertEquals(extracted, PsbtInputs.outpointIds(draft.psbt.serialize()).map { it.lowercase() })
            } finally {
                signer.close()
                signedPersister.close()
            }
        } finally {
            wallet.close()
            funded.persister.close()
        }
    }

    private fun fundTwoUtxos(): Funded {
        val mnemonic = Mnemonic(WordCount.WORDS12)
        val secret = DescriptorSecretKey(NetworkKind.TEST, mnemonic, "")
        val external = Descriptor.newBip84(secret, KeychainKind.EXTERNAL, NetworkKind.TEST)
        val internal = Descriptor.newBip84(secret, KeychainKind.INTERNAL, NetworkKind.TEST)
        val captured = MemoryPersistence(null)
        val createPersister = Persister.custom(captured)
        val created = Wallet(external.asPublic(), internal.asPublic(), Network.SIGNET, createPersister, 25u)
        val address0 = created.revealNextAddress(KeychainKind.EXTERNAL)
        val address1 = created.revealNextAddress(KeychainKind.EXTERNAL)
        created.persist(createPersister)
        val genesis = created.latestCheckpoint()
        val tx = Transaction(
            rawTx(
                listOf(
                    address0.address.scriptPubkey().toBytes() to 50_000L,
                    address1.address.scriptPubkey().toBytes() to 80_000L,
                ),
            ),
        )
        val anchor = Anchor(
            ConfirmationBlockTime(BlockId(genesis.height, genesis.hash), 1_700_000_000uL),
            tx.computeTxid(),
        )
        val graph = TxGraphChangeSet(
            listOf(tx),
            emptyMap<HashableOutPoint, TxOut>(),
            listOf(anchor),
            emptyMap<Txid, ULong>(),
            emptyMap<Txid, ULong>(),
            emptyMap<Txid, ULong>(),
        )
        val merged = ChangeSet.fromMerge(captured.snapshot(), ChangeSet.fromTxGraphChangeset(graph))
        created.close()
        createPersister.close()
        val loadPersistence = MemoryPersistence(merged)
        val persister = Persister.custom(loadPersistence)
        val wallet = Wallet.load(external.asPublic(), internal.asPublic(), persister, 25u)
        val public = PublicDescriptors(external.asPublic().toString(), internal.asPublic().toString())
        return Funded(wallet, persister, public, external, internal)
    }

    private fun rawTx(outputs: List<Pair<ByteArray, Long>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }
        fun le64(value: Long) {
            var rest = value
            repeat(8) {
                out.write((rest and 0xff).toInt())
                rest = rest ushr 8
            }
        }
        le32(2)
        out.write(1)
        repeat(32) { out.write(0xab) }
        le32(0)
        out.write(0)
        le32(-1)
        out.write(outputs.size)
        outputs.forEach { (script, value) ->
            le64(value)
            out.write(script.size)
            out.write(script)
        }
        le32(0)
        return out.toByteArray()
    }
}

private class Funded(
    val wallet: Wallet,
    val persister: Persister,
    val descriptors: PublicDescriptors,
    val privateExternal: Descriptor,
    val privateInternal: Descriptor,
)

private class MemoryPersistence(initial: ChangeSet?) : Persistence {
    private var state: ChangeSet? = initial

    fun snapshot(): ChangeSet = state ?: ChangeSet()

    override fun initialize(): ChangeSet = state ?: ChangeSet()

    override fun persist(changeset: ChangeSet) {
        val current = state
        state = if (current == null) changeset else ChangeSet.fromMerge(current, changeset)
    }
}
