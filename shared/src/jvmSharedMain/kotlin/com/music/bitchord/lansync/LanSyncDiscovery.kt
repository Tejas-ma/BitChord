package com.music.bitchord.lansync

import com.music.bitchord.data.DebugLog as Log
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Finding the other BitChord on the same network, without being told an address.
 *
 * The host registers a DNS-SD service; a receiver browses for that type and
 * resolves each hit to the address and port the host is actually on. Both are
 * JmDNS, so the desktop sees every advertisement; the phone uses Android's
 * NsdManager for the same job, against the same service type and the same TXT keys.
 *
 * Every operation here is best-effort: a network with no multicast, a machine
 * whose own address cannot be resolved, or a port already taken all leave the
 * app working, just without a peer to find.
 */
private const val TAG = "LanSyncDiscovery"

/** The address to advertise on, or null when this machine cannot say. */
internal fun localAddress(): InetAddress? =
    runCatching { InetAddress.getLocalHost() }
        .getOrNull()
        ?.takeUnless { it.isLoopbackAddress }
        ?: runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        }.getOrNull()

/** Advertises this app's WebSocket on the LAN for as long as it is started. */
class LanSyncAnnouncer(
    private val deviceId: String,
    private val deviceName: String,
    private val port: Int,
    /** The signed-in account's hash. Nothing is advertised without one. */
    private val accountHash: String?,
) {
    private var mdns: JmDNS? = null
    private var service: ServiceInfo? = null

    /** False when nobody is signed in, multicast could not be joined, or the registration failed. */
    fun start(): Boolean {
        val key = LanSyncIdentity.advertisementKey(accountHash) ?: return false
        return runCatching {
            val address = localAddress() ?: error("no address to advertise")
            val created = JmDNS.create(address)
            val properties = mapOf(
                LAN_SYNC_TXT_ID to deviceId,
                LAN_SYNC_TXT_NAME to deviceName,
                LAN_SYNC_TXT_ACCOUNT to key,
                LAN_SYNC_TXT_PROTOCOL to LAN_SYNC_PROTOCOL.toString(),
            )
            val info = ServiceInfo.create(
                LAN_SYNC_SERVICE_TYPE,
                // The name has to be unique on the network: two devices called
                // "BitChord" must not collide, so the device id rides along.
                "BitChord-$deviceId",
                port,
                0,
                0,
                properties,
            )
            created.registerService(info)
            mdns = created
            service = info
            Log.d(TAG, "announcing $deviceName on $address:$port for account ${key.take(6)}…")
            true
        }.onFailure { Log.d(TAG, "could not announce: ${it.message}") }.getOrDefault(false)
    }

    fun stop() {
        runCatching { service?.let { mdns?.unregisterService(it) } }
        runCatching { mdns?.close() }
        service = null
        mdns = null
    }
}

/**
 * Watches the LAN for hosts.
 *
 * [peers] is everything currently advertised by another device on the same account;
 * a host that goes away drops out of it. A host advertising a different account —
 * someone else's phone on the same Wi-Fi — is filtered out here, so the UI never
 * offers a device that would only refuse the connection.
 */
class LanSyncBrowser(
    /** The signed-in account's hash, or null when nobody is signed in. */
    private val accountHash: String? = null,
) {

    private val _peers = MutableStateFlow<List<LanSyncPeer>>(emptyList())
    val peers: StateFlow<List<LanSyncPeer>> = _peers.asStateFlow()

    private var mdns: JmDNS? = null

    private val accountKey: String? get() = LanSyncIdentity.advertisementKey(accountHash)

    fun start(): Boolean = runCatching {
        if (mdns != null) return true
        if (accountKey == null) {
            Log.d(TAG, "not signed in; not browsing for sync hosts")
            return false
        }
        val created = JmDNS.create(localAddress())
        created.addServiceListener(
            LAN_SYNC_SERVICE_TYPE,
            object : ServiceListener {
                // JmDNS tells the three apart by which method it calls, not by an event
                // code: an advertisement arrives as "added", and again as "resolved"
                // once its address is known.
                override fun serviceAdded(event: ServiceEvent) = resolve(created, event)
                override fun serviceResolved(event: ServiceEvent) = publish(event.info)
                override fun serviceRemoved(event: ServiceEvent) = forget(event)
            },
        )
        mdns = created
        Log.d(TAG, "browsing for $LAN_SYNC_SERVICE_TYPE on account ${accountKey?.take(6)}…")
        true
    }.onFailure { Log.d(TAG, "could not browse: ${it.message}") }.getOrDefault(false)

    /** An advertisement carries no address, so the service is asked for its own. */
    private fun resolve(source: JmDNS, event: ServiceEvent) {
        runCatching {
            val info = event.info ?: source.getServiceInfo(event.type, event.name)
            publish(info)
        }.onFailure { Log.d(TAG, "could not resolve ${event.name}: ${it.message}") }
    }

    private fun publish(info: ServiceInfo?) {
        val peer = info?.toPeer() ?: return
        // Only advertise off the same account. A host that advertises no key is not
        // signed in, so it could not serve us anyway.
        if (accountKey == null || peer.accountKey != accountKey) return
        _peers.update { current ->
            (current.filterNot { it.id == peer.id } + peer).sortedBy { it.name }
        }
    }

    private fun forget(event: ServiceEvent) {
        val id = runCatching { event.info?.getPropertyString(LAN_SYNC_TXT_ID) }.getOrNull()
        _peers.update { current ->
            current.filterNot { it.id == id || it.name == event.name || "BitChord-${it.id}" == event.name }
        }
    }

    private fun ServiceInfo.toPeer(): LanSyncPeer? {
        val id = runCatching { getPropertyString(LAN_SYNC_TXT_ID) }.getOrNull().orEmpty()
        val name = runCatching { getPropertyString(LAN_SYNC_TXT_NAME) }.getOrNull()
            ?: runCatching { name.takeIf { it.isNotBlank() } }.getOrNull()
        val key = runCatching { getPropertyString(LAN_SYNC_TXT_ACCOUNT) }.getOrNull()
        // IPv4 first: the phone and the desktop talk over whichever address the LAN
        // gave them, and an IPv6 address is not always the one the socket is bound to.
        val addresses = runCatching { inetAddresses?.toList() }.getOrNull().orEmpty()
        val address = addresses
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?: addresses.firstOrNull { !it.isLoopbackAddress }?.hostAddress
            ?: hostAddress
            ?: return null
        if (port <= 0) return null
        return LanSyncPeer(
            id = id.ifBlank { "$address:$port" },
            name = name?.takeIf { it.isNotBlank() } ?: "BitChord",
            host = address,
            port = port,
            accountKey = key?.takeIf { it.isNotBlank() },
        )
    }

    fun stop() {
        runCatching { mdns?.close() }
        mdns = null
        _peers.value = emptyList()
    }
}
