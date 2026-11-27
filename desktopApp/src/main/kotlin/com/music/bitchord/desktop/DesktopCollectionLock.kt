package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.security.CollectionLocks
import com.music.bitchord.data.security.LockPersistence
import com.music.bitchord.data.security.LockPinRecord
import com.music.bitchord.data.security.LockSecurity
import java.util.prefs.Preferences

/**
 * The desktop's side of [CollectionLocks], in a preference node of its own.
 *
 * Only the salted PBKDF2 hash is written — [LockSecurity] — and the locked ids
 * are a delimited string rather than a list value, because `java.util.prefs`
 * has no set type.
 */
object DesktopLockPrefs : LockPersistence {
    private const val NODE = "com/music/bitchord/collection-lock"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_ITERATIONS = "pin_iterations"
    private const val KEY_LOCKED = "locked_ids"
    private const val KEY_BIOMETRIC = "biometric_enabled"
    private const val SEPARATOR = "\u001F"

    private val prefs: Preferences by lazy { Preferences.userRoot().node(NODE) }

    fun install() {
        CollectionLocks.install(this)
    }

    override fun loadPin(): LockPinRecord? {
        val salt = prefs.get(KEY_SALT, "").takeIf { it.isNotBlank() } ?: return null
        val hash = prefs.get(KEY_HASH, "").takeIf { it.isNotBlank() } ?: return null
        return LockPinRecord(salt, hash, prefs.getInt(KEY_ITERATIONS, LockSecurity.DEFAULT_ITERATIONS))
    }

    override fun savePin(record: LockPinRecord?) {
        if (record == null) {
            prefs.remove(KEY_SALT)
            prefs.remove(KEY_HASH)
            prefs.remove(KEY_ITERATIONS)
        } else {
            prefs.put(KEY_SALT, record.saltHex)
            prefs.put(KEY_HASH, record.hashHex)
            prefs.putInt(KEY_ITERATIONS, record.iterations)
        }
    }

    override fun loadLocked(): Set<String> =
        prefs.get(KEY_LOCKED, "")
            .split(SEPARATOR)
            .filterTo(LinkedHashSet()) { it.isNotBlank() }

    override fun saveLocked(ids: Set<String>) {
        if (ids.isEmpty()) prefs.remove(KEY_LOCKED) else prefs.put(KEY_LOCKED, ids.joinToString(SEPARATOR))
    }

    override fun loadBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)

    override fun saveBiometricEnabled(enabled: Boolean) {
        if (enabled) prefs.putBoolean(KEY_BIOMETRIC, true) else prefs.remove(KEY_BIOMETRIC)
    }
}

/** A locked collection waiting on a PIN, and what runs once it is given. */
data class DesktopLockRequest(val id: String, val title: String, val onAllowed: () -> Unit)

/**
 * The desktop's lock flow: which dialog is up, and the collection waiting
 * behind it.
 *
 * Object state rather than a remembered instance so every page — a shelf card,
 * a release page, a search hit, the Settings rows — reaches the same prompt
 * without threading a parameter through the whole app.
 */
object DesktopLocks {
    var unlock by mutableStateOf<DesktopLockRequest?>(null)
    var setup by mutableStateOf<DesktopLockRequest?>(null)
    var changePin by mutableStateOf(false)
    var toggleBiometric by mutableStateOf(false)

    fun guard(collectionId: String?, title: String, action: () -> Unit) {
        if (CollectionLocks.isLocked(collectionId)) {
            unlock = DesktopLockRequest(collectionId.orEmpty(), title) { action() }
        } else {
            action()
        }
    }

    fun toggleLock(collectionId: String?, title: String) {
        val id = collectionId ?: return
        if (CollectionLocks.isLocked(id)) {
            unlock = DesktopLockRequest(id, title) { CollectionLocks.setLocked(id, false) }
        } else if (!CollectionLocks.hasPin()) {
            setup = DesktopLockRequest(id, title) { CollectionLocks.setLocked(id, true) }
        } else {
            CollectionLocks.setLocked(id, true)
        }
    }

    fun startChangePin() {
        changePin = true
    }

    fun startBiometricToggle() {
        toggleBiometric = true
    }
}

/**
 * The lock marker on the desktop's artwork: a white glyph on near-black glass,
 * so it reads on a cover of any colour.
 */
