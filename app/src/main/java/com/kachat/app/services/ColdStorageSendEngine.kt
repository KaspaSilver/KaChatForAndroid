package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.KsptCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Builds unsigned transactions for a Cold Storage (kpub watch-only) address and broadcasts the
 * signed response scanned back from a KasSigner device — the "send" half of Cold Storage
 * ([ColdStorageManager]/[ColdStorageAddressDiscovery] only cover the watch-only half). Deliberately
 * has no dependency on [WalletManager]: this engine never sees a mnemonic or private key, only
 * public addresses and whatever signature KasSigner hands back.
 */
@Singleton
class ColdStorageSendEngine @Inject constructor(
    private val networkService: NetworkService,
    private val nodePoolManager: NodePoolManager
) {
    // Same reasoning as KaspaWalletEngine.sendMutex — one build-then-broadcast sequence at a time.
    private val mutex = Mutex()

    data class UnsignedColdTx(
        val rawTx: RawTransaction,
        // Same order as rawTx.inputs — KSPT's per-input amount/scriptPublicKey come from here,
        // since RawInput alone (just an outpoint + empty signatureScript) doesn't carry them.
        val inputUtxos: List<UtxoEntry>,
        val feeSompi: Long,
        val changeSompi: Long
    )

    /**
     * KasSigner's KSPT wire format carries no BIP32 derivation path per input — the device
     * presumably resolves a signing key per input by matching its scriptPublicKey against its own
     * derived address set, but nothing in the format lets KaChat *tell* it which path to use. To
     * stay unambiguous, every input in a single send is sourced from exactly one address (picked
     * by the user from the account's address list), never aggregated across several.
     */
    suspend fun buildUnsignedTransaction(fromAddress: String, toAddress: String, amountSompi: Long): Result<UnsignedColdTx> = mutex.withLock {
        try {
            require(KaspaAddress.isValid(toAddress)) { "Invalid recipient address" }
            require(amountSompi > 0) { "Amount must be greater than zero" }

            val api = networkService.kaspaRestApi.value
                ?: return@withLock Result.failure(IllegalStateException("Network service unavailable"))

            val utxos = api.getUtxos(fromAddress)
            if (utxos.isEmpty()) {
                return@withLock Result.failure(IllegalStateException("No spendable UTXOs at this address"))
            }

            val feeRateSompiPerGram = try {
                val estimate = api.getFeeEstimate()
                val quoted = estimate.normalBuckets.firstOrNull()?.feerate ?: KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
                ceil(quoted).toLong().coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            } catch (e: Exception) {
                KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
            }

            val recipientScriptHex = KaspaAddress.getScriptPublicKey(toAddress)
            val changeScriptHex = KaspaAddress.getScriptPublicKey(fromAddress)

            val selection = KaspaUtxoSelector.selectUtxosAndCalculateFee(
                utxos = utxos,
                amountSompi = amountSompi,
                feeRateSompiPerGram = feeRateSompiPerGram,
                payloadBytes = null,
                recipientScriptLen = recipientScriptHex.length / 2,
                changeScriptLen = changeScriptHex.length / 2
            )
            if (selection.totalSelected < selection.requiredAmount) {
                return@withLock Result.failure(
                    IllegalStateException("Insufficient funds: needed ${selection.requiredAmount}, have ${selection.totalSelected}")
                )
            }
            if (selection.selectedUtxos.size > KsptCodec.MAX_INPUTS) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "This send would need ${selection.selectedUtxos.size} UTXOs, but KasSigner only supports " +
                            "${KsptCodec.MAX_INPUTS} inputs per transaction — send a smaller amount or consolidate this address first"
                    )
                )
            }

            val outputs = mutableListOf<RawOutputWithVersion>(
                RawOutputWithVersion(amount = selection.finalAmount, scriptPublicKey = ScriptPublicKeyWithVersion(recipientScriptHex, 0))
            )
            if (selection.changeAmount > 500) { // matches KaspaWalletEngine's dust threshold
                outputs.add(
                    RawOutputWithVersion(amount = selection.changeAmount, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0))
                )
            }
            if (outputs.size > KsptCodec.MAX_OUTPUTS) {
                return@withLock Result.failure(IllegalStateException("Too many outputs for KSPT"))
            }

            val rawTx = RawTransaction(
                inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
                outputs = outputs
            )

            Result.success(
                UnsignedColdTx(
                    rawTx = rawTx,
                    inputUtxos = selection.selectedUtxos,
                    feeSompi = selection.estimatedFee,
                    changeSompi = selection.changeAmount.coerceAtLeast(0L)
                )
            )
        } catch (e: Exception) {
            Log.e("ColdStorageSendEngine", "Failed to build unsigned transaction", e)
            Result.failure(e)
        }
    }

    /** KSPT-encodes [tx] for display as an (animated) QR sequence — see [com.kachat.app.util.QrFrameChunker]. */
    fun toKspt(tx: UnsignedColdTx): ByteArray {
        return KsptCodec.encodeUnsigned(
            txVersion = tx.rawTx.version,
            lockTime = tx.rawTx.lockTime,
            subnetworkIdHex = tx.rawTx.subnetworkId,
            gas = tx.rawTx.gas,
            payloadHex = tx.rawTx.payload,
            inputs = tx.rawTx.inputs.mapIndexed { index, input ->
                val utxo = tx.inputUtxos[index]
                KsptCodec.UnsignedInput(
                    prevTxId = input.previousOutpoint.transactionId,
                    prevIndex = input.previousOutpoint.index,
                    amountSompi = utxo.utxoEntry.amount,
                    sequence = input.sequence,
                    sigOpCount = input.sigOpCount,
                    spkVersion = 0,
                    spkScriptHex = utxo.utxoEntry.scriptPublicKey.scriptPublicKey
                )
            },
            outputs = tx.rawTx.outputs.map { output ->
                KsptCodec.UnsignedOutput(
                    valueSompi = output.amount,
                    spkVersion = output.scriptPublicKey.version,
                    spkScriptHex = output.scriptPublicKey.scriptPublicKey
                )
            }
        )
    }

    /**
     * Merges a scanned signed-KSPT response's per-input Schnorr signatures back into
     * [unsignedTx]'s inputs and broadcasts. Verifies every input's outpoint AND every output's
     * amount/script against what was actually sent for signing before broadcasting anything — a
     * compromised/malfunctioning device altering the destination or amount must fail loudly here,
     * not get silently broadcast.
     */
    suspend fun broadcastSigned(unsignedTx: UnsignedColdTx, decoded: KsptCodec.Decoded): Result<String> {
        return try {
            require(decoded.signed) { "Scanned transaction is not signed" }
            require(decoded.inputs.size == unsignedTx.rawTx.inputs.size) { "Signed transaction has a different number of inputs" }
            require(decoded.outputs.size == unsignedTx.rawTx.outputs.size) { "Signed transaction has a different number of outputs" }

            decoded.outputs.forEachIndexed { index, decodedOutput ->
                val original = unsignedTx.rawTx.outputs[index]
                require(decodedOutput.valueSompi == original.amount && decodedOutput.spkScriptHex == original.scriptPublicKey.scriptPublicKey) {
                    "Signed transaction's outputs don't match what was sent for signing — refusing to broadcast"
                }
            }

            val signedInputs = unsignedTx.rawTx.inputs.mapIndexed { index, input ->
                val decodedInput = decoded.inputs[index]
                require(
                    decodedInput.prevTxId == input.previousOutpoint.transactionId &&
                        decodedInput.prevIndex == input.previousOutpoint.index
                ) { "Signed transaction's input $index doesn't match what was sent for signing" }

                val sigHex = decodedInput.signatureHex
                    ?: return Result.failure(IllegalStateException("Input $index wasn't signed"))
                val sigBytes = sigHex.hexToBytesLocal()
                require(sigBytes.size == 64) { "Unexpected signature length for input $index" }

                val sigScript = ByteArray(66)
                sigScript[0] = 0x41
                sigBytes.copyInto(sigScript, 1)
                sigScript[65] = (decodedInput.sighashType ?: 0x01).toByte()
                input.copy(signatureScript = sigScript.toHexStringLocal())
            }

            val signedTx = unsignedTx.rawTx.copy(inputs = signedInputs)
            val txId = nodePoolManager.getBroadcastConnection().submitTransaction(signedTx)
            Result.success(txId)
        } catch (e: Exception) {
            Log.e("ColdStorageSendEngine", "Failed to broadcast signed transaction", e)
            Result.failure(e)
        }
    }

    private fun String.hexToBytesLocal(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexStringLocal(): String = joinToString("") { "%02x".format(it) }
}
