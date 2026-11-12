package io.embodit.chefboard.domain.place

import kotlin.math.abs

/**
 * D9 (prompts/snippets/native-window-move.md §7 item 9): a window opened while the head is pitched opens pitched,
 * so every openWindowContainer first asks here and only opens once the head has been level (|pitch| <= 10°) for
 * 300 ms. Pure Kotlin: the caller feeds the time and the head pitch / yaw (degrees; null when there is no valid
 * head pose). With no valid head pose for [noHeadMs] it opens anyway, so the window is never stuck.
 *
 * ChefBoard addition: the system opens the window straight ahead of the head and code cannot place it, while the design
 * wants it 20° left of the stove. So an ask can carry a [target] yaw (deg, + left, world): the gate then also waits for
 * the head to face within [yawTolDeg] of it (the Stage shows「菜谱窗在这 · 看过来」there). After [targetTimeoutMs] the
 * target is dropped and the window opens wherever the user looks (Arrange then turns the kitchen to the window).
 *
 * QA #1 q1-1: a head that stays pitched (cooking = looking down at the counter) must not hold the window forever: after
 * [pitchTimeoutMs] the pitch is ignored (the HUD shows「平视前方」until then, on the sight line). Every ask therefore
 * opens within max([pitchTimeoutMs], [targetTimeoutMs]) = 8 s whatever the head does.
 */
class LevelGate(
    val maxPitchDeg: Float = 10f,
    val dwellMs: Long = 300L,
    val noHeadMs: Long = 5000L,
    val yawTolDeg: Float = Arrange.GATE_TOL_DEG,
    val targetTimeoutMs: Long = 8000L,
    val pitchTimeoutMs: Long = 3000L,
) {
    var pending: String? = null; private set
    var target: Float? = null; private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L
    val waiting: Boolean get() = pending != null

    fun ask(why: String, now: Long, targetYawDeg: Float? = null) {
        if (pending == null) askedMs = now
        pending = why
        target = targetYawDeg
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() { pending = null; target = null; levelSinceMs = -1L; noHeadSinceMs = -1L }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** The yaw target still applies (not timed out). */
    fun targetActive(now: Long): Boolean = target != null && now - askedMs < targetTimeoutMs

    private fun facing(now: Long, yawDeg: Float?): Boolean {
        val t = target ?: return true
        if (!targetActive(now)) return true
        if (yawDeg == null) return false
        return abs(Layout.diffDeg(yawDeg, t)) <= yawTolDeg
    }

    /** Returns why to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?, yawDeg: Float? = null): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        val pitchOk = abs(pitchDeg) <= maxPitchDeg || now - askedMs >= pitchTimeoutMs
        if (!pitchOk || !facing(now, yawDeg)) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        val late = target != null && !targetActive(now)
        val pitched = abs(pitchDeg) > maxPitchDeg
        cancel()
        return why + (if (late) "/notarget" else "") + (if (pitched) "/pitched" else "")
    }

    fun hintWanted(pitchDeg: Float?): Boolean = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg

    /** The「look here」hint at the target yaw is wanted (target set, not yet faced, not timed out). */
    fun lookHintWanted(now: Long, yawDeg: Float?): Boolean = pending != null && targetActive(now) && !facing(now, yawDeg)
}
