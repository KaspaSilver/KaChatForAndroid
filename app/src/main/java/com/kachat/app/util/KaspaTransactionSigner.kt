package com.kachat.app.util

import com.kachat.app.services.RawTransaction
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.UtxoEntry
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles Kaspa transaction signing (Sighash calculation + Schnorr).
 */
object KaspaTransactionSigner {

    fun signTransaction(
        rawTx: RawTransaction,
        utxos: List<UtxoEntry>,
        privateKey: ByteArray
    ): RawTransaction {
        val signedInputs = rawTx.inputs.mapIndexed { index, input ->
            val utxo = utxos[index]
            val hash = calculateSighash(rawTx, index, utxo.utxoEntry.amount, utxo.utxoEntry.scriptPublicKey.scriptPublicKey)
            val signature = Schnorr.sign(hash, privateKey)
            
            // Signature script for Schnorr is just the 64-byte signature
            input.copy(signatureScript = signature.toHexString())
        }
        
        return rawTx.copy(inputs = signedInputs)
    }

    private fun calculateSighash(
        tx: RawTransaction,
        inputIndex: Int,
        amount: Long,
        scriptPublicKey: String
    ): ByteArray {
        val digest = Blake2bDigest(null, 32, null, "TransactionHash\u0000".toByteArray().copyOf(16))
        
        val buffer = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(tx.version.toShort())

        // 1. Outpoints Hash
        val outpointsDigest = Blake2bDigest(null, 32, null, "OutpointsHash\u0000\u0000\u0000".toByteArray().copyOf(16))
        tx.inputs.forEach { input ->
            outpointsDigest.update(input.previousOutpoint.transactionId.hexToBytes(), 0, 32)
            outpointsDigest.update(intToLeBytes(input.previousOutpoint.index), 0, 4)
        }
        val outpointsHash = ByteArray(32)
        outpointsDigest.doFinal(outpointsHash, 0)
        buffer.put(outpointsHash)

        // 2. Sequences Hash
        val sequencesDigest = Blake2bDigest(null, 32, null, "SequencesHash\u0000\u0000\u0000".toByteArray().copyOf(16))
        tx.inputs.forEach { input ->
            sequencesDigest.update(longToLeBytes(input.sequence), 0, 8)
        }
        val sequencesHash = ByteArray(32)
        sequencesDigest.doFinal(sequencesHash, 0)
        buffer.put(sequencesHash)

        // 3. SigOpCounts Hash
        val sigOpCountsDigest = Blake2bDigest(null, 32, null, "SigOpCountsHash\u0000".toByteArray().copyOf(16))
        tx.inputs.forEach { input ->
            sigOpCountsDigest.update(byteArrayOf(input.sigOpCount.toByte()), 0, 1)
        }
        val sigOpCountsHash = ByteArray(32)
        sigOpCountsDigest.doFinal(sigOpCountsHash, 0)
        buffer.put(sigOpCountsHash)

        // 4. Outputs Hash
        val outputsDigest = Blake2bDigest(null, 32, null, "OutputsHash\u0000\u0000\u0000\u0000\u0000".toByteArray().copyOf(16))
        tx.outputs.forEach { output ->
            outputsDigest.update(longToLeBytes(output.amount), 0, 8)
            val scriptBytes = output.scriptPublicKey.scriptPublicKey.hexToBytes()
            outputsDigest.update(longToLeBytes(scriptBytes.size.toLong()), 0, 8)
            outputsDigest.update(scriptBytes, 0, scriptBytes.size)
        }
        val outputsHash = ByteArray(32)
        outputsDigest.doFinal(outputsHash, 0)
        buffer.put(outputsHash)

        buffer.putLong(tx.lockTime)
        buffer.put(tx.subnetworkId.hexToBytes())
        
        // 5. Payload Hash
        if (tx.payload != null) {
            val payloadBytes = tx.payload.hexToBytes()
            val payloadDigest = Blake2bDigest(null, 32, null, "PayloadHash\u0000\u0000\u0000\u0000\u0000".toByteArray().copyOf(16))
            payloadDigest.update(payloadBytes, 0, payloadBytes.size)
            val payloadHash = ByteArray(32)
            payloadDigest.doFinal(payloadHash, 0)
            buffer.put(payloadHash)
        } else {
            buffer.put(ByteArray(32))
        }

        buffer.putInt(inputIndex)

        // 6. Current Input Data
        val currentInput = tx.inputs[inputIndex]
        buffer.put(currentInput.previousOutpoint.transactionId.hexToBytes())
        buffer.putInt(currentInput.previousOutpoint.index)
        
        val scriptBytes = scriptPublicKey.hexToBytes()
        buffer.putLong(scriptBytes.size.toLong())
        buffer.put(scriptBytes)
        
        buffer.putLong(amount)
        buffer.putLong(currentInput.sequence)
        buffer.put(currentInput.sigOpCount.toByte())
        
        buffer.put(0x01.toByte()) // SIGHASH_ALL

        val finalHash = ByteArray(32)
        digest.update(buffer.array(), 0, buffer.position())
        digest.doFinal(finalHash, 0)
        
        return finalHash
    }

    private fun String.hexToBytes(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    private fun intToLeBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
    
    private fun longToLeBytes(value: Long): ByteArray {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }
}
