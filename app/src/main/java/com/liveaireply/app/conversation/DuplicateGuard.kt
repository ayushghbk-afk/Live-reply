package com.liveaireply.app.conversation

import com.liveaireply.app.util.MillisClock

/**
 * Time-limited memory of what the detector has already handled.
 *
 * Three separate memories, because they answer three different questions:
 *  - [hasSeen]: "have I already looked at this bubble?" (scrolling, UI churn)
 *  - [isPending]: "is a reply for this bubble already being generated?" (debounce races)
 *  - [isSelfReply]: "is this bubble something *I* wrote?" (loop prevention)
 *
 * Entries expire so a long-running service does not grow without bound, and the map is
 * capped so a pathological chat cannot exhaust memory.
 */
class DuplicateGuard(
    private val clock: MillisClock = MillisClock.SYSTEM,
    private val maxTrackedHashes: Int = 400,
    private val seenTtlMs: Long = 15 * 60 * 1000L,
    private val selfReplyTtlMs: Long = 30 * 60 * 1000L
) {
    private val seen = LinkedHashMap<String, Long>()
    private val pending = LinkedHashMap<String, Long>()
    private val selfReplies = LinkedHashMap<String, Long>()

    @Synchronized
    fun hasSeen(hash: String): Boolean {
        prune()
        return seen.containsKey(hash)
    }

    @Synchronized
    fun remember(hash: String) {
        if (hash.isBlank()) return
        prune()
        seen[hash] = clock.now()
        capTo(seen, maxTrackedHashes)
    }

    @Synchronized
    fun rememberAll(hashes: Collection<String>) = hashes.forEach { remember(it) }

    @Synchronized
    fun markPending(hash: String) {
        if (hash.isBlank()) return
        pending[hash] = clock.now()
        capTo(pending, maxTrackedHashes)
    }

    @Synchronized
    fun clearPending(hash: String) {
        pending.remove(hash)
    }

    @Synchronized
    fun isPending(hash: String): Boolean = pending.containsKey(hash)

    /**
     * Records that the app itself produced [text]. Any later bubble with the same
     * content is ignored, which is what stops
     * "AI reply -> detector sees AI reply -> AI replies again".
     */
    @Synchronized
    fun markSelfReply(text: String) {
        if (MessageHasher.normalize(text).isEmpty()) return
        val now = clock.now()
        // Register the content hash *and* both directional hashes: the detector compares
        // direction-aware hashes, and a reply can come back classified either way.
        val hashes = listOf(
            MessageHasher.hash(text),
            MessageHasher.structuralHash(text, TurnDirection.INCOMING),
            MessageHasher.structuralHash(text, TurnDirection.OUTGOING)
        )
        for (hash in hashes) {
            selfReplies[hash] = now
            remember(hash)
        }
        capTo(selfReplies, maxTrackedHashes)
    }

    @Synchronized
    fun isSelfReply(hash: String): Boolean {
        prune()
        return selfReplies.containsKey(hash)
    }

    @Synchronized
    fun forget(hash: String) {
        seen.remove(hash)
        pending.remove(hash)
    }

    @Synchronized
    fun reset() {
        seen.clear()
        pending.clear()
        selfReplies.clear()
    }

    /** Drops self-reply entries but keeps "seen" memory. Used when a chat is reopened. */
    @Synchronized
    fun clearSelfReplies() = selfReplies.clear()

    @Synchronized
    fun snapshotStats(): DuplicateGuardStats =
        DuplicateGuardStats(seen.size, pending.size, selfReplies.size)

    private fun prune() {
        val now = clock.now()
        seen.entries.removeIf { now - it.value > seenTtlMs }
        pending.entries.removeIf { now - it.value > seenTtlMs }
        selfReplies.entries.removeIf { now - it.value > selfReplyTtlMs }
    }

    private fun capTo(map: LinkedHashMap<String, Long>, max: Int) {
        while (map.size > max) {
            val oldest = map.keys.iterator().let { if (it.hasNext()) it.next() else return }
            map.remove(oldest)
        }
    }
}

data class DuplicateGuardStats(
    val seenCount: Int,
    val pendingCount: Int,
    val selfReplyCount: Int
)
