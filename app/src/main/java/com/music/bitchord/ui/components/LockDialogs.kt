package com.music.bitchord.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.security.CollectionLocks
import com.music.bitchord.data.security.LockSecurity

/**
 * The device's system authentication sheet, asked about before it is raised.
 *
 * The framework prompt this used to call could only show biometrics and needed
 * a negative button to get back to the PIN, which is why the button inside the
 * PIN dialog appeared to do nothing: the sheet either could not be built on the
 * device or was cancelled into a handler that had no PIN to fall back to. The
 * AndroidX prompt asks the system for biometrics *and* the device credential in
 * one sheet — the one Chrome shows for a passkey — and `DEVICE_CREDENTIAL`
 * carries the system PIN/pattern fallback itself, so no negative button may be
 * set beside it.
 */
object BiometricGate {

    /** Fingerprint, face or iris, at whichever strength the device enrols. */
    private const val BIOMETRICS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    /**
     * The authenticators this device can really show, or 0 for none.
     *
     * Asked of [BiometricManager] rather than guessed from package features, so
     * a phone with a sensor but no enrolment, or no secure lock screen, says so
     * before a prompt is attempted instead of failing silently.
     */
    fun authenticators(context: Context): Int {
        val manager = BiometricManager.from(context)
        val withCredential = BIOMETRICS or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(withCredential) == BiometricManager.BIOMETRIC_SUCCESS) {
            return withCredential
        }
        if (manager.canAuthenticate(BIOMETRICS) == BiometricManager.BIOMETRIC_SUCCESS) return BIOMETRICS
        return 0
    }

    fun isAvailable(context: Context): Boolean = authenticators(context) != 0

    /**
     * Shows the system sheet. Returns false when it could not be raised at all,
     * so the caller can put its own PIN field up instead of leaving the tap with
     * nothing on screen.
     *
     * [onUsePin] is called when the sheet is dismissed by the user or by its
     * negative button — never for a hard error, which is reported as such.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        authenticators: Int,
        onSuccess: () -> Unit,
        onUsePin: () -> Unit,
        onFailure: (String) -> Unit,
    ): Boolean {
        val executor = ContextCompat.getMainExecutor(activity)
        val info = runCatching {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle.isNotBlank()) setSubtitle(subtitle) }
                .setAllowedAuthenticators(authenticators)
                .apply {
                    // Android throws IllegalArgumentException for a negative
                    // button combined with DEVICE_CREDENTIAL; the sheet already
                    // offers the system credential as its own fallback. Without
                    // it, the button says where to go and arrives back here as
                    // ERROR_NEGATIVE_BUTTON, which switches to the PIN field.
                    if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                        setNegativeButtonText(activity.getString(R.string.use_pin))
                    }
                }
                .build()
        }.getOrNull() ?: return false

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    // One finger that did not match: the system sheet stays up
                    // and reports its own retry, so nothing changes here.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        -> onUsePin()

                        else -> onFailure(errString.toString())
                    }
                }
            },
        )
        return runCatching {
            prompt.authenticate(info)
            true
        }.getOrDefault(false)
    }
}

/** The host activity behind a Compose context, unwrapping the theme wrappers. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return if (current is Activity) current else null
}

/**
 * A 4–6 digit PIN field: masked, numeric keypad, and never autofilled from a
 * password manager — this is a device secret, not an account one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PinField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onValueChange(typed.filter(Char::isDigit).take(LockSecurity.PIN_MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

/**
 * The one-time PIN setup: enter it, then enter it again.
 *
 * The confirm field is what makes a mistyped PIN recoverable — a lock whose PIN
 * nobody knows is a playlist nobody can open — so a mismatch is shown inline
 * and both fields stay put rather than closing the sheet on an error.
 */
