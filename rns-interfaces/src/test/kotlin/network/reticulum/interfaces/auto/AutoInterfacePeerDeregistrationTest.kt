package network.reticulum.interfaces.auto

import io.kotest.matchers.shouldBe
import network.reticulum.interfaces.toRef
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

/**
 * `AutoInterface.detach()` deregisters its spawned peer refs.
 *
 * `detach()` used to only call `peer.detach()` on each spawned peer — which
 * clears the peer's `online` / `detached` flags but leaves its `InterfaceRef`
 * registered with Transport. Only `removePeer()` deregistered. So every
 * AutoInterface teardown leaked one registered (dead) peer ref per peer
 * address, and the leak survived because `detach()` itself returned early
 * whenever `start()` had not completed (`running == false`) — which is exactly
 * the state a failed or partial start leaves behind.
 *
 * Peers are adopted through the `adoptPeerForTest` seam rather than discovered:
 * `addPeer` resolves an IPv6 link-local address via `NetworkInterface`, which no
 * CI host can guarantee. Nothing here opens a multicast socket, and
 * `AutoInterface.start()` is never called — the first test therefore also pins
 * that the teardown works on an interface that was never (or never
 * successfully) started.
 */
@DisplayName("AutoInterface detach deregisters spawned peer refs")
class AutoInterfacePeerDeregistrationTest {

    /** Refs this test put in Transport, so a failed assertion cannot leak them. */
    private val tracked = mutableListOf<InterfaceRef>()

    private fun newAuto(): AutoInterface = AutoInterface(name = "AutoInterfaceTest-${UUID.randomUUID()}")

    private fun newPeer(
        auto: AutoInterface,
        address: String,
    ): AutoInterfacePeer {
        val peer =
            AutoInterfacePeer(
                name = "${auto.name}[$address]",
                parent = auto,
                targetAddress = InetSocketAddress(InetAddress.getByName(address), 48_555),
                interfaceName = "lo",
            )
        peer.start() // Allocates the outbound DatagramSocket; no packet is sent.
        return peer
    }

    private fun adopt(
        auto: AutoInterface,
        address: String,
    ): AutoInterfacePeer {
        val peer = newPeer(auto, address)
        val ref = peer.toRef()
        tracked.add(ref)
        auto.adoptPeerForTest(address, peer)
        return peer
    }

    private fun isRegistered(ref: InterfaceRef): Boolean = Transport.getInterfaces().any { it === ref }

    private fun anyRegisteredWithHash(hash: ByteArray): Boolean =
        Transport.getInterfaces().any { it.hash.contentEquals(hash) }

    @AfterEach
    fun teardown() {
        for (ref in tracked) runCatching { Transport.deregisterInterface(ref) }
        tracked.clear()
    }

    @Test
    @DisplayName("detach() leaves no peer refs registered (peers stayed registered before the fix)")
    fun detachDeregistersPeers() {
        val auto = newAuto()
        val address = "::1"
        val peer = newPeer(auto, address)
        val ref = peer.toRef()
        tracked.add(ref)
        auto.adoptPeerForTest(address, peer)

        auto.peerCount() shouldBe 1
        isRegistered(ref) shouldBe true

        auto.detach()

        isRegistered(ref) shouldBe false
        anyRegisteredWithHash(peer.getHash()) shouldBe false
        auto.peerCount() shouldBe 0
        (auto.spawnedInterfaces?.size ?: 0) shouldBe 0
    }

    @Test
    @DisplayName("detach() drops EVERY peer, not just the first")
    fun detachDeregistersAllPeers() {
        val auto = newAuto()
        val peers = listOf("::1", "::2", "::3").map { address -> adopt(auto, address) }
        peers.forEach { peer -> anyRegisteredWithHash(peer.getHash()) shouldBe true }

        auto.detach()

        peers.forEach { peer -> anyRegisteredWithHash(peer.getHash()) shouldBe false }
        auto.peerCount() shouldBe 0
        (auto.spawnedInterfaces?.size ?: 0) shouldBe 0
    }

    @Test
    @DisplayName("detach() is idempotent — a second call neither throws nor re-registers")
    fun detachIsIdempotent() {
        val auto = newAuto()
        val peer = adopt(auto, "::1")
        val ref = tracked.last()

        auto.detach()
        auto.detach()

        isRegistered(ref) shouldBe false
        anyRegisteredWithHash(peer.getHash()) shouldBe false
        auto.peerCount() shouldBe 0
    }
}
