package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinkifyTest {

    private fun display(text: String, match: UrlMatch) = text.substring(match.range.first, match.range.last + 1)

    @Test
    fun `no urls in plain text`() {
        assertTrue(TextLinkify.findUrls("hey how are you").isEmpty())
    }

    @Test
    fun `finds a bare scheme url`() {
        val text = "https://kaspa.org"
        val matches = TextLinkify.findUrls(text)
        assertEquals(1, matches.size)
        assertEquals(text, display(text, matches[0]))
        assertEquals(text, matches[0].uri)
    }

    @Test
    fun `finds a url embedded in a sentence`() {
        val text = "check out https://kaspa.org for more info"
        val matches = TextLinkify.findUrls(text)
        assertEquals(1, matches.size)
        assertEquals("https://kaspa.org", display(text, matches[0]))
    }

    @Test
    fun `trailing punctuation is not included in the link`() {
        val text = "visit https://kaspa.org."
        val matches = TextLinkify.findUrls(text)
        assertEquals("https://kaspa.org", display(text, matches[0]))
    }

    @Test
    fun `url in parentheses does not include the closing paren`() {
        val text = "(see https://kaspa.org)"
        val matches = TextLinkify.findUrls(text)
        assertEquals("https://kaspa.org", display(text, matches[0]))
    }

    @Test
    fun `finds multiple urls in one message`() {
        val text = "https://kaspa.org and https://kasia.fyi"
        val matches = TextLinkify.findUrls(text)
        assertEquals(2, matches.size)
        assertEquals("https://kaspa.org", display(text, matches[0]))
        assertEquals("https://kasia.fyi", display(text, matches[1]))
    }

    @Test
    fun `http without s is still matched`() {
        assertEquals(1, TextLinkify.findUrls("http://example.com").size)
    }

    @Test
    fun `a bare domain with no scheme is matched, and gets an https scheme when opened`() {
        val text = "check out youtube.com"
        val matches = TextLinkify.findUrls(text)
        assertEquals(1, matches.size)
        assertEquals("youtube.com", display(text, matches[0]))
        assertEquals("https://youtube.com", matches[0].uri)
    }

    @Test
    fun `a bare domain with a path is matched fully`() {
        val text = "youtube.com/watch?v=abc123"
        val matches = TextLinkify.findUrls(text)
        assertEquals(1, matches.size)
        assertEquals(text, display(text, matches[0]))
    }

    @Test
    fun `a www-prefixed bare domain is matched`() {
        val text = "www.youtube.com"
        val matches = TextLinkify.findUrls(text)
        assertEquals(1, matches.size)
        assertEquals(text, display(text, matches[0]))
    }

    @Test
    fun `a kns dot-kas domain is never linkified`() {
        assertTrue(TextLinkify.findUrls("check out kaspasilver.kas").isEmpty())
    }

    @Test
    fun `ordinary abbreviations are not mistaken for domains`() {
        assertTrue(TextLinkify.findUrls("e.g. this and i.e. that").isEmpty())
    }

    @Test
    fun `a kaspa address is not mistaken for a domain`() {
        assertTrue(TextLinkify.findUrls("kaspa:qq4ntwy65nuqcdmwdz2y2pdya59k8kryhled2gfcavm0yspg55e0grmjx5e7a").isEmpty())
    }
}
