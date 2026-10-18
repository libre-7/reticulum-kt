# Python Reference Implementation Deviations

This document tracks intentional behavioral differences between the
Kotlin (reticulum-kt) implementation and the Python reference (RNS).
Each entry explains **what** differs, **why**, and the **risk**.

Python is always the ground truth. Deviations documented here are
temporary workarounds or conscious trade-offs, not design choices.

---

## Transport: Stale Path Entry Removal on Outbound

**Python behavior** (`Transport.py:load_path_table`):
When loading persisted paths on startup, Python resolves the stored
interface hash to a live interface object. If `find_interface_from_hash()`
returns `None`, the path entry is simply not loaded (line 105:
"The interface is no longer available").

**Kotlin behavior** (`Transport.kt:processOutbound`):
Kotlin loads all persisted paths regardless of interface availability.
During outbound routing, if `findInterfaceByHash()` returns null for
a path entry, Kotlin removes the stale entry from the path table at
that point and falls through to broadcast.

**Why**: Kotlin's path persistence loads paths before all interfaces
are registered (AutoInterface/discovered interfaces register later).
Filtering at load time would discard valid paths for interfaces that
haven't connected yet.

**Risk**: Low. The end result is the same — stale paths are eventually
removed. The timing differs (Python: at load; Kotlin: at first use).

**Resolution**: Add a deferred path validation pass after all interfaces
are registered, matching Python's load-time filtering.

---

## LXMRouter: Ratchet Self-Storage Removed from generateAnnounceData

**Python behavior** (`Destination.py:announce()`):
Python does NOT store the ratchet public key under the local
destination's hash during announce generation. The ratchet public
key is only stored by REMOTE nodes that receive the announce.

**Kotlin behavior (before fix)**:
`generateAnnounceData()` called `setRatchetForDestination(hash, ratchetPub)`
and `Identity.rememberRatchet(hash, ratchetPub)`, storing the ratchet
public key under the local destination hash. This confused the
`Destination.encrypt()` path because `Identity.get_ratchet(destHash)`
would find a public key for a LOCAL destination and use it instead
of the identity's base key.

**Kotlin behavior (after fix)**: Matches Python — removed self-storage.

**Risk**: None. This was a bug fix, not a deviation.

---

## AutoInterface: Adaptive Announce Interval

**Python behavior** (`AutoInterface.py:announce_handler`):
Sends multicast discovery announcements every 1.6 seconds continuously
with no adaptation. This interval is hardcoded and never changes.

**Kotlin behavior** (`AutoInterface.kt:startAnnouncementLoop`):
The announce interval adapts based on peer activity and device state:

| State | Announce Interval | vs Python (1.6s) |
|-------|------------------|------------------|
| Startup / peer change | 1.6s (+ immediate reply) | Same |
| Stable, 30s | ~60s | 37x less |
| Stable, 60s+ | 2 minutes | 75x less |
| Doze, stable | 10 minutes | 375x less |
| New peer during Doze | Immediate reply → 1.6s | Same responsiveness |

The interval linearly ramps from 1.6s to `maxAnnounceIntervalMs *
throttleMultiplier` over 60 seconds after the last peer topology
change. Any peer add/remove resets to fast mode AND triggers an
immediate announce so the new peer discovers us within ~1 second.

Doze awareness: `NativeReticulumProtocol` observes `DozeStateObserver`
and sets `AutoInterface.throttleMultiplier` to 5.0 during Doze,
scaling the max interval from 2 minutes to 10 minutes.

**Why**: Continuous 1.6s multicast is the single biggest battery drain
on the native stack. Receiving other nodes' multicast is free (blocking
`socket.receive()`), but sending wakes the WiFi radio every 1.6s
indefinitely. Since peer discovery is symmetric, other nodes sending
at their rate still discover us via receiving — we only need to send
often enough that they discover us within a reasonable time.

**Multicast socket cleanup**: Kotlin explicitly calls `leaveGroup()`
before closing multicast sockets on detach, preventing OS-level
multicast group membership from lingering after the interface is
stopped. Python relies on `socket.close()` for implicit cleanup.

**Risk**: Low. A node running Kotlin at 2-minute intervals next to a
node running Python at 1.6s will still discover each other: the Python
node's multicast arrives within 1.6s, and the Kotlin node's immediate
reply fires within ~1 second. After discovery, unicast data flow is
unaffected.

**Resolution**: This is an intentional improvement, not a workaround.
`throttleMultiplier` can be set to 1.0 and `maxAnnounceIntervalMs`
can be set to `ANNOUNCE_INTERVAL_MS` to match exact Python behavior
if needed.

---

## Conformance Test Status

78 of 79 conformance tests pass (only `bz2_compress` differs due to
library version producing different but equally valid compressed output).

All 4 ratchet lifecycle tests pass, including:
- Announce with ratchet pack/unpack
- Ratchet extraction from announce
- Full lifecycle encrypt/decrypt
- Cross-implementation encrypt/decrypt

## Identity: Bounded known_destinations cache (memory + periodic save)

**What differs:** `Identity.knownDestinations` is capped at
`Platform.recommendedKnownDestinationsSize` (20k entries on Android, 200k on
JVM) instead of growing without limit. Overflow evicts the oldest entries by
`timestamp`, oldest-first, in three places: amortized during `remember()`
(trim once 10% over the cap), during `loadKnownDestinations()`, and against the
union of memory + on-disk entries during `saveKnownDestinations()`.

Python RNS keeps `Identity.known_destinations` unbounded and serializes the
whole dict to `known_destinations` (msgpack) on exit/periodically.

**Why:** On Android the map reached 93,853 entries in normal Callout mesh use,
and the periodic save (every 5 min via `Transport`) held the whole file
(`readBytes()`), a full `Map<ByteArray, List<Any?>>` parse of it, and a
`ByteArrayOutputStream` of the merged map — three ~32 MiB buffers per save,
which repeatedly OOM-killed the mesh service (Oct 2026 crash class). The cache
is a discovery/announce cache: an evicted destination re-announces and is
re-learned, so eviction is safe.

**Risk:** A destination whose identity has been evicted cannot be resolved
until it announces again (same risk profile as any cache miss; no protocol
change). Wire format of the `known_destinations` file is unchanged
(msgpack map: dest_hash -> [timestamp, packet_hash, public_key, app_data|nil]).

**Additional note:** `saveKnownDestinations()` no longer merges on-disk entries
back into the in-memory map. Python serializes the in-memory dict as-is, so
the previous merge was the deviation. Startup still loads the file into memory
(then caps it).

## Transport: Platform-scaled packet hashlist cap

**What differs:** Hashlist rotation uses `Platform.recommendedHashlistSize`
(50,000 on Android, 1,000,000 on JVM) instead of the fixed
`TransportConstants.HASHLIST_MAXSIZE` (1M) constant, and
`loadPacketHashlist()` stops after `hashlistMaxSize` entries.

**Why:** The 1M desktop constant meant an Android mesh service could accumulate
1M 32-byte hashes (~2x that while rotated) before eviction. 50k is the
port's own platform recommendation; the dedupe window remains hours of traffic
at mesh rates.

**Risk:** Duplicate suppression window is shorter on Android. No wire or file
format change (still one hex hash per line).
