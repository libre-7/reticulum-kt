package network.reticulum.interfaces.auto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-Kotlin unit tests for the D7 peer-validity (remember) window:
 * an inbound frame from an address we have SEEN within the window must be
 * eligible for processing, never dropped as "unknown peer" — even long
 * after the 22s discovery-timeout pruned it. No live network involved.
 */
class PeerRememberWindowTest {

    private val window = 10 * 60 * 1000L // 10 minutes, matches PEER_REMEMBER_MS

    @Test
    fun `never-seen address is not recently known`() {
        val w = PeerRememberWindow(windowMs = window)
        assertFalse(w.isRecentlyKnown("fe80::1", now = 1000))
    }

    @Test
    fun `address seen inside window is recently known`() {
        val w = PeerRememberWindow(windowMs = window)
        val t0 = 1_000_000L
        w.onSeen("fe80::1", t0)
        // 22s later: past the peering timeout, well inside the remember window.
        assertTrue(w.isRecentlyKnown("fe80::1", t0 + 22_000))
        // Even 9m59s later — the whole point of D7.
        assertTrue(w.isRecentlyKnown("fe80::1", t0 + 9 * 60_000 + 59_000))
    }

    @Test
    fun `address beyond window expires`() {
        val w = PeerRememberWindow(windowMs = window)
        val t0 = 0L
        w.onSeen("fe80::1", t0)
        assertFalse(w.isRecentlyKnown("fe80::1", t0 + window + 1))
        // Expired entry is forgotten, not just reported false.
        assertFalse(w.isRecentlyKnown("fe80::1", t0 + window + 2))
        assertEquals(0, w.size())
    }

    @Test
    fun `window slides on continued traffic`() {
        val w = PeerRememberWindow(windowMs = window)
        var t = 0L
        w.onSeen("fe80::1", t)
        // Keep hearing from the peer every 5 minutes for an hour — it must
        // NEVER go unknown regardless of how flaky its mcast announcements are.
        repeat(12) {
            t += 5 * 60_000L
            assertTrue(w.isRecentlyKnown("fe80::1", t), "peer went unknown at t=$t")
        }
    }

    @Test
    fun `scope id is stripped for lookup`() {
        val w = PeerRememberWindow(windowMs = window)
        w.onSeen("fe80::abcd%eth0", 1000)
        assertTrue(w.isRecentlyKnown("fe80::abcd", 2000))
        assertTrue(w.isRecentlyKnown("fe80::abcd%wlan0", 3000))
    }

    @Test
    fun `forget removes the entry immediately`() {
        val w = PeerRememberWindow(windowMs = window)
        w.onSeen("fe80::1", 1000)
        w.forget("fe80::1")
        assertFalse(w.isRecentlyKnown("fe80::1", 1500))
    }

    @Test
    fun `bounded memory evicts oldest entries`() {
        val w = PeerRememberWindow(windowMs = window, maxEntries = 3)
        w.onSeen("fe80::a", 1000)
        w.onSeen("fe80::b", 2000)
        w.onSeen("fe80::c", 3000)
        w.onSeen("fe80::d", 4000) // oldest ("a") should be evicted
        assertEquals(3, w.size())
        assertFalse(w.isRecentlyKnown("fe80::a", 5000))
        assertTrue(w.isRecentlyKnown("fe80::b", 5000))
    }

    @Test
    fun `default window matches the hardening constant`() {
        assertEquals(600_000L, AutoInterfaceConstants.PEER_REMEMBER_MS)
    }
}
