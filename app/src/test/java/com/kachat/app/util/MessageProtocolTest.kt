package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

class MessageProtocolTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    @Test
    fun `comm payload builds and parses back to the original alias and message`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)

        val encrypted = MessageProtocol.encrypt("hey there", recipientPub)
        val payloadBytes = MessageProtocol.buildCommPayload("alice", encrypted)

        assertTrue(MessageProtocol.isKaChatPayload(payloadBytes))
        assertTrue(String(payloadBytes, Charsets.UTF_8).startsWith("ciph_msg:1:comm:alice:"))

        val (alias, parsedEncrypted) = MessageProtocol.parseCommPayload(payloadBytes)!!
        assertEquals("alice", alias)
        assertEquals("hey there", MessageProtocol.decrypt(parsedEncrypted, recipientPriv))
    }

    @Test
    fun `handshake payload is raw binary after the ascii prefix, not base64`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)

        val json = """{"type":"handshake","alias":"bob"}"""
        val encrypted = MessageProtocol.encrypt(json, recipientPub)
        val payloadBytes = MessageProtocol.buildHandshakePayload(encrypted)

        assertTrue(MessageProtocol.isHandshakePayload(payloadBytes))

        val parsed = MessageProtocol.parseHandshakePayload(payloadBytes)!!
        assertEquals(json, MessageProtocol.decrypt(parsed, recipientPriv))
    }

    @Test
    fun `alias containing a colon is sanitized so parsing stays unambiguous`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)

        val encrypted = MessageProtocol.encrypt("msg", recipientPub)
        val payloadBytes = MessageProtocol.buildCommPayload("bad:alias", encrypted)

        val (alias, _) = MessageProtocol.parseCommPayload(payloadBytes)!!
        assertEquals("bad_alias", alias)
    }

    @Test
    fun `non ciph_msg payload is not recognized`() {
        assertNull(MessageProtocol.parseCommPayload("not a payload".toByteArray()))
        assertTrue(!MessageProtocol.isKaChatPayload("random bytes".toByteArray()))
    }
}
