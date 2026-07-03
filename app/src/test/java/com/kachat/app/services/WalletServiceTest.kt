package com.kachat.app.services

import org.junit.Assert.assertTrue
import org.junit.Test

class WalletServiceTest {

    @Test
    fun `generated alias is 12 lowercase hex characters, matching the real protocol format`() {
        repeat(20) {
            val alias = WalletService.generateAlias()
            assertTrue("alias '$alias' must be 12 lowercase hex chars", alias.matches(Regex("^[0-9a-f]{12}$")))
        }
    }

    @Test
    fun `generated aliases are not all identical`() {
        val aliases = (1..10).map { WalletService.generateAlias() }.toSet()
        assertTrue("expected randomness across generated aliases", aliases.size > 1)
    }
}
