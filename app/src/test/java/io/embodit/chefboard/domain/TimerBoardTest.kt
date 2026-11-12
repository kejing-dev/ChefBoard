package io.embodit.chefboard.domain

import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.timer.Ev
import io.embodit.chefboard.domain.timer.Hint
import io.embodit.chefboard.domain.timer.Label
import io.embodit.chefboard.domain.timer.TState
import io.embodit.chefboard.domain.timer.TimerBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timer layer of design stateModel (docked → staged → flying → running ⇄ paused → ringing → stopped → returning → docked). */
class TimerBoardTest {
    private var now = 1_000_000L
    private val b = TimerBoard { now }
    private val spot = Layout.spotCenter(1)
    private fun advance(ms: Long) { var left = ms; while (left > 0) { val d = minOf(16L, left); now += d; left -= d; b.tick() } }
    private fun land(slot: Int, sec: Int, at: Vec3 = spot) {
        b.pick(slot, sec, Label("煮面", "Pasta")); advance(400); b.tapSpot(1, at); advance(650)
        assertEquals(TState.RUNNING, b.timers[slot].state)
    }

    @Test fun fullLoopEndsBackOnTheRack() {
        assertTrue(b.pick(0).any { it is Ev.Picked })
        assertEquals(TState.STAGED, b.timers[0].state)
        assertEquals(300, b.timers[0].shownSeconds(now))
        advance(400)
        b.tapSpot(1, spot)
        assertEquals(TState.FLYING, b.timers[0].state)
        advance(650)
        assertEquals(TState.RUNNING, b.timers[0].state)
        advance(300_000)
        assertEquals(TState.RINGING, b.timers[0].state)
        assertTrue(b.tapTimer(0).any { it is Ev.Stopped })
        advance(1600)
        assertEquals(TState.RETURNING, b.timers[0].state)
        advance(600)
        assertEquals(TState.DOCKED, b.timers[0].state)
        assertNull(b.timers[0].spotWorld)
    }

    /** M2: 10:00 → +5 → +1 = 16:00; clear = 0:00 and a ring refuses with「先加点时间」. */
    @Test fun addTimeAndClear() {
        b.stepTimer(600, Label("煮面", "Pasta")); advance(400)
        b.stagedAdd(5); b.stagedAdd(1)
        assertEquals(16 * 60, b.staged!!.shownSeconds(now))
        b.stagedClear()
        assertEquals(0, b.staged!!.shownSeconds(now))
        val ev = b.tapSpot(2, Layout.spotCenter(2))
        assertTrue(ev.single() is Ev.Refused)
        assertEquals(TState.STAGED, b.staged!!.state)
        assertEquals(Hint.ADD_TIME, b.staged!!.hint)
        repeat(30) { b.stagedAdd(5) }
        assertEquals(99 * 60, b.staged!!.shownSeconds(now)) // clamped 0–99 min
    }

    /** M16: one floating at a time; others don't answer; 2 pinches within 2 s cancel; 60 s idle goes home. */
    @Test fun stagingRules() {
        land(1, 300)
        b.pick(0); advance(400)
        assertFalse("placed timer answers while one floats", b.timerTappable(1))
        assertFalse("rack timer answers while one floats", b.timerTappable(2))
        assertTrue(b.dimmed(1)); assertTrue(b.dimmed(2)); assertFalse(b.dimmed(0))
        assertTrue(b.tapTimer(1).isEmpty())
        // first pinch on the floating timer only asks
        assertTrue(b.tapTimer(0).single() is Ev.AskAgain)
        assertEquals(TState.STAGED, b.timers[0].state)
        advance(2100) // too late: back to the first pinch
        b.tapTimer(0)
        advance(500)
        assertTrue(b.tapTimer(0).any { it is Ev.Returning })
        advance(600)
        assertEquals(TState.DOCKED, b.timers[0].state)
        // idle 60 s → home
        b.pick(2); advance(60_100)
        assertEquals(TState.RETURNING, b.timers[2].state)
    }

