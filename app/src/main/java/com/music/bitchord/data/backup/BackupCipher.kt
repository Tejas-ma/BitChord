package com.music.bitchord.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption around every file BitChord puts in Google Drive.
 *
 * AES-256-GCM with a fresh random IV per file and the tag kept with the
 * ciphertext, so a payload that was altered in Drive — a month quietly edited,
 * a cover swapped — fails to open rather than being restored as if it were
 * real. The key comes from the listener's passphrase through PBKDF2-HMAC-SHA256
 * with a random salt, and the work factor travels in the file's own header so a
 * backup written by a build with a different iteration count still opens.
 *
 * Every encrypted file starts with [MAGIC]. A payload that does not is one of
 * this app's older, unencrypted gzipped backups: it is returned exactly as it
 * came, which is how a restore from before encryption keeps working.
 *
 * With no passphrase set — the default — [seal] returns the payload unchanged,
 * so a listener who has not opted in keeps the upload/download/restore
 * behaviour they had, including restoring on a new phone with nothing to type.
 * That is the fallback, and it is deliberately not silent: Settings says which
 * of the two modes the account is in.
 */
object BackupCipher {

    private const val PREFS = "bitchord_backup_cipher"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_SALT = "salt"
    private const val KEY_ITERATIONS = "iterations"
    private const val KEY_ACCOUNT_ID = "account_key_id"
    private const val KEY_ACCOUNT_WRAPPED = "account_wrapped_key"

    /** Shortest passphrase accepted: long enough to be worth a KDF at all. */
    const val MIN_PASSPHRASE_LENGTH = 8

    private const val VERSION = 1
    private const val KDF_PBKDF2_SHA256 = 1
    private const val DEFAULT_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** "BCHRBK01": what says the bytes behind it were sealed by this object. */
    private val MAGIC = "BCHRBK01".toByteArray(Charsets.US_ASCII)

    /** The payload is encrypted and no passphrase has been given for this run. */
    class PassphraseRequired :
        Exception("This backup is encrypted. Enter its passphrase to continue.")

    /** The tag did not verify: a wrong passphrase, or a file that was altered or truncated. */
    class PassphraseRejected :
        Exception("That passphrase did not unlock the backup, or the file is damaged.")

    /** The header itself is not a shape this build writes. */
    class UnreadableBackup :
        Exception("The backup file could not be read")

    /**
     * A file sealed with an account key, and no Drive account connected yet.
     *
     * The key is derived from the account, so connecting the account that made
     * the file is the whole answer — there is nothing to type.
     */
    class AccountKeyRequired :
        Exception("This backup is encrypted with its Google account. Connect that account to restore it.")

    /** The account key opened nothing: another account, or a file that was altered. */
    class DecryptionFailed :
        Exception("This backup could not be decrypted. It may belong to another Google account, or the file was altered.")

    private var prefs: SharedPreferences? = null

    /**
     * A passphrase being tried for this run, before it has opened a file.
     *
     * Kept apart from the stored one so a wrong guess never replaces a
     * passphrase that works: only a file that actually decrypted with it can
     * promote it to the account's stored passphrase.
     */
    private var pending: String? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val store: SharedPreferences
        get() = checkNotNull(prefs) { "BackupCipher.install was not called" }

    /** Whether a passphrase is set — the optional layer older backups may carry. */
    fun isEnabled(): Boolean = !store.getString(KEY_PASSPHRASE, null).isNullOrBlank()

    /** Whether the connected Drive account's key seals new uploads. */
    fun hasAccountKey(): Boolean = !store.getString(KEY_ACCOUNT_ID, null).isNullOrBlank()

    /** Whether anything at all is encrypting uploads right now. */
    fun encryptionActive(): Boolean = hasAccountKey() || isEnabled()

