package com.music.bitchord.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A PIN as it is stored: a random salt, the PBKDF2 hash and the work factor
 * that produced it.
 *
 * The PIN itself is never written anywhere. [iterations] travels with the
 * record so a hash made by an older build still verifies after the default
 * work factor is raised.
 */
data class LockPinRecord(
    val saltHex: String,
    val hashHex: String,
    val iterations: Int = LockSecurity.DEFAULT_ITERATIONS,
)

/**
 * Hashing and checking the device-local lock PIN.
 *
 * PBKDF2-HMAC-SHA256 with a per-record random salt, compared in constant time:
 * a 4–6 digit PIN is far too short to store as a plain digest, and the salt is
 * what stops one rainbow table from answering every device. The work factor is
 * deliberately high — verifying a PIN happens on an explicit user action, never
 * in a loop, so a few tens of milliseconds buys real resistance.
 *
 * Shared between the phone and the desktop so one PIN rule, one hash format and
 * one comparison sit behind both UIs.
 */
object LockSecurity {
    const val PIN_MIN_LENGTH: Int = 4
    const val PIN_MAX_LENGTH: Int = 6

    /** Work factor for new records; ~60 ms on a mid-range phone. */
    const val DEFAULT_ITERATIONS: Int = 60_000

    private const val KEY_LENGTH_BITS = 256

