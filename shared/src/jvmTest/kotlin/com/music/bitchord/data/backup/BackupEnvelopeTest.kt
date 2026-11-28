package com.music.bitchord.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Drive backup's encryption, attacked directly.
 *
 * The Android side adds a Keystore-wrapped key and a preference file; the
 * format, the key derivation and every tag check live here, so these are the
 * tests that say a backup in Drive cannot be read without the account that
 * wrote it, and cannot be edited without the restore noticing.
 */
class BackupEnvelopeTest {

    private val key = BackupAccountKey.derive("117244000000000000001")

    @Test
    fun `a sealed payload opens with the account it was sealed for`() {
        val plain = "a month of listening".toByteArray()
        val sealed = BackupEnvelope.seal(plain, key)

        assertTrue(BackupEnvelope.isEnvelope(sealed))
        assertFalse(sealed.containsSubsequence(plain), "the payload was written in the clear")
        assertContentEquals(plain, BackupEnvelope.open(sealed, key))
    }

    @Test
    fun `every seal uses a fresh data key and IV`() {
        val plain = ByteArray(64) { it.toByte() }
        val first = BackupEnvelope.seal(plain, key)
        val second = BackupEnvelope.seal(plain, key)

        assertFalse(first.contentEquals(second), "two seals of one payload came out identical")
        assertContentEquals(plain, BackupEnvelope.open(first, key))
        assertContentEquals(plain, BackupEnvelope.open(second, key))
    }

    @Test
    fun `another account's key opens nothing`() {
        val sealed = BackupEnvelope.seal("mine".toByteArray(), key)
        val other = BackupAccountKey.derive("someone-else@example.com")

        assertFailsWith<BackupEnvelope.Tampered> { BackupEnvelope.open(sealed, other) }
    }

    @Test
    fun `a ciphertext byte flipped in Drive fails the restore`() {
        val sealed = BackupEnvelope.seal("month-2026-11".toByteArray(), key)
        sealed[sealed.lastIndex - 4] = (sealed[sealed.lastIndex - 4].toInt() xor 0x01).toByte()

        assertFailsWith<BackupEnvelope.Tampered> { BackupEnvelope.open(sealed, key) }
    }

    @Test
    fun `a truncated file fails rather than restoring part of a month`() {
        val sealed = BackupEnvelope.seal("month-2026-11".toByteArray(), key)

        assertFailsWith<BackupEnvelope.Tampered> { BackupEnvelope.open(sealed.copyOf(sealed.size - 8), key) }
    }

    @Test
    fun `a header from a future version is refused`() {
        val sealed = BackupEnvelope.seal("x".toByteArray(), key)
        // The version byte sits immediately after the 13-byte magic.
        sealed[13] = 99

        assertFailsWith<BackupEnvelope.Tampered> { BackupEnvelope.open(sealed, key) }
    }

    @Test
    fun `bytes written before the envelope are returned unchanged`() {
        val legacy = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0)

        assertFalse(BackupEnvelope.isEnvelope(legacy))
        assertContentEquals(legacy, BackupEnvelope.open(legacy, key))
    }

    @Test
    fun `the account key is 256 bits and deterministic`() {
        val first = BackupAccountKey.derive("117244000000000000001")
        val second = BackupAccountKey.derive("  117244000000000000001 ")

        assertEquals(32, first.size)
        assertContentEquals(first, second, "the key depends on how the account id was spelled")
    }

    @Test
    fun `an email in any case derives the same key, and another account does not`() {
        assertContentEquals(
            BackupAccountKey.derive("Listener@Example.com"),
            BackupAccountKey.derive("listener@example.com"),
        )
        assertFalse(
            BackupAccountKey.derive("listener@example.com")
                .contentEquals(BackupAccountKey.derive("other@example.com")),
        )
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
