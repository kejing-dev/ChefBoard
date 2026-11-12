package io.embodit.chefboard.domain.place

import io.embodit.chefboard.domain.math.Vec3

/**
 * Cold start S00 → S01 (QA #1 q1-1 / q1-3). Pure Kotlin, fed every frame by the Stage:
 *  - the first steady head pose held for [steadyMs] places the kitchen right away, whatever the pitch / roll
 *    (yaw-only, [Arrange.coldStart]); the window is then asked for straight ahead (no yaw target, no glance needed);
 *  - no steady pose after [fallbackMs]: the last pose seen (or a default eye at 1.6 m, yaw 0) is used — never stuck.
 * The window itself still waits for a level head, at most [LevelGate.pitchTimeoutMs].
 */
class ColdStart(val steadyMs: Long = 300L, val fallbackMs: Long = 4000L) {
    var done = false; private set
    private var steadySinceMs = -1L

    data class Result(val pose: Layout.Pose, val why: String)

    fun reset() { done = false; steadySinceMs = -1L }

    /** [sinceStartMs] = time since the Stage started; [position]/[forward] = this frame's head (null = invalid). */
    fun step(sinceStartMs: Long, position: Vec3?, forward: Vec3?, steady: Boolean, lastPosition: Vec3? = null, lastForward: Vec3? = null): Result? {
        if (done) return null
        if (position != null && forward != null && steady) {
            if (steadySinceMs < 0L) steadySinceMs = sinceStartMs
            if (sinceStartMs - steadySinceMs >= steadyMs) {
                done = true
                return Result(Arrange.coldStart(position, Arrange.headingOf(forward)), "start")
            }
        } else steadySinceMs = -1L
        if (sinceStartMs >= fallbackMs) {
            done = true
            val p = position ?: lastPosition
            val f = forward ?: lastForward
            return if (p != null && f != null) Result(Arrange.coldStart(p, Arrange.headingOf(f)), "start/unsteady")
            else Result(Arrange.coldStart(Vec3(0f, 1.6f, 0f), 0f), "start/nohead")
        }
        return null
    }
}
