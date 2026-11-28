package com.music.bitchord.data.security

import android.content.Context
import android.content.SharedPreferences

/**
 * The phone's side of [CollectionLocks]: the PIN record, the locked collection
 * ids and the biometric switch, in one private preference file.
 *
 * Only a salted PBKDF2 hash is written — see [LockSecurity] — so a copied
 * preference file does not reveal the PIN.
 */
object LockPrefs : LockPersistence {
    private const val PREF_NAME = "bitchord_collection_lock"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_ITERATIONS = "pin_iterations"
    private const val KEY_LOCKED = "locked_ids"
    private const val KEY_BIOMETRIC = "biometric_enabled"
    private const val KEY_TIMEOUT = "lock_timeout"

    private lateinit var prefs: SharedPreferences

    /** Reads this device's lock state into [CollectionLocks]. Idempotent. */
    fun install(context: Context) {
        if (!this::prefs.isInitialized) {
            prefs = context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
        CollectionLocks.install(this)
    }

    override fun loadPin(): LockPinRecord? {
        if (!this::prefs.isInitialized) return null
        val salt = prefs.getString(KEY_SALT, null)?.takeIf { it.isNotBlank() } ?: return null
        val hash = prefs.getString(KEY_HASH, null)?.takeIf { it.isNotBlank() } ?: return null
        val iterations = prefs.getInt(KEY_ITERATIONS, LockSecurity.DEFAULT_ITERATIONS)
        return LockPinRecord(salt, hash, iterations)
    }

    override fun savePin(record: LockPinRecord?) {
        if (!this::prefs.isInitialized) return
        prefs.edit().apply {
            if (record == null) {
                remove(KEY_SALT)
                remove(KEY_HASH)
                remove(KEY_ITERATIONS)
            } else {
                putString(KEY_SALT, record.saltHex)
                putString(KEY_HASH, record.hashHex)
                putInt(KEY_ITERATIONS, record.iterations)
            }
        }.apply()
    }

    override fun loadLocked(): Set<String> {
        if (!this::prefs.isInitialized) return emptySet()
        return prefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty().toSet()
    }

    override fun saveLocked(ids: Set<String>) {
        if (!this::prefs.isInitialized) return
        // A copy, because SharedPreferences keeps the set it was handed.
        prefs.edit().putStringSet(KEY_LOCKED, ids.toSet()).apply()
    }

    override fun loadBiometricEnabled(): Boolean {
        if (!this::prefs.isInitialized) return false
        return prefs.getBoolean(KEY_BIOMETRIC, false)
    }

    override fun saveBiometricEnabled(enabled: Boolean) {
        if (!this::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    override fun loadLockTimeout(): LockTimeout {
        if (!this::prefs.isInitialized) return LockTimeout.IMMEDIATE
        return LockTimeout.fromName(prefs.getString(KEY_TIMEOUT, null))
    }

    override fun saveLockTimeout(timeout: LockTimeout) {
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(KEY_TIMEOUT, timeout.name).apply()
    }
}