@Composable
fun SetLockPinDialog(
    onDismiss: () -> Unit,
    onConfirmed: (String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.lock_pin_mismatch)
    val tooShort = stringResource(R.string.lock_pin_too_short)
    val validFirst = LockSecurity.isValidPin(first)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_up_lock_pin)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.lock_pin_setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PinField(
                    value = first,
                    label = stringResource(R.string.lock_pin_enter),
                    onValueChange = {
                        first = it
                        error = null
                    },
                    isError = error != null,
                )
                PinField(
                    value = second,
                    label = stringResource(R.string.lock_pin_confirm),
                    onValueChange = {
                        second = it
                        error = null
                    },
                    isError = error != null,
                )
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
                        !validFirst -> tooShort
                        first != second -> mismatch
                        else -> null
                    }
                    if (error == null && CollectionLocks.setPin(first)) onConfirmed(first)
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Verifies the PIN before something is changed — a new PIN, the biometric
 * switch, or removing a lock.
 *
 * Biometrics are offered first when they are enabled and the device can show
 * the system sheet, with the PIN field visible underneath as the immediate
 * fallback: a fingerprint that fails three times must not close the only way
 * in. [autoPromptBiometric] is false where the caller has already raised the
 * system sheet once and this dialog is the answer to cancelling it.
 */
