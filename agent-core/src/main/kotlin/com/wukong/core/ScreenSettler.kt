package com.wukong.core

/** Runs on its caller's worker. A quiet node snapshot does not prove network loading completed. */
class ScreenSettler(
    private val quietMillis: Long = 200,
    private val pollMillis: Long = 100,
    private val budgetMillis: Long = 1800,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    init { require(quietMillis > 0 && pollMillis > 0 && budgetMillis >= quietMillis) }

    fun observe(read: () -> Screen, check: () -> Unit = {}): Screen {
        check()
        val started = now()
        var latest = read()
        check()
        var samples = 1
        var unchangedSince = now()
        while (true) {
            check()
            val elapsed = now() - started
            if (elapsed >= budgetMillis) return latest.copy(observation = Observation(false, samples, elapsed, latest.uiEvents))
            pause(minOf(pollMillis, budgetMillis - elapsed))
            check()
            val fresh = read()
            check()
            samples++
            val sampledAt = now()
            val same = fresh.packageName == latest.packageName && fresh.fingerprint == latest.fingerprint && fresh.uiRevision == latest.uiRevision
            if (!same) unchangedSince = sampledAt
            latest = fresh
            if (same && sampledAt - unchangedSince >= quietMillis && fresh.uiQuietMillis >= quietMillis) {
                return latest.copy(observation = Observation(true, samples, sampledAt - started, latest.uiEvents))
            }
        }
    }
}
