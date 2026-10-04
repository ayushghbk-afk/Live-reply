package com.liveaireply.app.util

/**
 * Quiet-period tracker used by the accessibility service.
 *
 * Chat apps redraw several times for a single message (text arrives, then a timestamp,
 * then a read receipt). The service calls [onChanged] for every relevant event and only
 * processes the window once [isQuiet] reports that nothing has changed for the
 * configured delay.
 *
 * Pure time arithmetic, so the behaviour is covered by unit tests.
 */
class Debouncer(private val clock: MillisClock = MillisClock.SYSTEM) {

    private var lastChangeAtMs: Long? = null
    private var lastKey: String? = null
    private var changeCount = 0

    /** Records a UI change. Returns how many changes have happened in this burst. */
    fun onChanged(key: String): Int {
        val now = clock.now()
        if (lastKey == key && lastChangeAtMs != null) {
            changeCount++
        } else {
            changeCount = 1
        }
        lastKey = key
        lastChangeAtMs = now
        return changeCount
    }

    /** True when at least [delayMs] has passed since the last recorded change. */
    fun isQuiet(delayMs: Long): Boolean {
        val last = lastChangeAtMs ?: return true
        return clock.now() - last >= delayMs
    }

    /** Milliseconds remaining before the window counts as stable. */
    fun quietInMs(delayMs: Long): Long {
        val last = lastChangeAtMs ?: return 0L
        return (last + delayMs - clock.now()).coerceAtLeast(0L)
    }

    fun burstSize(): Int = changeCount

    fun reset() {
        lastChangeAtMs = null
        lastKey = null
        changeCount = 0
    }
}
