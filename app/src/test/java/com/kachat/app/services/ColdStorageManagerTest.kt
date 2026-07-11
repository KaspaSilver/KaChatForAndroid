package com.kachat.app.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ColdStorageManager itself needs a real Android Context (EncryptedSharedPreferences), so —
 * exactly like WalletManagerTest — these tests exercise the stored data shape directly via Gson
 * rather than instantiating the manager.
 */
class ColdStorageManagerTest {

    @Test
    fun `old-shape stored account JSON without maxDerivedIndex deserializes safely with default 0`() {
        val oldShapeJson = """[{"id":"abc-123","name":"My Cold Storage","kpub":"kpub1..."}]"""
        val type = object : TypeToken<List<ColdStorageManager.ColdAccount>>() {}.type
        val accounts: List<ColdStorageManager.ColdAccount> = Gson().fromJson(oldShapeJson, type)

        assertEquals(1, accounts.size)
        assertEquals(0, accounts[0].maxDerivedIndex)
        assertEquals("My Cold Storage", accounts[0].name)
        assertEquals("kpub1...", accounts[0].kpub)
    }

    @Test
    fun `new-shape stored account JSON round-trips maxDerivedIndex correctly`() {
        val gson = Gson()
        val original = ColdStorageManager.ColdAccount(id = "abc-123", name = "Test", kpub = "kpub1...", maxDerivedIndex = 7)
        val json = gson.toJson(listOf(original))
        val type = object : TypeToken<List<ColdStorageManager.ColdAccount>>() {}.type
        val accounts: List<ColdStorageManager.ColdAccount> = gson.fromJson(json, type)

        assertEquals(7, accounts[0].maxDerivedIndex)
    }
}
