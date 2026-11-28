package com.music.bitchord.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.security.CollectionLocks
import com.music.bitchord.data.security.LockSecurity

/**
 * The device's biometric hardware, asked about without pulling in a dependency.
 *
 * Android's own [BiometricPrompt] arrived in API 28; below that, and on a
 * device with no fingerprint/face/iris sensor or no secure lock screen, the
 * flow falls back to the PIN immediately rather than promising something the
 * device cannot do.
 */
object BiometricGate {

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        if (manager.isDeviceSecure.not()) return false
        val packages = context.packageManager
        return packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
            packages.hasSystemFeature(PackageManager.FEATURE_FACE) ||
            packages.hasSystemFeature(PackageManager.FEATURE_IRIS)
    }

    /**
     * Shows the system biometric sheet. Returns false when it could not be
     * raised, so the caller can put the PIN field in front of the user instead
     * of leaving them staring at a dialog that does nothing.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onUsePin: () -> Unit,
        onFailure: (String) -> Unit,
    ): Boolean {
        val executor = activity.mainExecutor
        val prompt = runCatching {
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton(
                    activity.getString(R.string.use_pin),
                    executor,
                ) { _, _ -> onUsePin() }
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            prompt.authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }
            })
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
 * Biometrics are offered first when they are enabled and the hardware is
 * there, with the PIN field visible underneath as the immediate fallback: a
 * fingerprint that fails three times must not close the only way in.
 */
@Composable
fun VerifyLockPinDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val biometricEnabled by CollectionLocks.biometricEnabled.collectAsStateWithLifecycle()
    val hardware = remember(context) { BiometricGate.isAvailable(context) }
    val canUseBiometric = biometricEnabled && hardware && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var prompted by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.lock_pin_incorrect)

    fun prompt() {
        if (!canUseBiometric || activity == null) return
        val raised = BiometricGate.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle.orEmpty(),
            onSuccess = { onVerified() },
            onUsePin = { },
            onFailure = { message -> if (message.isNotBlank()) error = message },
        )
        if (!raised) error = null
    }

    LaunchedEffect(Unit) {
        if (canUseBiometric && !prompted) {
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

/** The PIN/biometric gate shown before a locked playlist or album is opened. */
@Composable
fun UnlockCollectionDialog(
    title: String,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    VerifyLockPinDialog(
        title = stringResource(R.string.unlock_collection_title, title),
        subtitle = stringResource(R.string.unlock_collection_subtitle),
        onDismiss = onDismiss,
        onVerified = onUnlocked,
    )
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
