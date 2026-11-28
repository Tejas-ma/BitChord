package com.music.bitchord.lansync

import com.music.bitchord.data.DebugLog as Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/**
 * The side that is playing, publishing what it plays to whoever on the LAN asks.
 *
 * One Ktor CIO server with three routes:
 *
 *  - `GET /sync/ws` — the live feed. The client says hello with the account hash and
 *    is then sent the full session on every change, plus a beat.
 *  - `GET /sync/state` — the same session, once, over plain HTTP. For a hand-off or a
 *    command-line peek, without the ceremony of a socket.
 *  - `POST /sync/handoff` — a device asking to pick this playback up.
 *
 * All three require [accountHash] to match the caller's; a host with nobody signed in
 * refuses to start rather than serve an open port. [publish] is the rest of the API:
 * hand it the queue, the track and where the track is, and every connected receiver
 * has it. A receiver that joins late gets the last [publish] straight away, and the
 * host repeats it on a beat so a dropped frame cannot leave a listener stale.
 *
 * Stopping is not an error: a host that finds its port taken tries the next few
 * before giving up, and a network that refuses multicast still serves anyone who
 * knows the address.
 */
class LanSyncHost(
    private val deviceId: String,
    private val deviceName: String,
    /** The signed-in account's hash. Null means nobody is signed in and nothing is served. */
    private val accountHash: String?,
    private val port: Int = LAN_SYNC_DEFAULT_PORT,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = LanSyncJson

    /** Replayed once, so a receiver that connects between publishes still gets the state. */
    private val frames = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var server: EmbeddedServer<*, *>? = null
    private var announcer: LanSyncAnnouncer? = null
    private var beat: Job? = null

    @Volatile
    private var latest: LanSyncSession? = null

    private val _handoffTarget = MutableStateFlow<String?>(null)

    /** The device id that last asked to take this playback over, until another does. */
    val handoffTarget: StateFlow<String?> = _handoffTarget.asStateFlow()

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = server != null

    /**
     * Serves on [port] (or the next few above it), then advertises on the LAN.
     * Returns the port actually taken, or null when nothing could be served —
     * including when nobody is signed in.
     */
    fun start(): Int? {
        if (server != null) return boundPort
        if (accountHash.isNullOrBlank()) {
            Log.d(TAG, "not signed in; LAN sync host not started")
            return null
        }
        var engine: EmbeddedServer<*, *>? = null
        var chosen = 0
        for (candidate in port..(port + 8)) {
            val started = runCatching {
                embeddedServer(CIO, port = candidate) {
                    install(WebSockets) {
                        pingPeriod = 20.seconds
                        timeout = 30.seconds
                    }
                    routing { syncRoutes() }
                }.start(wait = false)
            }.onFailure { Log.d(TAG, "port $candidate: ${it.message}") }.getOrNull() ?: continue
            engine = started
            chosen = candidate
            break
        }
        if (engine == null) {
            Log.d(TAG, "no port available; LAN sync host not started")
            return null
        }
        server = engine
        boundPort = chosen
        announcer = LanSyncAnnouncer(deviceId, deviceName, chosen, accountHash).also { it.start() }
        beat = scope.launch {
            while (true) {
                delay(LAN_SYNC_BEAT_MS)
                latest?.let(::publish)
            }
        }
        Log.d(TAG, "serving $deviceName on $chosen")
        return chosen
    }

    private fun Route.syncRoutes() {
        // Both paths, one handler: the alias exists for a peer built a release apart.
        webSocket(LAN_SYNC_WS_PATH) { serveSync() }
        webSocket(LAN_SYNC_PATH) { serveSync() }

        get(LAN_SYNC_STATE_PATH) {
            val caller = call.request.headers[LAN_SYNC_ACCOUNT_HEADER]
            if (!LanSyncIdentity.matches(caller, accountHash)) {
                call.respondText(
                    text = json.encodeToString(LanSyncState(ok = false, reason = "not the same account")),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Unauthorized,
                )
                return@get
            }
            call.respondText(
                text = json.encodeToString(LanSyncState(ok = true, session = latest)),
                contentType = ContentType.Application.Json,
            )
        }

        post(LAN_SYNC_HANDOFF_PATH) {
            val body = runCatching { json.decodeFromString<LanSyncHandoff>(call.receiveText()) }.getOrNull()
            if (body == null || !LanSyncIdentity.matches(body.accountHash, accountHash)) {
                call.respondText(
                    text = json.encodeToString(LanSyncState(ok = false, reason = "not the same account")),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Unauthorized,
                )
                return@post
            }
            _handoffTarget.value = body.deviceId
            // Republish with the target on it, so a receiver watching the socket sees
            // that playback is being taken over rather than having to poll this route.
            latest?.let { publish(it.copy(handoffTo = body.deviceId)) }
            Log.d(TAG, "handoff to ${body.deviceId} (${body.deviceName})")
            call.respondText(
                text = json.encodeToString(LanSyncState(ok = true, session = latest)),
                contentType = ContentType.Application.Json,
            )
        }
    }

    /**
     * One authenticated client: greet it, wait for its hash, then feed it the session.
     * A client that does not present the same account is told why and closed, which
     * is the only way it learns the difference between "wrong account" and "not
     * answering" — the former is worth showing, the latter worth retrying.
     */
    private suspend fun DefaultWebSocketServerSession.serveSync() {
        send(text(LanSyncMessage.Hello(deviceId = deviceId, deviceName = deviceName)))
        val greeting = withTimeoutOrNull(HELLO_TIMEOUT_MS) {
            (incoming.receive() as? Frame.Text)?.readText()
        }
        val hello = greeting
            ?.let { runCatching { json.decodeFromString<LanSyncMessage>(it) }.getOrNull() }
            as? LanSyncMessage.Hello
        if (hello == null) {
            send(text(LanSyncMessage.Reject("no greeting")))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no greeting"))
            return
        }
        if (hello.protocol != LAN_SYNC_PROTOCOL || !LanSyncIdentity.matches(hello.accountHash, accountHash)) {
            send(text(LanSyncMessage.Reject("not the same account")))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "account mismatch"))
            return
        }
        // Whichever state there is, before the pump starts: a receiver opening the
        // socket gets an answer even if nothing has been published since it started.
        latest?.let { send(text(LanSyncMessage.Session(it))) }
        val pump = launch {
            frames.collect { frame -> send(Frame.Text(frame)) }
        }
        try {
            readOnlyReceiver()
        } finally {
            pump.cancel()
        }
    }

    /** Receivers do not talk back after the greeting; the socket is drained so it stays open. */
    private suspend fun DefaultWebSocketServerSession.readOnlyReceiver() {
        runCatching {
            for (frame in incoming) {
                if (frame is Frame.Text) frame.readText()
            }
        }
    }

    /** Publishes a whole session. Cheap: one encode, one emit, no per-client work here. */
    fun publish(session: LanSyncSession) {
        latest = session
        frames.tryEmit(text(LanSyncMessage.Session(session)))
    }

    private fun text(message: LanSyncMessage): String = json.encodeToString(message)

    fun stop() {
        runCatching { frames.tryEmit(text(LanSyncMessage.Bye(deviceId))) }
        beat?.cancel()
        beat = null
        announcer?.stop()
        announcer = null
        runCatching { server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000) }
        server = null
        latest = null
        _handoffTarget.value = null
        boundPort = 0
    }

    private companion object {
        const val TAG = "LanSyncHost"

        /** How long a socket may stay silent before it is treated as not a client at all. */
        const val HELLO_TIMEOUT_MS = 5_000L
    }
}
