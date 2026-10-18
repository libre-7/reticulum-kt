package network.reticulum.identity

import io.kotest.matchers.shouldBe
import network.reticulum.common.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.msgpack.core.MessagePack
import java.nio.file.Files

/**
 * Pins the streamed + capped known-destinations persistence introduced to fix
 * the periodic OOM: the old save held destFile.readBytes() + a full
 * Map<ByteArray, List<Any?>> copy + a ByteArrayOutputStream of the whole map
 * (three ~32 MiB buffers every 5 minutes at ~94k destinations), and nothing
 * ever pruned the cache.
 */
@DisplayName("Known destinations streaming persistence")
class KnownDestinationsStreamingTest {

    private val tempDir = Files.createTempDirectory("rns-known-dest-test").toFile()

    @BeforeEach
    fun setup() {
        Identity.identityStore = null
        Identity.clearKnownDestinations()
        Identity.knownDestinationsMaxSize = Platform.recommendedKnownDestinationsSize
        Identity.setStoragePath(tempDir.absolutePath)
    }

    @AfterEach
    fun teardown() {
        Identity.identityStore = null
        Identity.clearKnownDestinations()
        Identity.knownDestinationsMaxSize = Platform.recommendedKnownDestinationsSize
        tempDir.deleteRecursively()
    }

    private fun destHash(i: Int): ByteArray {
        val out = ByteArray(16)
        out[0] = (i and 0xFF).toByte()
        out[1] = ((i shr 8) and 0xFF).toByte()
        out[2] = ((i shr 16) and 0xFF).toByte()
        out[15] = 0x77
        return out
    }

    private fun publicKey(i: Int): ByteArray {
        val out = ByteArray(64) { 3 }
        out[0] = (i and 0xFF).toByte()
        out[1] = ((i shr 8) and 0xFF).toByte()
        return out
    }

    private fun packetHash(i: Int): ByteArray = ByteArray(32) { (i and 0xFF).toByte() }

    private fun destFile() = java.io.File(tempDir, "known_destinations")

    /** Writes a legacy-format destinations file with controlled timestamps. */
    private fun writeFile(entries: List<Triple<Int, Long, ByteArray?>>) {
        MessagePack.newDefaultPacker(destFile().outputStream()).use { packer ->
            packer.packMapHeader(entries.size)
            for ((i, ts, appData) in entries) {
                val key = destHash(i)
                packer.packBinaryHeader(key.size)
                packer.writePayload(key)
                packer.packArrayHeader(4)
                packer.packLong(ts)
                val ph = packetHash(i)
                packer.packBinaryHeader(ph.size)
                packer.writePayload(ph)
                val pk = publicKey(i)
                packer.packBinaryHeader(pk.size)
                packer.writePayload(pk)
                if (appData == null) {
                    packer.packNil()
                } else {
                    packer.packBinaryHeader(appData.size)
                    packer.writePayload(appData)
                }
            }
        }
    }

    /** Reads the current destinations file back into key -> (timestamp, appData). */
    private fun readFile(): Map<Int, Pair<Long, ByteArray?>> {
        val result = mutableMapOf<Int, Pair<Long, ByteArray?>>()
        MessagePack.newDefaultUnpacker(destFile().inputStream()).use { unpacker ->
            val mapSize = unpacker.unpackMapHeader()
            repeat(mapSize) {
                val keyLen = unpacker.unpackBinaryHeader()
                val key = ByteArray(keyLen)
                unpacker.readPayload(key)
                unpacker.unpackArrayHeader()
                val ts = unpacker.unpackLong()
                val phLen = unpacker.unpackBinaryHeader()
                unpacker.readPayload(ByteArray(phLen))
                val pkLen = unpacker.unpackBinaryHeader()
                unpacker.readPayload(ByteArray(pkLen))
                val appData = if (unpacker.tryUnpackNil()) {
                    null
                } else {
                    val adLen = unpacker.unpackBinaryHeader()
                    val d = ByteArray(adLen)
                    unpacker.readPayload(d)
                    d
                }
                // Index is encoded in the low two bytes of every key we wrote.
                val idx = (key[0].toInt() and 0xFF) or ((key[1].toInt() and 0xFF) shl 8) or
                    ((key[2].toInt() and 0xFF) shl 16)
                result[idx] = ts to appData
            }
        }
        return result
    }

