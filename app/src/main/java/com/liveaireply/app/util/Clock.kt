package com.liveaireply.app.util

/** Injectable time source so debounce/TTL logic can be tested deterministically. */
interface MillisClock {
    fun now(): Long

    companion object {
        val SYSTEM: MillisClock = object : MillisClock {
            override fun now(): Long = System.currentTimeMillis()
        }

        /** A clock the test suite drives by hand. */
        fun fixed(startMs: Long = 0L): ManualClock = ManualClock(startMs)
    }
}

class ManualClock(private var currentMs: Long) : MillisClock {
    override fun now(): Long = currentMs
    fun advance(deltaMs: Long) {
        currentMs += deltaMs
    }
    fun setTo(ms: Long) {
        currentMs = ms
    }
}
