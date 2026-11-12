package io.embodit.chefboard.domain

import io.embodit.chefboard.domain.math.Quat4
import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Arrange
import io.embodit.chefboard.domain.place.ColdStart
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.place.LevelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * QA #1 q1-1 / q1-3 (profile D9 2026-10-30, D1 手语老师「一打开就要看得见」): cold start with the head level, pitched
 * down / up 30° or rolled 20°, at many headings → the kitchen is placed (yaw only) within 0.5 s, the window opens within
 * 2 s when level and within 8 s whatever the head does, and the window lands at az −20° of the stove (kitchen kept).
 */
class ColdStartTest {
    private val eye = Vec3(0.2f, 1.55f, -0.3f)

    /** Head forward for yaw (deg, + left), pitch (deg, + up), roll (deg). */
    private fun headRot(yaw: Float, pitch: Float, roll: Float): Quat4 {
        fun r(d: Float) = Math.toRadians(d.toDouble()).toFloat()
        return Quat4.axisAngle(Vec3.UP, r(yaw)) * Quat4.axisAngle(Vec3.RIGHT, r(pitch)) * Quat4.axisAngle(Vec3(0f, 0f, 1f), r(roll))
    }

    private data class Run(val placedAt: Long, val openedAt: Long, val pose: Layout.Pose, val openWhy: String)

    /** Simulates the Stage: 16 ms frames, head held at (yaw, pitch, roll) from the first frame. */
    private fun run(yaw: Float, pitch: Float, roll: Float): Run {
        val c = ColdStart(); val g = LevelGate()
        val f = headRot(yaw, pitch, roll).rotate(Vec3.AHEAD)
        val pitchDeg = Math.toDegrees(kotlin.math.asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        val yawDeg = Math.toDegrees(Layout.yawOf(Vec3(f.x, 0f, f.z).normalized()).toDouble()).toFloat()
        var t = 0L; var placed: Pair<Long, Layout.Pose>? = null; var opened: Pair<Long, String>? = null
        while (t < 20_000L && opened == null) {
            c.step(t, eye, f, steady = t >= 48L)?.let { r -> placed = t to r.pose; g.ask(r.why, t, null) }
            g.step(t, pitchDeg, yawDeg)?.let { opened = t to it }
            t += 16
        }
        assertNotNull("never placed ($yaw, $pitch, $roll)", placed); assertNotNull("window never opened ($yaw, $pitch, $roll)", opened)
        return Run(placed!!.first, opened!!.first, placed!!.second, opened!!.second)
    }

    @Test fun everyHeadPoseGetsKitchenAndWindow() {
        for (yaw in listOf(-150f, -45f, 0f, 30f, 90f, 179f)) for ((pitch, roll) in listOf(0f to 0f, -30f to 0f, 30f to 0f, 0f to 20f, 0f to -20f, -30f to 20f)) {
            val r = run(yaw, pitch, roll)
            assertTrue("kitchen at ${r.placedAt} ms ($yaw,$pitch,$roll)", r.placedAt <= 500L)
            val level = abs(pitch) <= 10f
            if (level) assertTrue("level window at ${r.openedAt} ms ($yaw,$pitch,$roll)", r.openedAt <= 2000L)
            else assertTrue("pitched window at ${r.openedAt} ms ($yaw,$pitch,$roll)", r.openedAt <= 8000L)
            assertFalse(r.openWhy.contains("notarget"))
            // yaw only: the stove is 20° right of the head heading, whatever the pitch / roll
            assertEquals(0f, Layout.diffDeg(r.pose.yawDeg, yaw - 20f), 1.0f)
            // the window opens straight ahead of the (unmoved) head → Arrange keeps the kitchen, window at az −20°
            val wy = Math.toRadians(yaw.toDouble()).toFloat()
            assertEquals(r.pose, Arrange.onWindowOpen(r.pose, eye, wy))
            assertEquals(Layout.WINDOW_AZ, Arrange.windowAz(r.pose, wy), 1.0f)
            // the kitchen frame is upright (pose is yaw only): rack up = world up
            assertEquals(1f, r.pose.rotation.rotate(Vec3.UP).y, 1e-5f)
            assertEquals(eye, r.pose.origin)
        }
    }

    @Test fun pitchedHeadShowsTheLevelHintUntilTheWindowOpens() {
        val g = LevelGate(); g.ask("start", 0L, null)
        assertTrue(g.hintWanted(-30f))
        assertNull(g.step(1000, -30f, 0f)); assertNull(g.step(2900, -30f, 0f))
        assertNull(g.step(3000, -30f, 0f)) // dwell starts
        assertEquals("start/pitched", g.step(3300, -30f, 0f))
    }

    @Test fun noHeadOrUnsteadyFallsBackIn4s() {
        val c = ColdStart()
        for (t in 0L until 4000L step 16) assertNull(c.step(t, null, null, false))
        val r = c.step(4000L, null, null, false); assertNotNull(r); assertEquals("start/nohead", r!!.why)
        val c2 = ColdStart()
        for (t in 0L until 4000L step 16) assertNull(c2.step(t, eye, Vec3.AHEAD, steady = false))
        val r2 = c2.step(4000L, eye, Vec3.AHEAD, steady = false); assertEquals("start/unsteady", r2!!.why); assertEquals(eye, r2.pose.origin)
    }

    @Test fun straightDownLookStillPlaces() {
        val c = ColdStart(); var r: ColdStart.Result? = null; var t = 0L
        while (r == null) { r = c.step(t, eye, Vec3(0f, -1f, 0f), true); t += 16 }
        assertTrue(t <= 500L); assertEquals(1f, r.pose.rotation.rotate(Vec3.UP).y, 1e-5f)
    }
}