    @Test
    fun `save then load round-trips entries and app data`() {
        Identity.remember(packetHash(1), destHash(1), publicKey(1), "one".toByteArray())
        Identity.remember(packetHash(2), destHash(2), publicKey(2), null)
        Identity.remember(packetHash(3), destHash(3), publicKey(3), "three".toByteArray())
        Identity.knownDestinationCount() shouldBe 3

        Identity.saveKnownDestinations()
        destFile().exists() shouldBe true

        Identity.clearKnownDestinations()
        Identity.loadKnownDestinations()

        Identity.knownDestinationCount() shouldBe 3
        (Identity.recallAppData(destHash(1))) shouldBe "one".toByteArray()
        (Identity.recallAppData(destHash(2))) shouldBe null
        (Identity.recallAppData(destHash(3))) shouldBe "three".toByteArray()
    }

    @Test
    fun `save preserves disk-only entries and memory wins on conflict`() {
        writeFile(listOf(Triple(10, 100L, null), Triple(11, 200L, null)))
        Identity.remember(packetHash(11), destHash(11), publicKey(11), "fresh".toByteArray())
        Identity.remember(packetHash(12), destHash(12), publicKey(12), null)

        Identity.saveKnownDestinations()

        val written = readFile()
        written.size shouldBe 3
        // Memory entry for destination 11 wins over the older disk entry.
        (written[11]!!.second) shouldBe "fresh".toByteArray()
        written[10]!!.first shouldBe 100L
        written.containsKey(12) shouldBe true
        (written.containsKey(11)) shouldBe true
        destFile().parentFile.listFiles()!!.none { it.name.endsWith(".tmp") } shouldBe true
    }

    @Test
    fun `save prunes oldest beyond cap across memory and disk`() {
        // 120 old disk entries, timestamps 1..120.
        writeFile((1..120).map { Triple(it, it.toLong(), null) })
        // 5 fresh memory entries.
        for (i in 1..5) {
            Identity.remember(packetHash(500 + i), destHash(500 + i), publicKey(500 + i), null)
        }

        Identity.knownDestinationsMaxSize = 100
        Identity.saveKnownDestinations()

        val written = readFile()
        written.size shouldBe 100
        // Oldest 25 disk entries evicted; the newest disk entries and all memory entries survive.
        (written.containsKey(25)) shouldBe false
        (written.containsKey(26)) shouldBe true
        (written.containsKey(120)) shouldBe true
        (written.containsKey(505)) shouldBe true
        // Memory retains only runtime-learned entries (the streaming save no
        // longer merges disk history back into the live map — matching the
        // Python reference, which serializes the in-memory dict as-is).
        Identity.knownDestinationCount() shouldBe 5
        Identity.isKnown(destHash(501)) shouldBe true
    }

    @Test
    fun `load prunes to the cap keeping the newest entries`() {
        writeFile((1..150).map { Triple(it, it.toLong(), null) })

        Identity.knownDestinationsMaxSize = 100
        Identity.loadKnownDestinations()

        Identity.knownDestinationCount() shouldBe 100
        Identity.isKnown(destHash(1)) shouldBe false   // oldest evicted
        Identity.isKnown(destHash(150)) shouldBe true // newest kept
    }

    @Test
    fun `save leaves no temp file behind`() {
        Identity.remember(packetHash(1), destHash(1), publicKey(1), null)
        Identity.saveKnownDestinations()

        val leftovers = tempDir.listFiles()!!.filter { it.name.contains(".tmp") }
        leftovers.size shouldBe 0
    }
}
