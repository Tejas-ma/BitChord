package com.music.bitchord.lansync

import java.security.MessageDigest

/**
 * Who is allowed to sync with whom.
 *
 * LAN sync needs no pairing step: two devices showing the same signed-in account are
 * the same person, and that is the whole test. The account id is not itself something
 * to put on the wire — it identifies an account to Google, and mDNS is readable by
 * every machine on the network — so both sides hash it and compare hashes instead.
 *
 * Two consequences worth being plain about, since the name "hash" can imply more
 * than it gives:
 *
 *  - It is an **identity tag, not a secret**. Anyone who knows the account id can
 *    compute the same hash; what it buys is that a device cannot claim someone
 *    else's account without knowing that id, and that the id never appears in a
 *    discovery packet or a log line.
 *  - Traffic is not encrypted. This trust is for the home Wi-Fi these two devices
 *    already share, and the same-account check keeps a stranger on that Wi-Fi from
 *    being followed by accident or curiosity. It is not a defence against someone
 *    who is already sniffing the LAN.
 *
 * The pepper keeps the hash from being a bare SHA-256 of an account id, so an
 * unrelated rainbow table or a pasted-in digest from another app does not transfer.
 */
object LanSyncIdentity {

    /** Namespaces the digest so this hash is only ever this hash. */
    private const val PEPPER = "bitchord-lan-sync:v1:"

    /** How much of the hash discovery advertisements carry. See [advertisementKey]. */
    private const val ADVERTISEMENT_CHARS = 16

    /**
     * The pairing hash for [accountId], or null when nobody is signed in.
     *
     * Case and surrounding whitespace are folded because the same account can be
     * written either way by a login flow, and a device that failed to pair over a
     * stray space would be a miserable bug to find.
     */
    fun accountHash(accountId: String?): String? {
        val id = accountId?.trim()?.lowercase().orEmpty()
        if (id.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((PEPPER + id).toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * The short form that rides in the DNS-SD advertisement.
     *
     * A browse happens before any connection exists, so this is what lets a receiver
     * skip hosts that belong to someone else without opening a socket to each one.
     * 64 bits is far more than a home network will ever collide on, and the real
     * check is the handshake, not this.
     */
    fun advertisementKey(hash: String?): String? =
        hash?.takeIf { it.isNotBlank() }?.take(ADVERTISEMENT_CHARS)

    /** Constant-time comparison: one person's hash must not be guessable byte by byte. */
    fun matches(candidate: String?, expected: String?): Boolean {
        if (candidate.isNullOrBlank() || expected.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            candidate.trim().lowercase().toByteArray(Charsets.UTF_8),
            expected.trim().lowercase().toByteArray(Charsets.UTF_8),
        )
    }
}
