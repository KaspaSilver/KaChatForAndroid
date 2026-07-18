package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.SwapTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SwapDao {
    @Query("SELECT * FROM swap_transactions ORDER BY createdAtMillis DESC")
    fun getSwaps(): Flow<List<SwapTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(swap: SwapTransactionEntity)

    @Query("UPDATE swap_transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE swap_transactions SET kasSendTxId = :txId WHERE id = :id")
    suspend fun setKasSendTxId(id: String, txId: String)

    @Query("UPDATE swap_transactions SET addedToPortfolio = 1 WHERE id = :id")
    suspend fun markAddedToPortfolio(id: String)

    @Query("DELETE FROM swap_transactions WHERE id = :id")
    suspend fun delete(id: String)
}
