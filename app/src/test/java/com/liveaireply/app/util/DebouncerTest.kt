package com.liveaireply.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebouncerTest {

    @Test
    fun staysBusyWhileEventsKeepArriving() {
        val clock = ManualClock(0L)
        val debouncer = Debouncer(clock)
        debouncer.onChanged("whatsapp/chat")
        clock.advance(300)
        debouncer.onChanged("whatsapp/chat")
        clock.advance(300)
        assertFalse(debouncer.isQuiet(900))
        clock.advance(600)
        assertTrue(debouncer.isQuiet(900))
    }

    @Test
    fun countsTheBurstSize() {
        val clock = ManualClock(0L)
        val debouncer = Debouncer(clock)
        assertEquals(1, debouncer.onChanged("k"))
        assertEquals(2, debouncer.onChanged("k"))
        assertEquals(3, debouncer.onChanged("k"))
        assertEquals(1, debouncer.onChanged("other"))
    }

    @Test
    fun reportsHowLongIsLeft() {
        val clock = ManualClock(0L)
        val debouncer = Debouncer(clock)
        debouncer.onChanged("k")
        assertEquals(900L, debouncer.quietInMs(900))
        clock.advance(400)
        assertEquals(500L, debouncer.quietInMs(900))
        clock.advance(600)
        assertEquals(0L, debouncer.quietInMs(900))
    }

    @Test
    fun resetMakesItQuietAgain() {
        val clock = ManualClock(0L)
        val debouncer = Debouncer(clock)
        debouncer.onChanged("k")
        debouncer.reset()
        assertTrue(debouncer.isQuiet(900))
        assertEquals(0, debouncer.burstSize())
    }
}
