package com.kachat.app.services.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.DeletedContactEntity
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
        DeletedContactEntity::class,
    ],
    version = 16,
    exportSchema = true
)
abstract class KaChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun broadcastDao(): BroadcastDao

    companion object {
        /**
         * v15 -> v16: drops `contacts.isArchived` (replaced by full delete + [DeletedContactEntity]
         * tombstones) and adds the new `deleted_contacts` table. v15 is the schema the last public
         * release ("2.0 The Broadcast Update") actually shipped with — without this, anyone
         * updating from that release would silently lose every local contact/message the moment
         * Room's `fallbackToDestructiveMigration` kicked in.
         *
         * SQLite didn't gain `ALTER TABLE ... DROP COLUMN` until 3.35 (2021), and Android's bundled
         * SQLite version varies by OS release, so this uses the portable rebuild-the-table pattern
         * instead: create the new-shape table, copy every row across explicitly (column-by-column,
         * skipping isArchived), drop the old table, rename. The exact resulting `contacts` shape
         * (column names/types/order/PK) is copied verbatim from Room's own generated schema
         * (`app/schemas/.../16.json`), and this was verified end-to-end against a real SQLite
         * engine — a v15-shaped table seeded with sample rows (mixed isArchived/nulls), migrated,
         * checked column-for-column against the expected v16 shape, and confirmed every row's data
         * survived intact — before being written here.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contacts_new` (" +
                        "`id` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `alias` TEXT, `knsName` TEXT, " +
                        "`publicKeyHex` TEXT, `handshakeComplete` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "`conversationStatus` TEXT NOT NULL, `theirAlias` TEXT, `myAlias` TEXT, `knsAvatarUrl` TEXT, " +
                        "`systemContactId` TEXT, `systemContactName` TEXT, `systemContactLinkSource` TEXT, " +
                        "PRIMARY KEY(`id`, `walletAddress`))"
                )
                db.execSQL(
                    "INSERT INTO `contacts_new` (id, walletAddress, alias, knsName, publicKeyHex, handshakeComplete, " +
                        "addedAt, conversationStatus, theirAlias, myAlias, knsAvatarUrl, systemContactId, systemContactName, systemContactLinkSource) " +
                        "SELECT id, walletAddress, alias, knsName, publicKeyHex, handshakeComplete, " +
                        "addedAt, conversationStatus, theirAlias, myAlias, knsAvatarUrl, systemContactId, systemContactName, systemContactLinkSource FROM `contacts`"
                )
                db.execSQL("DROP TABLE `contacts`")
                db.execSQL("ALTER TABLE `contacts_new` RENAME TO `contacts`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_contacts` (" +
                        "`contactId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contactId`, `walletAddress`))"
                )
            }
        }
    }
}