@Composable
fun DesktopLockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(8.dp)
            .size(28.dp)
            .background(Color.Black.copy(alpha = 0.72f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.34f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = DesktopStrings["locked_badge", "Locked"],
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A masked 4–6 digit field: the desktop's PIN input. */
@Composable
private fun DesktopPinField(
    value: String,
    label: String,
    isError: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onValueChange(typed.filter(Char::isDigit).take(LockSecurity.PIN_MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Enter PIN, then enter it again. A mismatch is inline, not a closed dialog. */
@Composable
fun DesktopSetPinDialog(onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    var first by mutableStateOf("")
    var second by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DesktopStrings["set_up_lock_pin", "Set up Lock PIN"]) },
        text = {
            Column {
                Text(
                    DesktopStrings[
                        "lock_pin_setup_subtitle",
                        "This PIN protects the playlists and albums you lock. It is stored only as a salted hash.",
                    ],
                    style = MaterialTheme.typography.bodyMedium,
                    color = DesktopSecondary,
                )
                DesktopPinField(first, DesktopStrings["lock_pin_enter", "Enter PIN"], error != null) {
                    first = it
                    error = null
                }
                DesktopPinField(second, DesktopStrings["lock_pin_confirm", "Re-enter PIN"], error != null) {
                    second = it
                    error = null
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        !LockSecurity.isValidPin(first) ->
                            DesktopStrings["lock_pin_too_short", "Use 4 to 6 digits."]
                        first != second ->
                            DesktopStrings["lock_pin_mismatch", "The PINs don't match. Try again."]
                        else -> null
                    }
                    if (error == null && CollectionLocks.setPin(first)) onConfirmed()
                },
            ) { Text(DesktopStrings["save", "Save"]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings["cancel", "Cancel"]) }
        },
    )
}

/**
 * The PIN check used before opening a locked collection and before changing the
 * security settings.
 *
 * The desktop has no system biometric prompt that this app can drive, so the
 * PIN is the only factor here and the Settings toggle says so rather than
 * promising a fingerprint it cannot read.
 */
@Composable
fun DesktopVerifyPinDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    var pin by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = DesktopSecondary)
                }
                DesktopPinField(pin, DesktopStrings["lock_pin_enter", "Enter PIN"], error != null) {
                    pin = it
                    error = null
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (CollectionLocks.verifyPin(pin)) onVerified() else error = DesktopStrings["lock_pin_incorrect", "Incorrect PIN."] },
                enabled = LockSecurity.isValidPin(pin),
            ) { Text(DesktopStrings["unlock", "Unlock"]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings["cancel", "Cancel"]) }
        },
    )
}

/** Current PIN → new PIN → confirm, in that order. */
@Composable
private fun DesktopChangePinDialog(onDismiss: () -> Unit) {
    var verified by mutableStateOf(false)
    if (verified) {
        DesktopSetPinDialog(onDismiss = onDismiss, onConfirmed = onDismiss)
        return
    }
    DesktopVerifyPinDialog(
        title = DesktopStrings["change_pin", "Change PIN"],
        subtitle = DesktopStrings["change_pin_verify_subtitle", "Confirm your current PIN first."],
        onDismiss = onDismiss,
        onVerified = { verified = true },
    )
}

/** Hosts the desktop's lock dialogs; one instance at the app root. */
@Composable
fun DesktopLockDialogs() {
    DesktopLocks.unlock?.let { request ->
        DesktopVerifyPinDialog(
            title = DesktopStrings["unlock_collection_title", "Unlock"] + " \u201C${request.title}\u201D",
            subtitle = DesktopStrings[
                "unlock_collection_subtitle",
                "Enter your PIN to open this.",
            ],
            onDismiss = { DesktopLocks.unlock = null },
            onVerified = {
                DesktopLocks.unlock = null
                request.onAllowed()
            },
        )
    }
    DesktopLocks.setup?.let { request ->
        DesktopSetPinDialog(
            onDismiss = { DesktopLocks.setup = null },
            onConfirmed = {
                DesktopLocks.setup = null
                request.onAllowed()
            },
        )
    }
    if (DesktopLocks.changePin) {
        DesktopChangePinDialog(onDismiss = { DesktopLocks.changePin = false })
    }
    if (DesktopLocks.toggleBiometric) {
        val enabling = !CollectionLocks.biometricEnabled.value
        DesktopVerifyPinDialog(
            title = DesktopStrings["enable_biometric_unlock", "Enable Biometric Unlock"],
            subtitle = DesktopStrings[
                "security_biometric_subtitle",
                "Use your fingerprint or face to open locked playlists and albums.",
            ],
            onDismiss = { DesktopLocks.toggleBiometric = false },
            onVerified = {
                CollectionLocks.setBiometricEnabled(enabling)
                DesktopLocks.toggleBiometric = false
            },
        )
    }
}