    /**
     * Binds this install to [accountKeyId] — the Drive account's stable id — so
     * uploads are sealed with the key derived from it.
     *
     * Called when an account is connected and again on every start, because the
     * wrapped copy may have been dropped with the app's data while the account
     * grant was restored. Passing null forgets the key entirely; the files it
     * sealed still open for whichever account is connected next.
     */
    fun setAccountKeyId(accountKeyId: String?) {
        val id = accountKeyId?.trim()?.takeIf { it.isNotEmpty() }
        val current = store.getString(KEY_ACCOUNT_ID, null)
        if (id == current) return
        store.edit {
            if (id == null) {
                remove(KEY_ACCOUNT_ID)
                remove(KEY_ACCOUNT_WRAPPED)
            } else {
                putString(KEY_ACCOUNT_ID, id)
                // A different account's blob is useless, and leaving it would
                // mean unwrapping it against the wrong key on every read.
                remove(KEY_ACCOUNT_WRAPPED)
            }
        }
    }

    /** Forgets the account key: a disconnect, or an account that changed hands. */
    fun clearAccountKey() = setAccountKeyId(null)

    /**
     * The account's 256-bit master key, rebuilt when the wrapped copy is gone.
     *
     * Null when no account is bound. The derivation is deterministic, so a lost
     * or unreadable Keystore entry costs one HKDF rather than a lost backup.
     */
    private fun accountMasterKey(): ByteArray? {
        val id = store.getString(KEY_ACCOUNT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        store.getString(KEY_ACCOUNT_WRAPPED, null)?.fromHex()?.let { blob ->
            AccountKeyVault.unwrap(blob)?.let { return it }
        }
        val derived = BackupAccountKey.derive(id)
        AccountKeyVault.wrap(derived)?.let { blob ->
            store.edit { putString(KEY_ACCOUNT_WRAPPED, blob.toHex()) }
        }
        return derived
    }

    /**
     * Turns encryption on with [passphrase], or off when it is blank.
     *
     * False when a non-blank passphrase is shorter than [MIN_PASSPHRASE_LENGTH]:
     * a backup guarded by four characters is worse than one that says plainly it
     * is not encrypted. Turning it off does not make existing Drive files
     * readable — they keep their headers and still need the passphrase.
     */
    fun setPassphrase(passphrase: String?): Boolean {
        val trimmed = passphrase?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            store.edit { remove(KEY_PASSPHRASE) }
            pending = null
            return true
        }
        if (trimmed.length < MIN_PASSPHRASE_LENGTH) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        store.edit {
            putString(KEY_PASSPHRASE, trimmed)
            putString(KEY_SALT, salt.toHex())
            putInt(KEY_ITERATIONS, DEFAULT_ITERATIONS)
        }
        pending = null
        return true
    }

    /** A passphrase to try against the next file read — a restore's prompt. */
    fun tryPassphrase(passphrase: String) {
        pending = passphrase.trim().takeIf { it.isNotEmpty() }
    }

    fun clearPending() {
        pending = null
    }

    /** Whether [stored] carries the passphrase header rather than the older plain gzip. */
    fun isEncrypted(stored: ByteArray): Boolean =
        stored.size > MAGIC.size && MAGIC.indices.all { stored[it] == MAGIC[it] }

    /** Whether [stored] is sealed at all — account envelope or passphrase header. */
    fun isSealed(stored: ByteArray): Boolean =
        BackupEnvelope.isEnvelope(stored) || isEncrypted(stored)

