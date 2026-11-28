package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.backup.BackupCipher
import com.music.bitchord.R
import com.music.bitchord.data.backup.DriveBackup
import com.music.bitchord.data.backup.RemoteDevice
import com.music.bitchord.ui.components.ConfirmationAlert
import com.music.bitchord.ui.components.TextValueAlert
import dev.chrisbanes.haze.HazeState

/**
 * The questions Google Drive backup asks — disconnect, remove a device,
 * restore as one, rename this phone — as the one piece of state the app's
 * root holds for them. One holder rather than four `remember`s: the root
 * composable is already as large as the JVM lets a method be.
 */
@Stable
class DriveBackupAlerts {
    var disconnect by mutableStateOf(false)
    var remove by mutableStateOf<RemoteDevice?>(null)
    var restore by mutableStateOf<RemoteDevice?>(null)

    /** This phone's device name being edited, or null. */
    var renaming by mutableStateOf<String?>(null)

    /** The backup passphrase being typed, or null when its alert is closed. Empty opens it. */
    var settingPassphrase by mutableStateOf<String?>(null)
}

/**
 * Draws whichever of [alerts] is up, in the app's frosted alert like every
 * other confirmation — which is why it lives at the root, where [hazeState]
 * is, rather than on the pages that raise them.
 *
 * [onRestoreStarted] lets the root step back from the device chooser to the
 * backup page, where the progress bar shows the restore running.
 */
@Composable
fun DriveBackupAlertsHost(
    alerts: DriveBackupAlerts,
    hazeState: HazeState,
    onRestoreStarted: () -> Unit,
) {
    val context = LocalContext.current

    if (alerts.disconnect) {
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.drive_disconnect_confirm_title),
            description = stringResource(R.string.drive_disconnect_confirm_body),
            confirmLabel = stringResource(R.string.disconnect),
            onConfirm = {
                DriveBackup.clearResult()
                DriveBackup.disconnect(context)
                alerts.disconnect = false
            },
            onDismiss = { alerts.disconnect = false },
        )
    }

    alerts.remove?.let { device ->
        val mine = device.deviceId == DriveBackup.settings.value.identity.deviceId
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.drive_remove_confirm_title, device.name),
            description = stringResource(
                if (mine) R.string.drive_delete_mine_body else R.string.drive_remove_confirm_body,
            ),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                DriveBackup.remove(context, device)
                alerts.remove = null
            },
            onDismiss = { alerts.remove = null },
        )
    }

    alerts.restore?.let { device ->
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.drive_restore_confirm_title, device.name),
            description = stringResource(R.string.drive_restore_confirm_body, device.name),
            confirmLabel = stringResource(R.string.drive_restore_action),
            onConfirm = {
                DriveBackup.clearResult()
                DriveBackup.restoreAs(context, device)
                alerts.restore = null
                onRestoreStarted()
            },
            onDismiss = { alerts.restore = null },
        )
    }

    // A backup that is encrypted asks for the passphrase that opens it, and
    // carries on with the restore or sync once it is given.
    val prompt by DriveBackup.passphrasePrompt.collectAsStateWithLifecycle()
    prompt?.let { waiting ->
        var typed by androidx.compose.runtime.remember(waiting) { mutableStateOf("") }
        TextValueAlert(
            hazeState = hazeState,
            title = stringResource(R.string.backup_passphrase_set_title),
            message = stringResource(
                if (waiting.rejected) R.string.backup_passphrase_wrong else R.string.backup_passphrase_required,
            ),
            placeholder = stringResource(R.string.backup_passphrase_hint),
            value = typed,
            onValueChange = { typed = it },
            onSave = { if (typed.isNotBlank()) DriveBackup.submitPassphrase(typed) },
            onDismiss = { DriveBackup.dismissPassphrasePrompt() },
            saveEnabled = typed.length >= BackupCipher.MIN_PASSPHRASE_LENGTH,
            masked = true,
        )
    }

    alerts.settingPassphrase?.let { typed ->
        val enabled = DriveBackup.hasPassphrase()
        TextValueAlert(
            hazeState = hazeState,
            title = stringResource(R.string.backup_passphrase_set_title),
            message = stringResource(R.string.backup_passphrase_set_body),
            placeholder = stringResource(R.string.backup_passphrase_hint),
            value = typed,
            onValueChange = { alerts.settingPassphrase = it },
            onSave = {
                if (DriveBackup.setBackupPassphrase(typed)) alerts.settingPassphrase = null
            },
            // A blank passphrase turns encryption off; the button says so.
            onRemove = if (enabled) {
                {
                    DriveBackup.setBackupPassphrase(null)
                    alerts.settingPassphrase = null
                }
            } else {
                null
            },
            onDismiss = { alerts.settingPassphrase = null },
            // Off (blank) is allowed to save; a one-character passphrase is not.
            saveEnabled = typed.isBlank() || typed.length >= BackupCipher.MIN_PASSPHRASE_LENGTH,
            masked = true,
        )
    }

    alerts.renaming?.let { name ->
        TextValueAlert(
            hazeState = hazeState,
            title = stringResource(R.string.drive_rename_title),
            message = stringResource(R.string.drive_rename_message),
            placeholder = DriveBackup.deviceName(),
            value = name,
            onValueChange = { alerts.renaming = it.take(60) },
            onSave = {
                DriveBackup.rename(name)
                alerts.renaming = null
            },
            onDismiss = { alerts.renaming = null },
            saveEnabled = name.isNotBlank(),
        )
    }
}