    @Test fun stepTimerRetargetsTheFloatingOneAndReportsFull() {
        b.stepTimer(600, Label("煮面", "Pasta")); advance(400)
        b.stepTimer(480, Label("酱汁", "Sauce"))
        assertEquals(1, b.timers.count { it.state == TState.STAGED })
        assertEquals("酱汁", b.staged!!.label!!.zh); assertEquals(480, b.staged!!.shownSeconds(now))
        b.cancelStaged(); advance(600)
        for (s in 0 until 4) land(s, 600, Layout.spotCenter(s))
        assertTrue(b.allOut)
        assertTrue(b.stepTimer(60, null).single() is Ev.Full)
    }

    @Test fun pauseAddRecall() {
        land(0, 300)
        advance(10_000)
        b.tapTimer(0)
        assertEquals(TState.PAUSED, b.timers[0].state); assertEquals(0, b.pauseFocus)
        val left = b.timers[0].shownSeconds(now)
        advance(30_000)
        assertEquals(left, b.timers[0].shownSeconds(now))
        b.pausedAdd()
        assertEquals(left + 60, b.timers[0].shownSeconds(now))
        b.tapTimer(0); assertEquals(TState.RUNNING, b.timers[0].state)
        b.tapTimer(0); b.pausedRecall(); advance(600)
        assertEquals(TState.DOCKED, b.timers[0].state)
    }

    @Test fun bellCadenceAndAmberStopsAll() {
        land(0, 60); land(1, 60, Layout.spotCenter(3))
        val bells = ArrayList<Long>()
        val t0 = now
        repeat(140_000 / 16) { now += 16; b.tick().filterIsInstance<Ev.Bell>().filter { it.slot == 0 }.forEach { bells += now - t0 } }
        assertTrue(b.ringing.size == 2)
        val zero = bells
        // every 1.2 s for the first minute, then every 5 s
        assertTrue(zero.zipWithNext().take(20).all { (a, c) -> c - a in 1190L..1220L })
        assertTrue(zero.zipWithNext().last().let { (a, c) -> c - a in 4990L..5020L })
        assertTrue(b.stopAll().size == 2)
        assertTrue(b.ringing.isEmpty())
    }

    /** M10: elapsedRealtime-based, background / foreground 5× (no ticks for minutes) → error ≤ 1 s. */
    @Test fun accuracyAcrossBackground() {
        land(0, 20 * 60)
        val start = now; val total = b.timers[0].remainingMs(now)
        repeat(5) { now += 61_000; b.tick(); advance(3_000) } // 61 s with no frames, then 3 s of frames
        val expected = total - (now - start)
        assertTrue(kotlin.math.abs(b.timers[0].remainingMs(now) - expected) <= 1000)
        now += 30 * 60_000L // ends while in the background
        b.tick()
        assertEquals(TState.RINGING, b.timers[0].state)
    }

    /** M17: a ring within 0.15 m of a placed timer is not free (by position, also after the kitchen moves). */
    @Test fun occupancyByPosition() {
        land(0, 300, Layout.spotCenter(2))
        assertFalse(b.spotFree(Layout.spotCenter(2)))
        assertFalse(b.spotFree(Layout.spotCenter(2) + Vec3(0.12f, 0f, 0f)))
        assertTrue(b.spotFree(Layout.spotCenter(3)))
        b.pick(1); advance(400)
        assertTrue(b.tapSpot(2, Layout.spotCenter(2)).isEmpty())
        assertEquals(TState.STAGED, b.timers[1].state)
    }

    @Test fun debounce300() {
        assertFalse(b.debounce("timer:0"))
        now += 200
        assertTrue(b.debounce("timer:0"))
        now += 310
        assertFalse(b.debounce("timer:0"))
    }

    @Test fun flyingTimerIgnoresTaps() {
        b.pick(0)
        assertFalse(b.timerTappable(0)) // pick-up animation (380 ms)
        advance(400)
        b.tapSpot(1, spot)
        assertFalse(b.timerTappable(0))
        assertTrue(b.tapTimer(0).isEmpty())
    }
}
