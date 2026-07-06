package com.kachat.app.services.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.HiddenBroadcastSenderEntity
import com.kachat.app.models.MessageEntity

/**
 * Room database — local persistence layer.
 *
 * Equivalent to the Core Data stack in the iOS app.
 * Phase 4 will add DAOs for messages and contacts.
 * Phase 8 will add sync metadata for multi-device support.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        BroadcastChannelEntity::class,
        BroadcastMessageEntity::class,
        HiddenBroadcastSenderEntity::class,
    ],
    version = 15,
    exportSchema = true
)
abstract class KaChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun broadcastDao(): BroadcastDao
}
