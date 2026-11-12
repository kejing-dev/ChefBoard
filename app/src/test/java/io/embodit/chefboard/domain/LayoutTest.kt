package io.embodit.chefboard.domain

import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Arrange
import io.embodit.chefboard.domain.place.HeadSampler
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.place.LevelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** design spatialLayout: D35 / M9 (window vs 3D), D17 / M7 (targets), D14 (clearance), D29 / M14 (recall), D9 (level gate). */
class LayoutTest {
    private val eyes = listOf(Vec3.ZERO, Vec3(-0.032f, 0f, 0f), Vec3(0.032f, 0f, 0f)) // design eye + emulator left eye / right eye

    /** M9: window corners (incl. TabBar above, caption bar below) never inside the 3D boxes, and eye→corner never crosses them. */
    @Test fun windowNeverCoversTheRackTheFloatingGroupOrTheRings() {
        val boxes = listOf(Layout.rackBox(), Layout.stageBox()) + (0 until 5).map { Layout.spotTimerBox(it) }
        // the window really opens within ±GATE_TOL of az −20° (else Arrange turns the kitchen to put it there)
        for (az in listOf(Layout.WINDOW_AZ - Arrange.GATE_TOL_DEG, Layout.WINDOW_AZ, Layout.WINDOW_AZ + Arrange.GATE_TOL_DEG)) {
            val rect = Layout.windowRect(az)
            // corners + edge midpoints + centre: the window plate itself
            val pts = rect.corners + rect.corners.zipWithNext { a, b -> (a + b) * 0.5f } + listOf(rect.center)
            for (b in boxes) for (p in pts) {
                assertFalse("window point inside a box at az $az", b.contains(p))
                for (e in eyes) assertFalse("eye→window crosses a 3D box at az $az", b.hitBy(e, p))
            }
            // and the boxes are not behind / in front of the window plate: sample the window face densely
            for (i in 0..20) for (j in 0..20) {
                val p = rect.center + rect.right * (rect.halfW * (i / 10f - 1f)) + rect.up * (rect.halfH * (j / 10f - 1f))
                for (b in boxes) assertFalse("sight line through window point crosses a box at az $az", b.hitBy(Vec3.ZERO, p))
            }
        }
    }

    /** D17 / M7: 3D targets ≥ 8 cm, gaps ≥ 2 cm (and ≥ 0.3 × target), angle ≥ 2° at the real viewing distance. */
    @Test fun targetSizesGapsAngles() {
        // rack slots
        val slotGap = (Layout.SLOT_X[1] - Layout.SLOT_X[0]) - Layout.COLLIDER_D_DOCKED
        assertTrue(slotGap >= 0.02f && slotGap >= 0.3f * Layout.COLLIDER_D_DOCKED)
        assertTrue(Layout.angleDeg(Layout.COLLIDER_D_DOCKED, Layout.RACK_DIST) >= 2f)
        // rings: chord between neighbours − collider Ø
        val chord = Layout.spotCenter(0).distanceTo(Layout.spotCenter(1))
        assertTrue(chord - Layout.SPOT_COLLIDER_D >= 0.02f)
        val ringDist = Layout.spotCenter(0).length
        assertTrue(Layout.angleDeg(Layout.SPOT_COLLIDER_D, ringDist) >= 2f)
        assertTrue(Layout.angleDeg(chord - Layout.SPOT_COLLIDER_D, ringDist) >= 2f)
        // 3D buttons: Ø 0.09, gap 0.04 at 0.9 m (floating) and 0.98 m (on a ring)
        assertTrue(Layout.BTN_D >= 0.08f && Layout.BTN_GAP >= 0.02f)
        assertTrue(Layout.angleDeg(Layout.BTN_GAP, Layout.stageBase.length) >= 2f)
        assertTrue(Layout.angleDeg(Layout.BTN_GAP, Layout.spotCenter(0).length) >= 2f)
        // the paused row on a ring does not reach the neighbour ring's tag
        val halfRow = 0.11f; val halfTag = Layout.TAG_W / 2f
        assertTrue(chord - halfRow - halfTag >= 0.02f)
    }

    /** D14: rings ≥ 0.6 m from the head (horizontal). */
    @Test fun clearance() {
        for (i in 0 until 5) { val c = Layout.spotCenter(i); assertTrue(Vec3(c.x, 0f, c.z).length >= 0.6f) }
        val r = Layout.rackTopCenter; assertTrue(Vec3(r.x, 0f, r.z).length >= 0.6f)
    }

    /** design topView: window left, rack right, rings −36…+36°, rack and floating group within the stated angles. */
    @Test fun anglesFromTheEye() {
        val rackL = Layout.azOf(Layout.rackPoint(-Layout.RACK_LEN / 2)); val rackR = Layout.azOf(Layout.rackPoint(Layout.RACK_LEN / 2))
        assertTrue(rackL > 0f && rackR < 45f)
        for (i in 0 until 5) assertEquals(Layout.SPOT_AZ[i], Layout.azOf(Layout.spotCenter(i)), 0.01f)
        assertTrue(Layout.elOf(Layout.spotCenter(2)) < -40f)
    }

