package com.kachat.app.services

import com.kachat.app.models.ChatHistoryArchiveMessage
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryExportImportServiceTest {

    private val myAddress = "kaspa:me"
    private val contactAddress = "kaspa:them"

    private fun message(
        id: String = "txid1",
        type: String = MessageProtocol.TYPE_COMM,
        direction: String = "sent",
        deliveryStatus: String = "sent"
    ) = MessageEntity(
        id = id,
        contactId = contactAddress,
        walletAddress = myAddress,
        type = type,
        direction = direction,
        plaintextBody = "hello",
        encryptedPayload = "deadbeef",
        amountSompi = null,
        blockTimestamp = 1_700_000_000_000L,
        isRead = false,
        deliveryStatus = deliveryStatus
    )

    // --- type mapping ---------------------------------------------------------------

    @Test
    fun `entity type maps to the matching archive type`() {
        assertEquals("handshake", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_HANDSHAKE))
        assertEquals("contextual", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_COMM))
        assertEquals("payment", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_PAY))
    }

    @Test
    fun `archive type maps back to the matching entity type`() {
        assertEquals(MessageProtocol.TYPE_HANDSHAKE, ChatHistoryExportImportService.entityMessageType("handshake"))
        assertEquals(MessageProtocol.TYPE_COMM, ChatHistoryExportImportService.entityMessageType("contextual"))
        assertEquals(MessageProtocol.TYPE_PAY, ChatHistoryExportImportService.entityMessageType("payment"))
    }

    @Test
    fun `an iOS-only audio type imports as a regular contextual message`() {
        assertEquals(MessageProtocol.TYPE_COMM, ChatHistoryExportImportService.entityMessageType("audio"))
    }

    // --- toArchiveMessage -------------------------------------------------------------

    @Test
    fun `an outgoing message maps sender to me and receiver to the contact`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(direction = "sent"), myAddress)
        assertEquals(myAddress, archived.senderAddress)
        assertEquals(contactAddress, archived.receiverAddress)
        assertTrue(archived.isOutgoing)
    }

    @Test
    fun `an incoming message maps sender to the contact and receiver to me`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(direction = "received"), myAddress)
        assertEquals(contactAddress, archived.senderAddress)
        assertEquals(myAddress, archived.receiverAddress)
        assertTrue(!archived.isOutgoing)
    }

    @Test
    fun `id and txId both carry the entity's own id, since Android does not distinguish them`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(id = "abc123"), myAddress)
        assertEquals("abc123", archived.id)
        assertEquals("abc123", archived.txId)
    }

    // --- toMessageEntity ---------------------------------------------------------------

    private fun archiveMessage(
        isOutgoing: Boolean = true,
        deliveryStatus: String = "sent",
        messageType: String = "contextual"
    ) = ChatHistoryArchiveMessage(
        id = "txid1",
        txId = "txid1",
        senderAddress = if (isOutgoing) myAddress else contactAddress,
        receiverAddress = if (isOutgoing) contactAddress else myAddress,
        content = "hello",
        timestamp = "2023-11-14T22:13:20Z",
        blockTime = 1_700_000_000_000L,
        isOutgoing = isOutgoing,
        messageType = messageType,
        deliveryStatus = deliveryStatus
    )

    @Test
    fun `imported messages are always marked read, since the archive has no per-message read state`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = false), contactAddress, myAddress)
        assertTrue(entity.isRead)
    }

    @Test
    fun `outgoing archive message becomes a sent-direction entity`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = true), contactAddress, myAddress)
        assertEquals("sent", entity.direction)
    }

    @Test
    fun `incoming archive message becomes a received-direction entity`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = false), contactAddress, myAddress)
        assertEquals("received", entity.direction)
    }

    @Test
    fun `an iOS warning delivery status is preserved as-is`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(deliveryStatus = "warning"), contactAddress, myAddress)
        assertEquals("warning", entity.deliveryStatus)
    }

    @Test
    fun `an unrecognized delivery status falls back to sent rather than crashing`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(deliveryStatus = "bogus"), contactAddress, myAddress)
        assertEquals("sent", entity.deliveryStatus)
    }

    @Test
    fun `entity always attaches to the currently active wallet address, not any address in the archive`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(), contactAddress, myAddress)
        assertEquals(myAddress, entity.walletAddress)
    }
}