@Composable
fun VerifyLockPinDialog(
    title: String,
    subtitle: String? = null,
    autoPromptBiometric: Boolean = true,
    showBiometricAction: Boolean = true,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val biometricEnabled by CollectionLocks.biometricEnabled.collectAsStateWithLifecycle()
    val authenticators = remember(context) { BiometricGate.authenticators(context) }
    val canUseBiometric = showBiometricAction && biometricEnabled && authenticators != 0 && activity != null

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var prompted by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.lock_pin_incorrect)

    fun prompt() {
        val host = activity ?: return
        if (!canUseBiometric) return
        val raised = BiometricGate.authenticate(
            activity = host,
            title = title,
            subtitle = subtitle.orEmpty(),
            authenticators = authenticators,
            onSuccess = { onVerified() },
            // The sheet already carries the system credential, so dismissing
            // it simply leaves this PIN field in front of the user.
            onUsePin = { },
            onFailure = { message -> if (message.isNotBlank()) error = message },
        )
        if (!raised) error = null
    }

    LaunchedEffect(Unit) {
        if (canUseBiometric && autoPromptBiometric && !prompted) {
            prompted = true
            prompt()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PinField(
                    value = pin,
                    label = stringResource(R.string.lock_pin_enter),
                    onValueChange = {
                        pin = it
                        error = null
                    },
                    isError = error != null,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (canUseBiometric) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { prompt() }) {
                        Text(stringResource(R.string.use_biometric))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (CollectionLocks.verifyPin(pin)) onVerified() else error = failedText
                },
                enabled = LockSecurity.isValidPin(pin),
            ) { Text(stringResource(R.string.unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * The gate shown before a locked playlist or album is opened.
 *
 * With biometric unlock on, the system sheet is raised straight away: no custom
 * dialog is drawn first, because a button inside one was the thing that did
 * nothing. Android's own sheet offers fingerprint/face and, when the device has
 * a secure lock screen, its PIN/pattern/password as the same sheet's fallback.
 * Dismissing it, or a device that cannot show it, puts this app's PIN field up
 * instead, with a way back to the system sheet while it is still available.
 */
@Composable
fun UnlockCollectionDialog(
    title: String,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val biometricEnabled by CollectionLocks.biometricEnabled.collectAsStateWithLifecycle()
    val authenticators = remember(context) { BiometricGate.authenticators(context) }
    val canUseSystemSheet = biometricEnabled && authenticators != 0 && activity != null
    val unlockTitle = stringResource(R.string.unlock_collection_title, title)
    val unlockSubtitle = stringResource(R.string.unlock_collection_subtitle)

    // True once the sheet has been shown and answered away: the PIN is then the
    // fallback rather than a second automatic prompt.
    var pinFallback by remember { mutableStateOf(!canUseSystemSheet) }
    var raised by remember { mutableStateOf(false) }

    if (!pinFallback && activity != null) {
        LaunchedEffect(Unit) {
            if (raised) return@LaunchedEffect
            raised = true
            val shown = BiometricGate.authenticate(
                activity = activity,
                title = unlockTitle,
                subtitle = unlockSubtitle,
                authenticators = authenticators,
                onSuccess = onUnlocked,
                onUsePin = { pinFallback = true },
                onFailure = { pinFallback = true },
            )
            if (!shown) pinFallback = true
        }
    } else {
        VerifyLockPinDialog(
            title = unlockTitle,
            subtitle = unlockSubtitle,
            autoPromptBiometric = false,
            showBiometricAction = canUseSystemSheet,
            onDismiss = onDismiss,
            onVerified = onUnlocked,
        )
    }
}

/**
 * Current PIN → new PIN → confirm.
 *
 * When no PIN is set yet the caller raises [SetLockPinDialog] instead, so this
 * dialog is only ever the "change" half of the flow.
 */
@Composable
fun ChangeLockPinDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val title = stringResource(R.string.change_pin)
    val verifySubtitle = stringResource(R.string.change_pin_verify_subtitle)
    var verifying by remember { mutableStateOf(true) }
    var setup by remember { mutableStateOf(false) }

    if (setup) {
        SetLockPinDialog(
            onDismiss = onDismiss,
            onConfirmed = {
                setup = false
                onChanged()
            },
        )
        return
    }

    if (verifying) {
        VerifyLockPinDialog(
            title = title,
            subtitle = verifySubtitle,
            onDismiss = onDismiss,
            onVerified = {
                verifying = false
                setup = true
            },
        )
    }
}

/** Where the locked collection's key and title came from, for the flow's dialogs. */
data class LockRequest(
    val id: String,
    val title: String,
    val onAllowed: () -> Unit,
)

/**
 * The lock flow's state: which dialog is up, and what runs once it passes.
 *
 * Held by the app's composition root so both the collection menus and the
 * cards/pages they open go through one gate, with one PIN prompt.
 */
class LockFlow {
    var unlock by mutableStateOf<LockRequest?>(null)
    var setup by mutableStateOf<LockRequest?>(null)
    var changePin by mutableStateOf(false)
    var toggleBiometric by mutableStateOf(false)

    /**
     * Runs [action] now when the collection is not locked, or after a
     * successful unlock when it is.
     */
    fun guard(collectionId: String?, title: String, action: () -> Unit) {
        if (CollectionLocks.isGated(collectionId)) {
            unlock = LockRequest(collectionId.orEmpty(), title) { action() }
        } else {
            action()
        }
    }

    /**
     * The menu's Lock/Unlock row.
     *
     * Locking with no PIN set first asks for one, and locks only once it is
     * confirmed — a lock nobody can satisfy is worse than no lock. Unlocking an
     * already-locked collection asks for the PIN first.
     */
    fun toggleLock(collectionId: String?, title: String) {
        val id = collectionId ?: return
        if (CollectionLocks.isLocked(id)) {
            unlock = LockRequest(id, title) { CollectionLocks.setLocked(id, false) }
        } else if (!CollectionLocks.hasPin()) {
            setup = LockRequest(id, title) { CollectionLocks.setLocked(id, true) }
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

    fun clear() {
        unlock = null
        setup = null
        changePin = false
        toggleBiometric = false
    }
}

/**
 * Hosts the flow's dialogs. One instance at the app root, so a lock applied
 * from a card and a lock applied from its page open the same Prompt.
 */
@Composable
fun LockFlowHost(flow: LockFlow) {
    flow.unlock?.let { request ->
        UnlockCollectionDialog(
            title = request.title,
            onDismiss = { flow.unlock = null },
            onUnlocked = {
                flow.unlock = null
                // The PIN was given, so this run opens it without asking
                // again — the lock, and its badge, stay on.
                CollectionLocks.grant(request.id)
                request.onAllowed()
            },
        )
    }
    flow.setup?.let { request ->
        SetLockPinDialog(
            onDismiss = { flow.setup = null },
            onConfirmed = {
                flow.setup = null
                request.onAllowed()
            },
        )
    }
    if (flow.changePin) {
        ChangeLockPinDialog(
            onDismiss = { flow.changePin = false },
            onChanged = { flow.changePin = false },
        )
    }
    if (flow.toggleBiometric) {
        val enabling = !CollectionLocks.biometricEnabled.collectAsStateWithLifecycle().value
        VerifyLockPinDialog(
            title = stringResource(R.string.enable_biometric_unlock),
            subtitle = stringResource(R.string.security_biometric_subtitle),
            onDismiss = { flow.toggleBiometric = false },
            onVerified = {
                CollectionLocks.setBiometricEnabled(enabling)
                flow.toggleBiometric = false
            },
        )
    }
}
