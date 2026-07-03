package com.kachat.app.viewmodels

import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {

    private fun message(type: String, direction: String, deliveryStatus: String = "sent") = MessageEntity(
        id = "$type-$direction-${System.identityHashCode(Any())}",
        contactId = "kaspa:test",
        walletAddress = "kaspa:me",
        type = type,
        direction = direction,
        plaintextBody = "text",
        encryptedPayload = "deadbeef",
        amountSompi = null,
        blockTimestamp = 0L,
        deliveryStatus = deliveryStatus
    )

    @Test
    fun `no warning before any message has been sent`() {
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(emptyList()))
    }

    @Test
    fun `warning shows after sending a comm message with no handshake and no reply`() {
        val messages = listOf(message(MessageProtocol.TYPE_COMM, "sent"))
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `warning clears once they reply`() {
        val messages = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_COMM, "received")
        )
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `warning clears once a handshake exists in either direction`() {
        val sentHandshake = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_HANDSHAKE, "sent")
        )
        val receivedHandshake = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_HANDSHAKE, "received")
        )
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(sentHandshake))
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(receivedHandshake))
    }

    @Test
    fun `warning clears once a payment has ever been exchanged`() {
        val messages = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_PAY, "received")
        )
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `retry is offered for a failed sent comm message`() {
        val failed = message(MessageProtocol.TYPE_COMM, "sent", deliveryStatus = "failed")
        assertTrue(ChatViewModel.shouldShowRetryOption(failed))
    }

    @Test
    fun `retry is not offered for a sent message that is still pending`() {
        val pending = message(MessageProtocol.TYPE_COMM, "sent", deliveryStatus = "pending")
        assertFalse(ChatViewModel.shouldShowRetryOption(pending))
    }

    @Test
    fun `retry is not offered for a successfully sent message`() {
        val sent = message(MessageProtocol.TYPE_COMM, "sent", deliveryStatus = "sent")
        assertFalse(ChatViewModel.shouldShowRetryOption(sent))
    }

    @Test
    fun `retry is never offered for a failed payment`() {
        val failedPayment = message(MessageProtocol.TYPE_PAY, "sent", deliveryStatus = "failed")
        assertFalse(ChatViewModel.shouldShowRetryOption(failedPayment))
    }

    @Test
    fun `retry is never offered for a received message regardless of status`() {
        val received = message(MessageProtocol.TYPE_COMM, "received", deliveryStatus = "failed")
        assertFalse(ChatViewModel.shouldShowRetryOption(received))
    }

    @Test
    fun `alias can be auto-set to a domain when it was never set`() {
        assertTrue(ChatViewModel.canAutoUpdateAliasToDomain(null))
    }

    @Test
    fun `a real custom nickname is never auto-overwritten`() {
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("Mom"))
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("My Best Friend"))
    }

    @Test
    fun `once any domain is associated with a contact, auto-refresh never touches it again`() {
        // Regression: previously this returned true because "oldname.kas" still "looks like" a
        // domain, so refreshKnsNamesForAllContacts would silently revert an explicit non-primary
        // domain selection back to the contact's on-chain primary.
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("oldname.kas", knsName = "oldname.kas"))
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("chosen.kas", knsName = "chosen.kas"))
    }

    @Test
    fun `a contact linked to a system contact is never auto-overwritten, even with no alias`() {
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain(null, systemContactId = "lookup-key-1"))
    }

    @Test
    fun `a contact linked to a system contact is never auto-overwritten, even if the alias looks like a kns domain`() {
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("oldname.kas", systemContactId = "lookup-key-1"))
    }
}
