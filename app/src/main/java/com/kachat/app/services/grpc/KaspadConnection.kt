package com.kachat.app.services.grpc

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.kachat.app.services.RawTransaction
import protowire.Messages
import protowire.RPCGrpcKt
import protowire.Rpc
import protowire.getBlockDagInfoRequestMessage
import protowire.getInfoRequestMessage
import protowire.getPeerAddressesRequestMessage
import protowire.kaspadRequest
import protowire.rpcOutpoint
import protowire.rpcScriptPublicKey
import protowire.rpcTransaction
import protowire.rpcTransactionInput
import protowire.rpcTransactionOutput
import protowire.submitTransactionRequestMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A single plaintext gRPC connection to one Kaspa node ("ip:port"), wrapping the
 * node's single bidirectional-streaming `MessageStream` RPC. Requests/responses
 * are multiplexed over that one stream by a client-assigned request id, matching
 * the real Kaspa RPC protocol (`protowire.RPC` service, see rpc.proto/messages.proto).
 *
 * One connection is opened per probe and closed immediately after — see
 * NodeProfiler.probeNode(). This trades a little per-cycle connection setup cost
 * for much simpler state management, acceptable for a status-display's call volume.
 */
class KaspadConnection internal constructor(
    private val scope: CoroutineScope,
    private val channel: ManagedChannel
) {
    constructor(address: String, scope: CoroutineScope) : this(
        scope,
        ManagedChannelBuilder.forTarget(address).usePlaintext().build()
    )

    private val stub = RPCGrpcKt.RPCCoroutineStub(channel)

    private val outbound = Channel<Messages.KaspadRequest>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Messages.KaspadResponse>>()
    private val nextId = AtomicLong(1)

    private var streamJob: Job? = null

    fun connect() {
        streamJob = scope.launch {
            try {
                stub.messageStream(outbound.receiveAsFlow()).collect { response ->
                    pending.remove(response.id)?.complete(response)
                    // Unsolicited notifications (no matching pending id) are ignored in v1 —
                    // no UTXO/block-added subscriptions are in scope for the connection-status feature.
                }
            } catch (e: Exception) {
                pending.values.forEach { it.completeExceptionally(e) }
                pending.clear()
            }
        }
    }

    /** Exposed for testing the id-multiplexing/timeout-cleanup behavior directly. */
    internal val pendingCount: Int get() = pending.size

    internal suspend fun <T> call(
        timeoutMs: Long = 5000,
        build: (id: Long) -> Messages.KaspadRequest,
        extract: (Messages.KaspadResponse) -> T
    ): T {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Messages.KaspadResponse>()
        pending[id] = deferred
        try {
            outbound.send(build(id))
            return withTimeout(timeoutMs) { extract(deferred.await()) }
        } finally {
            // Must clean up on timeout/cancellation too, not just on success — otherwise a
            // node that never responds leaks a CompletableDeferred into `pending` forever.
            pending.remove(id)
        }
    }

    suspend fun getInfo(): Rpc.GetInfoResponseMessage = call(
        build = { id -> kaspadRequest { this.id = id; getInfoRequest = getInfoRequestMessage {} } },
        extract = { it.getInfoResponse }
    )

    suspend fun getBlockDagInfo(): Rpc.GetBlockDagInfoResponseMessage = call(
        build = { id -> kaspadRequest { this.id = id; getBlockDagInfoRequest = getBlockDagInfoRequestMessage {} } },
        extract = { it.getBlockDagInfoResponse }
    )

    suspend fun getPeerAddresses(): Rpc.GetPeerAddressesResponseMessage = call(
        build = { id -> kaspadRequest { this.id = id; getPeerAddressesRequest = getPeerAddressesRequestMessage {} } },
        extract = { it.getPeerAddressesResponse }
    )

    /**
     * Broadcasts a signed transaction via direct gRPC SubmitTransaction, bypassing the
     * REST gateway entirely. The REST `POST /transactions` endpoint (api.kaspa.org)
     * works fine for plain payments but rejects payload-carrying transactions with a
     * false "signature script" failure — the real signature is cryptographically valid
     * (verified against official rusty-kaspa test vectors), so the bug is somewhere in
     * the REST gateway's JSON-to-RPC translation of the payload field, not in the
     * transaction itself. The iOS reference app (KaChat) never uses REST for broadcast
     * either — it submits exclusively over gRPC, so this matches the proven-working path.
     */
    suspend fun submitTransaction(tx: RawTransaction, allowOrphan: Boolean = false): String {
        val rpcTx = rpcTransaction {
            version = tx.version
            inputs.addAll(
                tx.inputs.map { input ->
                    rpcTransactionInput {
                        previousOutpoint = rpcOutpoint {
                            transactionId = input.previousOutpoint.transactionId
                            index = input.previousOutpoint.index
                        }
                        signatureScript = input.signatureScript
                        sequence = input.sequence
                        sigOpCount = input.sigOpCount
                    }
                }
            )
            outputs.addAll(
                tx.outputs.map { output ->
                    rpcTransactionOutput {
                        amount = output.amount
                        scriptPublicKey = rpcScriptPublicKey {
                            version = output.scriptPublicKey.version
                            scriptPublicKey = output.scriptPublicKey.scriptPublicKey
                        }
                    }
                }
            )
            lockTime = tx.lockTime
            subnetworkId = tx.subnetworkId
            gas = tx.gas
            tx.payload?.let { payload = it }
        }

        val response = call(
            timeoutMs = 15000,
            build = { id ->
                kaspadRequest {
                    this.id = id
                    submitTransactionRequest = submitTransactionRequestMessage {
                        transaction = rpcTx
                        this.allowOrphan = allowOrphan
                    }
                }
            },
            extract = { it.submitTransactionResponse }
        )

        if (response.hasError()) {
            throw IllegalStateException(response.error.message)
        }
        return response.transactionId
    }

    fun close() {
        streamJob?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        channel.shutdownNow()
    }
}
