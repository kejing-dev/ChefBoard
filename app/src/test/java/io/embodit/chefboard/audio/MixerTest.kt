package io.embodit.chefboard.audio

import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.timer.Ev
import io.embodit.chefboard.domain.timer.Label
import io.embodit.chefboard.domain.timer.TState
import io.embodit.chefboard.domain.timer.TimerBoard
import io.embodit.chefboard.platform.audio.Mixer
import io.embodit.chefboard.platform.audio.SoundCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** q3-1: a cut voice whose fade ends must leave the mixer, not park at a huge position (AIOOBE index=2147483647). */
class MixerTest {
    private val sr = 48000
    private fun tone(len: Int) = FloatArray(len) { (it % 100) / 100f - 0.5f }

    private fun mixer(len: Int) = Mixer(sr).apply { putSample("wood", tone(len)); putSample("bell", tone(sr)) }

    private fun renderMs(m: Mixer, ms: Int, burst: Int = 256): Float {
        val buf = FloatArray(burst * 2); var peak = 0f
        var left = sr * ms / 1000
        while (left > 0) { val n = minOf(burst, left); m.render(buf, n); for (i in 0 until n * 2) peak = maxOf(peak, abs(buf[i])); left -= n }
        return peak
    }

    /** Same sound twice in a row, each longer than the 3 ms cut-fade: no crash, the cut copy goes away. */
    @Test fun sameSoundTwiceLongerThanFade() {
        for (burst in intArrayOf(64, 192, 256, 1024)) {
            val m = mixer(sr / 2) // 500 ms ≫ 3 ms fade
            m.play("wood", 0.65f, -0.3f)
            m.play("wood", 0.65f, 0.3f) // same frame: the first copy is cut
            assertEquals(2, m.active())
            renderMs(m, 10, burst) // fade (144 frames) is over
            assertEquals("cut copy removed (burst $burst)", 1, m.active())
            renderMs(m, 20, burst) // used to throw ArrayIndexOutOfBoundsException here
            m.play("wood", 0.65f) // and a third one cutting the second
            renderMs(m, 600, burst)
            assertEquals(0, m.active())
        }
    }

    /** Cut in the middle of playback (not on the first frame) and stopKind on several copies. */
    @Test fun cutMidwayAndStopKind() {
        val m = mixer(sr)
        m.play("wood", 0.6f); renderMs(m, 50)
        m.play("wood", 0.6f); renderMs(m, 5)
        m.play("bell", 0.6f); m.play("bell", 0.6f)
        m.stopKind("bell"); m.stopKind("wood")
        renderMs(m, 30)
        assertEquals(0, m.active())
        renderMs(m, 30)
    }

    /** Two timers ring together, 全停 on the amber bar, both fly home and dock in the same frame → 笃 + 笃. */
    @Test fun twoTimersReturnToRackTogether() {
        repeat(5) { round ->
            var now = 1_000_000L
            val b = TimerBoard { now }
            val m = mixer(sr * 3 / 10)
            val buf = FloatArray(1024 * 2)
            fun step(ms: Long) {
                var left = ms
                while (left > 0) {
                    val d = minOf(16L, left); now += d; left -= d
                    b.tick().flatMap { SoundCues.of(it) }.forEach { m.play(it.kind, it.gain, if (it.slot == 0) -0.5f else 0.5f) }
                    m.render(buf, (sr * d / 1000).toInt())
                }
            }
            b.placeRunning(0, 0, Layout.spotCenter(0), 1, Label("煮面", "Pasta"))
            b.placeRunning(1, 1, Layout.spotCenter(1), 1, Label("煎蛋", "Egg"))
            step(1200)
            assertEquals(2, b.ringing.size)
            val stopped = b.stopAll()
            assertEquals(2, stopped.count { it is Ev.Stopped })
            step(5000) // stopped → returning → docked, both on the same tick
            assertTrue("round $round", b.timers.all { it.state == TState.DOCKED })
            step(1000)
            assertEquals(0, m.active())
        }
    }
}
