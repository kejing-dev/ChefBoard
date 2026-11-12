package io.embodit.chefboard.domain.place

import io.embodit.chefboard.domain.math.Vec3

/**
 * The one placement function (profile 48「窗口摆位只写一个函数」): cold start, every window open (first, reopen after ×
 * via the rack's name plate, [叫回面前], debug recall) ends in [onWindowOpen].
 *
 * The kitchen pose = the eye + the stove's yaw. It is taken first (cold start: the level head once tracking is steady;
 * [叫回面前]: [HeadSampler] after the user turned to the stove, D29), then the window is asked for at the stove yaw + 20°
 * (left, design WINDOW_AZ −20°). When the window really opened there (± [GATE_TOL_DEG]) the kitchen stays; when it
 * opened elsewhere (the 6 s fallback, or a reopen while the user looked somewhere else) the kitchen turns so the window
 * sits at az −20° again — window and rack / rings never overlap (D35 unit test). Yaw only (D9).
 */
object Arrange {
    const val GATE_TOL_DEG = 6f

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    /** World yaw (deg, + left) at which the window is wanted for [pose]. */
    fun windowTargetYawDeg(pose: Layout.Pose): Float = deg(pose.yaw) - Layout.WINDOW_AZ

    /** Cold start / [叫回面前] sample: stove straight ahead of the sampled head. */
    fun fromSample(head: Vec3, yaw: Float): Layout.Pose = Layout.Pose(head, yaw)

    /** The window just opened straight ahead of [head] at [windowYaw] (radians, + left). */
    fun onWindowOpen(pose: Layout.Pose?, head: Vec3, windowYaw: Float): Layout.Pose {
        if (pose != null && kotlin.math.abs(Layout.diffDeg(deg(windowYaw), windowTargetYawDeg(pose))) <= GATE_TOL_DEG) return pose
        return Layout.Pose(head, windowYaw + rad(Layout.WINDOW_AZ))
    }

    /** Where the window really is relative to the stove (deg, + right), for the D35 check and the logs. */
    fun windowAz(pose: Layout.Pose, windowYaw: Float): Float = -Layout.diffDeg(deg(windowYaw), deg(pose.yaw))
}
