package network.reticulum.transport

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.InterfaceMode
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.identity.Identity
import network.reticulum.storage.InMemoryPathStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The interface registry is a hash-keyed UPSERT that a stop empties.
 *
 * Measured on two devices: each held 8 dead + 8 live interface refs after one
 * stop/start cycle, and GLASS enumerated a 16-entry interface list for 8
 * interfaces. `Interface.getHash()` hashes `toString()`, and every interface's
 * `toString()` is its rendezvous (`TCPClientInterface[name -> host:port]`), so a
 * revived interface is a NEW instance under the SAME hash. The registry used to
 * be a bare `interfaces.add(...)` that was never cleared at `stop()`, which left
 * the dead twin registered FIRST — and `findInterfaceByHash` could then hand
 * every path-routed send to the dead instance (`transmit()` swallows the
 * refusal and the caller still sees `sent = true`).
 *
 * Each test below is written against that failure mode; the ones marked
 * "fails on the append-only registry" return two entries / a dead instance /
 * retained stats when run against the pre-fix code.
 */
@DisplayName("Transport interface registry (hash-keyed upsert, cleared on stop)")
class TransportInterfaceRegistryTest {

    /** Minimal [InterfaceRef]; [hash] stands in for the rendezvous-derived hash. */
    private class FakeInterface(
        override val name: String,
        override val hash: ByteArray,
        override val online: Boolean = true,
        override val isConnectedToSharedInstance: Boolean = false,
    ) : InterfaceRef {
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        var detachCount = 0

        override fun detach() {
            detachCount++
        }

        override fun send(data: ByteArray) = Unit
    }

    private lateinit var pathStore: InMemoryPathStore

