package io.embodit.chefboard.domain.place

import io.embodit.chefboard.domain.math.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * headSample (D29, q1-2): a button that places something "in front of you" must not use the head pose of the moment it
 * was pressed — the user is looking at the button. After the press the app waits for the user to turn to where they
 * want "front" and hold still there:
 *  - the head must have turned at least [turnDeg] away from the press direction, then held still ([toleranceDeg]) and
 *    level (|pitch| ≤ [maxPitchDeg]) for [dwellMs];
 *  - if the user does not turn within [settleMs], the button direction was meant (still + level for [dwellMs]);
 *  - [timeoutMs] after the press the current pose is taken whatever it is (never stuck).
 * Position + yaw only, pitch / roll dropped (D9). Pure Kotlin; the caller feeds every frame.
 */
class HeadSampler(
    val dwellMs: Long = 500L,
    val toleranceDeg: Float = 2f,
    val turnDeg: Float = 10f,
    val settleMs: Long = 2500L,
    val maxPitchDeg: Float = 10f,
    val timeoutMs: Long = 8000L,
) {
    data class Sample(val position: Vec3, val yawDeg: Float, val forward: Vec3)

    private var startedMs = -1L
    private var stillSinceMs = -1L
    private var refYaw = 0f
    private var pressYaw: Float? = null
    private var turned = false
    var pending = false
        private set

    /** [pressForward] = the head forward at the press (null: the first frame fed counts as the press direction). */
    fun start(now: Long, pressForward: Vec3? = null) {
        pending = true; startedMs = now; stillSinceMs = -1L; turned = false
        pressYaw = pressForward?.let { yawDeg(it) }
    }

    fun cancel() { pending = false }

    /** True while waiting and the user has not turned away from the button yet (window hint「转到正前方」). */
    val waitingForTurn: Boolean get() = pending && !turned

    /** Returns the sample once the user has settled (see class doc), else null. */
    fun step(now: Long, position: Vec3, forward: Vec3): Sample? {
        if (!pending) return null
        val yaw = yawDeg(forward)
        val press = pressYaw ?: yaw.also { pressYaw = it }
        if (abs(angleDiff(yaw, press)) >= turnDeg) turned = true
        val level = abs(pitchDeg(forward)) <= maxPitchDeg
        if (stillSinceMs < 0L || abs(angleDiff(yaw, refYaw)) > toleranceDeg || !level) { stillSinceMs = now; refYaw = yaw }
        val settled = level && now - stillSinceMs >= dwellMs && (turned || now - startedMs >= settleMs)
        if (settled || now - startedMs > timeoutMs) {
            pending = false
            val f = Vec3(forward.x, 0f, forward.z).normalized()
            return Sample(position, yaw, f)
        }
        return null
    }

    companion object {
        fun yawDeg(forward: Vec3): Float = Math.toDegrees(atan2(-forward.x.toDouble(), -forward.z.toDouble())).toFloat()
        fun pitchDeg(forward: Vec3): Float = Math.toDegrees(asin(forward.normalized().y.coerceIn(-1f, 1f).toDouble())).toFloat()
        fun angleDiff(a: Float, b: Float): Float { var d = a - b; while (d > 180f) d -= 360f; while (d < -180f) d += 360f; return d }
    }
}
