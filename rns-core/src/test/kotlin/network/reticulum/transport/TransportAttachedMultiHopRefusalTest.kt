package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The transport refuses an attached emission to a multi-hop destination.
 *
 * The failure this pins (measured on the wire): a `HEADER_1` SINGLE DATA
 * packet (flags `0x00`, `transportId == null`) was attached to one interface
 * for a destination the path table knew to be 2 hops away. The directly
 * connected hub accepted it and dropped it, because a transport only relays
 * packets whose `transportId` matches its own identity (HEADER_2). The app saw
 * a normal transmit and nothing ever arrived.
 *
 * `processOutbound` cannot let any caller produce that packet: when
 * `pathTable[dest].hops > 1`, an attached emission on one interface is
 * refused, logged with interface + destination hash + hops, and reported to
 * the caller as NOT sent.
 *
 * Scope of the refusal (each of these is a working case pinned below):
 *  - `hops == 1`: the attached interface reaches the destination directly, so
 *    a HEADER_1 send is correct on-link delivery.
 *  - no path entry: the legitimate pathless/broadcast shape is unchanged.
 *  - path-routed (non-attached) multi-hop DATA: still HEADER_2-wrapped and
 *    sent on the path interface — that is the shape that works.
 *  - ANNOUNCE: PATH_RESPONSEs are attached by construction
 *    (`processPathRequest`), and the path's hops describe the announced
 *    destination, not the response's recipient. Refusing those would starve
 *    `requestPath` across every multi-hop network.
 */
@DisplayName("Transport refuses attached emission to a multi-hop destination")
class TransportAttachedMultiHopRefusalTest {

    /** A minimal [InterfaceRef] that captures bytes handed to [send]. */
    private class CapturingInterface(
        override val name: String,
        override val hash: ByteArray,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
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

    private fun hashOf(fill: Int): ByteArray =
        ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { fill.toByte() }

    /**
     * Install a path for [destHash]. An `expires` in the past keeps
     * `processOutbound`'s path-routing branch from consuming the packet, which
     * is the reachable shape for an attached emission: either the known path
     * has expired, or its receiving interface vanished (removed just above the
     * attached branch).
     */
    private fun installPath(
        destHash: ByteArray,
        hops: Int,
        expires: Long,
        receivingInterfaceHash: ByteArray,
    ) {
        Transport.pathTable[destHash.toKey()] = PathEntry(
            timestamp = System.currentTimeMillis(),
            nextHop = hashOf(0xBB),
            hops = hops,
            expires = expires,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = receivingInterfaceHash,
            announcePacketHash = hashOf(0xCC),
            state = PathState.ACTIVE,
            failureCount = 0,
        )
    }

    private fun dataPacket(
        destHash: ByteArray,
        attached: InterfaceRef?,
    ): Packet = Packet.createRaw(
        destinationHash = destHash,
        data = ByteArray(32) { 0x22.toByte() },
        packetType = PacketType.DATA,
        destinationType = DestinationType.SINGLE,
        context = PacketContext.NONE,
        headerType = HeaderType.HEADER_1,
        createReceipt = false,
    ).apply { attachedInterface = attached }

    /** Run [block] with stdout captured so the refusal log line is assertable. */
    private fun captureOut(block: () -> Boolean): Pair<Boolean, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        var result = false
        try {
            System.setOut(PrintStream(buffer, true, "UTF-8"))
            result = block()
        } finally {
            System.setOut(original)
        }
        return result to buffer.toString("UTF-8")
    }

    // ---- the refusal -------------------------------------------------------

    @Test
    @DisplayName("attached DATA to a 2-hop destination is not transmitted, logged, and reported unsent")
    fun attachedDataOnMultiHopPathIsRefused() {
        val dest = hashOf(0x51)
        val attached = register(CapturingInterface("callout-rns-attached", hashOf(0x61)))
        val other = register(CapturingInterface("callout-rns-other", hashOf(0x62)))
        // Expired path: hops is still known (the destination IS multi-hop), but
        // the path branch will not wrap-and-send, so the attached branch sees it.
        installPath(dest, hops = 2, expires = System.currentTimeMillis() - 1_000, receivingInterfaceHash = attached.hash)

        val (sent, log) = captureOut { Transport.outbound(dataPacket(dest, attached)) }

        sent shouldBe false
        attached.sent.size shouldBe 0
        other.sent.size shouldBe 0
        log.contains(dest.toHexString()) shouldBe true
        log.contains("2 hops") shouldBe true
        log.contains(attached.name) shouldBe true
    }

