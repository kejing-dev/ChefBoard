package io.embodit.chefboard.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.place.LevelGate

/**
 * Opens / reopens the one Planar control window from the Stage (native-window-move.md §3.3, §3.5, §7 item 9). Every open
 * first asks the [LevelGate] — level head for 300 ms, and (ChefBoard) facing the window's place 20° left of the stove;
 * the Stage feeds it every frame and shows「平视前方」/「菜谱窗在这 · 看过来」while it waits. Reopen = close, wait for
 * ON_DESTROY, open again: the system puts the window straight ahead of the head (it cannot be placed by code).
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false)
        private set
    var lookHint by mutableStateOf(false)
        private set
    var panelState by mutableStateOf("never")
        private set
    val open: Boolean get() = panelState in setOf("ON_CREATE", "ON_START", "ON_RESUME", "ON_PAUSE")

    /** Set by exit: the Stage HUD stays empty while both containers close. */
    var exiting by mutableStateOf(false)

    /** Yaw (radians, + left) of the head when the window last opened: the system puts the window straight ahead. */
    @Volatile var openYaw: Float? = null
        private set
    @Volatile var openHead: Vec3? = null
        private set
    /** +1 on every open: the Stage runs Arrange.onWindowOpen once per open. */
    @Volatile var openSeq = 0
        private set
    @Volatile var openWhy = ""
        private set
    @Volatile var everOpened = false
        private set

    var closeStage: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private var openFn: (() -> Unit)? = null
    private var closeFn: (() -> Unit)? = null
    private var pendingAfterClose: Pair<String, Float?>? = null
    private var lastStepMs = 0L
    private var lastHead: Pair<Vec3, Vec3>? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(open: () -> Unit, close: () -> Unit) { openFn = open; closeFn = close }

    fun detach() {
        openFn = null; closeFn = null
        gate.cancel()
        main.removeCallbacks(watchdog)
    }

    /** Ask for the window; [targetYawDeg] (world yaw, + left) = wait until the user faces there (or 8 s; pitch ignored after 3 s). */
    fun request(why: String, targetYawDeg: Float? = null) {
        gate.ask(why, SystemClock.elapsedRealtime(), targetYawDeg)
        AppLog.i("WINDOW ask why=$why target=${targetYawDeg?.let { "%.1f".format(it) }} state=$panelState")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** Close it now; when it is gone, [then] (a reopen request) runs. */
    fun closeThen(why: String, targetYawDeg: Float?) {
        if (!open) { request(why, targetYawDeg); return }
        pendingAfterClose = why to targetYawDeg
        AppLog.i("WINDOW close for $why")
        runCatching { closeFn?.invoke() }
    }

    /** Close and do not reopen (叫回面前 waits for the user to face the stove first). */
    fun closeOnly(why: String) {
        gate.cancel(); levelHint = false; lookHint = false
        if (open) { AppLog.i("WINDOW close for $why"); runCatching { closeFn?.invoke() } }
    }

    fun close() { runCatching { closeFn?.invoke() } }

    fun onPanelLifecycle(event: String) {
        panelState = event
        if (event == "ON_RESUME") everOpened = true
        AppLog.i("WINDOW lifecycle $event")
        if (event == "ON_STOP") onClosed?.invoke()
        if (event == "ON_DESTROY") pendingAfterClose?.let { (why, t) -> pendingAfterClose = null; request(why, t) }
    }

    /** Every Stage frame: head position + forward (null when not valid), pitch and yaw (deg, yaw + left). */
    fun stepFrame(head: Pair<Vec3, Vec3>?, pitchDeg: Float?, yawDeg: Float?) {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (head != null) lastHead = head
        if (!gate.waiting) { if (levelHint) levelHint = false; if (lookHint) lookHint = false; return }
        val lh = gate.hintWanted(pitchDeg)
        if (levelHint != lh) levelHint = lh
        val look = !lh && gate.lookHintWanted(now, yawDeg)
        if (lookHint != look) lookHint = look
        gate.step(now, pitchDeg, yawDeg)?.let { why -> levelHint = false; lookHint = false; openNow(why, pitchDeg) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        lastHead?.let { (p, f) ->
            openYaw = Layout.yawOf(Vec3(f.x, 0f, f.z).normalized())
            openHead = p
        }
        openWhy = why
        openSeq++
        AppLog.i("WINDOW open why=$why pitch=$pitch yaw=${openYaw?.let { "%.1f".format(Math.toDegrees(it.toDouble())) }}")
        openFn?.invoke()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; lookHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }
}
