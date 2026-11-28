package com.music.bitchord.data.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.PeerListening
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.TimeUnit

/** How often this phone syncs on its own. */
enum class BackupFrequency(val days: Long) {
    OFF(0),
    DAILY(1),
    WEEKLY(7),
    MONTHLY(30),
}

/**
 * Settings → Backup and Restore → Google Drive backup: this phone's listening and
 * settings kept in the listener's Drive under a device of its own, and every
 * other device's listening brought back down to add into the Replay. See
 * [ReplaySync] for how that merges without counting anything twice.
 *
 * Its own preference file rather than AppSettings', for two reasons. A restore
 * replaces AppSettings wholesale, and must not also sign this phone out of
 * Drive, change which device it is, or reset its schedule. And the grant and
 * the identity belong to this install: Auto Backup leaves the file out (see
 * backup_rules.xml), so a reinstall asks which device it is rather than
 * silently writing as one another phone may still be using.
 *
 * Runs started from the screen go on this object's own scope, so they begin
 * the moment the button is pressed and survive leaving the page; the schedule
 * goes through [DriveBackupWorker]. One [Mutex] keeps them from overlapping.
 */
object DriveBackup {

    data class Settings(
        /** The Google account syncing to, or null before one is connected. */
        val account: String? = null,
        val accountName: String? = null,
        /** Drive's own stable id for the account: what the backup key is derived from. */
        val accountKeyId: String? = null,
        val frequency: BackupFrequency = BackupFrequency.DAILY,
        /** Also over mobile data; off, the schedule waits for Wi-Fi. */
        val overCellular: Boolean = false,
        val lastSyncAt: Long = 0L,
        /** Why the last attempt failed, until one succeeds. */
        val lastError: String? = null,
        /** The grant has gone — revoked, or the account removed — and only the screen can ask again. */
        val needsSignIn: Boolean = false,
        val identity: DeviceIdentity = DeviceIdentity(installId = ""),
        /** Set when another phone restored as this one and it carried on under this name. */
        val forkedTo: String? = null,
    ) {
        /** Connected, but not yet told which device this phone is. */
        val needsDevice: Boolean get() = account != null && identity.deviceId == null
    }

    /** What is happening right now, for the progress bar. */
    sealed interface Progress {
        data object Idle : Progress
        data class Running(val step: ReplaySync.Step) : Progress
    }

    /** The devices in Drive, as last looked up. */
    sealed interface Devices {
        data object Unknown : Devices
        data object Checking : Devices
        data class Loaded(val list: List<RemoteDevice>) : Devices
        data class Failed(val message: String) : Devices
    }

    /** How the last run started from the screen went, shown on it once. */
    sealed interface Result {
        data class Synced(val uploaded: Int, val downloaded: Int) : Result
        data class Restored(val summary: Backup.Summary, val device: String) : Result
        data class Failed(val message: String, val restoring: Boolean) : Result
    }

    /** The grant has gone; the run stops and the screen asks again. */
    class NeedsSignIn : Exception("Sign in to Google Drive again to continue")

    /**
     * A backup that will not open until its passphrase is given.
     *
     * Raised as state rather than as a failed run: the passphrase is a
     * question for the listener, not an error to report and forget.
     * [rejected] marks a guess that did not verify, so the prompt can say so.
     */
    data class PassphrasePrompt(
        val context: Context,
        /** The device being restored as, or null when a sync met an encrypted peer. */
        val device: RemoteDevice?,
        val rejected: Boolean,
    ) {
        val restoring: Boolean get() = device != null
    }

    private const val PREFS = "bitchord_drive_backup"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_FREQUENCY = "frequency"
    private const val KEY_CELLULAR = "over_cellular"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_LAST_PULL = "last_pull_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_NEEDS_SIGN_IN = "needs_sign_in"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_ADOPTED_AT = "adopted_manifest_at"
    private const val KEY_FORKED_TO = "forked_to"
    private const val KEY_ACCOUNT_KEY_ID = "account_key_id"
    private const val KEY_RESTORED_ACCOUNT = "restored_account"
    private const val KEY_RESTORED_SNAPSHOT = "restored_snapshot"
    private const val KEY_RESTORED_AT = "restored_at"

