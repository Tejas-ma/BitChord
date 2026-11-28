package com.music.bitchord.desktop

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Chromium's encrypted cookies on the two POSIX desktops.
 *
 * The format is the same; only the key stretching differs (one round on Linux,
 * 1003 on macOS). These tests build a cookie exactly as each platform's browser
 * would and check that the right reader opens it and the other does not — which
 * is the failure that would otherwise appear as "importing my browser session
 * does nothing" on a Mac.
 */
class DesktopChromiumCookiesTest {

    private val salt = "saltysalt".toByteArray(Charsets.UTF_8)
    // Named for what it is, not `iv`: inside `Cipher.run { }` a bare `iv`
    // resolves to the cipher's own getIV() property, which is null until init.
    private val ivBytes = ByteArray(16) { ' '.code.toByte() }

    @Test
    fun `a Linux v10 cookie opens with the keyring password at one round`() {
        val cookie = seal("SID=linux-session", "keyring-secret", iterations = 1)

        assertEquals(
            "SID=linux-session",
            DesktopBrowserCookies.decryptPosix(
                cookie,
                host = "",
                keyringPassword = "keyring-secret".toByteArray(),
                iterations = 1,
                fallbackPassword = "peanuts".toByteArray(),
            ),
        )
    }

    @Test
    fun `a macOS v10 cookie needs the thousand-and-three round key`() {
        val cookie = seal("SID=mac-session", "keychain-secret", iterations = DesktopBrowserCookies.MAC_ITERATIONS)

        assertEquals(
            "SID=mac-session",
            DesktopBrowserCookies.decryptPosix(
                cookie,
                host = "",
                keyringPassword = "keychain-secret".toByteArray(),
                iterations = DesktopBrowserCookies.MAC_ITERATIONS,
                fallbackPassword = null,
            ),
        )

        // The Linux reader must not open it: that is the bug this parameter exists to stop.
        assertNull(
            DesktopBrowserCookies.decryptPosix(
                cookie,
                host = "",
                keyringPassword = "keychain-secret".toByteArray(),
                iterations = 1,
                fallbackPassword = "peanuts".toByteArray(),
            ),
        )
    }

    @Test
    fun `macOS does not silently fall back to the Linux password`() {
        val cookie = seal("SID=anything", "peanuts", iterations = 1)

        assertNull(
            DesktopBrowserCookies.decryptPosix(
                cookie,
                host = "",
                keyringPassword = null,
                iterations = DesktopBrowserCookies.MAC_ITERATIONS,
                fallbackPassword = null,
            ),
        )
    }

    @Test
    fun `a v11 cookie refuses to open without a keyring password`() {
        val sealed = "v11".toByteArray() + ByteArray(32) { 7 }

        assertNull(
            DesktopBrowserCookies.decryptPosix(
                sealed,
                host = "",
                keyringPassword = null,
                iterations = 1,
                fallbackPassword = "peanuts".toByteArray(),
            ),
        )
    }

    /** The same AES-128-CBC construction Chromium writes, with a v10 prefix. */
    private fun seal(plain: String, password: String, iterations: Int): ByteArray {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 128))
            .encoded
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
            doFinal(plain.toByteArray(Charsets.UTF_8))
        }
        return "v10".toByteArray(Charsets.US_ASCII) + encrypted
    }
}
