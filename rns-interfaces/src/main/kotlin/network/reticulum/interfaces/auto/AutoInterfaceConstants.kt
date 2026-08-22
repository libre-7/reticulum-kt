package network.reticulum.interfaces.auto

/**
 * Constants for AutoInterface, matching Python RNS implementation exactly.
 */
object AutoInterfaceConstants {
    // Default ports
    const val DEFAULT_DISCOVERY_PORT = 29716
    const val DEFAULT_DATA_PORT = 42671
    val DEFAULT_GROUP_ID: ByteArray = "reticulum".toByteArray(Charsets.UTF_8)

    // Timing constants (in seconds, converted to millis where needed)
    const val PEERING_TIMEOUT = 22.0           // seconds before peer removal
    const val ANNOUNCE_INTERVAL = 1.6          // seconds between discovery announcements
    const val PEER_JOB_INTERVAL = 4.0          // seconds between peer management jobs
    const val MCAST_ECHO_TIMEOUT = 6.5         // seconds before carrier loss detected

    // Timing in milliseconds for convenience
    val PEERING_TIMEOUT_MS = (PEERING_TIMEOUT * 1000).toLong()
    val ANNOUNCE_INTERVAL_MS = (ANNOUNCE_INTERVAL * 1000).toLong()
    val PEER_JOB_INTERVAL_MS = (PEER_JOB_INTERVAL * 1000).toLong()
    val MCAST_ECHO_TIMEOUT_MS = (MCAST_ECHO_TIMEOUT * 1000).toLong()

    // Network settings
    const val HW_MTU = 1196
    const val BITRATE_GUESS = 10_000_000       // 10 Mbps default estimate
    const val DEFAULT_IFAC_SIZE = 16

    // IPv6 multicast scopes
    const val SCOPE_LINK = "2"                 // link-local (default)
    const val SCOPE_ADMIN = "4"                // administrative
    const val SCOPE_SITE = "5"                 // site-local
    const val SCOPE_ORGANISATION = "8"         // organizational
    const val SCOPE_GLOBAL = "e"               // global

    // Multicast address types
    const val MULTICAST_TEMPORARY_ADDRESS_TYPE = "1"   // default
    const val MULTICAST_PERMANENT_ADDRESS_TYPE = "0"

    // Deque for multi-interface duplicate detection
    const val MULTI_IF_DEQUE_LEN = 48          // max recent packets to track
    const val MULTI_IF_DEQUE_TTL = 0.75        // seconds to remember packets
    val MULTI_IF_DEQUE_TTL_MS = (MULTI_IF_DEQUE_TTL * 1000).toLong()

    // D7 hardening (2026-08-22): how long a pruned peer's address stays
    // eligible for rehydration when inbound DATA arrives from it. Generous
    // by design — multicast flapping (emulator QEMU/SLIRP, congested Wi-Fi)
    // must not cost us inbound GROUP traffic. Data or discovery from the
    // peer refreshes the window, so a continuously-talking peer never goes
    // "unknown" no matter how flaky its discovery announcements are.
    const val PEER_REMEMBER = 600.0            // seconds (10 minutes)
    val PEER_REMEMBER_MS = (PEER_REMEMBER * 1000).toLong()

    // Platform-specific ignored interfaces
    val LINUX_IGNORE_IFS = listOf("lo")
    val DARWIN_IGNORE_IFS = listOf("awdl0", "llw0", "lo0", "en5")
    val ANDROID_IGNORE_IFS = listOf("dummy0", "lo", "tun0")
    val ALL_IGNORE_IFS = listOf("lo0")

    // Android-specific timeout adjustment
    const val ANDROID_PEERING_TIMEOUT = 27.5   // seconds (longer for Android)
}
