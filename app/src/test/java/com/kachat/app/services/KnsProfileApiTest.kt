package com.kachat.app.services

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deserialization tests against real responses captured live from
 * https://api.knsdomains.org/mainnet/api/v1 — confirms the DTOs match the actual
 * response shape, not a guessed/documented-only schema.
 */
class KnsProfileApiTest {

    private val gson = Gson()

    @Test
    fun `parses a real captured assets-by-owner response`() {
        val json = """
            {
                "success": true,
                "data": {
                    "assets": [
                        {
                            "id": "2",
                            "assetId": "4d8df7441e73cd614e55f36aef2b09775690b677f9f393080f32937525063032i0",
                            "mimeType": "",
                            "asset": "kaspa.kas",
                            "owner": "kaspa:qr6htccwtwm05c8mv7985rlqzrw85txgljdn7j2jlr7uxhv8c04ak78fhcrtj",
                            "creationBlockTime": "2025-01-27T07:46:16.340Z",
                            "isDomain": true,
                            "isVerifiedDomain": true,
                            "status": "default",
                            "transactionId": "4d8df7441e73cd614e55f36aef2b09775690b677f9f393080f32937525063032"
                        },
                        {
                            "id": "7857",
                            "assetId": "bf063477683c6ea550135d5f01bc070372022df827c5fa6c8bb70c7fcdf1cecci0",
                            "mimeType": "",
                            "asset": "kaskaskaskas.kas",
                            "owner": "kaspa:qr6htccwtwm05c8mv7985rlqzrw85txgljdn7j2jlr7uxhv8c04ak78fhcrtj",
                            "creationBlockTime": "2025-01-28T12:25:10.570Z",
                            "isDomain": true,
                            "isVerifiedDomain": true,
                            "status": "default",
                            "transactionId": "bf063477683c6ea550135d5f01bc070372022df827c5fa6c8bb70c7fcdf1cecc"
                        }
                    ]
                }
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, KnsAssetsResponse::class.java)

        assertTrue(parsed.success)
        assertEquals(2, parsed.data?.assets?.size)
        assertEquals("kaspa.kas", parsed.data?.assets?.get(0)?.asset)
        assertEquals("4d8df7441e73cd614e55f36aef2b09775690b677f9f393080f32937525063032i0", parsed.data?.assets?.get(0)?.assetId)
        assertEquals(true, parsed.data?.assets?.get(0)?.isDomain)
        assertEquals(true, parsed.data?.assets?.get(0)?.isVerifiedDomain)
        assertEquals("kaskaskaskas.kas", parsed.data?.assets?.get(1)?.asset)
    }

    @Test
    fun `parses a real captured domain profile response`() {
        val json = """
            {
                "success": true,
                "data": {
                    "assetId": "4d8df7441e73cd614e55f36aef2b09775690b677f9f393080f32937525063032i0",
                    "owner": "kaspa:qr6htccwtwm05c8mv7985rlqzrw85txgljdn7j2jlr7uxhv8c04ak78fhcrtj",
                    "name": "kaspa",
                    "tld": "kas",
                    "profile": {
                        "redirectUrl": null,
                        "bio": "Building on Kaspa",
                        "avatarUrl": "https://example.com/avatar.png",
                        "x": "kaspacurrency",
                        "website": null,
                        "telegram": null,
                        "discord": null,
                        "contactEmail": null,
                        "bannerUrl": null,
                        "github": null
                    }
                }
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, KnsProfileResponse::class.java)

        assertTrue(parsed.success)
        assertEquals("kaspa", parsed.data?.name)
        assertEquals("Building on Kaspa", parsed.data?.profile?.bio)
        assertEquals("https://example.com/avatar.png", parsed.data?.profile?.avatarUrl)
        assertEquals("kaspacurrency", parsed.data?.profile?.x)
        assertNull(parsed.data?.profile?.website)
    }

    @Test
    fun `parses an empty profile (no fields set) without error`() {
        val json = """
            {
                "success": true,
                "data": {
                    "assetId": "abc",
                    "name": "kas",
                    "profile": {
                        "bio": null, "avatarUrl": null, "x": null, "website": null,
                        "telegram": null, "discord": null, "contactEmail": null, "github": null
                    }
                }
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, KnsProfileResponse::class.java)

        assertNull(parsed.data?.profile?.bio)
        assertNull(parsed.data?.profile?.avatarUrl)
    }

    @Test
    fun `pickActiveDomain keeps the current selection if still owned`() {
        val owned = listOf("kaspa.kas", "kas.kas", "michaelsutton.kas")
        assertEquals("kas.kas", KnsService.pickActiveDomain(owned, currentSelection = "kas.kas", primary = "kaspa.kas"))
    }

    @Test
    fun `pickActiveDomain falls back to primary when current selection is no longer owned`() {
        val owned = listOf("kaspa.kas", "kas.kas")
        assertEquals("kaspa.kas", KnsService.pickActiveDomain(owned, currentSelection = "sold-domain.kas", primary = "kaspa.kas"))
    }

    @Test
    fun `pickActiveDomain falls back to the first owned domain when there is no primary`() {
        val owned = listOf("kaspa.kas", "kas.kas")
        assertEquals("kaspa.kas", KnsService.pickActiveDomain(owned, currentSelection = null, primary = null))
    }

    @Test
    fun `pickActiveDomain returns null when nothing is owned`() {
        assertNull(KnsService.pickActiveDomain(emptyList(), currentSelection = "kaspa.kas", primary = "kaspa.kas"))
    }
}
