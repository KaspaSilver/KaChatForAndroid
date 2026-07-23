package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.PortfolioTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolio_transactions WHERE walletAddress = :walletAddress ORDER BY timestampMillis ASC")
    fun getTransactions(walletAddress: String): Flow<List<PortfolioTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: PortfolioTransactionEntity)

    @Query("DELETE FROM portfolio_transactions WHERE id = :id")
    suspend fun delete(id: String)

    /** One-time claim of pre-wallet-scoping rows (walletAddress = "") for whichever account first loads Portfolio after the upgrade. A no-op once every row has been claimed. */
    @Query("UPDATE portfolio_transactions SET walletAddress = :walletAddress WHERE walletAddress = ''")
    suspend fun claimUnscopedTransactions(walletAddress: String)
}
