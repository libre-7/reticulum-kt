package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Pins the streamed packet-hashlist persistence introduced to fix the
 * shutdown-time OOM: the old save built List<String> + joined String
 * (>130 MiB at the 1M desktop cap), and the old load read the whole file
 * into a List<String> with no cap.
 */
@DisplayName("Packet hashlist streaming persistence")
class PacketHashlistStreamingTest {

    private val tempDir = Files.createTempDirectory("rns-hashlist-test").toFile()

    @AfterEach
    fun teardown() {
        Transport.clearPacketHashlist()
        Transport.hashlistMaxSize = Platform.recommendedHashlistSize
        tempDir.deleteRecursively()
    }

    private fun hash(i: Int): ByteArray {
        val out = ByteArray(32)
        out[0] = (i and 0xFF).toByte()
        out[1] = ((i shr 8) and 0xFF).toByte()
        out[2] = ((i shr 16) and 0xFF).toByte()
        out[3] = ((i shr 24) and 0xFF).toByte()
        out[31] = 0x5A
        return out
    }

    private fun Int.toHashHex(): String = java.lang.String.format("%064x", this.toLong())

    @Test
    fun `save writes one hex line per hash and load round-trips`() {
        Transport.clearPacketHashlist()
        repeat(5_000) { Transport.addPacketHash(hash(it)) }
        Transport.packetHashlistSize() shouldBe 5_000

        val file = java.io.File(tempDir, "packet_hashlist")
        Transport.savePacketHashlist(file)

        val lines = file.readLines().filter { it.isNotBlank() }
        lines.size shouldBe 5_000
        (lines.all { it.length == 64 }) shouldBe true // 32-byte hash, hex-encoded

        Transport.clearPacketHashlist()
        Transport.loadPacketHashlist(file)
        Transport.packetHashlistSize() shouldBe 5_000
    }

    @Test
    fun `load stops at the configured cap`() {
        val file = java.io.File(tempDir, "packet_hashlist")
        file.writeText((0 until 300).joinToString("\n") { it.toHashHex() })

        Transport.clearPacketHashlist()
        Transport.hashlistMaxSize = 100
        Transport.loadPacketHashlist(file)

        Transport.packetHashlistSize() shouldBe 100
    }

    @Test
    fun `rotation uses the platform-scaled cap and loses no hashes`() {
        Transport.clearPacketHashlist()
        Transport.hashlistMaxSize = 100

        repeat(150) { Transport.addPacketHash(hash(it)) }

        // The 101st insert rotates the active list into the previous list.
        Transport.packetHashlistPrevSize() shouldBe 101
        Transport.packetHashlistSize() shouldBe 49
        (Transport.packetHashlistSize() + Transport.packetHashlistPrevSize()) shouldBe 150
    }
}
