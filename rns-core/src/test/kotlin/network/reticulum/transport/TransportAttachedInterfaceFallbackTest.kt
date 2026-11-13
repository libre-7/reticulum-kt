package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression guard for the attached-interface branch of
 * [Transport.processOutbound].
 *
 * Every PATH_RESPONSE goes through that branch
 * (`Destination.announce(attachedInterface = receivingInterface)` in
 * `processPathRequest`), and it used to transmit ONLY on the attached instance:
 * if that instance was not `canSend`/`online` it logged
 * `Attached interface … is not available` and left `sent = false`, dropping the
 * response. `requestPath` then never learns the path it asked for — the
 * mechanism both nodes in the cross-network trace actually depended on.
 *
 * The dead-twin shape is not hypothetical in this codebase:
 * `registerInterface` is a bare add, an interface's hash is derived from its
 * NAME (`fullHash(toString())`), and a revived interface (same host:port, new
 * instance) therefore has the SAME name/hash as the detached one.
 * `findInterfaceByHash` returns the FIRST match — the dead instance — so a
 * targeted path response is attached to a dead twin by construction.
 *
 * The fix has two rungs, both pinned here:
 *  1. prefer a LIVE instance registered under the same name (keeps the reply
 *     targeted at the same route);
 *  2. if there is none, fall back to the broadcast rather than drop the packet.
 *
 * And the honesty case: with no usable interface at all, `outbound` still
 * returns false — the fallback must not fabricate a send.
 */
@DisplayName("Transport attached-interface fallback (PATH_RESPONSE)")
class TransportAttachedInterfaceFallbackTest {

