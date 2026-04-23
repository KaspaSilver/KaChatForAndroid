package com.kachat.app.services

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Path
import retrofit2.http.Query

// -------------------------------------------------------------------------
// Data transfer objects (DTOs) — match Kaspa REST API response shapes
// Phase 3 will expand these as needed
// -------------------------------------------------------------------------

data class BalanceResponse(
    val address: String,
    val balance: Long   // in sompi (1 KAS = 100_000_000 sompi)
)

data class TransactionResponse(
    val transactionId: String,
    val inputs: List<TransactionInput>,
    val outputs: List<TransactionOutput>,
    val blockTime: Long?,
    val payload: String?  // hex-encoded — contains ciph_msg:1:* for KaChat messages
)

data class TransactionInput(
    val previousOutpoint: Outpoint,
    val signatureScript: String
)

data class TransactionOutput(
    val amount: Long,
    val scriptPublicKey: ScriptPublicKey
)

data class Outpoint(
    val transactionId: String,
    val index: Int
)

data class ScriptPublicKey(
    val scriptPublicKey: String
)

data class UtxoEntry(
    val address: String,
    val outpoint: Outpoint,
    val utxoEntry: UtxoData
)

data class UtxoData(
    val amount: Long,
    val scriptPublicKey: ScriptPublicKey,
    val blockDaaScore: Long,
    val isCoinbase: Boolean
)

// -------------------------------------------------------------------------
// Retrofit interface — Kaspa REST API
// -------------------------------------------------------------------------

interface KaspaRestApi {

    @GET("addresses/{address}/balance")
    suspend fun getBalance(
        @Path("address") address: String
    ): BalanceResponse

    @GET("addresses/{address}/utxos")
    suspend fun getUtxos(
        @Path("address") address: String
    ): List<UtxoEntry>

    @GET("transactions/{txId}")
    suspend fun getTransaction(
        @Path("txId") txId: String,
        @Query("inputs") inputs: Boolean = true
    ): TransactionResponse

    @GET("addresses/{address}/full-transactions")
    suspend fun getTransactions(
        @Path("address") address: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): List<TransactionResponse>

    @POST("transactions")
    suspend fun postTransaction(
        @Body transaction: PostTransactionRequest
    ): PostTransactionResponse

    @GET("info/fee-estimate")
    suspend fun getFeeEstimate(): FeeEstimateResponse
}

data class FeeEstimateResponse(
    val priorityBucket: Double,
    val normalBucket: Double,
    val lowBucket: Double
)

data class PostTransactionRequest(
    val transaction: RawTransaction
)

data class ScriptPublicKeyWithVersion(
    val scriptPublicKey: String,
    val version: Int = 0
)

data class RawOutputWithVersion(
    val amount: Long,
    val scriptPublicKey: ScriptPublicKeyWithVersion
)

data class RawTransaction(
    val version: Int = 0,
    val inputs: List<RawInput>,
    val outputs: List<RawOutputWithVersion>,
    val lockTime: Long = 0,
    val subnetworkId: String = "0000000000000000000000000000000000000000",
    val payload: String? = null // hex-encoded
)

data class RawInput(
    val previousOutpoint: Outpoint,
    val signatureScript: String,
    val sequence: Long = 0,
    val sigOpCount: Int = 1
)

data class RawOutput(
    val amount: Long,
    val scriptPublicKey: ScriptPublicKey
)

data class PostTransactionResponse(
    val transactionId: String
)

// -------------------------------------------------------------------------
// Retrofit interface — Kasia Indexer API
// Phase 3 will add POST for sending messages and registering push addresses
// -------------------------------------------------------------------------

interface KasiaIndexerApi {

    @GET("messages/{address}")
    suspend fun getMessages(
        @Path("address") address: String,
        @Query("after_block") afterBlock: Long? = null
    ): List<TransactionResponse>
}

// -------------------------------------------------------------------------
// Retrofit interface — KNS API
// -------------------------------------------------------------------------

data class KnsResolveResponse(
    val address: String?,
    val name: String?
)

interface KnsApi {

    @GET("names/{domain}/address")
    suspend fun resolveName(
        @Path("domain") domain: String
    ): KnsResolveResponse

    @GET("addresses/{address}/names")
    suspend fun reverseResolve(
        @Path("address") address: String
    ): KnsResolveResponse
}
