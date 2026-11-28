package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.lansync.LanSyncPeer
import com.music.bitchord.lansync.LanSyncReceived
import com.music.bitchord.lansync.LanSyncReceiver
import com.music.bitchord.lansync.LanSyncState
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The desktop's side of LAN sync: it finds a BitChord playing on the same network, on
 * the same account, and follows what it plays — the queue, the track, and where in
 * that track it is. No code to type, no PIN: the account is the pairing.
 *
 * The receiving itself lives in `:shared` ([LanSyncReceiver]), because the phone needs
 * the mirror image of it to publish; this is only the wiring — one receiver for the
 * app's lifetime, restarted whenever the signed-in account or the setting changes, and
 * the banner that says what it is following.
 */
internal object DesktopLanSync {

    /** Where the setting lives; on by default, as the phone's is. */
    const val KEY_ENABLED = "lan_sync"

    private const val KEY_DEVICE_ID = "lan_sync_device_id"

    private val persistence = DesktopPersistence()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(DesktopLanSyncStatus.OFF)

    /** What the setting's subtitle says, and what the palette reports. */
    val status: StateFlow<DesktopLanSyncStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<LanSyncPeer>>(emptyList())

    /** Hosts advertising on this network right now, on this account. */
    val peers: StateFlow<List<LanSyncPeer>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<LanSyncReceived?>(null)

    /** What the followed host last said, or null when nothing is being followed. */
    val state: StateFlow<LanSyncReceived?> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)

    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var receiver: LanSyncReceiver? = null
    private var collectors: List<Job> = emptyList()
    private var watcher: Job? = null

    /** The setting as stored, so the app can start from it without a state of its own. */
    val enabled: Boolean get() = persistence.boolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) = persistence.saveBoolean(KEY_ENABLED, enabled)

    /**
     * Restarts sync for [accountHash]. Called on launch, on the toggle, and whenever
     * the signed-in account changes — signing out stops advertising, signing in starts
     * discovering again, which is what "enabled automatically upon sign-in" means.
     */
    fun apply(enabled: Boolean, accountHash: String?) {
        stop()
        if (!enabled) {
            _status.value = DesktopLanSyncStatus.OFF
            return
        }
        if (accountHash.isNullOrBlank()) {
            _status.value = DesktopLanSyncStatus.SIGNED_OUT
            return
        }
        val created = LanSyncReceiver(
            accountHash = accountHash,
            deviceId = deviceId(),
            deviceName = deviceName(),
            scope = scope,
        )
        receiver = created
        _status.value = DesktopLanSyncStatus.SEARCHING
        created.start()
        collectors = listOf(
            scope.launch {
                created.state.collect { received ->
                    _state.value = received
                    _status.value = if (received != null) {
                        DesktopLanSyncStatus.FOLLOWING
                    } else {
                        DesktopLanSyncStatus.SEARCHING
                    }
                }
            },
            scope.launch { created.connected.collect { _connected.value = it } },
            scope.launch {
                created.unauthorized.collect { refused ->
                    if (refused) {
                        _status.value = DesktopLanSyncStatus.REFUSED
                        _state.value = null
                    }
                }
            },
        )
        // Automatic pairing: a host on this account turns up and is followed without
        // anyone picking it, unless one is already being followed.
        watcher = scope.launch {
            created.peers.collect { peers ->
                _peers.value = peers
                if (created.followed == null && peers.isNotEmpty()) {
                    created.connect(peers.first())
                }
            }
        }
    }

    fun follow(peer: LanSyncPeer) = receiver?.connect(peer)

    fun forget() = receiver?.disconnect()

    /** Where the followed track is now, on this machine's clock. */
    fun positionNow(): Long = receiver?.positionNow() ?: 0L

    /** One-shot fetch instead of the socket — the hand-off path. */
    suspend fun snapshot(peer: LanSyncPeer? = null): LanSyncState? = receiver?.snapshot(peer)

    /** Asks the followed device for playback, so it can move here. */
    suspend fun requestHandoff(peer: LanSyncPeer? = null): LanSyncState? = receiver?.requestHandoff(peer)

    fun stop() {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        watcher?.cancel()
        watcher = null
        receiver?.stop()
        receiver = null
        _peers.value = emptyList()
        _state.value = null
        _connected.value = false
    }

    /** A stable id per machine, so two desktops are not the same device to a host. */
    private fun deviceId(): String {
        val stored = persistence.string(KEY_DEVICE_ID, "").takeIf { it.isNotBlank() }
        if (stored != null) return stored
        val created = "desktop-${UUID.randomUUID()}"
        persistence.saveString(KEY_DEVICE_ID, created)
        return created
    }

    private fun deviceName(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.substringBefore('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Desktop"
}

/** Why the setting is where it is, in the order a person would ask. */
internal enum class DesktopLanSyncStatus {
    /** The setting is off. */
    OFF,

    /** On, but nobody is signed in here, so there is no account to pair with. */
    SIGNED_OUT,

    /** Signed in, watching the Wi-Fi for another BitChord on this account. */
    SEARCHING,

    /** Connected to one: its queue, track and position are being followed. */
    FOLLOWING,

    /** A host was found but refused this device's account. */
    REFUSED,
}

/**
 * The strip that says the desktop is following another BitChord: whose queue, which
 * track, and where in it the host is — the position ticks locally from the host's
 * last report rather than waiting for the next one.
 */
@Composable
internal fun DesktopLanSyncBanner(modifier: Modifier = Modifier) {
    val received by DesktopLanSync.state.collectAsState()
    val session = received?.session ?: return
    val song = session.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.deviceId, session.updatedAtEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val position = if (received?.live == true) session.positionAt(now) else received?.positionAtReceivedMs ?: session.positionMs
    val duration = song?.durationMs ?: 0L

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE6141416))
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (received?.live == true) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.3f)),
        )
        Text(
            text = session.deviceName,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = song?.let { "${it.title} · ${it.artist}" } ?: "Nothing playing",
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (duration > 0) "${clockOf(position)} / ${clockOf(duration)}" else clockOf(position),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            maxLines = 1,
        )
        // The queue comes with the session: which row of how many the host is on.
        Text(
            text = "${session.index + 1}/${session.songs.size}",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            text = "Stop",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { DesktopLanSync.forget() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** m:ss, the same shape the players use for a track's own clock. */
private fun clockOf(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
