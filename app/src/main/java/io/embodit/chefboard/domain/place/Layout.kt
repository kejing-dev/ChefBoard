package io.embodit.chefboard.domain.place

import io.embodit.chefboard.domain.math.Quat4
import io.embodit.chefboard.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * design spatialLayout in the kitchen frame: origin = the eye when the layout was taken, −Z = toward the stove (az 0°),
 * +X right, +Y up. Azimuths in degrees, + = right (design: window −20°, rack +22°, rings −36…+36°). Pure Kotlin, so the
 * D35 / D17 / M9 / M17 numbers are unit-tested.
 */
object Layout {
    // ---- control window: the system opens it straight ahead of the head and code cannot move it (native-window-move §4).
    // It is drawn as if 0.82 m away at 0.0008 m/dp (pico-cli-feedback「Planar 窗口实际画出来的大小」, region-snap 实测),
    // so the window is opened while the user glances at az −20° (LevelGate target) and everything else is laid out around it.
    const val WINDOW_AZ = -20f
    const val WINDOW_W_DP = 640f
    const val WINDOW_H_DP = 560f
    var windowDist = 0.82f
    var mPerDp = 0.0008f
    /** System caption bar (dot / × / handle / −) below the window and the TabBar capsule above it, drawn dp. */
    const val CAPTION_BELOW_DP = 80f
    const val TABBAR_ABOVE_DP = 96f

    // ---- rack E1 (walnut shelf + 4 shallow wells + brass name plate on the right end)
    const val RACK_AZ = 22f
    const val RACK_DIST = 0.90f
    const val RACK_TOP_Y = -0.42f
    const val RACK_LEN = 0.68f
    const val RACK_THICK = 0.035f
    const val RACK_DEPTH = 0.12f
    val SLOT_X = floatArrayOf(-0.265f, -0.115f, 0.035f, 0.185f)
    const val PLATE_X = 0.30f
    const val PLATE_W = 0.08f
    const val STAGE_RISE = 0.15f
    /**
     * QA #1 q1-2: the「◀ 捏铭牌开菜谱」hint sits just past the rack's right end, level with the brass plate, pointing at
     * it (rack local). Not over a timer, and clear of a timer standing on ring 4 (under the plate it was half covered).
     */
    const val PLATE_HINT_W = 0.20f
    const val PLATE_HINT_H = 0.036f
    val plateHintLocal: Vec3 get() = Vec3(RACK_LEN / 2f + 0.012f + PLATE_HINT_W / 2f, -RACK_THICK / 2f, RACK_DEPTH / 2f + 0.004f)

    // ---- drop rings E5
    val SPOT_AZ = floatArrayOf(-36f, -18f, 0f, 18f, 36f)
    const val SPOT_DIST = 0.70f
    const val SPOT_Y = -0.68f
    const val SPOT_RING_D = 0.12f
    const val SPOT_COLLIDER_D = 0.14f
    const val SPOT_COLLIDER_H = 0.10f
    /** A ring closer than this to a placed timer is hidden and cannot be tapped (M17). */
    const val SPOT_OCCUPIED_M = 0.15f

    // ---- timer E2 / tag E3 / 3D buttons E4
    const val TIMER_D = 0.09f
    const val TIMER_H = 0.07f
    const val RING_D = 0.12f
    const val COLLIDER_D = 0.12f
    const val COLLIDER_D_DOCKED = 0.11f
    const val COLLIDER_H = 0.10f
    const val TAG_W = 0.16f
    const val TAG_H = 0.045f
    const val TAG_GAP = 0.02f
    const val BTN_D = 0.09f
    const val BTN_T = 0.02f
    const val BTN_GAP = 0.04f
    const val LAND_LIFT = 0.005f

    /** Unit horizontal direction of azimuth [az] (deg, + right). */
    fun dir(az: Float): Vec3 { val a = Math.toRadians(az.toDouble()); return Vec3(sin(a).toFloat(), 0f, -cos(a).toFloat()) }

    /** Horizontal azimuth of a kitchen-frame point seen from the eye (deg, + right). */
    fun azOf(p: Vec3): Float = Math.toDegrees(atan2(p.x.toDouble(), (-p.z).toDouble())).toFloat()

