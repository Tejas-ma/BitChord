package com.music.bitchord.data.backup

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed container every Google Drive backup file is now written in.
 *
 * AES-256-GCM with a fresh data key per file and its own random IV. The data
 * key is itself wrapped under the account's master key, so rotating or binding
 * that key never means re-reading the payload, and two files written for the
 * same account still share no key material. The tag is kept with the
 * ciphertext: a month quietly edited in Drive, or a cover swapped for another,
 * fails to open instead of being restored as if it were real.
 *
 * Layout, written big-endian:
 * ```
 * "BCHORD_ENC_V1"  magic, 13 bytes
 * version          1 byte
 * payload IV       12 bytes
 * wrap IV          12 bytes
 * data key length  1 byte
 * wrapped data key data key length + 16 bytes of tag
 * ciphertext       the rest, tag included
 * ```
 *
 * This object is platform-free on purpose: the phone derives its master key
 * through the Android Keystore and the desktop has no Drive backup, but the
 * format and the maths are shared, so the tests can attack one implementation
 * rather than a copy of it.
 */
object BackupEnvelope {

    private val MAGIC = "BCHORD_ENC_V1".toByteArray(Charsets.US_ASCII)

    private const val VERSION = 1
    private const val DATA_KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8

    /** The bytes do not open: another account's key, or a file that was altered. */
    class Tampered :
        Exception("This backup could not be decrypted. It may belong to another Google account, or the file was altered.")

    /** Whether [stored] was written by [seal] rather than by an older build. */
    fun isEnvelope(stored: ByteArray): Boolean =
        stored.size > MAGIC.size + 1 && MAGIC.indices.all { stored[it] == MAGIC[it] }

    /**
     * Seals [plain] under [masterKey] (32 bytes).
     *
     * [random] exists so a test can pin the IVs; nothing else should pass it.
     */
    fun seal(
        plain: ByteArray,
        masterKey: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(masterKey.size == DATA_KEY_BYTES) { "The master key must be 256 bits" }
        val dataKey = ByteArray(DATA_KEY_BYTES).also(random::nextBytes)
        try {
            val payloadIv = ByteArray(IV_BYTES).also(random::nextBytes)
            val wrapIv = ByteArray(IV_BYTES).also(random::nextBytes)
            val wrapped = gcm(Cipher.ENCRYPT_MODE, masterKey, wrapIv, dataKey)
            val sealed = gcm(Cipher.ENCRYPT_MODE, dataKey, payloadIv, plain)
            return ByteArrayOutputStream(MAGIC.size + 2 + IV_BYTES * 2 + 1 + wrapped.size + sealed.size)
                .also { out ->
                    out.write(MAGIC)
                    out.write(VERSION)
                    out.write(payloadIv)
                    out.write(wrapIv)
                    out.write(DATA_KEY_BYTES)
                    out.write(wrapped)
                    out.write(sealed)
                }
                .toByteArray()
        } finally {
            // The data key exists only for this call; GCM needs nothing back
            // from it afterwards, so it does not outlive the seal.
            dataKey.fill(0)
        }
    }

    /**
     * [stored] opened under [masterKey].
     *
     * A payload that is not an envelope is returned exactly as it came, which
     * is how a backup written before this format is still read. [Tampered] when
     * the header is not one this build writes or either tag fails to verify.
     */
    fun open(stored: ByteArray, masterKey: ByteArray): ByteArray {
        if (!isEnvelope(stored)) return stored
        require(masterKey.size == DATA_KEY_BYTES) { "The master key must be 256 bits" }

        var at = MAGIC.size
        fun byte(): Int {
            if (at >= stored.size) throw Tampered()
            return stored[at++].toInt() and 0xFF
        }

        fun bytes(count: Int): ByteArray {
            if (count < 0 || at + count > stored.size) throw Tampered()
            return stored.copyOfRange(at, at + count).also { at += count }
        }

        if (byte() != VERSION) throw Tampered()
        val payloadIv = bytes(IV_BYTES)
        val wrapIv = bytes(IV_BYTES)
        val keyLength = byte()
        if (keyLength != DATA_KEY_BYTES) throw Tampered()
        val wrapped = bytes(keyLength + TAG_BYTES)
        val ciphertext = bytes(stored.size - at)
        if (ciphertext.size <= TAG_BYTES) throw Tampered()

        val dataKey = try {
            gcm(Cipher.DECRYPT_MODE, masterKey, wrapIv, wrapped)
        } catch (e: GeneralSecurityException) {
            throw Tampered()
        }
        return try {
            gcm(Cipher.DECRYPT_MODE, dataKey, payloadIv, ciphertext)
        } catch (e: GeneralSecurityException) {
            throw Tampered()
        } finally {
            dataKey.fill(0)
        }
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(input)
    }
}

/**
 * The master key a Google Drive backup is sealed with.
 *
 * Derived with HKDF-SHA256 from the connected account's stable identifier, so
 * every device signed into that account arrives at the same 256 bits without a
 * passphrase ever being typed, and a backup on Drive is unreadable to any other
 * account. The phone keeps only a Keystore-wrapped copy; the derivation itself
 * is deterministic, which is what makes a restore on a new device possible.
 *
 * This is confidentiality against the file, not against the account: anyone who
 * can sign in as the account can derive the same key. That is the trade the
 * no-passphrase design makes, and the Settings screen says backups travel with
 * the account rather than promising a secret only the listener holds.
 */
object BackupAccountKey {

    private const val SALT = "bitchord-drive-account-v1"
    private const val INFO = "bitchord-drive-backup-aes-256-gcm-v1"
    private const val LENGTH = 32
    private const val HASH = "HmacSHA256"

    /** 256 bits for [accountKeyId] (a Drive permission id, or the email). */
    fun derive(accountKeyId: String): ByteArray {
        require(accountKeyId.isNotBlank()) { "A Google account identifier is required" }
        // A permission id is numeric and an email may arrive in any case; the
        // key must not depend on which one, or the spelling of it, a device saw.
        val input = accountKeyId.trim().lowercase().toByteArray(Charsets.UTF_8)
        val pseudorandomKey = hmac(SALT.toByteArray(Charsets.UTF_8), input)
        return hmac(pseudorandomKey, INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
            .copyOf(LENGTH)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HASH)
        mac.init(SecretKeySpec(key, HASH))
        return mac.doFinal(data)
    }
}
