package com.kachat.app.util

/** A detected link: [range] is where it sits in the original text, [uri] is what to actually open (scheme added if the displayed text didn't have one). */
data class UrlMatch(val range: IntRange, val uri: String)

/** Finds every URL-shaped substring in a message, no Compose/Android dependency so it's directly unit-testable. */
object TextLinkify {
    // Deliberately excludes ".kas" — that's a KNS domain identifier, not a real web address;
    // opening it as a URL would just fail. Covers common general + this app's own ecosystem TLDs.
    private const val TLDS = "com|org|net|io|co|dev|app|xyz|info|biz|me|tv|gg|ai|edu|gov|us|uk|ca|de|fr|jp|cn|ru|in|au|br|link|shop|store|tech|online|site|fyi|wtf"

    private const val LABEL = """[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"""

    private val URL_REGEX = Regex(
        """https?://\S+|(?:www\.)?$LABEL(?:\.$LABEL)*\.(?:$TLDS)\b(?:/\S*)?""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', '\'', '"', ')', ']', '}', '>')

    fun findUrls(text: String): List<UrlMatch> {
        return URL_REGEX.findAll(text).mapNotNull { match ->
            var end = match.range.last
            while (end >= match.range.first && text[end] in TRAILING_PUNCTUATION) end--
            if (end < match.range.first) return@mapNotNull null

            val range = match.range.first..end
            val display = text.substring(range.first, range.last + 1)
            val uri = if (display.contains("://")) display else "https://$display"
            UrlMatch(range, uri)
        }.toList()
    }
}
