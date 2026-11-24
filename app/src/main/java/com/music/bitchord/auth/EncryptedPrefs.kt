package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.bitchord.data.DebugLog as Log

/**
 * Encrypted preferences that repair themselves when their keyset stops
 * matching the device's Keystore.
 *
 * An [EncryptedSharedPreferences] file carries its Tink keyset wrapped by a
 * Keystore key, and the Keystore key never leaves the device. Anything that
 * restores the file without the key (auto-backup, a device transfer) leaves a
 * keyset nothing can unwrap: every open fails with keymint's
 * `VERIFICATION_FAILED`, measured on-device on every launch, and the old
 * fallback then kept credentials in plain prefs forever. The file's contents
 * are unreadable either way, so it is deleted and recreated, and whatever the
 * plain fallback collected meanwhile is moved back in.
 *
 * A Keystore that can't be used at all, which a handful of OEM builds ship,
 * still degrades to the plain file rather than failing launch.
 */
internal object EncryptedPrefs {

    private const val TAG = "BitChord"

    /** Tink's own entry inside the prefs file; its presence is what makes a failed open a stale keyset. */
    private const val KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

    /** One store per file per process; every failed open was a Keystore round trip. */
    private val opened = HashMap<String, SharedPreferences>()

    @Synchronized
    fun open(context: Context, name: String, plainName: String): SharedPreferences =
        opened.getOrPut(name) { resolve(context.applicationContext, name, plainName) }

    private fun resolve(context: Context, name: String, plainName: String): SharedPreferences {
        val encrypted = try {
            create(context, name)
        } catch (e: Throwable) {
            val staleKeyset = isUnverifiable(e) &&
                context.getSharedPreferences(name, Context.MODE_PRIVATE).contains(KEYSET_ENTRY)
            if (!staleKeyset) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable for $name, falling back: ${describe(e)}")
                return context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
            }
            Log.w(TAG, "$name's keyset no longer matches this device's Keystore (${describe(e)}); recreating it")
            context.deleteSharedPreferences(name)
            try {
                create(context, name)
            } catch (retry: Throwable) {
                Log.w(TAG, "$name could not be recreated, falling back: ${describe(retry)}")
                return context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
            }
        }
        moveIn(context, plainName, encrypted, name)
        return encrypted
    }

    private fun create(context: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /**
     * Moves what an earlier fallback wrote into the encrypted store, then
     * deletes the plain file.
     *
     * Plain values win over encrypted ones: while the fallback was in use it
     * was the only store being written. The plain file is deleted only after
     * every value reads back from the encrypted store, so a failure part-way
     * leaves it as the source of truth and the next launch tries again.
     */
    private fun moveIn(context: Context, plainName: String, encrypted: SharedPreferences, name: String) {
        val entries = context.getSharedPreferences(plainName, Context.MODE_PRIVATE).all
        if (entries.isEmpty()) return
        val edit = encrypted.edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!edit.commit()) {
            Log.w(TAG, "could not write $plainName into $name; keeping the plain copy")
            return
        }
        val stored = encrypted.all
        val unmatched = entries.keys.filter { stored[it] != entries[it] }
        if (unmatched.isNotEmpty()) {
            Log.w(TAG, "${unmatched.size} of ${entries.size} entries from $plainName did not read back; keeping the plain copy")
            return
        }
        context.deleteSharedPreferences(plainName)
        Log.d(TAG, "moved ${entries.size} entries from $plainName into $name")
    }

    /**
     * Whether [e] says the stored keyset failed verification, which no retry
     * can change, as opposed to a Keystore that is merely unavailable.
     */
    private fun isUnverifiable(e: Throwable): Boolean = causes(e).any {
        it is javax.crypto.AEADBadTagException ||
            it.javaClass.name.endsWith("InvalidProtocolBufferException") ||
            // android.security.KeyStoreException is API 33+; matched by name so older devices never load it.
            (it.javaClass.name == "android.security.KeyStoreException" &&
                it.message.orEmpty().contains("VERIFICATION_FAILED", ignoreCase = true))
    }

    private fun describe(e: Throwable): String =
        causes(e).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }

    private fun causes(e: Throwable): List<Throwable> =
        generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
}

/**
 * The ACRCloud key pair used by the hum search, as one record.
 *
 * Three strings that are only useful together, plus the switch that says
 * whether to use them; read and written as a unit so a half-saved trio cannot
 * exist. [isConfigured] is the question the recognition sheet asks, and it is
 * answered here rather than at each call site.
 */
internal data class AcrCloudCredentials(
    val host: String = "",
    val accessKey: String = "",
    val accessSecret: String = "",
    val enabled: Boolean = true,
) {
    val isConfigured: Boolean
        get() = enabled && host.isNotBlank() && accessKey.isNotBlank() && accessSecret.isNotBlank()
}

/**
 * The ACRCloud entries inside [AuthStore]'s encrypted file.
 *
 * Deliberately not a store of their own: another [EncryptedSharedPreferences]
 * open is another Keystore round trip on the path to the settings screen, and
 * these are credentials of exactly the kind that file already holds.
 *
 * Nothing here is exported: a backup carries the listener's configuration, not
 * the keys to a project billing account.
 */
internal object AcrCloudPrefs {

    private const val KEY_HOST = "acrcloud_host"
    private const val KEY_ACCESS_KEY = "acrcloud_access_key"
    private const val KEY_ACCESS_SECRET = "acrcloud_access_secret"
    private const val KEY_ENABLED = "acrcloud_enabled"

    fun read(prefs: SharedPreferences): AcrCloudCredentials = AcrCloudCredentials(
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        accessKey = prefs.getString(KEY_ACCESS_KEY, "").orEmpty(),
        accessSecret = prefs.getString(KEY_ACCESS_SECRET, "").orEmpty(),
        enabled = prefs.getBoolean(KEY_ENABLED, true),
    )

    fun write(prefs: SharedPreferences, credentials: AcrCloudCredentials) {
        prefs.edit().apply {
            putString(KEY_HOST, credentials.host)
            putString(KEY_ACCESS_KEY, credentials.accessKey)
            putString(KEY_ACCESS_SECRET, credentials.accessSecret)
            putBoolean(KEY_ENABLED, credentials.enabled)
        }.apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().apply {
            remove(KEY_HOST)
            remove(KEY_ACCESS_KEY)
            remove(KEY_ACCESS_SECRET)
            putBoolean(KEY_ENABLED, true)
        }.apply()
    }
}