    @Test
    @DisplayName("attached DATA on a multi-hop path whose interface vanished is still refused")
    fun attachedDataOnMultiHopPathWithVanishedInterfaceIsRefused() {
        // The stale-interface removal above the attached branch drops the path
        // entry; the destination is still known multi-hop, so attaching a
        // HEADER_1 packet to another interface must not resurrect the defect.
        val dest = hashOf(0x52)
        val attached = register(CapturingInterface("callout-rns-attached-stale", hashOf(0x63)))
        installPath(
            dest,
            hops = 3,
            expires = System.currentTimeMillis() + TransportConstants.PATHFINDER_E,
            receivingInterfaceHash = hashOf(0x71), // never registered
        )

        val (sent, log) = captureOut { Transport.outbound(dataPacket(dest, attached)) }

        sent shouldBe false
        attached.sent.size shouldBe 0
        Transport.pathTable.containsKey(dest.toKey()) shouldBe false
        log.contains("3 hops") shouldBe true
    }

    // ---- cases that must keep working -------------------------------------

    @Test
    @DisplayName("attached DATA to a 1-hop destination still transmits (on-link delivery)")
    fun attachedDataOnOneHopPathStillTransmits() {
        val dest = hashOf(0x53)
        val attached = register(CapturingInterface("callout-rns-onlink", hashOf(0x64)))
        // Expired so the attached branch (not the path branch) is exercised;
        // the hop count itself is the point: 1 hop is direct delivery.
        installPath(dest, hops = 1, expires = System.currentTimeMillis() - 1_000, receivingInterfaceHash = attached.hash)

        Transport.outbound(dataPacket(dest, attached)) shouldBe true

        attached.sent.size shouldBe 1
    }

    @Test
    @DisplayName("attached DATA with no path entry keeps its current behaviour")
    fun attachedDataWithNoPathEntryStillTransmits() {
        val dest = hashOf(0x54)
        val attached = register(CapturingInterface("callout-rns-pathless", hashOf(0x65)))
        val other = register(CapturingInterface("callout-rns-pathless-other", hashOf(0x66)))
        // No path entry at all: the pathless/broadcast shape, unchanged.

        Transport.outbound(dataPacket(dest, attached)) shouldBe true

        attached.sent.size shouldBe 1
        other.sent.size shouldBe 0
    }

    @Test
    @DisplayName("path-routed non-attached multi-hop DATA is unaffected (still HEADER_2)")
    fun pathRoutedMultiHopDataIsUnaffected() {
        val dest = hashOf(0x55)
        val pathIface = register(CapturingInterface("callout-rns-path", hashOf(0x67)))
        installPath(
            dest,
            hops = 2,
            expires = System.currentTimeMillis() + TransportConstants.PATHFINDER_E,
            receivingInterfaceHash = pathIface.hash,
        )
        val packet = dataPacket(dest, attached = null)
        val packedSize = packet.pack().size

        Transport.outbound(packet) shouldBe true

        pathIface.sent.size shouldBe 1
        val wire = pathIface.sent[0]
        (wire[0].toInt() and 0x40) shouldBe 0x40 // HEADER_2 — what a hub forwards
        wire.size shouldBe packedSize + RnsConstants.TRUNCATED_HASH_BYTES
    }

    @Test
    @DisplayName("an attached PATH_RESPONSE for a multi-hop destination is NOT refused")
    fun attachedPathResponseForMultiHopDestinationIsNotRefused() {
        // processPathRequest attaches the response to the requesting interface.
        // pathTable[dest].hops is how far the ANNOUNCED destination is from us,
        // not how far the requester is — so this is not the unfowardable shape
        // and must keep going out targeted.
        val dest = hashOf(0x56)
        val requesting = register(CapturingInterface("callout-rns-requester", hashOf(0x68)))
        installPath(
            dest,
            hops = 3,
            expires = System.currentTimeMillis() + TransportConstants.PATHFINDER_E,
            receivingInterfaceHash = hashOf(0x72),
        )
        val announce = Packet.createRaw(
            destinationHash = dest,
            data = ByteArray(64) { 0x11.toByte() },
            packetType = PacketType.ANNOUNCE,
            destinationType = DestinationType.SINGLE,
            context = PacketContext.PATH_RESPONSE,
            headerType = HeaderType.HEADER_1,
            createReceipt = false,
        ).apply { attachedInterface = requesting }

        Transport.outbound(announce) shouldBe true

        requesting.sent.size shouldBe 1
    }
}