    /** Elevation of a kitchen-frame point seen from the eye (deg, + up). */
    fun elOf(p: Vec3): Float = Math.toDegrees(atan2(p.y.toDouble(), sqrt((p.x * p.x + p.z * p.z).toDouble()))).toFloat()

    /** Right-hand axis of something standing at [p] and facing the eye (horizontal, perpendicular to the sight line). */
    fun facingRight(p: Vec3): Vec3 { val f = Vec3(p.x, 0f, p.z).normalized(); return Vec3(-f.z, 0f, f.x) }

    /** Yaw (radians, + left, about +Y) that turns local +Z toward the eye for something at [p] (D10: yaw only). */
    fun yawToEye(p: Vec3): Float = atan2(-p.x, -p.z)

    // ---- rack
    val rackTopCenter: Vec3 get() = dir(RACK_AZ) * RACK_DIST + Vec3(0f, RACK_TOP_Y, 0f)
    val rackRight: Vec3 get() = facingRight(rackTopCenter)
    fun rackPoint(x: Float, y: Float = 0f): Vec3 = rackTopCenter + rackRight * x + Vec3(0f, y, 0f)
    fun slotBase(slot: Int): Vec3 = rackPoint(SLOT_X[slot])
    val plateCenter: Vec3 get() = rackPoint(PLATE_X, -RACK_THICK / 2f)
    /** Kitchen-frame rectangle of the plate hint (facing the eye like the rack). */
    fun plateHintRect(): Rect3 {
        val l = plateHintLocal; val toEye = Vec3(-rackTopCenter.x, 0f, -rackTopCenter.z).normalized()
        return Rect3(rackPoint(l.x, l.y) + toEye * l.z, rackRight, Vec3.UP, PLATE_HINT_W / 2f, PLATE_HINT_H / 2f)
    }
    /** A docked timer on slot [s] with its collider (D35 / q1-2 tests). */
    fun dockedTimerBox(s: Int): Box = upright(slotBase(s) + Vec3(0f, COLLIDER_H / 2f, 0f), COLLIDER_D_DOCKED / 2f, COLLIDER_H / 2f, COLLIDER_D_DOCKED / 2f)
    /** Where a picked-up timer floats: 0.15 m above the rack middle (its base). */
    val stageBase: Vec3 get() = rackPoint(0f, STAGE_RISE)

    // ---- rings
    fun spotCenter(i: Int): Vec3 = dir(SPOT_AZ[i]) * SPOT_DIST + Vec3(0f, SPOT_Y, 0f)

    // ---- timer parts, relative to a timer base (bottom centre)
    fun tagCenter(base: Vec3): Vec3 = base + Vec3(0f, TIMER_H + TAG_GAP + TAG_H / 2f, 0f)
    fun buttonRowCenter(base: Vec3): Vec3 = base + Vec3(0f, TIMER_H + TAG_GAP + TAG_H + TAG_GAP + BTN_D / 2f, 0f)
    /** Centres of n big 3D buttons in a row above the tag, facing the eye (E4: Ø 0.09, gap 0.04). */
    fun buttonCenters(base: Vec3, n: Int): List<Vec3> {
        val c = buttonRowCenter(base); val r = facingRight(c); val pitch = BTN_D + BTN_GAP
        return List(n) { k -> c + r * ((k - (n - 1) / 2f) * pitch) }
    }

    // ---- control window (kitchen frame), drawn size
    data class Rect3(val center: Vec3, val right: Vec3, val up: Vec3, val halfW: Float, val halfH: Float) {
        val corners: List<Vec3> get() = listOf(center - right * halfW - up * halfH, center + right * halfW - up * halfH, center + right * halfW + up * halfH, center - right * halfW + up * halfH)
    }

    /** The window rectangle incl. the caption bar below and the TabBar above (what must stay clear), window at [az]. */
    fun windowRect(az: Float = WINDOW_AZ): Rect3 {
        val c0 = dir(az) * windowDist
        val r = facingRight(c0)
        val top = WINDOW_H_DP / 2f + TABBAR_ABOVE_DP; val bottom = WINDOW_H_DP / 2f + CAPTION_BELOW_DP
        val center = c0 + Vec3(0f, (top - bottom) / 2f * mPerDp, 0f)
        return Rect3(center, r, Vec3.UP, WINDOW_W_DP / 2f * mPerDp, (top + bottom) / 2f * mPerDp)
    }

