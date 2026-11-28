package com.music.bitchord.data.backup

import android.content.Context
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.PlaylistCoverStore
import com.music.bitchord.data.security.CollectionLocks
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.data.stats.BucketMath
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.PeerListening
import com.music.bitchord.data.stats.StoredBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Who this install is, as far as sync is concerned.
 *
 * [deviceId] names the *partition* this phone writes — the only listening it
 * ever uploads — and is null until the listener has chosen to back up as a new
 * device or restore as an existing one. [installId] names this install, and is
 * what a device's manifest records as its owner: when someone restores *as*
 * this device on another phone, the owner changes, and this install finds out
 * on its next sync (see [ReplaySync.sync]).
 */
data class DeviceIdentity(
    val installId: String,
    val deviceId: String? = null,
    val deviceName: String = "",
    /**
     * The manifest's Drive timestamp when this install adopted [deviceId], or
     * null for one it created. A manifest owned by another install and stamped
     * differently means another phone has written it since.
     */
    val adoptedManifestAt: String? = null,
)

/**
 * One device's record in Drive, written *after* its months: a peer reads only
 * the months listed here, so a sync cut off halfway is never half-read.
 */
@Serializable
data class DeviceManifest(
    val schema: Int = SCHEMA,
    val deviceId: String,
    val name: String = "",
    val ownerInstall: String = "",
    val updatedAt: Long = 0L,
    val versionName: String = "",
    /** Month → SHA-256 of its canonical bytes. */
    val months: Map<String, String> = emptyMap(),
    /** That device's settings, restored only by restoring as it. */
    val settings: Map<String, Backup.PrefValue> = emptyMap(),
    /**
     * Cover registry key → SHA-256 of its file: the custom playlist and album
     * covers this device has uploaded. A manifest written before covers were
     * backed up has none, and a restore of one simply finds no covers to bring
     * back — the rest of it is read exactly as before.
     */
    val covers: Map<String, String> = emptyMap(),
    /**
     * The collection ids this device has locked behind its PIN.
     *
     * The PIN itself deliberately never travels — only the list of what it
     * guards. A restore re-applies the list, so a locked playlist comes back
     * locked and asks the restoring device's own PIN; a manifest written
     * before locks existed has none and restores as it always did.
     */
    val locked: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

/** A device found in Drive, for the device list and the chooser. */
data class RemoteDevice(
    val deviceId: String,
    val name: String,
    val updatedAt: Long,
    val months: Int,
    val sizeBytes: Long,
    val manifest: DeviceManifest,
)

/**
 * What a sync has to do, worked out from what is here and what is in Drive
 * with no I/O of its own, so it can be tested on its own. See [ReplaySync].
 */
internal object SyncPlanner {

    data class Push(val upload: List<String>, val delete: List<String>)

    /**
     * This device's months to upload — changed since last uploaded, or
     * missing from Drive — and to delete, pruned here but still in Drive.
     */
    fun push(local: Map<String, String>, synced: Map<String, String>, remote: Set<String>): Push = Push(
        upload = local.filter { (month, hash) -> synced[month] != hash || month !in remote }.keys.sorted(),
        delete = (remote - local.keys).sorted(),
    )

    data class Pull(val download: List<String>, val drop: List<String>)

    /**
     * One other device's months to fetch — listed in its manifest, present in
     * Drive, and changed since the copy held — and to drop, held here but no
     * longer listed.
     */
    fun pull(listed: Set<String>, remoteMd5: Map<String, String?>, held: Map<String, String>): Pull {
        val available = listed.filter { it in remoteMd5 }.toSet()
        return Pull(
            download = available.filter { held[it] == null || held[it] != remoteMd5[it] }.sorted(),
            drop = (held.keys - available).sorted(),
        )
    }

    /**
     * Which covers this device has to upload — every one whose hash its
     * manifest does not already record — and which to delete, pruned here but
     * still in Drive.
     */
    fun covers(local: Map<String, String>, recorded: Map<String, String>): Push = Push(
        upload = local.filter { (key, hash) -> recorded[key] != hash }.keys.sorted(),
        delete = (recorded.keys - local.keys).sorted(),
    )

    /** Whether another install has restored as this device since this one took it. */
    fun supersededBy(identity: DeviceIdentity, manifest: DeviceManifest?, manifestAt: String?): Boolean =
        manifest != null &&
            manifest.ownerInstall.isNotEmpty() &&
            manifest.ownerInstall != identity.installId &&
            manifestAt != identity.adoptedManifestAt
}

/**
 * Keeps this account's devices' listening in step through Google Drive.
 *
 * Each device writes only its own partition — `replay-<deviceId>-<month>` plus
 * `device-<deviceId>` — and downloads everybody else's into [PeerListening],
 * which the Replay adds in when it is read. Because nothing is ever folded
 * into anyone else's partition, every play is in exactly one place, and a
 * sync can run any number of times in any order without changing the totals.
 */
object ReplaySync {

    private const val KIND = "kind"
    private const val KIND_DEVICE = "device"
    private const val KIND_REPLAY = "replay"
    private const val KIND_COVER = "cover"
    private const val DEVICE_ID = "deviceId"
    private const val MONTH = "month"
    private const val TARGET = "target"
    private const val SYNCED_DIR = "listening-synced"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    sealed interface Step {
        data object Preparing : Step
        data class Uploading(val done: Int, val total: Int) : Step
        data class Downloading(val device: String, val done: Int, val total: Int) : Step
        data object Restoring : Step
    }

    data class Report(
        val identity: DeviceIdentity,
        val uploaded: Int,
        val downloaded: Int,
        val devices: List<RemoteDevice>,
        /** Set when another phone restored as this device: the name this one forked to. */
        val forkedTo: String? = null,
    )

    // ---- Listing ------------------------------------------------------------

    /** What Drive holds, sorted into devices. */
    private class Listing(files: List<DriveApi.DriveFile>) {
        val manifests: Map<String, DriveApi.DriveFile> = files
            .filter { it.properties[KIND] == KIND_DEVICE }
            .mapNotNull { file -> file.properties[DEVICE_ID]?.let { it to file } }
            .toMap()

        /** Device → month → file. */
        val months: Map<String, Map<String, DriveApi.DriveFile>> = files
            .filter { it.properties[KIND] == KIND_REPLAY }
            .groupBy { it.properties[DEVICE_ID].orEmpty() }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, list) -> list.mapNotNull { f -> f.properties[MONTH]?.let { it to f } }.toMap() }

        /** Device → cover key → file. */
        val covers: Map<String, Map<String, DriveApi.DriveFile>> = files
            .filter { it.properties[KIND] == KIND_COVER }
            .groupBy { it.properties[DEVICE_ID].orEmpty() }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, list) -> list.mapNotNull { f -> f.properties[TARGET]?.let { it to f } }.toMap() }
    }

    /**
     * One device's manifest, decrypted when it carries a header and read as it
     * is when it is one of the older unencrypted files. A manifest sealed with a
     * passphrase this device has not been given throws
     * [BackupCipher.PassphraseRequired], which the screen turns into a prompt.
     */
    private suspend fun readManifest(token: String, file: DriveApi.DriveFile): DeviceManifest =
        json.decodeFromString(
            DeviceManifest.serializer(),
            gunzip(BackupCipher.open(DriveApi.download(token, file.id))).decodeToString(),
        )

    /** Every device in Drive, this one included, newest sync first. */
    suspend fun devices(token: String): List<RemoteDevice> {
        val listing = Listing(DriveApi.listAll(token))
        return devicesOf(token, listing)
    }

    private suspend fun devicesOf(token: String, listing: Listing): List<RemoteDevice> =
        listing.manifests.mapNotNull { (id, file) ->
            val manifest = runCatching { readManifest(token, file) }.getOrNull() ?: return@mapNotNull null
            val files = listing.months[id].orEmpty()
            RemoteDevice(
                deviceId = id,
                name = manifest.name,
                updatedAt = manifest.updatedAt,
                months = manifest.months.keys.count { it in files },
                sizeBytes = files.values.sumOf { it.sizeBytes } +
                    listing.covers[id].orEmpty().values.sumOf { it.sizeBytes } +
                    file.sizeBytes,
                manifest = manifest,
            )
        }.sortedByDescending { it.updatedAt }

    // ---- Sync ---------------------------------------------------------------

    /**
     * One sync. With [push], this device's changed months and its manifest go
     * up first; then every other device's changed months come down.
     */
    suspend fun sync(
        context: Context,
        token: String,
        identity: DeviceIdentity,
        push: Boolean,
        onStep: (Step) -> Unit,
    ): Report = withContext(Dispatchers.IO) {
        onStep(Step.Preparing)
        var me = identity
        val deviceId = me.deviceId ?: error("Choose this phone's device first")
        var listing = Listing(DriveApi.listAll(token))
        var forkedTo: String? = null

        // Another phone has restored as this device since this one last
        // synced. Whatever was played here since that last sync is this
        // phone's alone, so it carries on as a device of its own with exactly
        // that — not the whole history, which is now the other phone's.
        val ownManifestFile = listing.manifests[deviceId]
        val ownManifest = ownManifestFile?.let { runCatching { readManifest(token, it) }.getOrNull() }
        if (SyncPlanner.supersededBy(me, ownManifest, ownManifestFile?.modifiedAt)) {
            val unsynced = ListeningStats.exportAll().mapNotNull { month ->
                val before = synced(context, month.month)
                val left = if (before != null) BucketMath.minus(month, before) else month
                left.takeUnless(BucketMath::isEmpty)
            }
            ListeningStats.importAll(unsynced)
            clearSynced(context)
            val name = "${me.deviceName.ifBlank { DriveBackup.deviceName() }} (2)"
            me = me.copy(deviceId = UUID.randomUUID().toString(), deviceName = name, adoptedManifestAt = null)
            forkedTo = name
        }
        val myId = me.deviceId!!

        var uploaded = 0
        if (push) {
            val own = ListeningStats.exportAll()
            val bytes = own.associate { it.month to BucketMath.canonicalBytes(it) }
            val hashes = bytes.mapValues { BucketMath.hash(it.value) }
            val remoteOwn = listing.months[myId].orEmpty()
            val plan = SyncPlanner.push(
                local = hashes,
                synced = hashes.keys.mapNotNull { m -> syncedHash(context, m)?.let { m to it } }.toMap(),
                remote = remoteOwn.keys,
            )
            plan.upload.forEachIndexed { index, month ->
                onStep(Step.Uploading(index, plan.upload.size))
                val unsealed = gzip(bytes.getValue(month))
                val payload = BackupCipher.seal(unsealed)
                // The gzipped month is the only unencrypted copy that ever
                // leaves memory's owner; it is wiped once it has been sealed.
                if (payload !== unsealed) unsealed.fill(0)
                DriveApi.put(
                    token = token,
                    name = "replay-$myId-$month.json.gz",
                    bytes = payload,
                    properties = mapOf(KIND to KIND_REPLAY, DEVICE_ID to myId, MONTH to month),
                    existingId = remoteOwn[month]?.id,
                )
                writeSynced(context, month, bytes.getValue(month))
                uploaded++
            }
            plan.delete.forEach { month ->
                remoteOwn[month]?.let { DriveApi.delete(token, it.id) }
                File(syncedDir(context), "$month.json").delete()
            }
            // Covers go up before the manifest for the same reason the months
            // do: the manifest is what tells another device they exist. Only
            // the ones whose bytes changed are sent, so editing one playlist's
            // artwork does not re-upload the rest.
            val localCovers = PlaylistCoverStore.exportCovers()
            val remoteCovers = listing.covers[myId].orEmpty()
            val coverPlan = SyncPlanner.covers(
                local = localCovers.mapValues { it.value.hash },
                recorded = ownManifest?.covers.orEmpty(),
            )
            val manifestCovers = buildMap {
                coverPlan.upload.forEach { key ->
                    val cover = localCovers[key] ?: return@forEach
                    val bytes = runCatching { File(cover.path).readBytes() }.getOrNull()
                        ?: return@forEach
                    val unsealed = gzip(bytes)
                    val payload = BackupCipher.seal(unsealed)
                    if (payload !== unsealed) unsealed.fill(0)
                    val written = runCatching {
                        DriveApi.put(
                            token = token,
                            name = "cover-$myId-${cover.hash.take(16)}.webp.gz",
                            bytes = payload,
                            properties = mapOf(KIND to KIND_COVER, DEVICE_ID to myId, TARGET to key),
                            existingId = remoteCovers[key]?.id,
                        )
                    }.getOrNull() ?: return@forEach
                    if (written.id.isNotEmpty()) put(key, cover.hash)
                }
                // Unchanged covers stay claimed, so the manifest keeps naming
                // every cover this device has rather than only this run's.
                localCovers.forEach { (key, cover) ->
                    if (key !in coverPlan.upload) put(key, cover.hash)
                }
            }
            coverPlan.delete.forEach { key -> remoteCovers[key]?.let { DriveApi.delete(token, it.id) } }
            // Last, so a peer never reads a month list ahead of its months.
            onStep(Step.Uploading(plan.upload.size, plan.upload.size))
            val manifest = DeviceManifest(
                deviceId = myId,
                name = me.deviceName,
                ownerInstall = me.installId,
                updatedAt = System.currentTimeMillis(),
                versionName = BuildConfig.VERSION_NAME,
                months = hashes,
                settings = AppSettings.exportPrefs().mapNotNull { (k, v) -> Backup.PrefValue.of(v)?.let { k to it } }.toMap(),
                covers = manifestCovers,
                locked = CollectionLocks.exportLocked().sorted(),
            )
            // The manifest names the covers and the locked collections, so it
            // is sealed the same way the months and covers are.
            val unsealed = gzip(json.encodeToString(DeviceManifest.serializer(), manifest).toByteArray())
            val payload = BackupCipher.seal(unsealed)
            if (payload !== unsealed) unsealed.fill(0)
            val written = DriveApi.put(
                token = token,
                name = "device-$myId.json.gz",
                bytes = payload,
                properties = mapOf(KIND to KIND_DEVICE, DEVICE_ID to myId),
                existingId = listing.manifests[myId]?.id,
            )
            // This install now owns the manifest outright.
            me = me.copy(adoptedManifestAt = written.modifiedAt)
            listing = Listing(DriveApi.listAll(token))
        }

        val devices = devicesOf(token, listing)
        val downloaded = pull(context, token, listing, devices, myId, onStep)
        Report(identity = me, uploaded = uploaded, downloaded = downloaded, devices = devices, forkedTo = forkedTo)
    }

    /** Brings every other device's changed months down, and drops what has gone. */
    private suspend fun pull(
        context: Context,
        token: String,
        listing: Listing,
        devices: List<RemoteDevice>,
        myId: String,
        onStep: (Step) -> Unit,
    ): Int {
        var downloaded = 0
        var changed = false
        val held = PeerListening.peers().associateBy { it.deviceId }
        // Gone from Drive, or now this phone's own partition.
        held.keys.filter { id -> id == myId || devices.none { it.deviceId == id } }.forEach {
            PeerListening.removeDevice(it)
            changed = true
        }
        devices.filter { it.deviceId != myId }.forEach { device ->
            val files = listing.months[device.deviceId].orEmpty()
            val plan = SyncPlanner.pull(
                listed = device.manifest.months.keys,
                remoteMd5 = files.mapValues { it.value.md5 },
                held = held[device.deviceId]?.months.orEmpty(),
            )
            PeerListening.touch(device.deviceId, device.name, device.updatedAt)
            plan.download.forEachIndexed { index, month ->
                onStep(Step.Downloading(device.name, index, plan.download.size))
                val file = files.getValue(month)
                val bytes = gunzip(BackupCipher.open(DriveApi.download(token, file.id, file.sizeBytes)))
                // Parsed before it is kept: a month this build can't read is
                // left out rather than breaking the Replay it would be added to.
                if (runCatching { BucketMath.decode(bytes) }.isSuccess) {
                    PeerListening.putMonth(device.deviceId, device.name, device.updatedAt, month, bytes, file.md5.orEmpty())
                    downloaded++
                    changed = true
                }
            }
            plan.drop.forEach {
                PeerListening.removeMonth(device.deviceId, it)
                changed = true
            }
        }
        if (changed) ListeningStats.peersChanged()
        return downloaded
    }

    // ---- Restore as ---------------------------------------------------------

    /**
     * Makes this phone [device]: its months and settings replace this phone's,
     * and this install becomes the one that writes them.
     *
     * With [keepLocal] — a phone that has never synced, whose listening is in
     * no partition yet — this phone's own months are *added* to the adopted
     * ones rather than lost. Otherwise they already live in Drive under the
     * device this phone was until now, which stays there as a device of its
     * own, and would be counted twice if they were kept here too.
     */
    suspend fun restoreAs(
        context: Context,
        token: String,
        identity: DeviceIdentity,
        device: RemoteDevice,
        keepLocal: Boolean,
        onStep: (Step) -> Unit,
    ): Pair<DeviceIdentity, Backup.Summary> = withContext(Dispatchers.IO) {
        val listing = Listing(DriveApi.listAll(token))
        val manifestFile = listing.manifests[device.deviceId] ?: error("That device is no longer in Google Drive")
        val manifest = readManifest(token, manifestFile)
        val files = listing.months[device.deviceId].orEmpty()
        val months = manifest.months.keys.filter { it in files }.sorted()

        val remote = months.mapIndexed { index, month ->
            onStep(Step.Downloading(manifest.name, index, months.size))
            val bytes = gunzip(BackupCipher.open(DriveApi.download(token, files.getValue(month).id)))
            month to BucketMath.decode(bytes)
        }.toMap()
        onStep(Step.Restoring)

        val local = if (keepLocal) ListeningStats.exportAll().associateBy { it.month } else emptyMap()
        val combined: List<StoredBucket> = (remote.keys + local.keys).sorted().map { month ->
            val a = remote[month]
            val b = local[month]
            when {
                // Counter by counter, the larger of the two rather than their
                // sum. A phone that has never synced can still hold an older
                // copy of this very device — Android's own backup brings the
                // listening folder back on a reinstall — and summing would
                // count all of that twice. What this phone played on its own
                // is still kept: it is whatever it has beyond the download.
                a != null && b != null -> BucketMath.plus(a, BucketMath.minus(b, a))
                else -> a ?: b!!
            }
        }
        ListeningStats.importAll(combined)
        AppSettings.importPrefs(manifest.settings.mapValues { it.value.decoded() })
        SearchHistory.reload()

        // Covers come last and never fail the restore on their own: a file
        // that will not decode, or a manifest naming one Drive no longer has,
        // is skipped so the listening and settings that did come down are kept.
        var restoredCovers = 0
        val coverFiles = listing.covers[device.deviceId].orEmpty()
        manifest.covers.keys.sorted().forEachIndexed { index, key ->
            val file = coverFiles[key] ?: return@forEachIndexed
            onStep(Step.Downloading(manifest.name, index, manifest.covers.size))
            val bytes = runCatching {
                gunzip(BackupCipher.open(DriveApi.download(token, file.id, file.sizeBytes)))
            }.getOrNull() ?: return@forEachIndexed
            val cover = PlaylistCoverStore.restoreCover(key, bytes).getOrNull()
                ?: return@forEachIndexed
            // An album's cover is read straight from the registry, so writing
            // and registering it is the whole job. A local playlist keeps a
            // copy of the path on its own model, which has to be re-pointed at
            // this device's file for the list to draw it.
            if (!PlaylistCoverStore.isAlbumKey(key)) {
                LocalPlaylistStore.applyRestoredCover(key, cover)
            }
            restoredCovers++
        }

        // The lock list is the last of the overlay: applying it turns the
        // restored playlists back into the locked ones they were, guarded by
        // the PIN this device has (which is never the one the backup came
        // from, since the PIN itself is not in it).
        CollectionLocks.restoreLocked(manifest.locked)

        // What is in Drive for this partition is exactly what came down, so
        // the next sync uploads only what this phone adds to it.
        clearSynced(context)
        remote.forEach { (month, bucket) -> writeSynced(context, month, BucketMath.canonicalBytes(bucket)) }
        PeerListening.removeDevice(device.deviceId)
        ListeningStats.peersChanged()

        val adopted = identity.copy(
            deviceId = device.deviceId,
            deviceName = manifest.name,
            adoptedManifestAt = manifestFile.modifiedAt,
        )
        adopted to Backup.Summary(
            months = combined.size,
            settings = manifest.settings.size,
            covers = restoredCovers,
            from = manifest.versionName,
            at = manifest.updatedAt.toString(),
        )
    }

    /** Deletes [device]'s files from Drive, so its listening leaves every Replay. */
    suspend fun remove(token: String, device: RemoteDevice) {
        val listing = Listing(DriveApi.listAll(token))
        listing.months[device.deviceId].orEmpty().values.forEach { DriveApi.delete(token, it.id) }
        listing.manifests[device.deviceId]?.let { DriveApi.delete(token, it.id) }
        PeerListening.removeDevice(device.deviceId)
        ListeningStats.peersChanged()
    }

    /** Forgets what was last uploaded: a new partition starts from nothing. */
    fun clearSynced(context: Context) {
        syncedDir(context).deleteRecursively()
    }

    /** Whether this phone has uploaded any listening as its current device. */
    fun hasSynced(context: Context): Boolean = syncedDir(context).listFiles()?.isNotEmpty() == true

    // ---- Last-uploaded copies ----------------------------------------------

    private fun syncedDir(context: Context) = File(context.filesDir, SYNCED_DIR)

    private fun synced(context: Context, month: String): StoredBucket? =
        File(syncedDir(context), "$month.json").takeIf { it.exists() }
            ?.let { runCatching { BucketMath.decode(it.readBytes()) }.getOrNull() }

    private fun syncedHash(context: Context, month: String): String? =
        File(syncedDir(context), "$month.json").takeIf { it.exists() }?.let { BucketMath.hash(it.readBytes()) }

    private fun writeSynced(context: Context, month: String, bytes: ByteArray) {
        val dir = syncedDir(context).apply { mkdirs() }
        val temporary = File(dir, "$month.json.tmp")
        temporary.writeBytes(bytes)
        val file = File(dir, "$month.json")
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    // ---- Bytes --------------------------------------------------------------

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
