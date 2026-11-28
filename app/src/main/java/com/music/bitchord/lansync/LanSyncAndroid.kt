package com.music.bitchord.lansync

import android.content.ComponentName
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's side of LAN sync: it is the device that is usually playing, so it is the
 * host — it serves the queue, the track and the position over the routes in
 * [LanSyncHost], and advertises itself with Android's own [NsdManager] instead of
 * JmDNS, which is the thing a phone with Bonjour-free network stack should use.
 *
 * There is no pairing step to get wrong. The account is the pairing: the signed-in
 * account id is hashed ([LanSyncIdentity]) and the hash goes in the advertisement and
 * in the handshake, so a desktop on the same Wi-Fi and the same account finds and
 * trusts this phone without a PIN, a code or a QR.
 *
 * Everything is off unless [AppSettings.lanSyncEnabled] is on (it defaults to on) and
 * somebody is signed in — the host refuses to start with no account, so an idle
 * signed-out phone does not leave a port open.
 */
class LanSyncAndroid private constructor(context: Context) {

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Opened on first use: the app constructs its own AuthStore at startup. */
    private val auth by lazy { AuthStore(context) }

    private var loop: Job? = null
    private var publishing: Job? = null

    private var host: LanSyncHost? = null
    private var hostHash: String? = null

    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private fun install() {
        if (loop != null) return
        loop = scope.launch {
            // The toggle takes effect immediately; the account is re-read on a slow
            // tick because signing in happens in the WebView, a world away from here.
            launch { AppSettings.lanSyncEnabled.collect { apply() } }
            while (isActive) {
                apply()
                delay(ACCOUNT_RECHECK_MS)
            }
        }
    }

    /** Starts, stops or re-points the host to match the setting and the account. */
    private fun apply() {
        val enabled = AppSettings.lanSyncEnabled.value
        val hash = LanSyncIdentity.accountHash(auth.activeAccountId)
        when {
            !enabled -> stopHost()
            hash == null -> stopHost()
            hash != hostHash -> restart(hash)
        }
    }

    private fun restart(hash: String) {
        stopHost()
        hostHash = hash
        scope.launch {
            val created = LanSyncHost(
                deviceId = deviceId(),
                deviceName = deviceName(),
                accountHash = hash,
            )
            val port = withContext(Dispatchers.IO) { created.start() }
            if (port == null) {
                // No port, or no multicast: try again on the next tick rather than
                // sitting on a host that will never serve.
                hostHash = null
                return@launch
            }
            // The account changed while the engine was coming up — this host belongs
            // to the account it was started for, so it is closed rather than adopted.
            if (hostHash != hash) {
                created.stop()
                return@launch
            }
            host = created
            register(port, hash)
            bindController()
            publishLoop(created)
        }
    }

    /**
     * Advertises the port DNS-SD style. The TXT keys are the same ones the desktop's
     * JmDNS browser reads, so a phone and a desktop agree on the service type and on
     * which account it belongs to.
     */
    private fun register(port: Int, hash: String) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val key = LanSyncIdentity.advertisementKey(hash) ?: return
        val info = NsdServiceInfo().apply {
            // The name has to be unique on the network, so the device id rides along.
            serviceName = "BitChord-${deviceId().takeLast(8)}"
            // NsdManager wants the bare "_type._tcp", not the ".local." mDNS suffix.
            serviceType = LAN_SYNC_SERVICE_TYPE.removeSuffix(".local.")
            setPort(port)
            setAttribute(LAN_SYNC_TXT_ID, deviceId())
            setAttribute(LAN_SYNC_TXT_NAME, deviceName())
            setAttribute(LAN_SYNC_TXT_ACCOUNT, key)
            setAttribute(LAN_SYNC_TXT_PROTOCOL, LAN_SYNC_PROTOCOL.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "advertising ${info.serviceName} on ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "unregistration failed: $errorCode")
            }
        }
        nsdManager = manager
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.d(TAG, "could not register: ${it.message}") }
    }

    /** Binds the media session so the published queue is the one actually playing. */
    private fun bindController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val bound = runCatching { future.get() }.getOrNull() ?: return@addListener
                if (host == null) {
                    MediaController.releaseFuture(future)
                    controllerFuture = null
                    return@addListener
                }
                controller = bound
                bound.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publishOnce()
                })
                publishOnce()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun publishLoop(host: LanSyncHost) {
        publishing?.cancel()
        publishing = scope.launch {
            while (isActive && this@LanSyncAndroid.host === host) {
                publishOnce()
                delay(PUBLISH_MS)
            }
        }
    }

    /**
     * Publishes the whole session. The position is the session's own playhead, and it
     * is re-read on every tick rather than derived from a start time, so a seek on the
     * phone shows up on the desktop within a second.
     */
    private fun publishOnce() {
        val host = host ?: return
        val controller = controller ?: return
        val now = System.currentTimeMillis()
        val count = controller.mediaItemCount
        val index = if (count > 0) controller.currentMediaItemIndex.coerceIn(0, count - 1) else 0
        val songs = (0 until minOf(count, MAX_QUEUE)).map { position ->
            val item = controller.getMediaItemAt(position)
            val metadata = item.mediaMetadata
            LanSyncSong(
                videoId = item.mediaId.takeIf { it.isNotBlank() } ?: "item-$position",
                title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown track",
                artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: metadata.albumArtist?.toString()?.takeIf { it.isNotBlank() }
                    ?: metadata.albumTitle?.toString().orEmpty(),
                thumbnailUrl = metadata.artworkUri?.toString(),
                durationMs = if (position == index) {
                    controller.duration.takeIf { it > 0 } ?: (metadata.durationMs ?: 0L)
                } else {
                    metadata.durationMs ?: 0L
                },
            )
        }
        host.publish(
            LanSyncSession(
                deviceId = deviceId(),
                deviceName = deviceName(),
                songs = songs,
                index = index,
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                isPlaying = controller.isPlaying,
                positionAtEpochMs = now,
                updatedAtEpochMs = now,
                shuffle = controller.shuffleModeEnabled,
                repeat = when (controller.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "ONE"
                    Player.REPEAT_MODE_ALL -> "ALL"
                    else -> "OFF"
                },
            ),
        )
    }

    private fun stopHost() {
        publishing?.cancel()
        publishing = null
        host?.stop()
        host = null
        hostHash = null
        registration?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registration = null
        nsdManager = null
        controllerFuture?.let { future -> runCatching { MediaController.releaseFuture(future) } }
        controllerFuture = null
        controller = null
    }

    /** A stable id per install, so a reinstalled app is a different device. */
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = "phone-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    /** What the desktop shows in its banner: the model, which is what a person calls it. */
    private fun deviceName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Phone"

    companion object {
        private const val TAG = "LanSyncAndroid"
        private const val PREFS = "bitchord_lan_sync"
        private const val KEY_DEVICE_ID = "device_id"
        private const val PUBLISH_MS = 1_000L
        private const val ACCOUNT_RECHECK_MS = 30_000L
        private const val MAX_QUEUE = 200

        @Volatile
        private var instance: LanSyncAndroid? = null

        /**
         * Starts sync for this process. Idempotent: the app calls it from
         * [com.music.bitchord.BitChordApplication] once settings are readable.
         */
        fun install(context: Context) {
            val existing = instance
            if (existing != null) return
            val created = LanSyncAndroid(context)
            instance = created
            created.install()
        }
    }
}