    @BeforeEach
    fun setup() {
        runCatching { Transport.stop() }
        Transport.pathTable.clear()
        pathStore = InMemoryPathStore()
        // A store keeps stop()'s persist step off the filesystem and gives the
        // restart tests something to reload from.
        Transport.pathStore = pathStore
        Transport.start(Identity.create(), enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        Transport.pathStore = null
        Transport.pathTable.clear()
        runCatching { Transport.stop() }
    }

    private fun hashOf(fill: Int): ByteArray =
        ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { fill.toByte() }

    // ---- 1. upsert ---------------------------------------------------------

    @Test
    @DisplayName("registering the same hash twice leaves ONE entry, and it is the newest (2 before the fix)")
    fun sameHashRegistersOnce() {
        val dead = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x11))
        val revived = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x11))

        Transport.registerInterface(dead)
        Transport.registerInterface(revived)

        Transport.getInterfaces().size shouldBe 1
        (Transport.getInterfaces()[0] === revived) shouldBe true
        Transport.getInterfaces().any { it === dead } shouldBe false
    }

    @Test
    @DisplayName("re-registering the same instance is idempotent (2 before the fix)")
    fun reRegisteringSameInstanceIsIdempotent() {
        val iface = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x12))

        Transport.registerInterface(iface)
        Transport.registerInterface(iface)

        Transport.getInterfaces().size shouldBe 1
        (Transport.getInterfaces()[0] === iface) shouldBe true
    }

    @Test
    @DisplayName("an upsert keeps the entry's position so broadcast ordering is stable across a revive")
    fun upsertPreservesPosition() {
        val first = FakeInterface("iface-a", hashOf(0x13))
        val second = FakeInterface("iface-b", hashOf(0x14))
        val firstRevived = FakeInterface("iface-a", hashOf(0x13))

        Transport.registerInterface(first)
        Transport.registerInterface(second)
        Transport.registerInterface(firstRevived)

        Transport.getInterfaces().map { it.name } shouldBe listOf("iface-a", "iface-b")
        (Transport.getInterfaces()[0] === firstRevived) shouldBe true
        (Transport.getInterfaces()[1] === second) shouldBe true
    }

    @Test
    @DisplayName("an upsert does not detach the superseded instance (Transport does not own its lifecycle)")
    fun upsertDoesNotDetachSupersededInstance() {
        val dead = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x15))
        val revived = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x15))

        Transport.registerInterface(dead)
        Transport.registerInterface(revived)

        dead.detachCount shouldBe 0
        revived.detachCount shouldBe 0
    }

    @Test
    @DisplayName("the local-client list does not accumulate across a revive (2 before the fix)")
    fun localClientListDoesNotAccumulate() {
        val first = FakeInterface("SharedInstanceClient", hashOf(0x16))
        val revived = FakeInterface("SharedInstanceClient", hashOf(0x16))

        Transport.registerLocalClientInterface(first)
        Transport.registerLocalClientInterface(revived)

        Transport.localClientCount() shouldBe 1
        Transport.getInterfaces().size shouldBe 1
        (Transport.getInterfaces()[0] === revived) shouldBe true
    }

    @Test
    @DisplayName("a stale shared-instance twin cannot be resurrected by the local-client fallback")
    fun staleLocalClientIsNotReturnedByHash() {
        val stale = FakeInterface("SharedInstanceClient", hashOf(0x17), isConnectedToSharedInstance = true)
        val revived = FakeInterface("SharedInstanceClient", hashOf(0x17), isConnectedToSharedInstance = true)

        Transport.registerLocalClientInterface(stale)
        Transport.registerLocalClientInterface(revived)

        (Transport.findInterfaceByHashForTest(hashOf(0x17)) === revived) shouldBe true
    }

    // ---- 2. stop() ---------------------------------------------------------

    @Test
    @DisplayName("stop() empties the registry, and a restart begins from empty (8 dead refs before the fix)")
    fun stopClearsInterfaceList() {
        Transport.registerInterface(FakeInterface("iface-a", hashOf(0x21)))
        Transport.registerInterface(FakeInterface("iface-b", hashOf(0x22)))
        Transport.getInterfaces().size shouldBe 2

        Transport.stop()

        Transport.getInterfaces().size shouldBe 0
        Transport.localClientCount() shouldBe 0

        Transport.start(Identity.create(), enableTransport = false)

        // A restart must not inherit the previous session's registrations.
        Transport.getInterfaces().size shouldBe 0

        // Repopulating from a fresh set of instances yields exactly one entry per
        // interface — no accumulation across the cycle.
        Transport.registerInterface(FakeInterface("iface-a", hashOf(0x21)))
        Transport.registerInterface(FakeInterface("iface-b", hashOf(0x22)))
        Transport.getInterfaces().size shouldBe 2
    }

    @Test
    @DisplayName("stop() discards per-interface stats and announce bookkeeping (retained before the fix)")
    fun stopClearsPerInterfaceState() {
        val iface = FakeInterface("iface-a", hashOf(0x23))
        Transport.registerInterface(iface)
        Transport.recordTxBytes(iface, 256)
        Transport.recordRxBytes(iface, 128)
        Transport.getInterfaceStats(iface) shouldNotBe null

        Transport.stop()

        Transport.getInterfaceStats(iface) shouldBe null
        Transport.getActiveInterfaces().size shouldBe 0
        Transport.prioritizeInterfaces().size shouldBe 0
    }

    @Test
    @DisplayName("stop() clears the shared-instance flag along with the client interface that set it")
    fun stopClearsSharedInstanceFlag() {
        val client = FakeInterface("SharedInstanceClient", hashOf(0x24), isConnectedToSharedInstance = true)

        Transport.registerInterface(client)
        Transport.isConnectedToSharedInstance shouldBe true

        Transport.stop()

        Transport.isConnectedToSharedInstance shouldBe false
        Transport.localClientCount() shouldBe 0
    }

    // ---- 3. findInterfaceByHash after a revive -----------------------------

    @Test
    @DisplayName("findInterfaceByHash returns the LIVE instance after a revive, not the dead twin (dead twin before the fix)")
    fun findInterfaceByHashReturnsLiveInstanceAfterRevive() {
        // Both instances look equally usable: nothing detached the stale one —
        // Transport.stop() does not own interface lifecycles — so it still
        // reports online/canSend. That is exactly why preferring a "usable"
        // match cannot disambiguate a twin; only the upsert can.
        val stale = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x31), online = true)
        val revived = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x31), online = true)

        Transport.registerInterface(stale)
        Transport.registerInterface(revived)

        (Transport.findInterfaceByHashForTest(hashOf(0x31)) === revived) shouldBe true
        (Transport.findInterfaceByHashForTest(hashOf(0x31)) === stale) shouldBe false
    }

    @Test
    @DisplayName("findInterfaceByHash still resolves a surviving interface after a stop/start/revive cycle")
    fun findInterfaceByHashAfterFullCycleResolvesRevivedInstance() {
        Transport.registerInterface(FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x32)))
        Transport.stop()
        Transport.start(Identity.create(), enableTransport = false)

        val revived = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x32))
        Transport.registerInterface(revived)

        (Transport.findInterfaceByHashForTest(hashOf(0x32)) === revived) shouldBe true
        Transport.getInterfaces().size shouldBe 1
    }

    // ---- 4. stats survive a revive (pinned, not assumed) -------------------

    @Test
    @DisplayName("traffic stats survive a revive: the stats row is keyed by hash, not by instance")
    fun statsSurviveRevive() {
        val first = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x41))
        Transport.registerInterface(first)
        Transport.recordTxBytes(first, 128)
        Transport.recordRxBytes(first, 64)
        Transport.recordAnnounceSent(first)

        val revived = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x41))
        Transport.registerInterface(revived)

        val afterRevive = Transport.getInterfaceStats(revived)
        afterRevive shouldNotBe null
        afterRevive!!.txBytes shouldBe 128L
        afterRevive.rxBytes shouldBe 64L
        afterRevive.announcesSent shouldBe 1L

        // The revived instance keeps writing into the same row...
        Transport.recordTxBytes(revived, 32)
        Transport.getInterfaceStats(revived)!!.txBytes shouldBe 160L
        // ...and the superseded instance reads the same row, because the key is
        // the hash (no split-brain counters).
        Transport.getInterfaceStats(first)!!.txBytes shouldBe 160L
    }

    @Test
    @DisplayName("deregistering an interface still discards its stats row (the one explicit removal path)")
    fun deregisterDiscardsStats() {
        val iface = FakeInterface("iface-a", hashOf(0x42))
        Transport.registerInterface(iface)
        Transport.recordTxBytes(iface, 16)
        Transport.getInterfaceStats(iface) shouldNotBe null

        Transport.deregisterInterface(iface)

        Transport.getInterfaceStats(iface) shouldBe null
        Transport.getInterfaces().size shouldBe 0
    }

    // ---- 5. path reuse across a restart must not regress -------------------

    @Test
    @DisplayName("learned paths still survive stop/start, and still resolve once the revived interface is back")
    fun pathReuseSurvivesStopStart() {
        val iface = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x51))
        Transport.registerInterface(iface)

        val dest = hashOf(0x52)
        val now = System.currentTimeMillis()
        val entry = PathEntry(
            timestamp = now,
            nextHop = hashOf(0x53),
            hops = 2,
            expires = now + TransportConstants.PATHFINDER_E,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = iface.hash,
            announcePacketHash = hashOf(0x54),
            state = PathState.ACTIVE,
            failureCount = 0,
        )
        Transport.pathTable[dest.toKey()] = entry
        pathStore.upsertPath(dest, entry) // what the write-through path does in production

        Transport.stop()

        // The registry is empty, but the persistent path table is untouched:
        // this is the state the app restores from ("Loaded N path entries").
        Transport.getInterfaces().size shouldBe 0
        pathStore.data.size shouldBe 1

        Transport.start(Identity.create(), enableTransport = false)

        Transport.getInterfaces().size shouldBe 0
        Transport.pathTable.containsKey(dest.toKey()) shouldBe true
        // Restored before any interface is registered: not dangling, so usable.
        Transport.hasPath(dest) shouldBe true

        // Once the revived interface is registered under the same hash, the
        // restored path resolves to it instead of being culled.
        val revived = FakeInterface("TCPClientInterface[callout-hub -> 10.0.0.5:4242]", hashOf(0x51))
        Transport.registerInterface(revived)

        Transport.hasPath(dest) shouldBe true
        (Transport.findInterfaceByHashForTest(iface.hash) === revived) shouldBe true
        Transport.pathTable[dest.toKey()]!!.receivingInterfaceHash.contentEquals(revived.hash) shouldBe true
    }
}