    /** Only the digits a numeric keypad can produce, and only 4–6 of them. */
    fun isValidPin(pin: String): Boolean =
        pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }

    /** A fresh salt and hash for [pin], or null when [pin] is not a valid PIN. */
    fun newRecord(pin: String): LockPinRecord? {
        if (!isValidPin(pin)) return null
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return LockPinRecord(
            saltHex = salt.toHex(),
            hashHex = hash(pin, salt, DEFAULT_ITERATIONS),
            iterations = DEFAULT_ITERATIONS,
        )
    }

    /** Constant-time check of [pin] against [record]. */
    fun matches(pin: String, record: LockPinRecord): Boolean {
        if (!isValidPin(pin)) return false
        val salt = record.saltHex.fromHex() ?: return false
        val expected = record.hashHex.fromHex() ?: return false
        val actual = hash(pin, salt, record.iterations).fromHex() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    private fun hash(pin: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations.coerceAtLeast(1), KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
                .toHex()
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}

/**
 * How long a collection stays open after its PIN has been given.
 *
 * The setting is persisted; the unlock it grants is not. [IMMEDIATE] ends the
 * grant the moment the page is left, the rest keep it for their window so
 * stepping out to a queue and back does not ask again. Either way the lock
 * itself, and the badge on its cover, never move.
 */
enum class LockTimeout(val millis: Long) {
    IMMEDIATE(0L),
    FIVE_SECONDS(5_000L),
    TEN_SECONDS(10_000L),
    THIRTY_SECONDS(30_000L),
    ;

    /** True when leaving the page relocks, rather than a countdown doing it. */
    val untilExit: Boolean get() = millis == 0L

    companion object {
        /** The stored value, or [IMMEDIATE] for one a build never wrote. */
        fun fromName(name: String?): LockTimeout =
            entries.firstOrNull { it.name == name } ?: IMMEDIATE
    }
}

/**
 * Where a platform keeps the lock: the PIN record, the set of locked
 * collections, the biometric toggle and the relock timer.
 *
 * The phone implements this over `SharedPreferences`, the desktop over its own
 * preference node; everything above this line is identical on both.
 */
interface LockPersistence {
    fun loadPin(): LockPinRecord?
    fun savePin(record: LockPinRecord?)
    fun loadLocked(): Set<String>
    fun saveLocked(ids: Set<String>)
    fun loadBiometricEnabled(): Boolean
    fun saveBiometricEnabled(enabled: Boolean)

    /** The chosen relock timer; [LockTimeout.IMMEDIATE] when none was stored. */
    fun loadLockTimeout(): LockTimeout = LockTimeout.IMMEDIATE

    fun saveLockTimeout(timeout: LockTimeout) {}
}

/**
 * The device's lock state: one PIN, one set of locked playlists/albums, and the
 * biometric switch.
 *
 * This is the single source of truth both UIs read, which is what makes a lock
 * applied from a card's menu the same lock the card, the page and the library
 * behind it draw. Keys are normalised collection ids (see [normalize]), so the
 * `VL` prefix a playlist page carries and the bare id its model has cannot
 * disagree about whether a playlist is locked.
 */
object CollectionLocks {
    private var persistence: LockPersistence? = null

    private val _pin = MutableStateFlow<LockPinRecord?>(null)
    val pin: StateFlow<LockPinRecord?> = _pin.asStateFlow()

    private val _locked = MutableStateFlow<Set<String>>(emptySet())
    val locked: StateFlow<Set<String>> = _locked.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _lockTimeout = MutableStateFlow(LockTimeout.IMMEDIATE)

    /** How long an unlocked page stays open after its PIN is given. */
    val lockTimeout: StateFlow<LockTimeout> = _lockTimeout.asStateFlow()

    /**
     * One temporary unlock: until the page is left, or until a moment in time.
     *
     * [untilExit] is [LockTimeout.IMMEDIATE]'s grant — it has no clock, because
     * what ends it is the page closing (see [retainExitGrants]). The others
     * expire on their own, which is what a 10 s window means.
     */
    private data class Grant(val untilMillis: Long, val untilExit: Boolean)

    /**
     * Collections whose PIN has been given for now, and when that ends.
     *
     * Deliberately memory only, never written: the lock itself stays on in
     * [locked], so the badge never disappears, but a page opened once with the
     * PIN does not ask again every time it re-renders or its card is tapped
     * again. Restarting the app clears it, and so does locking, restoring, or
     * the chosen timer running out.
     */
    private val _sessions = MutableStateFlow<Map<String, Grant>>(emptyMap())

    /**
     * Binds this device's storage and reads it once.
     *
     * Idempotent: both applications install it from their startup path, and a
     * second call must not clear state that is already live.
     */
    fun install(storage: LockPersistence) {
        if (persistence != null) return
        persistence = storage
        _pin.value = storage.loadPin()
        _locked.value = storage.loadLocked().mapTo(LinkedHashSet(), ::normalize)
        _biometricEnabled.value = storage.loadBiometricEnabled()
        _lockTimeout.value = storage.loadLockTimeout()
    }

    /** A playlist's page id (`VLPL…`), a model's id (`PL…`, `sp_local_…`) or an album's (`MPRE…`) all key the same slot. */
    fun normalize(collectionId: String?): String =
        collectionId?.trim().orEmpty()
            .removePrefix("local:playlist:")
            .removePrefix("VL")

    fun hasPin(): Boolean = _pin.value != null

    /** Stores [pin] as the lock PIN, replacing any old one. False for an invalid PIN. */
    fun setPin(pin: String): Boolean {
        val record = LockSecurity.newRecord(pin) ?: return false
        _pin.value = record
        persistence?.savePin(record)
        return true
    }

    fun verifyPin(pin: String): Boolean {
        val record = _pin.value ?: return false
        return LockSecurity.matches(pin, record)
    }

    /** Replaces the PIN only when [currentPin] is the one already stored. */
    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin)) return false
        return setPin(newPin)
    }

    /** Forgetting the PIN is a deliberate reset, so it also clears the locks it guarded. */
    fun clearPin() {
        _pin.value = null
        persistence?.savePin(null)
        _locked.value = emptySet()
        _sessions.value = emptyMap()
        persistence?.saveLocked(emptySet())
    }

    fun setBiometricEnabled(enabled: Boolean) {
        _biometricEnabled.value = enabled
        persistence?.saveBiometricEnabled(enabled)
    }

    /**
     * Changes how long a new unlock lasts. Grants already given keep the window
     * they were made under, so switching to [LockTimeout.IMMEDIATE] cannot
     * strand a page that is open right now.
     */
    fun setLockTimeout(timeout: LockTimeout) {
        if (_lockTimeout.value == timeout) return
        _lockTimeout.value = timeout
        persistence?.saveLockTimeout(timeout)
    }

    /** Ends every temporary unlock without touching the locks themselves. */
    fun clearGrants() {
        _sessions.value = emptyMap()
    }

    fun isLocked(collectionId: String?): Boolean {
        val key = normalize(collectionId)
        return key.isNotEmpty() && key in _locked.value
    }

    /**
     * Whether opening [collectionId] has to ask for the PIN first: locked, and
     * not already opened with it this run. This is what every entry point
     * gates on — a page, a card, a search hit, a menu's Open — so they cannot
     * disagree about whether the prompt is still owed.
     */
    fun isGated(collectionId: String?): Boolean {
        val key = normalize(collectionId)
        if (key.isEmpty() || key !in _locked.value) return false
        val grant = _sessions.value[key] ?: return true
        if (grant.untilExit) return false
        return System.currentTimeMillis() >= grant.untilMillis
    }

    /**
     * Records that [collectionId]'s PIN was accepted, for the window the
     * Locked Timer setting names.
     */
    fun grant(collectionId: String?) {
        val key = normalize(collectionId)
        if (key.isEmpty() || key !in _locked.value) return
        val timeout = _lockTimeout.value
        val now = System.currentTimeMillis()
        val grant = Grant(
            untilMillis = if (timeout.untilExit) Long.MAX_VALUE else now + timeout.millis,
            untilExit = timeout.untilExit,
        )
        _sessions.update { sessions ->
            val live = sessions.filter { (_, existing) -> existing.untilExit || now < existing.untilMillis }
            live + (key to grant)
        }
    }

    /**
     * Ends the exit-scoped grants for pages that are no longer open.
     *
     * Called with whatever playlist/album pages are on screen now: with
     * [LockTimeout.IMMEDIATE] the page that was left relocks here, and the next
     * tap on it asks again. Timed grants are left alone until their own clock
     * runs out, and expired ones are dropped on the way through.
     */
    fun retainExitGrants(open: Collection<String>) {
        val keep = open.mapTo(HashSet(), ::normalize)
        val now = System.currentTimeMillis()
        _sessions.update { sessions ->
            sessions.filter { (id, grant) ->
                if (grant.untilExit) id in keep else now < grant.untilMillis
            }
        }
    }

    fun setLocked(collectionId: String?, locked: Boolean) {
        val key = normalize(collectionId)
        if (key.isEmpty()) return
        val next = if (locked) _locked.value + key else _locked.value - key
        if (next == _locked.value) return
        _locked.value = next
        // Locking starts the gate over; unlocking needs no grant at all.
        if (locked) _sessions.update { it - key }
        persistence?.saveLocked(next)
    }

    /** Locks or unlocks from an explicit id without normalising it away. */
    fun setLockedExact(key: String, locked: Boolean) {
        if (key.isBlank()) return
        val next = if (locked) _locked.value + key else _locked.value - key
        if (next == _locked.value) return
        _locked.value = next
        persistence?.saveLocked(next)
    }

    /** Everything locked on this device, for the cloud backup. */
    fun exportLocked(): Set<String> = _locked.value

    /**
     * Re-applies the locked set a backup brought back.
     *
     * Entries are normalised and blanks dropped; a backup written before locks
     * existed simply has none, which is the empty case.
     */
    fun restoreLocked(ids: Collection<String>) {
        val next = ids.mapTo(LinkedHashSet(), ::normalize).filterTo(LinkedHashSet()) { it.isNotEmpty() }
        // Restored locks guard this device straight away, whatever was
        // opened with the PIN before the restore.
        _sessions.update { sessions -> sessions.filterKeys { it in next } }
        if (next == _locked.value) return
        _locked.value = next
        persistence?.saveLocked(next)
    }
}