    private fun fwd(yawDeg: Float, pitchDeg: Float = 0f): Vec3 {
        val y = Math.toRadians(yawDeg.toDouble()); val p = Math.toRadians(pitchDeg.toDouble())
        return Vec3((-sin(y) * cos(p)).toFloat(), sin(p).toFloat(), (-cos(y) * cos(p)).toFloat())
    }

    /**
     * M14 (D29): [叫回面前] pressed while looking at the button (window at +20° left), then the user turns to the stove
     * and holds still → the kitchen yaw is the stove within 2°, at many stove directions and dwell times; no dead zone
     * (a stove 12° or 3° from the button is still taken).
     */
    @Test fun recallLooksAtTheButtonThenTurns() {
        for (stove in listOf(-170f, -90f, -12f, -3f, 0f, 3f, 12f, 45f, 90f, 135f)) for (dwell in listOf(0L, 300L, 900L, 2000L)) {
            val button = stove + 20f
            val s = HeadSampler(dwellMs = 300L)
            var t = 0L
            s.start(t, fwd(button))
            var out: HeadSampler.Sample? = null
            while (t < dwell && out == null) { out = s.step(t, Vec3(0f, 1.6f, 0f), fwd(button)); t += 16 }
            if (out == null) for (k in 0..40) { if (out != null) break; val y = button + (stove - button) * k / 40f; out = s.step(t, Vec3(0f, 1.6f, 0f), fwd(y)); t += 16 }
            while (out == null && t < 20_000) { out = s.step(t, Vec3(0f, 1.6f, 0f), fwd(stove)); t += 16 }
            assertNotNull(out)
            val pose = Arrange.fromSample(out!!.position, Layout.yawOf(out.forward))
            val tol = if (abs(stove - button) < s.turnDeg) 20.5f else 2f // a 20° turn always passes the 10° turn test
            assertTrue("stove $stove dwell $dwell got ${pose.yawDeg}", abs(Layout.diffDeg(pose.yawDeg, stove)) < tol)
        }
    }

    /** Arrange.onWindowOpen: window where wanted → the kitchen stays; elsewhere → the kitchen turns so the window is at −20°. */
    @Test fun windowOpenKeepsOrTurnsTheKitchen() {
        val p = Arrange.fromSample(Vec3(0f, 1.6f, 0f), 0f)
        val want = Arrange.windowTargetYawDeg(p)
        assertEquals(20f, want, 0.01f)
        val kept = Arrange.onWindowOpen(p, Vec3(0f, 1.6f, 0f), Math.toRadians((want + 4f).toDouble()).toFloat())
        assertEquals(p, kept)
        for (w in listOf(-180f, -90f, 0f, 50f, 120f)) {
            val turned = Arrange.onWindowOpen(p, Vec3(0.1f, 1.6f, 0f), Math.toRadians(w.toDouble()).toFloat())
            assertEquals(Layout.WINDOW_AZ, Arrange.windowAz(turned, Math.toRadians(w.toDouble()).toFloat()), 0.01f)
        }
    }

    /** D9 + window target: level for 300 ms AND facing the target (±6°); target dropped after 6 s; no head 5 s → open. */
    @Test fun levelGateWithTarget() {
        val g = LevelGate()
        g.ask("start", 0L, 20f)
        assertNull(g.step(0, 0f, 0f)); assertNull(g.step(400, 0f, 0f)) // level but looking at the stove
        assertTrue(g.lookHintWanted(400, 0f))
        assertNull(g.step(500, -30f, 20f)) // facing but looking down 30°
        assertTrue(g.hintWanted(-30f))
        assertNull(g.step(600, 2f, 18f))
        assertEquals("start", g.step(950, 2f, 18f))
        // timeout: the target is dropped after 6 s
        g.ask("plate", 10_000L, 20f)
        assertNull(g.step(12_000, 0f, -60f))
        assertNull(g.step(16_100, 0f, -60f))
        assertEquals("plate/notarget", g.step(16_500, 0f, -60f))
        // no head pose
        g.ask("x", 20_000L)
        assertNull(g.step(20_000, null)); assertEquals("x/nohead", g.step(25_100, null))
    }

    /** D9 / placeUpright: everything faces the eye with yaw only (the rack's local up is world up whatever the head pitch). */
    @Test fun yawOnlyFacing() {
        for (p in listOf(Layout.rackTopCenter, Layout.stageBase, Layout.spotCenter(0), Layout.spotCenter(4))) {
            val q = io.embodit.chefboard.domain.math.Quat4.axisAngle(Vec3.UP, Layout.yawToEye(p))
            val up = q.rotate(Vec3.UP); assertEquals(1f, up.y, 1e-5f)
            val face = q.rotate(Vec3(0f, 0f, 1f)); val toEye = Vec3(-p.x, 0f, -p.z).normalized()
            assertTrue(face.dot(toEye) > 0.9999f)
        }
    }
}
