package com.music.bitchord.data.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The local half of the Drive backup key: the derived master key, sealed under
 * a key the device keeps in its own Keystore.
 *
 * The master key is deterministic — [BackupAccountKey] rebuilds it from the
 * account alone — so nothing here is what makes a restore possible. What this
 * buys is that the copy on disk is not the key itself: a pulled preference
 * file, or a backup of the app's data, yields ciphertext that only this
 * device's hardware-backed key can open. When the Keystore is unavailable the
 * blob is simply not stored and the key is derived again on the next use,
 * which costs a millisecond and loses nothing.
 */
internal object AccountKeyVault {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "bitchord_backup_account_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** [key] sealed for this device, as `iv || ciphertext`, or null without a Keystore. */
    fun wrap(key: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        cipher.iv + cipher.doFinal(key)
    }.getOrNull()

    /** The key inside [blob], or null when this device cannot open it. */
    fun unwrap(blob: ByteArray): ByteArray? = runCatching {
        if (blob.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deviceKey(),
            GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)),
        )
        cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }.getOrNull()

    /** Forgets the device key: what was wrapped under it can never be opened again. */
    fun clear() {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun deviceKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