    /**
     * A minimal [InterfaceRef] that captures bytes handed to [send].
     * [name] and [hash] are constructor parameters so a test can model two
     * instances of the SAME interface (the dead twin + its replacement).
     */
    private class CapturingInterface(
        override val name: String,
        override val online: Boolean = true,
        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAA.toByte() },
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    /**
     * An [InterfaceRef] whose availability accessors throw, as a torn-down RNS
     * interface does mid-read (the app's transport snapshot wraps these reads
     * for the same reason). [send] itself is never reachable: an unusable ref
     * must not be transmitted on.
     */
    private class ThrowingAvailabilityInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAB.toByte() }
        override val canSend: Boolean get() = throw IllegalStateException("interface torn down")
        override val canReceive: Boolean = true
        override val online: Boolean get() = throw IllegalStateException("interface torn down")
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    private val registered = mutableListOf<InterfaceRef>()

    @BeforeEach
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort — a prior test may have left things in an odd state.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        for (iface in registered) {
            runCatching { Transport.deregisterInterface(iface) }
        }
        registered.clear()
        Transport.pathTable.clear()
        runCatching { Transport.stop() }
    }

    private fun <T : InterfaceRef> register(iface: T): T {
        Transport.registerInterface(iface)
        registered.add(iface)
        return iface
    }

    /** An ANNOUNCE packet with [attached] set — the shape a PATH_RESPONSE takes. */
    private fun announceAttachedTo(attached: InterfaceRef?): Packet {
        val destHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x42.toByte() }
        return Packet.createRaw(
            destinationHash = destHash,
            data = ByteArray(64) { 0x11.toByte() },
            packetType = PacketType.ANNOUNCE,
            destinationType = DestinationType.SINGLE,
            context = PacketContext.NONE,
            headerType = HeaderType.HEADER_1,
            createReceipt = false,
        ).apply { attachedInterface = attached }
    }

    @Test
    @DisplayName("PATH_RESPONSE attached to a dead twin is sent on the live instance of the same name")
    fun pathResponseAttachedToDeadTwinUsesLiveInstance() {
        // Same name AND same hash: exactly what a revived rendezvous looks like.
        val name = "callout-rns-relay"
        val hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x33.toByte() }
        val dead = register(CapturingInterface(name = name, online = false, hash = hash))
        val live = register(CapturingInterface(name = name, online = true, hash = hash))

        Transport.outbound(announceAttachedTo(dead)) shouldBe true

        dead.sent.size shouldBe 0
        live.sent.size shouldBe 1
    }

    @Test
    @DisplayName("PATH_RESPONSE attached to an unavailable instance falls back to broadcast")
    fun pathResponseFallsBackToBroadcastWithoutATwin() {
        // The attached instance is gone and no same-name replacement exists, but
        // another route is usable: dropping the response would starve
        // requestPath, so it must leave on the usable interface.
        val dead = register(
            CapturingInterface(
                name = "callout-rns-dead",
                online = false,
                hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x44.toByte() },
            )
        )
        val live = register(
            CapturingInterface(
                name = "callout-rns-live",
                online = true,
                hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x55.toByte() },
            )
        )

        Transport.outbound(announceAttachedTo(dead)) shouldBe true

        dead.sent.size shouldBe 0
        live.sent.size shouldBe 1
    }

    @Test
    @DisplayName("a usable attached interface is still the ONLY recipient")
    fun usableAttachedInterfaceStaysTargeted() {
        val attached = register(
            CapturingInterface(
                name = "callout-rns-attached",
                online = true,
                hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x66.toByte() },
            )
        )
        val other = register(
            CapturingInterface(
                name = "callout-rns-other",
                online = true,
                hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x77.toByte() },
            )
        )

        Transport.outbound(announceAttachedTo(attached)) shouldBe true

        attached.sent.size shouldBe 1
        other.sent.size shouldBe 0
    }

    @Test
    @DisplayName("path-routed DATA uses the live twin, not the dead first registration")
    fun pathRoutedDataUsesLiveTwin() {
        // The measured dead-twin defect: the hash is derived from the name, the
        // detached instance registered first, and findInterfaceByHash returned
        // it, so transmit() swallowed the refusal and the caller still saw
        // sent=true (the grant-send class).
        val name = "callout-rns-relay"
        val hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x99.toByte() }
        val dead = register(CapturingInterface(name = name, online = false, hash = hash))
        val live = register(CapturingInterface(name = name, online = true, hash = hash))

        val destHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x41.toByte() }
        val now = System.currentTimeMillis()
        Transport.pathTable[destHash.toKey()] = PathEntry(
            timestamp = now,
            nextHop = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xBB.toByte() },
            hops = 1,
            expires = now + TransportConstants.PATHFINDER_E,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = hash,
            announcePacketHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xCC.toByte() },
            state = PathState.ACTIVE,
            failureCount = 0,
        )
        val packet = Packet.createRaw(
            destinationHash = destHash,
            data = ByteArray(32) { 0x22.toByte() },
            packetType = PacketType.DATA,
            destinationType = DestinationType.SINGLE,
            context = PacketContext.NONE,
            headerType = HeaderType.HEADER_1,
            createReceipt = false,
        )

        Transport.outbound(packet) shouldBe true

        dead.sent.size shouldBe 0
        live.sent.size shouldBe 1
    }

    @Test
    @DisplayName("a torn-down attached ref falls back instead of aborting the emission")
    fun tornDownAttachedInterfaceFallsBack() {
        // RNS accessors throw when an interface is torn down mid-read; the
        // emission must treat that as "unavailable" (and fall back), not abort.
        val torn = register(ThrowingAvailabilityInterface(name = "callout-rns-torn"))
        val live = register(
            CapturingInterface(
                name = "callout-rns-live-after-torn",
                online = true,
                hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xCD.toByte() },
            )
        )

        Transport.outbound(announceAttachedTo(torn)) shouldBe true

        torn.sent.size shouldBe 0
        live.sent.size shouldBe 1
    }

    @Test
    @DisplayName("no usable interface at all still reports the packet as NOT sent")
    fun noUsableInterfaceIsNotFabricated() {
        val dead = register(
            CapturingInterface(
                name = "callout-rns-only-dead",
                online = false,
                hash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x88.toByte() },
            )
        )

        Transport.outbound(announceAttachedTo(dead)) shouldBe false

        dead.sent.size shouldBe 0
    }
}
