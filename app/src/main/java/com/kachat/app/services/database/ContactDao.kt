package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isArchived = 0 AND walletAddress = :walletAddress")
    fun getActiveContacts(walletAddress: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isArchived = 1 AND walletAddress = :walletAddress")
    fun getArchivedContacts(walletAddress: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE walletAddress = :walletAddress")
    fun getAllContacts(walletAddress: String): Flow<List<ContactEntity>>

    @Query("UPDATE contacts SET isArchived = :isArchived WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun updateArchivedStatus(id: String, walletAddress: String, isArchived: Boolean)

    @Query("SELECT * FROM contacts WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun getContact(id: String, walletAddress: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE conversationStatus = :status AND isArchived = 0 AND walletAddress = :walletAddress")
    suspend fun getContactsByStatus(status: String, walletAddress: String): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    /** Every contact for this wallet, gone — used when wiping an entire account. */
    @Query("DELETE FROM contacts WHERE walletAddress = :walletAddress")
    suspend fun deleteAllForWallet(walletAddress: String)
}