    private const val PERIODIC_WORK = "drive_sync"
    private const val CATCH_UP_WORK = "drive_sync_catch_up"
    internal const val KEY_PUSH = "push"

    /** How stale other devices' listening may be when the app comes to the front. */
    private val CATCH_UP_MILLIS = TimeUnit.HOURS.toMillis(12)

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = Mutex()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _devices = MutableStateFlow<Devices>(Devices.Unknown)
    val devices: StateFlow<Devices> = _devices.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    private val _passphrasePrompt = MutableStateFlow<PassphrasePrompt?>(null)
    val passphrasePrompt: StateFlow<PassphrasePrompt?> = _passphrasePrompt.asStateFlow()

    private val _encryption = MutableStateFlow(false)

    /** Whether uploads are sealed at all — the account key, or a passphrase. */
    val encryptionEnabled: StateFlow<Boolean> = _encryption.asStateFlow()

    private val _passphrase = MutableStateFlow(false)

    /**
     * Whether a passphrase is set.
     *
     * Apart from [encryptionEnabled] because the account key now encrypts
     * every upload: the passphrase row must say what the passphrase does, not
     * repeat that the backup is encrypted.
     */
    val passphraseEnabled: StateFlow<Boolean> = _passphrase.asStateFlow()

    /**
     * Reads the settings and keeps the schedule in step with them. Off — no
     * account, no device, or no frequency — this never touches WorkManager on
     * launch. Also pulls other devices' listening when the app comes to the
     * front and it is more than half a day old.
     */
    fun init(app: Application) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Before the first manifest is read: every file may carry a header.
        BackupCipher.init(app)
        _settings.value = read()
        // Re-bound every start: the wrapped key is ordinary app data, so a
        // restore of the account grant without it must still be able to read
        // the backups that account wrote. The derivation itself is stateless.
        _settings.value.accountKeyId?.let { BackupCipher.setAccountKeyId(it) }
        _encryption.value = BackupCipher.encryptionActive()
        _passphrase.value = BackupCipher.isEnabled()
        if (_settings.value.identity.installId.isEmpty()) {
            save { it.copy(identity = it.identity.copy(installId = UUID.randomUUID().toString())) }
        }
        scope.launch {
            var firstLook = true
            settings
                .map { Triple(it.account != null && !it.needsDevice, it.frequency, it.overCellular) }
                .distinctUntilChanged()
                .collect { (ready, frequency, cellular) ->
                    if (ready && frequency != BackupFrequency.OFF) {
                        schedule(app, frequency, cellular, replace = !firstLook)
                    } else if (!firstLook) {
                        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC_WORK)
                    }
                    firstLook = false
                }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val s = _settings.value
                if (s.account == null || s.needsDevice || s.needsSignIn) return
                if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_PULL, 0L) < CATCH_UP_MILLIS) return
                val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                    .setConstraints(constraints(s.overCellular))
                    .setInputData(workDataOf(KEY_PUSH to false))
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(CATCH_UP_WORK, ExistingWorkPolicy.KEEP, request)
            }
        })
        // A phone already connected to Drive picks up the newest encrypted
        // snapshot without a visit to Settings. The per-account guard is what
        // stops it looping on one snapshot, and a phone that has synced is left
        // alone — see [autoRestore].
        scope.launch {
            val s = _settings.value
            if (s.account == null || s.needsSignIn) return@launch
            try {
                autoRestore(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupCipher.PassphraseRequired) {
                _passphrasePrompt.value = PassphrasePrompt(app, null, rejected = false)
            } catch (e: BackupCipher.PassphraseRejected) {
                _passphrasePrompt.value = PassphrasePrompt(app, null, rejected = true)
            } catch (e: Exception) {
                // Startup is not the place to report a Drive hiccup nobody asked about.
            }
        }
    }

    // ---- Settings -----------------------------------------------------------

    fun setFrequency(frequency: BackupFrequency) = save { it.copy(frequency = frequency) }

    fun setOverCellular(on: Boolean) = save { it.copy(overCellular = on) }

    /** Renames this phone as other devices will list it; sent with the next sync. */
    fun rename(name: String) {
        val trimmed = name.trim().take(60)
        if (trimmed.isEmpty()) return
        save { it.copy(identity = it.identity.copy(deviceName = trimmed)) }
    }

    fun clearResult() {
        _result.value = null
    }

    // ---- Encryption ---------------------------------------------------------

    /** Whether a passphrase is also set — the optional layer for older backups. */
    fun hasPassphrase(): Boolean = runCatching { BackupCipher.isEnabled() }.getOrDefault(false)

    /** Whether uploads are sealed with the connected Drive account's key. */
    fun usesAccountKey(): Boolean = runCatching { BackupCipher.hasAccountKey() }.getOrDefault(false)

    /** Sets (or clears, with a blank) the passphrase future backups are sealed with. */
    fun setBackupPassphrase(passphrase: String?): Boolean {
        val accepted = BackupCipher.setPassphrase(passphrase)
        if (accepted) {
            _encryption.value = BackupCipher.encryptionActive()
            _passphrase.value = BackupCipher.isEnabled()
        }
        return accepted
    }

    /** Gives up on the prompt: nothing is retried and no guess is kept. */
    fun dismissPassphrasePrompt() {
        BackupCipher.clearPending()
        _passphrasePrompt.value = null
    }

    /**
     * Tries [passphrase] against the file that asked for it and carries on with
     * what was waiting — the restore, or the sync.
     *
     * A guess only becomes this phone's passphrase once a file actually opened
     * with it, so a wrong one leaves the account's real key alone.
     */
    fun submitPassphrase(passphrase: String) {
        val prompt = _passphrasePrompt.value ?: return
        _passphrasePrompt.value = null
        BackupCipher.tryPassphrase(passphrase)
        val device = prompt.device
        if (device != null) restoreAs(prompt.context, device) else syncNow(prompt.context)
    }

    fun dismissForkNotice() = save { it.copy(forkedTo = null) }

    // ---- Account ------------------------------------------------------------

    /**
     * The first half of connecting: a token straight away if this phone has
     * already granted one, or the consent sheet for the screen to show.
     */
    suspend fun beginSignIn(context: Context): DriveAuth.Outcome =
        DriveAuth.authorize(context, _settings.value.account)

    /** The second half, once the sheet has been answered. */
    suspend fun finishSignIn(context: Context, data: Intent?) {
        when (val outcome = DriveAuth.fromIntent(context, data)) {
            is DriveAuth.Outcome.Granted -> signedIn(context, outcome.accessToken)
            is DriveAuth.Outcome.NeedsConsent -> error("Google Drive access wasn't granted")
        }
    }

    /**
     * Records whose Drive [token] is for and looks up the devices in it. With
     * none there yet, this phone simply becomes the first; otherwise the
     * screen asks which it is.
     */
    suspend fun signedIn(context: Context, token: String) {
        val profile = DriveApi.profile(token)
        val email = profile.email ?: error("Couldn't tell which Google account that was")
        // Bound before anything is read or written, so the first manifest of
        // this run is opened with the key of the account it belongs to.
        val keyId = profile.permissionId ?: email
        BackupCipher.setAccountKeyId(keyId)
        _encryption.value = BackupCipher.encryptionActive()
        save {
            it.copy(
                account = email,
                accountName = profile.name,
                accountKeyId = keyId,
                needsSignIn = false,
                // An error that was only the lost grant is answered by this.
                lastError = if (it.needsSignIn) null else it.lastError,
            )
        }
        refreshDevices(context)
        val found = (_devices.value as? Devices.Loaded)?.list.orEmpty()
        // A Drive that already holds this account's backup comes back down
        // first. Only an empty Drive needs this phone to become a new device;
        // a Drive with devices that could not be restored still asks which.
        val restored = autoRestore(context, found)
        if (!restored && _settings.value.needsDevice && found.isEmpty()) chooseNewDevice(context)
    }

    /** Forgets the account and withdraws the grant. Everything in Drive stays. */
    fun disconnect(context: Context) {
        val account = _settings.value.account ?: return
        // New uploads stop being sealed with that account's key. The files it
        // already wrote keep their header and open when it is connected again.
        BackupCipher.clearAccountKey()
        _encryption.value = BackupCipher.encryptionActive()
        save {
            Settings(
                frequency = it.frequency,
                overCellular = it.overCellular,
                identity = it.identity,
            )
        }
        _devices.value = Devices.Unknown
        PeerListening.clear()
        ListeningStats.peersChanged()
        scope.launch { DriveAuth.revoke(context.applicationContext, account) }
    }

    // ---- Devices ------------------------------------------------------------

    /** Looks up the devices in Drive, for the device list and the chooser. */
    suspend fun refreshDevices(context: Context) {
        if (_settings.value.account == null) {
            _devices.value = Devices.Unknown
            return
        }
        _devices.value = Devices.Checking
        _devices.value = try {
            Devices.Loaded(withToken(context) { ReplaySync.devices(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupCipher.PassphraseRequired) {
            // The devices are there; none of their manifests can be read yet.
            _passphrasePrompt.value = PassphrasePrompt(context.applicationContext, null, rejected = false)
            Devices.Failed(e.message ?: "This backup is encrypted")
        } catch (e: BackupCipher.PassphraseRejected) {
            _passphrasePrompt.value = PassphrasePrompt(context.applicationContext, null, rejected = true)
            Devices.Failed(e.message ?: "This backup is encrypted")
        } catch (e: Exception) {
            Devices.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Backs this phone up as a device of its own. Its listening stays its own;
     * every other device's is added to the Replay alongside it.
     */
    fun chooseNewDevice(context: Context) {
        if (_settings.value.identity.deviceId != null) return
        save {
            it.copy(
                identity = it.identity.copy(
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = it.identity.deviceName.ifBlank { deviceName() },
                    adoptedManifestAt = null,
                ),
            )
        }
        // First upload, and every other device's listening down, straight
        // away rather than whenever the schedule next comes round.
        syncNow(context)
    }

    /** Makes this phone [device]: see [ReplaySync.restoreAs]. */
    fun restoreAs(context: Context, device: RemoteDevice) {
        val app = context.applicationContext
        scope.launch {
            if (!running.tryLock()) return@launch
            var restored = false
            try {
                // A phone that has never synced has listening that is in no
                // device yet, so it is kept and added to what comes down.
                val keepLocal = _settings.value.identity.deviceId == null || !ReplaySync.hasSynced(app)
                val (identity, summary) = withToken(app) { token ->
                    ReplaySync.restoreAs(app, token, _settings.value.identity, device, keepLocal) { step ->
                        _progress.value = Progress.Running(step)
                    }
                }
                save { it.copy(identity = identity, forkedTo = null) }
                recordRestored(device)
                _result.value = Result.Restored(summary, device.name)
                restored = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsSignIn) {
                // Already marked on the settings; the screen asks.
            } catch (e: BackupCipher.PassphraseRequired) {
                _passphrasePrompt.value = PassphrasePrompt(app, device, rejected = false)
            } catch (e: BackupCipher.PassphraseRejected) {
                _passphrasePrompt.value = PassphrasePrompt(app, device, rejected = true)
            } catch (e: BackupCipher.UnreadableBackup) {
                _result.value = Result.Failed(e.message ?: "The backup could not be read", restoring = true)
            } catch (e: Exception) {
                _result.value = Result.Failed(e.message ?: e.javaClass.simpleName, restoring = true)
            } finally {
                _progress.value = Progress.Idle
                running.unlock()
                // A passphrase this run used is adopted only once a file opened
                // with it, which is inside the run.
                _passphrase.value = BackupCipher.isEnabled()
            }
            // Then a sync at once: this phone takes ownership of the device in
            // Drive, and every other device's listening comes down. Run
            // directly rather than through syncNow, so the line on the page
            // keeps saying what the restore did.
            if (restored) runSync(app, push = true)
        }
    }

    /**
     * Deletes [device]'s backup from Drive: its listening leaves the Replay on
     * every other phone.
     *
     * This phone's own backup can go too. Its Replay keeps everything — only
     * the copy in Drive is deleted — and auto sync is switched off, or the
     * next run would simply upload it all again. Syncing by hand starts a
     * fresh backup under the same device.
     */
    fun remove(context: Context, device: RemoteDevice) {
        val app = context.applicationContext
        val mine = device.deviceId == _settings.value.identity.deviceId
        scope.launch {
            if (!running.tryLock()) return@launch
            try {
                withToken(app) { ReplaySync.remove(it, device) }
                if (mine) {
                    // Nothing is uploaded any more, so the next sync sends every month.
                    ReplaySync.clearSynced(app)
                    save {
                        it.copy(
                            frequency = BackupFrequency.OFF,
                            lastSyncAt = 0L,
                            lastError = null,
                            identity = it.identity.copy(adoptedManifestAt = null),
                        )
                    }
                }
                refreshDevices(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsSignIn) {
                // The screen asks.
            } catch (e: Exception) {
                _result.value = Result.Failed(e.message ?: e.javaClass.simpleName, restoring = false)
            } finally {
                running.unlock()
            }
        }
    }

    // ---- Running ------------------------------------------------------------

    /** Syncs now, on this object's scope so leaving the screen doesn't stop it. */
    fun syncNow(context: Context) {
        val app = context.applicationContext
        scope.launch {
            _result.value = when (val outcome = runSync(app, push = true)) {
                is RunOutcome.Done -> Result.Synced(outcome.uploaded, outcome.downloaded)
                is RunOutcome.Failed -> Result.Failed(outcome.message, restoring = false)
                RunOutcome.Busy, RunOutcome.NeedsSignIn, RunOutcome.NotReady -> null
            }
        }
    }

    internal sealed interface RunOutcome {
        data class Done(val uploaded: Int, val downloaded: Int) : RunOutcome
        data class Failed(val message: String, val retryable: Boolean) : RunOutcome
        data object NeedsSignIn : RunOutcome
        data object NotReady : RunOutcome
        data object Busy : RunOutcome
    }

    /** One sync; with [push] false, only other devices' listening comes down. */
    internal suspend fun runSync(context: Context, push: Boolean): RunOutcome {
        val s = _settings.value
        if (s.account == null) return RunOutcome.NeedsSignIn
        if (s.needsDevice) return RunOutcome.NotReady
        if (!running.tryLock()) return RunOutcome.Busy
        return try {
            val report = withToken(context) { token ->
                ReplaySync.sync(context, token, _settings.value.identity, push) { step ->
                    _progress.value = Progress.Running(step)
                }
            }
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_PULL, now) }
            save {
                it.copy(
                    identity = report.identity,
                    lastSyncAt = if (push) now else it.lastSyncAt,
                    lastError = null,
                    forkedTo = report.forkedTo ?: it.forkedTo,
                )
            }
            _devices.value = Devices.Loaded(report.devices)
            RunOutcome.Done(report.uploaded, report.downloaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignIn) {
            RunOutcome.NeedsSignIn
        } catch (e: BackupCipher.PassphraseRequired) {
            // A peer's files are encrypted and this phone has no passphrase for
            // them yet. The run stops where it is; the prompt continues it.
            _passphrasePrompt.value = PassphrasePrompt(context.applicationContext, null, rejected = false)
            RunOutcome.Failed(e.message ?: "This backup is encrypted", retryable = false)
        } catch (e: BackupCipher.PassphraseRejected) {
            _passphrasePrompt.value = PassphrasePrompt(context.applicationContext, null, rejected = true)
            RunOutcome.Failed(e.message ?: "This backup is encrypted", retryable = false)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            save { it.copy(lastError = message) }
            // A refusal (quota full, say) won't change by asking again in a
            // minute; a dropped connection or a Drive hiccup might.
            RunOutcome.Failed(message, retryable = e is java.io.IOException && e !is DriveApi.DriveError)
        } finally {
            _progress.value = Progress.Idle
            running.unlock()
            _passphrase.value = BackupCipher.isEnabled()
        }
    }

    /**
     * Whether the schedule is due. A sync made by hand counts, so pressing
     * the button the day before a weekly run doesn't make two.
     */
    internal fun scheduledRunDue(): Boolean {
        val s = _settings.value
        if (s.account == null || s.needsDevice || s.frequency == BackupFrequency.OFF) return false
        val period = TimeUnit.DAYS.toMillis(s.frequency.days)
        return System.currentTimeMillis() - s.lastSyncAt >= period / 2
    }

    // ---- Automatic restore ---------------------------------------------------

    /**
     * Restores this install from Drive when the connected account holds a
     * genuinely newer encrypted snapshot than the one it last restored.
     *
     * Only a phone that has never uploaded anything is a candidate: once this
     * install owns a device its own listening is the truth, and quietly
     * replacing it with a peer's would lose whatever it played since. The guard
     * is scoped to the account, so connecting a second account does not skip
     * its own snapshot, and one snapshot is never restored twice. A backup that
     * will not open is reported as a message and leaves local data alone.
     */
    private suspend fun autoRestore(context: Context, known: List<RemoteDevice>? = null): Boolean {
        val app = context.applicationContext
        val s = _settings.value
        if (s.account == null || s.needsSignIn) return false
        if (ReplaySync.hasSynced(app)) return false
        return try {
            val devices = known ?: withToken(app) { ReplaySync.devices(it) }
            val guardAccount = prefs.getString(KEY_RESTORED_ACCOUNT, null)
            val guardId = if (guardAccount == s.account) prefs.getString(KEY_RESTORED_SNAPSHOT, null) else null
            val guardAt = if (guardAccount == s.account) prefs.getLong(KEY_RESTORED_AT, 0L) else 0L
            val candidate = devices
                .filter { it.deviceId != s.identity.deviceId && it.encrypted && it.updatedAt > 0L }
                .filter { it.updatedAt > guardAt && snapshotId(it) != guardId }
                .maxByOrNull { it.updatedAt }
                ?: return false
            if (!running.tryLock()) return false
            try {
                val (identity, summary) = withToken(app) { token ->
                    ReplaySync.restoreAs(app, token, s.identity, candidate, keepLocal = true) { step ->
                        _progress.value = Progress.Running(step)
                    }
                }
                save { it.copy(identity = identity, forkedTo = null) }
                recordRestored(candidate)
                _result.value = Result.Restored(summary, candidate.name)
            } finally {
                _progress.value = Progress.Idle
                running.unlock()
            }
            // Claim ownership of the restored device straight away, so the
            // next device that syncs sees this install as its writer.
            runSync(app, push = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupCipher.PassphraseRequired) {
            _passphrasePrompt.value = PassphrasePrompt(app, null, rejected = false)
            false
        } catch (e: BackupCipher.PassphraseRejected) {
            _passphrasePrompt.value = PassphrasePrompt(app, null, rejected = true)
            false
        } catch (e: BackupCipher.AccountKeyRequired) {
            _result.value = Result.Failed(e.message ?: "This backup is encrypted", restoring = true)
            false
        } catch (e: BackupCipher.DecryptionFailed) {
            _result.value = Result.Failed(e.message ?: "This backup could not be decrypted", restoring = true)
            false
        } catch (e: Exception) {
            // A connect is not the place to report a Drive hiccup; the screen
            // still shows the account and the sync button.
            false
        }
    }

    /** What tells two snapshots of one device apart: its id and its stamp. */
    private fun snapshotId(device: RemoteDevice): String = "${device.deviceId}@${device.updatedAt}"

    /** Remembers the snapshot this account restored, so it is never restored twice. */
    private fun recordRestored(device: RemoteDevice) {
        prefs.edit {
            putString(KEY_RESTORED_ACCOUNT, _settings.value.account)
            putString(KEY_RESTORED_SNAPSHOT, snapshotId(device))
            putLong(KEY_RESTORED_AT, device.updatedAt)
        }
    }

    // ---- Plumbing -----------------------------------------------------------

    /**
     * Runs [block] with a token for the connected account, minting a second
     * one if Drive refuses the first — tokens last an hour, and Play services
     * will hand back a cached one that has just run out.
     */
    private suspend fun <T> withToken(context: Context, block: suspend (String) -> T): T {
        val first = token(context)
        bindAccountKey(first)
        return try {
            block(first)
        } catch (e: DriveApi.Unauthorized) {
            DriveAuth.clearToken(context, first)
            val second = token(context)
            bindAccountKey(second)
            block(second)
        }
    }

    /**
     * Binds the backup key to the account the first time a token is used.
     *
     * An install that connected Drive before account encryption existed has no
     * key id stored. Resolving it here — from Drive's own permission id, the
     * same value every other device resolves — is what makes that install start
     * writing sealed files too, and keeps both phones deriving one key rather
     * than one using an email and the other an id.
     */
    private suspend fun bindAccountKey(token: String) {
        if (_settings.value.accountKeyId != null) return
        val profile = runCatching { DriveApi.profile(token) }.getOrNull() ?: return
        val id = profile.permissionId ?: profile.email ?: _settings.value.account ?: return
        BackupCipher.setAccountKeyId(id)
        _encryption.value = BackupCipher.encryptionActive()
        save { it.copy(accountKeyId = id) }
    }

    private suspend fun token(context: Context): String {
        val account = _settings.value.account ?: throw NeedsSignIn()
        return when (val outcome = DriveAuth.authorize(context, account)) {
            is DriveAuth.Outcome.Granted -> outcome.accessToken
            is DriveAuth.Outcome.NeedsConsent -> {
                save { it.copy(needsSignIn = true) }
                throw NeedsSignIn()
            }
        }
    }

    /** "Google Pixel 8", or "Pixel 8" alone where the model already names its maker. */
    internal fun deviceName(): String {
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase() }
        return if (maker.isBlank() || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }

    private fun constraints(cellular: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    private fun schedule(context: Context, frequency: BackupFrequency, cellular: Boolean, replace: Boolean) {
        val period = frequency.days * 24
        // Android batches the run with whatever else is waking the phone in
        // the last part of the period: a few hours for a daily sync, a day or
        // so for the longer ones.
        val flex = when (frequency) {
            BackupFrequency.DAILY -> 6L
            BackupFrequency.WEEKLY -> 24L
            else -> 72L
        }
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(period, TimeUnit.HOURS, flex, TimeUnit.HOURS)
            .setConstraints(constraints(cellular))
            .setInputData(workDataOf(KEY_PUSH to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun read(): Settings = Settings(
        account = prefs.getString(KEY_ACCOUNT, null),
        accountName = prefs.getString(KEY_ACCOUNT_NAME, null),
        frequency = prefs.getString(KEY_FREQUENCY, null)
            ?.let { name -> BackupFrequency.entries.firstOrNull { it.name == name } }
            ?: BackupFrequency.DAILY,
        accountKeyId = prefs.getString(KEY_ACCOUNT_KEY_ID, null),
        overCellular = prefs.getBoolean(KEY_CELLULAR, false),
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
        needsSignIn = prefs.getBoolean(KEY_NEEDS_SIGN_IN, false),
        identity = DeviceIdentity(
            installId = prefs.getString(KEY_INSTALL_ID, null).orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            deviceName = prefs.getString(KEY_DEVICE_NAME, null).orEmpty(),
            adoptedManifestAt = prefs.getString(KEY_ADOPTED_AT, null),
        ),
        forkedTo = prefs.getString(KEY_FORKED_TO, null),
    )

    private fun save(change: (Settings) -> Settings) {
        _settings.update(change)
        if (!this::prefs.isInitialized) return
        val s = _settings.value
        prefs.edit {
            putString(KEY_ACCOUNT, s.account)
            putString(KEY_ACCOUNT_NAME, s.accountName)
            putString(KEY_ACCOUNT_KEY_ID, s.accountKeyId)
            putString(KEY_FREQUENCY, s.frequency.name)
            putBoolean(KEY_CELLULAR, s.overCellular)
            putLong(KEY_LAST_SYNC, s.lastSyncAt)
            putString(KEY_LAST_ERROR, s.lastError)
            putBoolean(KEY_NEEDS_SIGN_IN, s.needsSignIn)
            putString(KEY_INSTALL_ID, s.identity.installId)
            putString(KEY_DEVICE_ID, s.identity.deviceId)
            putString(KEY_DEVICE_NAME, s.identity.deviceName)
            putString(KEY_ADOPTED_AT, s.identity.adoptedManifestAt)
            putString(KEY_FORKED_TO, s.forkedTo)
        }
    }
}

/**
 * The scheduled half of [DriveBackup]: a full sync daily, weekly or monthly as
 * chosen, or — queued when the app opens — a pull of other devices' listening.
 */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val push = inputData.getBoolean(DriveBackup.KEY_PUSH, true)
        if (push && !DriveBackup.scheduledRunDue()) return Result.success()
        return when (val outcome = DriveBackup.runSync(applicationContext, push)) {
            is DriveBackup.RunOutcome.Failed ->
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            // Busy: the screen is already running one. NeedsSignIn / NotReady:
            // the screen asks, and no retry will help.
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
