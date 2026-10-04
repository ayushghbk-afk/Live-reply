package com.liveaireply.app.conversation

import com.liveaireply.app.util.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DuplicateGuardTest {

    private lateinit var clock: ManualClock
    private lateinit var guard: DuplicateGuard

    @Before
    fun setUp() {
        clock = ManualClock(0L)
        guard = DuplicateGuard(clock, maxTrackedHashes = 5, seenTtlMs = 1_000L, selfReplyTtlMs = 2_000L)
    }

    @Test
    fun remembersWhatItHasSeen() {
        assertFalse(guard.hasSeen("a"))
        guard.remember("a")
        assertTrue(guard.hasSeen("a"))
    }

    @Test
    fun expiresEntriesAfterTheTtl() {
        guard.remember("a")
        assertTrue(guard.hasSeen("a"))
        clock.advance(1_001L)
        assertFalse("seen entries must expire", guard.hasSeen("a"))
    }

    @Test
    fun capsMemorySoALongSessionCannotGrowWithoutBound() {
        repeat(50) { guard.remember("hash-$it") }
        val stats = guard.snapshotStats()
        assertTrue("seen count was ${stats.seenCount}", stats.seenCount <= 5)
    }

    @Test
    fun selfReplySurvivesLongerThanSeenEntries() {
        guard.markSelfReply("I am on my way")
        clock.advance(1_500L)
        assertTrue(
            "self-reply memory must outlive the seen cache",
            guard.isSelfReply(MessageHasher.hash("I am on my way"))
        )
        clock.advance(1_000L)
        assertFalse(guard.isSelfReply(MessageHasher.hash("I am on my way")))
    }

    @Test
    fun tracksInFlightWorkSeparately() {
        guard.markPending("hash-a")
        assertTrue(guard.isPending("hash-a"))
        assertFalse(guard.hasSeen("hash-a"))
        guard.clearPending("hash-a")
        assertFalse(guard.isPending("hash-a"))
    }

    @Test
    fun resetClearsEverything() {
        guard.remember("a")
        guard.markPending("a")
        guard.markSelfReply("reply")
        guard.reset()
        assertEquals(0, guard.snapshotStats().seenCount)
        assertEquals(0, guard.snapshotStats().pendingCount)
        assertEquals(0, guard.snapshotStats().selfReplyCount)
    }
}
