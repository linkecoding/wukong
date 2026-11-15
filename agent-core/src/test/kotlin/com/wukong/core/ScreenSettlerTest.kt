package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class ScreenSettlerTest {
    private fun screen(id: String) = Screen("demo.app", id, emptyList())
    @Test fun aFastQuietPageReturnsBeforeTheOldFixedDelay() {
        var clock = 0L
        val result = ScreenSettler(now = { clock }, pause = { clock += it }).observe({ screen("same") })
        assertEquals(200L, clock); assertTrue(result.observation!!.settled); assertEquals(3, result.observation!!.samples)
    }
    @Test fun transitionsAndEventsResetQuietTimeEvenWhenTheTreeLooksTheSame() {
        var clock = 0L; var sample = 0
        val result = ScreenSettler(now = { clock }, pause = { clock += it }).observe({
            sample++
            screen(if (sample == 1) "old" else "new").copy(uiEvents = true, uiRevision = if (clock < 200) 1 else 2,
                uiQuietMillis = if (clock < 200) clock else clock - 200)
        })
        assertEquals(400L, clock); assertEquals("new", result.fingerprint); assertTrue(result.observation!!.settled)
    }
    @Test fun persistentMotionReturnsLatestStateInsteadOfThrowingOrWaitingForever() {
        var clock = 0L
        val result = ScreenSettler(now = { clock }, pause = { clock += it }).observe({ screen("frame-$clock") })
        assertEquals(1800L, clock); assertFalse(result.observation!!.settled); assertEquals("frame-1800", result.fingerprint)
    }
    @Test fun eventsFromAnAnimationAlsoBoundTheWaitWithoutAChangedTree() {
        var clock = 0L
        val result = ScreenSettler(now = { clock }, pause = { clock += it }).observe({ screen("same").copy(uiRevision = clock, uiQuietMillis = 0, uiEvents = true) })
        assertFalse(result.observation!!.settled); assertEquals(1800L, clock)
    }
    @Test fun cancellationAndUnknownReadFailuresAreNeverSilenced() {
        var clock = 0L
        try {
            ScreenSettler(now = { clock }, pause = { clock += it }).observe({ screen("same") }, { if (clock >= 100) throw InterruptedException() })
            fail("Must cancel")
        } catch (_: InterruptedException) { assertEquals(100L, clock) }
        try {
            ScreenSettler().observe({ error("connection lost") }); fail("Must propagate")
        } catch (e: IllegalStateException) { assertEquals("connection lost", e.message) }
    }
}
