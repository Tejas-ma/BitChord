package com.music.bitchord.lansync

import com.music.bitchord.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * The side that is listening: finds a playing BitChord on the LAN, and follows it.
 *
 * [peers] is whatever [LanSyncBrowser] can see on this device's account; [connect]
 * follows one of them. The connection is kept up by itself — a host that restarts, or
 * a laptop that dropped off the network and came back, reconnects without anyone
 * asking — and [state] is the last thing it said, aged against this device's own clock
 * so the position it reports is the host's position now, not when the message arrived.
 *
 * A host that refuses the account is not retried: [unauthorized] says why, and the
 * loop stops rather than knocking on a door that will keep not opening.
 */
class LanSyncReceiver(
    /** The signed-in account's hash. Nothing connects without one. */
    private val accountHash: String? = null,
    private val deviceId: String = "bitchord-receiver",
    private val deviceName: String = "BitChord",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val json = LanSyncJson
    private val browser = LanSyncBrowser(accountHash)

    /** Hosts currently advertised around this machine, all on this account. */
    val peers: StateFlow<List<LanSyncPeer>> = browser.peers

    private val _state = MutableStateFlow<LanSyncReceived?>(null)

    /** What the followed host last published, or null when nothing is being followed. */
    val state: StateFlow<LanSyncReceived?> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether the socket to the followed host is up right now. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _unauthorized = MutableStateFlow(false)

    /**
     * True when this device cannot sync: nobody is signed in here, or the host refused
     * the account. Distinct from "not connected", which is expected and temporary.
     */
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    private var follow: Job? = null

    /** The peer being followed, for [snapshot] and [requestHandoff] defaults. */
    @Volatile
    var followed: LanSyncPeer? = null
        private set

    private val client by lazy {
        HttpClient(OkHttp) {
            install(WebSockets)
        }
    }

    /** Starts watching the LAN. Nothing connects until [connect]. */
    fun start() {
        when {
            accountHash.isNullOrBlank() -> {
                _unauthorized.value = true
                Log.d(TAG, "not signed in; LAN sync receiver idle")
            }
            else -> {
                _unauthorized.value = false
                browser.start()
            }
        }
    }

    /** Follows [peer] until [disconnect] or [stop], reconnecting as the socket drops. */
    fun connect(peer: LanSyncPeer) {
        if (accountHash.isNullOrBlank()) {
            _unauthorized.value = true
            Log.d(TAG, "not signed in; refusing to follow ${peer.name}")
            return
        }
        follow?.cancel()
        followed = peer
        _unauthorized.value = false
        follow = scope.launch {
            while (isActive && !_unauthorized.value) {
                try {
                    withContext(Dispatchers.IO) {
                        client.webSocket(peer.url) {
                            // The hash is the handshake: until the host sees a matching
                            // one it sends no state, and a mismatched one is closed.
                            send(
                                Frame.Text(
                                    json.encodeToString<LanSyncMessage>(
                                        LanSyncMessage.Hello(
                                            deviceId = deviceId,
                                            deviceName = deviceName,
                                            accountHash = accountHash,
                                        ),
                                    ),
                                ),
                            )
                            _connected.value = true
                            for (frame in incoming) {
                                val text = (frame as? Frame.Text)?.readText() ?: continue
                                onMessage(text)
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.d(TAG, "socket to ${peer.name} dropped: ${failure.message}")
                } finally {
                    _connected.value = false
                    _state.value = _state.value?.copy(live = false)
                }
                if (_unauthorized.value) break
                delay(RECONNECT_MS)
            }
            _connected.value = false
        }
    }

    private fun onMessage(text: String) {
        when (val message = runCatching { json.decodeFromString<LanSyncMessage>(text) }.getOrNull()) {
            is LanSyncMessage.Hello -> {
                if (message.protocol != LAN_SYNC_PROTOCOL) {
                    Log.d(TAG, "ignoring host on protocol ${message.protocol}")
                }
            }
            is LanSyncMessage.Session -> accept(message.session)
            is LanSyncMessage.Reject -> {
                Log.d(TAG, "host refused this device: ${message.reason}")
                _unauthorized.value = true
                _state.value = null
            }
            is LanSyncMessage.Bye -> _state.value = _state.value?.copy(live = false)
            null -> Unit
        }
    }

    private fun accept(session: LanSyncSession) {
        val now = System.currentTimeMillis()
        _state.value = LanSyncReceived(
            session = session,
            receivedAtEpochMs = now,
            positionAtReceivedMs = session.positionAt(now),
        )
    }

    /**
     * The same session as [state], fetched once instead of streamed: for a hand-off,
     * or for anything that wants an answer rather than a subscription.
     */
    suspend fun snapshot(peer: LanSyncPeer? = followed): LanSyncState? = withContext(Dispatchers.IO) {
        val target = peer ?: return@withContext null
        val hash = accountHash ?: return@withContext null
        runCatching {
            val response = client.get(target.stateUrl) {
                header(LAN_SYNC_ACCOUNT_HEADER, hash)
            }
            if (response.status != HttpStatusCode.OK) {
                Log.d(TAG, "state ${target.name}: ${response.status}")
                null
            } else {
                json.decodeFromString<LanSyncState>(response.bodyAsText()).also { state ->
                    state.session?.let(::accept)
                }
            }
        }.onFailure { Log.d(TAG, "state ${target.name}: ${it.message}") }.getOrNull()
    }

    /**
     * Asks the host to hand playback over to this device. The host decides what that
     * means — on the phone it is a notification the user can act on — and the session
     * comes back in the answer either way.
     */
    suspend fun requestHandoff(peer: LanSyncPeer? = followed): LanSyncState? = withContext(Dispatchers.IO) {
        val target = peer ?: return@withContext null
        val hash = accountHash ?: return@withContext null
        runCatching {
            val response = client.post(target.handoffUrl) {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        LanSyncHandoff(
                            accountHash = hash,
                            deviceId = deviceId,
                            deviceName = deviceName,
                        ),
                    ),
                )
            }
            if (response.status != HttpStatusCode.OK) {
                Log.d(TAG, "handoff ${target.name}: ${response.status}")
                null
            } else {
                json.decodeFromString<LanSyncState>(response.bodyAsText())
            }
        }.onFailure { Log.d(TAG, "handoff ${target.name}: ${it.message}") }.getOrNull()
    }

    /**
     * Where the followed host's track is now. The session is the host's as of the
     * moment it was received, and the audio would have been playing since.
     */
    fun positionNow(): Long {
        val received = _state.value ?: return 0L
        if (!received.live) return received.positionAtReceivedMs
        return received.session.positionAt(System.currentTimeMillis())
    }

    fun disconnect() {
        follow?.cancel()
        follow = null
        followed = null
        _connected.value = false
        _state.value = null
    }

    fun stop() {
        disconnect()
        browser.stop()
        runCatching { client.close() }
    }

    private companion object {
        const val TAG = "LanSyncReceiver"
        const val RECONNECT_MS = 2_000L
    }
}
