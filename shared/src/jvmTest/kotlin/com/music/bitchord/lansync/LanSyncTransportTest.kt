package com.music.bitchord.lansync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * The LAN sync transport, end to end over a real socket: a host serves, a receiver
 * follows, and what was published arrives with its queue, its track and a position
 * that can be aged.
 *
 * The account rule is part of that transport, so it is tested the same way: a device
 * on another account is refused a socket and refused the HTTP routes, and a host with
 * nobody signed in does not serve at all.
 *
 * Discovery is not exercised here — JmDNS needs a network that carries multicast,
 * which a build machine cannot offer — but everything after discovery is.
 */
class LanSyncTransportTest {

    private val account = LanSyncIdentity.accountHash("account-1")
    private val stranger = LanSyncIdentity.accountHash("account-2")

    private fun port() = 47_900 + Random.nextInt(90)

    private fun session(position: Long = 42_000L, playing: Boolean = true) = LanSyncSession(
        deviceId = "host-1",
        deviceName = "Phone",
        songs = listOf(
            LanSyncSong("v1", "First", "Artist", durationMs = 200_000),
            LanSyncSong("v2", "Second", "Artist", durationMs = 180_000),
        ),
        index = 1,
        positionMs = position,
        isPlaying = playing,
        positionAtEpochMs = System.currentTimeMillis(),
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    private fun peer(port: Int) =
        LanSyncPeer(id = "host-1", name = "Phone", host = "127.0.0.1", port = port, accountKey = "ignored")

    @Test
    fun `a published session reaches a receiver on the same account`() = runBlocking {
        val host = LanSyncHost("host-1", "Phone", account, port = port())
        val bound = assertNotNull(host.start(), "the host could not open a port")
        val receiver = LanSyncReceiver(account, scope = this)
        try {
            receiver.connect(peer(bound))
            host.publish(session())
            val received = withTimeout(15_000) { receiver.state.first { it != null } }!!
            assertEquals("host-1", received.session.deviceId)
            assertEquals("v2", received.session.current?.videoId)
            assertEquals(2, received.session.songs.size)
            assertEquals(1, received.session.index)
            assertTrue(received.positionAtReceivedMs >= 42_000L)
            assertTrue(received.live)
            assertEquals(false, receiver.unauthorized.value)
        } finally {
            receiver.stop()
            host.stop()
        }
    }

    @Test
    fun `a receiver that joins late is sent the state already published`() = runBlocking {
        val host = LanSyncHost("host-2", "Phone", account, port = port())
        val bound = assertNotNull(host.start())
        val receiver = LanSyncReceiver(account, scope = this)
        try {
            host.publish(session(position = 5_000L, playing = false))
            // Published before anything is connected: the host keeps the last session for
            // whoever opens the socket next.
            receiver.connect(peer(bound))
            val received = withTimeout(15_000) { receiver.state.first { it != null } }!!
            assertEquals("v2", received.session.current?.videoId)
            assertEquals(5_000L, received.positionAtReceivedMs)
        } finally {
            receiver.stop()
            host.stop()
        }
    }

    @Test
    fun `receiver stops holding a session once disconnected`() = runBlocking {
        val host = LanSyncHost("host-3", "Phone", account, port = port())
        val bound = assertNotNull(host.start())
        val receiver = LanSyncReceiver(account, scope = this)
        try {
            receiver.connect(peer(bound))
            host.publish(session())
            withTimeout(15_000) { receiver.state.first { it != null } }
            receiver.disconnect()
            assertNull(receiver.state.value)
            assertEquals(0L, receiver.positionNow())
        } finally {
            receiver.stop()
            host.stop()
        }
    }

    @Test
    fun `another account is refused, and is not retried`() = runBlocking {
        val host = LanSyncHost("host-4", "Phone", account, port = port())
        val bound = assertNotNull(host.start())
        val receiver = LanSyncReceiver(stranger, scope = this)
        try {
            receiver.connect(peer(bound))
            host.publish(session())
            withTimeout(15_000) { receiver.unauthorized.first { it } }
            // A refusal is final: no session, and no state left behind to show.
            assertNull(receiver.state.value)
        } finally {
            receiver.stop()
            host.stop()
        }
    }

    @Test
    fun `a host with nobody signed in does not serve`() {
        val host = LanSyncHost("host-5", "Phone", accountHash = null, port = port())
        assertNull(host.start())
        assertTrue(!host.isRunning)
        val receiver = LanSyncReceiver(accountHash = null)
        receiver.start()
        assertTrue(receiver.unauthorized.value)
        receiver.stop()
    }

    @Test
    fun `the state route answers only the same account`() = runBlocking {
        val host = LanSyncHost("host-6", "Phone", account, port = port())
        val bound = assertNotNull(host.start())
        val anonymous = HttpClient(OkHttp)
        try {
            host.publish(session(position = 12_000L))
            // No header at all: the route exists, but it is not public.
            val refused = anonymous.get("http://127.0.0.1:$bound$LAN_SYNC_STATE_PATH")
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertTrue(!Json.decodeFromString<LanSyncState>(refused.bodyAsText()).ok)

            // The same account gets the snapshot, queue included.
            val receiver = LanSyncReceiver(account, scope = this)
            try {
                val state = assertNotNull(receiver.snapshot(peer(bound)))
                assertTrue(state.ok)
                assertEquals("v2", state.session?.current?.videoId)
                assertEquals(2, state.session?.songs?.size)
            } finally {
                receiver.stop()
            }
        } finally {
            anonymous.close()
            host.stop()
        }
    }

    @Test
    fun `handoff is recorded by the host and marked on the session`() = runBlocking {
        val host = LanSyncHost("host-7", "Phone", account, port = port())
        val bound = assertNotNull(host.start())
        val receiver = LanSyncReceiver(account, deviceId = "desktop-1", deviceName = "Desktop", scope = this)
        try {
            host.publish(session())
            val answer = assertNotNull(receiver.requestHandoff(peer(bound)))
            assertTrue(answer.ok)
            assertEquals("desktop-1", host.handoffTarget.value)
            // Published back out, so a receiver watching the socket sees the take-over
            // without having to poll the route.
            assertEquals("desktop-1", answer.session?.handoffTo)
        } finally {
            receiver.stop()
            host.stop()
        }
    }

    @Test
    fun `a playing position is aged to now, a paused one is not`() {
        val now = 10_000_000L
        val playing = LanSyncSession(
            deviceId = "h", deviceName = "Phone", positionMs = 30_000,
            isPlaying = true, positionAtEpochMs = now - 2_500,
            songs = listOf(LanSyncSong("v", "T", "A", durationMs = 300_000)),
        )
        assertEquals(32_500L, playing.positionAt(now))

        val paused = playing.copy(isPlaying = false)
        assertEquals(30_000L, paused.positionAt(now))

        // Never past the end of the track, however long the message sat around.
        val stale = playing.copy(positionMs = 299_000)
        assertEquals(300_000L, stale.positionAt(now + 60_000))
    }

    @Test
    fun `the account hash is stable, distinct, and absent when signed out`() {
        assertEquals(account, LanSyncIdentity.accountHash("account-1"))
        assertEquals(account, LanSyncIdentity.accountHash("  ACCOUNT-1 "))
        assertTrue(account != stranger)
        assertNull(LanSyncIdentity.accountHash(null))
        assertNull(LanSyncIdentity.accountHash("   "))

        val hash = assertNotNull(account)
        assertEquals(64, hash.length)
        assertEquals(hash.take(16), LanSyncIdentity.advertisementKey(hash))
        assertNull(LanSyncIdentity.advertisementKey(null))
        assertTrue(LanSyncIdentity.matches(hash, account))
        assertTrue(LanSyncIdentity.matches(hash.uppercase(), account))
        assertTrue(!LanSyncIdentity.matches(stranger, account))
        assertTrue(!LanSyncIdentity.matches(null, account))
    }
}
