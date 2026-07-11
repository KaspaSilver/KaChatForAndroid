package com.kachat.app.services

import org.bitcoinj.crypto.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Regression test for the "crashes whenever you create a wallet" bug: bitcoinj's own
 * `MnemonicCode()` no-arg constructor is documented as "Won't work on Android" — its static
 * initializer's attempt to load the wordlist via `Class.getResourceAsStream` fails silently
 * on Android (the exception is caught and swallowed, see bitcoinj's `MnemonicCode` static
 * init block), leaving `MnemonicCode.INSTANCE` null. Every call site
 * (`MnemonicCode.INSTANCE.toMnemonic(...)`, `MnemonicCode.INSTANCE.check(...)`) then throws
 * a NullPointerException — this is exactly what crashed both wallet creation and seed import.
 *
 * The fix bundles the same wordlist bitcoinj ships internally as an Android asset and
 * initializes `MnemonicCode.INSTANCE` from it directly (see `WalletManager`'s init block).
 * This test verifies the bundled asset file is present, well-formed (2048 words), and matches
 * bitcoinj's own expected digest, independent of Android's AssetManager (plain java.io.File,
 * since Gradle's JVM unit tests run with the module directory as the working directory).
 */
class MnemonicWordlistAssetTest {

    // Same digest bitcoinj computes internally for its own bundled English wordlist
    // (word bytes only, no line separators) — see MnemonicCode.BIP39_ENGLISH_SHA256.
    private val expectedDigest = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db"

    @Test
    fun `bundled wordlist asset loads and matches bitcoinj's expected digest`() {
        val file = File("src/main/assets/bip39-wordlist-english.txt")
        assertNotNull("bip39-wordlist-english.txt must exist in app/src/main/assets", file.takeIf { it.exists() })

        val mnemonicCode = file.inputStream().use { MnemonicCode(it, expectedDigest) }
        assertEquals(2048, mnemonicCode.wordList.size)
    }
}
