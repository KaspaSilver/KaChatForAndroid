package com.kachat.app.services

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deserialization tests against a real response captured live from
 * https://indexer.kasia.fyi/handshakes/by-receiver — confirms the DTOs match the
 * actual snake_case field names/shape, not a guessed/documented-only schema.
 */
class KasiaIndexerApiTest {

    private val gson = Gson()

    @Test
    fun `parses a real captured handshakes-by-receiver response`() {
        val json = """
            [
                {
                    "tx_id": "80cdbc4fe2a77b1ed507c0091eced627a9732d203964efeac189e8cdf9c8cb43",
                    "sender": "kaspa:qqfz0lftl35y6zfktwt9ywgf403chfjw5hqt7fwsy7tzgh7d8wzdy7ew48n0t",
                    "receiver": "kaspa:qrjymtgplw754wpk29acmcpp5rfhdctst5xjnsqpdldlzyar2c095uqctm634",
                    "block_time": 1782923632026,
                    "accepting_block": "455ea3c181d584c0b6c2362a9dcc1b77359f61f7efcb1bfbf898c9a36a8a654b",
                    "accepting_daa_score": 475037015,
                    "message_payload": "f0313b15fd24f2ece378ca4d030711ba29bdbfdfc7590b5a8467874bff9c08f"
                }
            ]
        """.trimIndent()

        val parsed = gson.fromJson(json, Array<HandshakeIndexerResponse>::class.java).toList()

        assertEquals(1, parsed.size)
        assertEquals("80cdbc4fe2a77b1ed507c0091eced627a9732d203964efeac189e8cdf9c8cb43", parsed[0].txId)
        assertEquals("kaspa:qqfz0lftl35y6zfktwt9ywgf403chfjw5hqt7fwsy7tzgh7d8wzdy7ew48n0t", parsed[0].sender)
        assertEquals("kaspa:qrjymtgplw754wpk29acmcpp5rfhdctst5xjnsqpdldlzyar2c095uqctm634", parsed[0].receiver)
        assertEquals(1782923632026L, parsed[0].blockTime)
        assertEquals("f0313b15fd24f2ece378ca4d030711ba29bdbfdfc7590b5a8467874bff9c08f", parsed[0].messagePayload)
    }

    @Test
    fun `parses a contextual-messages-by-sender response with an alias field`() {
        val json = """
            [
                {
                    "tx_id": "abc123",
                    "sender": "kaspa:qqfz0lftl35y6zfktwt9ywgf403chfjw5hqt7fwsy7tzgh7d8wzdy7ew48n0t",
                    "alias": "alice",
                    "block_time": 1782923999000,
                    "message_payload": "deadbeef"
                }
            ]
        """.trimIndent()

        val parsed = gson.fromJson(json, Array<ContextualMessageIndexerResponse>::class.java).toList()

        assertEquals(1, parsed.size)
        assertEquals("abc123", parsed[0].txId)
        assertEquals("alice", parsed[0].alias)
        assertEquals("deadbeef", parsed[0].messagePayload)
    }
}
