package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingScreenTest {

    @Test
    fun `splits on single spaces`() {
        assertEquals(listOf("abandon", "ability", "able"), parseSeedPhraseWords("abandon ability able"))
    }

    @Test
    fun `splits on newlines and multiple spaces from a paste`() {
        assertEquals(listOf("abandon", "ability", "able"), parseSeedPhraseWords("abandon\nability   able"))
    }

    @Test
    fun `lowercases every word`() {
        assertEquals(listOf("abandon", "ability"), parseSeedPhraseWords("Abandon ABILITY"))
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals(listOf("abandon", "ability"), parseSeedPhraseWords("  abandon ability  "))
    }

    @Test
    fun `blank input produces an empty list`() {
        assertEquals(emptyList<String>(), parseSeedPhraseWords(""))
        assertEquals(emptyList<String>(), parseSeedPhraseWords("   "))
    }

    @Test
    fun `counts exactly 12 and 24 words correctly`() {
        val twelve = (1..12).joinToString(" ") { "word$it" }
        val twentyFour = (1..24).joinToString(" ") { "word$it" }
        assertEquals(12, parseSeedPhraseWords(twelve).size)
        assertEquals(24, parseSeedPhraseWords(twentyFour).size)
    }
}
