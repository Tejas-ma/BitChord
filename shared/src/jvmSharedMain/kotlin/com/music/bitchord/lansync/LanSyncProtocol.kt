package com.music.bitchord.lansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire format between a BitChord that is playing and one that is listening in.
 *
 * The playing side is the host: it publishes what it is playing — the queue, the
 * track, where in that track it is — over a WebSocket, and announces itself on the
 * LAN so the other app can find it without being told an address. Everything here
 * is plain data, so the phone and the desktop agree on it without sharing code
 * beyond this package.
 *
 * Only devices signed into the same account are served: the host advertises a short
 * form of the account hash, and the full hash is what a client presents before a
 * single frame of state is sent. See [LanSyncIdentity].
 */
const val LAN_SYNC_PROTOCOL = 1

/** The DNS-SD type the host registers and a receiver browses for. */
const val LAN_SYNC_SERVICE_TYPE = "_bitchord._tcp.local."

/** Where the live WebSocket is served, on whatever port the host took. */
const val LAN_SYNC_WS_PATH = "/sync/ws"

/**
 * The route the first cut of this shipped on. Kept as an alias so a desktop and a
 * phone that were updated a release apart still talk — both are authenticated the
 * same way, so nothing is loosened by leaving it in.
 */
const val LAN_SYNC_PATH = "/sync"

/** One-shot snapshot over plain HTTP: the current track, position, state and queue. */
const val LAN_SYNC_STATE_PATH = "/sync/state"

/**
 * Asks the host to hand playback over to the caller — the "pick this up on the other
 * device" button. The host records who asked and republishes with [LanSyncSession.handoffTo]
 * set; what a host does with that is its own business, since not every device can be
 * told to stop.
 */
const val LAN_SYNC_HANDOFF_PATH = "/sync/handoff"

/** The header a client presents its account hash in. */
const val LAN_SYNC_ACCOUNT_HEADER = "X-BitChord-Account"

/** The port the host asks for first; a receiver never assumes it, it discovers it. */
const val LAN_SYNC_DEFAULT_PORT = 47600

/**
 * How often the host repeats its state. Pushed on every change, and on this beat
 * as well, so a receiver that joined late or dropped a packet does not sit on a
 * stale queue until something happens to be played.
 */
const val LAN_SYNC_BEAT_MS = 4_000L

/** DNS-SD TXT keys: the device's identity, its display name, and the short account key. */
const val LAN_SYNC_TXT_ID = "id"
const val LAN_SYNC_TXT_NAME = "n"
const val LAN_SYNC_TXT_ACCOUNT = "h"
const val LAN_SYNC_TXT_PROTOCOL = "p"

/** A track, as the receiving app needs it: enough to draw the row and nothing more. */
@Serializable
data class LanSyncSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
)

/**
 * What the host is playing right now.
 *
 * [positionMs] is true as of [positionAtEpochMs] on the host's own clock, not as of
 * when this message arrived: the two machines' clocks are not the same, so a
 * receiver ages the position from the moment it was read rather than trusting it to
 * still be current. See [positionAt].
 */
@Serializable
data class LanSyncSession(
    val deviceId: String,
    val deviceName: String,
    val songs: List<LanSyncSong> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val positionAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    /** The host's shuffle and repeat, so the queue a receiver shows is ordered as it is playing. */
    val shuffle: Boolean = false,
    val repeat: String = "OFF",
    /** The device id that last asked to pick this playback up, or null. */
    val handoffTo: String? = null,
) {
    val current: LanSyncSong? get() = songs.getOrNull(index)

    /**
     * Where the track has got to by [nowEpochMs], aged from the moment the position
     * was read — but never past the read plus the track's own length, which is what a
     * paused or finished track would otherwise drift beyond.
     */
    fun positionAt(nowEpochMs: Long): Long {
        val elapsed = if (isPlaying) (nowEpochMs - positionAtEpochMs).coerceAtLeast(0L) else 0L
        val duration = current?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return (positionMs + elapsed).coerceIn(0L, duration)
    }
}

/** Everything one side says to the other. */
@Serializable
sealed interface LanSyncMessage {

    /**
     * The first word from either side. The host sends its identity on connect; the
     * client answers with [accountHash], and nothing else is sent until that matches.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int = LAN_SYNC_PROTOCOL,
        val deviceId: String,
        val deviceName: String,
        /** Only a client sets this. Null on the host's own greeting. */
        val accountHash: String? = null,
    ) : LanSyncMessage

    /** The state, complete every time: a receiver never has to keep a delta. */
    @Serializable
    @SerialName("session")
    data class Session(val session: LanSyncSession) : LanSyncMessage

    /** A device was refused: a different account, or nobody signed in on the host. */
    @Serializable
    @SerialName("reject")
    data class Reject(val reason: String) : LanSyncMessage

    /** The host has stopped. The receiver keeps what it has, marked as no longer live. */
    @Serializable
    @SerialName("bye")
    data class Bye(val deviceId: String) : LanSyncMessage
}

/** The body of [LAN_SYNC_STATE_PATH] and [LAN_SYNC_HANDOFF_PATH]. */
@Serializable
data class LanSyncState(
    val ok: Boolean,
    val session: LanSyncSession? = null,
    /** Why not, when [ok] is false. Safe to show: it never contains the account hash. */
    val reason: String? = null,
)

/** What a device sends to ask for playback to move to it. */
@Serializable
data class LanSyncHandoff(
    val accountHash: String,
    val deviceId: String,
    val deviceName: String = "",
)

/**
 * The encoder both ends use: unknown fields are tolerated so a newer host cannot
 * break an older receiver, and the type is the discriminator kotlinx writes.
 */
val LanSyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** A host found on the LAN, ready to connect to. */
data class LanSyncPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** The advertised short account hash, when the host is signed in. */
    val accountKey: String? = null,
) {
    val url: String get() = "ws://$host:$port$LAN_SYNC_WS_PATH"

    val stateUrl: String get() = "http://$host:$port$LAN_SYNC_STATE_PATH"

    val handoffUrl: String get() = "http://$host:$port$LAN_SYNC_HANDOFF_PATH"
}

/** A session as received, with the local clock reading it arrived at. */
data class LanSyncReceived(
    val session: LanSyncSession,
    /** When this device received it, on its own clock. */
    val receivedAtEpochMs: Long,
    /** Where the track was at [receivedAtEpochMs]. */
    val positionAtReceivedMs: Long,
    /** True once the host has said goodbye or the socket dropped. */
    val live: Boolean = true,
)