    // ---- axis-aligned (in a local frame) boxes for the D35 test
    /** A box given by centre, unit axes and half sizes. */
    data class Box(val center: Vec3, val ax: Vec3, val ay: Vec3, val az: Vec3, val hx: Float, val hy: Float, val hz: Float) {
        fun contains(p: Vec3): Boolean { val d = p - center; return abs(d.dot(ax)) <= hx && abs(d.dot(ay)) <= hy && abs(d.dot(az)) <= hz }
        /** Segment a→b intersects the box (slab test in the box frame). */
        fun hitBy(a: Vec3, b: Vec3): Boolean {
            val o = a - center; val d = b - a
            var t0 = 0f; var t1 = 1f
            for ((axis, h) in listOf(ax to hx, ay to hy, az to hz)) {
                val os = o.dot(axis); val ds = d.dot(axis)
                if (abs(ds) < 1e-9f) { if (abs(os) > h) return false; continue }
                var ta = (-h - os) / ds; var tb = (h - os) / ds
                if (ta > tb) { val t = ta; ta = tb; tb = t }
                t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
                if (t0 > t1) return false
            }
            return true
        }
    }

    private fun upright(center: Vec3, hx: Float, hy: Float, hz: Float): Box {
        val r = facingRight(center); val toEye = Vec3(-center.x, 0f, -center.z).normalized()
        return Box(center, r, Vec3.UP, toEye, hx, hy, hz)
    }

    /** Rack + the four docked timers. */
    fun rackBox(): Box = upright(rackPoint(0f, (TIMER_H + 0.012f - RACK_THICK) / 2f), RACK_LEN / 2f, (TIMER_H + 0.012f + RACK_THICK) / 2f, RACK_DEPTH / 2f)

    /** Floating group: timer + tag + the three buttons (row 0.35 m). */
    fun stageBox(): Box {
        val b = stageBase; val top = TIMER_H + TAG_GAP + TAG_H + TAG_GAP + BTN_D
        return upright(b + Vec3(0f, top / 2f, 0f), 0.35f / 2f, top / 2f, RING_D / 2f)
    }

    /** A timer standing on ring [i] with its tag and the paused [+1] [收回] row (0.22 m). */
    fun spotTimerBox(i: Int): Box {
        val b = spotCenter(i) + Vec3(0f, LAND_LIFT, 0f); val top = TIMER_H + TAG_GAP + TAG_H + TAG_GAP + BTN_D
        return upright(b + Vec3(0f, top / 2f, 0f), 0.22f / 2f, top / 2f, RING_D / 2f)
    }

    /** Angular size (deg) of a target of size [m] at distance [d]. */
    fun angleDeg(m: Float, d: Float): Float = Math.toDegrees(2 * kotlin.math.atan((m / 2f / d).toDouble())).toFloat()

    /** Where the layout sits in the world: the eye + the stove's yaw (radians, + = turned left, like yawOf). */
    data class Pose(val origin: Vec3, val yaw: Float) {
        val rotation: Quat4 get() = Quat4.axisAngle(Vec3.UP, yaw)
        fun toWorld(local: Vec3): Vec3 = origin + rotation.rotate(local)
        fun dirToWorld(local: Vec3): Vec3 = rotation.rotate(local)
        fun toLocal(world: Vec3): Vec3 = rotation.conjugate().rotate(world - origin)
        val yawDeg: Float get() = Math.toDegrees(yaw.toDouble()).toFloat()
    }

    /** Yaw (radians, + left) of a horizontal forward vector. */
    fun yawOf(forward: Vec3): Float = atan2(-forward.x, -forward.z)

    /** Signed difference a − b in degrees within (−180, 180]. */
    fun diffDeg(a: Float, b: Float): Float { var d = a - b; while (d > 180f) d -= 360f; while (d <= -180f) d += 360f; return d }
}
