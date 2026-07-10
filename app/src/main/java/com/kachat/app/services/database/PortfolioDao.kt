package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.PortfolioTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolio_transactions ORDER BY timestampMillis ASC")
    fun getTransactions(): Flow<List<PortfolioTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: PortfolioTransactionEntity)

    @Query("DELETE FROM portfolio_transactions WHERE id = :id")
    suspend fun delete(id: String)
}
