package network.reticulum.interfaces.auto

/**
 * D7 hardening (2026-08-22): pure-Kotlin peer-validity window used by
 * [AutoInterface] to decide whether inbound DATA from a pruned peer's
 * address should be rehydrated (re-spawn the peer interface) instead of
 * dropped with "Data from unknown peer".
 *
 * Background: AutoInterface prunes a peer when no discovery-multicast
 * announcement is heard for [AutoInterfaceConstants.PEERING_TIMEOUT_MS].
 * On networks where IPv6 multicast is unreliable (QEMU/SLIRP emulators,
 * some Wi-Fi APs) peers flap through discover→prune cycles, and any data
 * packet landing in the de-registered window was silently discarded.
 *
 * This class remembers the last time each peer address was SEEN (via
 * discovery announcement OR data) and keeps the address eligible for
 * rehydration for [windowMs] (default 10 minutes, per the D7 hardening
 * requirement "announce within a generous window must be processed").
 *
 * Pure bookkeeping — no sockets, no coroutines — so it is unit-testable
 * with an injected clock (no live network).
 *
 * Thread-safety: all state is in a ConcurrentHashMap; safe for concurrent
 * data-handler / peer-management callers.
 */
class PeerRememberWindow(
    private val windowMs: Long = AutoInterfaceConstants.PEER_REMEMBER_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Record that [address] was seen at [now]. Refreshes (slides) the
     * window so a continuously-talking peer never expires.
     */
    fun onSeen(address: String, now: Long) {
        val clean = clean(address)
        lastSeen[clean] = now
        evictStale(now)
    }

    /**
     * True when [address] was seen within the remember window (i.e. it is
     * a recently-known peer whose inbound data must be processed, not
     * dropped). Also refreshes the stamp — eligibility implies continued
     * liveness.
     */
    fun isRecentlyKnown(address: String, now: Long): Boolean {
        val clean = clean(address)
        val seen = lastSeen[clean] ?: return false
        return if (now - seen <= windowMs) {
            lastSeen[clean] = now
            true
        } else {
            lastSeen.remove(clean)
            false
        }
    }

    fun forget(address: String) {
        lastSeen.remove(clean(address))
    }

    fun size(): Int = lastSeen.size

    private fun evictStale(now: Long) {
        if (lastSeen.size <= maxEntries) return
        // Bounded memory: drop the oldest entries beyond the cap.
        lastSeen.entries.sortedBy { it.value }
            .take(lastSeen.size - maxEntries)
            .forEach { lastSeen.remove(it.key) }
    }

    private fun clean(address: String): String = address.substringBefore('%')

    companion object {
        // Bounded memory: a mesh has a handful of peers; 64 is ample headroom.
        const val MAX_ENTRIES = 64
    }
}