    /**
     * Seals [plain] with the account's passphrase, or returns it unchanged when
     * none is set.
     *
     * A fresh IV every call, which is what GCM requires of one key: reusing one
     * would let two files be compared and would break the tag's guarantee
     * outright. The salt and work factor ride in the header so the key can be
     * rebuilt from the passphrase alone, on any device.
     */
    fun seal(plain: ByteArray): ByteArray {
        // The connected account's key is what a backup is sealed with, so the
        // listener never types anything and another device signed into the same
        // account opens it. A passphrase is the fallback for installs that have
        // not connected Drive yet, and it still opens the files it produced.
        accountMasterKey()?.let { key ->
            return try {
                BackupEnvelope.seal(plain, key)
            } finally {
                key.fill(0)
            }
        }
        val passphrase = store.getString(KEY_PASSPHRASE, null)?.takeIf { it.isNotBlank() }
            ?: return plain
        val iterations = store.getInt(KEY_ITERATIONS, DEFAULT_ITERATIONS).coerceAtLeast(1)
        val salt = store.getString(KEY_SALT, null)?.fromHex()
            ?: ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = derive(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plain)
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(KDF_PBKDF2_SHA256)
                out.writeInt(iterations)
                out.writeByte(salt.size)
                out.write(salt)
                out.writeByte(iv.size)
                out.write(iv)
            }
        }.toByteArray()
        return header + sealed
    }

    /**
     * [stored] as it was sealed, or as it was written by a build before
     * encryption.
     *
     * [PassphraseRequired] when the file is encrypted and nothing has been
     * supplied, [PassphraseRejected] when the tag does not verify. A passphrase
     * that opens the file becomes the stored one, so the next sync writes with
     * the key that this account's other devices already read.
     */
    fun open(stored: ByteArray): ByteArray {
        if (BackupEnvelope.isEnvelope(stored)) {
            val key = accountMasterKey() ?: throw AccountKeyRequired()
            return try {
                BackupEnvelope.open(stored, key)
            } catch (tampered: BackupEnvelope.Tampered) {
                throw DecryptionFailed()
            } finally {
                key.fill(0)
            }
        }
        if (!isEncrypted(stored)) return stored
        val header = parseHeader(stored)
        val candidates = listOfNotNull(
            pending,
            store.getString(KEY_PASSPHRASE, null)?.takeIf { it.isNotBlank() },
        ).distinct()
        if (candidates.isEmpty()) throw PassphraseRequired()
        for (candidate in candidates) {
            val key = derive(candidate, header.salt, header.iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.iv))
            val plain = try {
                cipher.doFinal(stored, header.length, stored.size - header.length)
            } catch (bad: AEADBadTagException) {
                continue
            } catch (bad: GeneralSecurityException) {
                continue
            }
            if (candidate == pending) adopt(candidate, header.salt, header.iterations)
            return plain
        }
        throw PassphraseRejected()
    }

    /** Store the passphrase a file just proved, with that file's own salt and work factor. */
    private fun adopt(passphrase: String, salt: ByteArray, iterations: Int) {
        store.edit {
            putString(KEY_PASSPHRASE, passphrase)
            putString(KEY_SALT, salt.toHex())
            putInt(KEY_ITERATIONS, iterations)
        }
        pending = null
    }

    private class Header(
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        /** Where the ciphertext starts. */
        val length: Int,
    )

    private fun parseHeader(stored: ByteArray): Header {
        var at = MAGIC.size
        fun byte(): Int {
            if (at >= stored.size) throw UnreadableBackup()
            return stored[at++].toInt() and 0xFF
        }

        fun int(): Int {
            if (at + 4 > stored.size) throw UnreadableBackup()
            val value = ((stored[at].toInt() and 0xFF) shl 24) or
                ((stored[at + 1].toInt() and 0xFF) shl 16) or
                ((stored[at + 2].toInt() and 0xFF) shl 8) or
                (stored[at + 3].toInt() and 0xFF)
            at += 4
            return value
        }

        fun bytes(count: Int): ByteArray {
            if (count < 0 || at + count > stored.size) throw UnreadableBackup()
            return stored.copyOfRange(at, at + count).also { at += count }
        }

        val version = byte()
        val kdf = byte()
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256) throw UnreadableBackup()
        val iterations = int().coerceAtLeast(1)
        val salt = bytes(byte())
        val iv = bytes(byte())
        if (salt.isEmpty() || iv.isEmpty()) throw UnreadableBackup()
        return Header(iterations, salt, iv, at)
    }

    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val encoded = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(encoded, "AES")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching { ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() } }
            .getOrNull()
    }
}
