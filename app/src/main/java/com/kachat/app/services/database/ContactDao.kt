package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isArchived = 0")
    fun getActiveContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isArchived = 1")
    fun getArchivedContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("UPDATE contacts SET isArchived = :isArchived WHERE id = :id")
    suspend fun updateArchivedStatus(id: String, isArchived: Boolean)

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getContact(id: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)
}
