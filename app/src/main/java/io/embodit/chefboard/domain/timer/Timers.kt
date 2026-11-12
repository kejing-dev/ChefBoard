package io.embodit.chefboard.domain.timer

import io.embodit.chefboard.domain.math.Vec3

/** Timer layer of design stateModel: docked → staged → flying → running ⇄ paused → ringing → stopped → returning → docked. */
enum class TState { DOCKED, STAGED, FLYING, RUNNING, PAUSED, RINGING, STOPPED, RETURNING }

/** Where a timer is or is going. Rack / Stage are resolved through the current layout; At is a world point (rings). */
sealed class Place {
    data class Rack(val slot: Int) : Place()
    data object Stage : Place()
    data class At(val world: Vec3) : Place()
}

data class Anim(val from: Place, val to: Place, val startMs: Long, val durMs: Long, val arc: Float, val dip: Float)

/** Second line of the timer's 3D tag when it is not the timer's name. */
enum class Hint { NONE, LOOK_RING, ADD_TIME, TAP_AGAIN }

/** A name in both languages (step timers carry the step's own word: 煮面 / Pasta). */
data class Label(val zh: String, val en: String)

/** What happened, for sound / logs / the window (one event per thing, played by the platform). */
sealed class Ev {
    data class Picked(val slot: Int) : Ev()
    data class Tick(val slot: Int) : Ev()
    data class Landed(val slot: Int) : Ev()
    data class Docked(val slot: Int) : Ev()
    data class Paused(val slot: Int) : Ev()
    data class Resumed(val slot: Int) : Ev()
    data class Ringing(val slot: Int) : Ev()
    data class Bell(val slot: Int) : Ev()
    data class Stopped(val slot: Int) : Ev()
    data class Refused(val spot: Int) : Ev()
    data class AskAgain(val slot: Int) : Ev()
    data class Returning(val slot: Int) : Ev()
    data object Full : Ev()
}

class Timer(val slot: Int) {
    var state = TState.DOCKED; internal set
    var label: Label? = null; internal set
    /** Remaining time when not running (ms). */
    internal var heldMs = 0L
    /** elapsedRealtime at which a running timer reaches 0. */
    internal var endAtMs = 0L
    /** World point of the ring it stands on (kept when the layout is recalled: placed timers never move). */
    var spotWorld: Vec3? = null; internal set
    var spotIndex: Int? = null; internal set
    var anim: Anim? = null; internal set
    var hint = Hint.NONE; internal set
    internal var hintUntilMs = 0L
    internal var firstTapMs = -1L
    var wiggleAtMs = Long.MIN_VALUE / 2; internal set
    var ringAtMs = 0L; internal set
    internal var nextBellMs = 0L
    internal var stoppedAtMs = 0L
    internal var lastActionMs = 0L

    fun remainingMs(now: Long): Long = when (state) {
        TState.RUNNING -> (endAtMs - now).coerceAtLeast(0L)
        TState.RINGING, TState.STOPPED -> 0L
        else -> heldMs
    }

    /** Whole seconds shown on the tag (rounded up, so 9:59.4 reads 10:00 only at the very start). */
    fun shownSeconds(now: Long): Int = ((remainingMs(now) + 999L) / 1000L).toInt()

    fun isOut(): Boolean = state != TState.DOCKED

    override fun toString() = "T$slot:$state ${heldMs / 1000}s spot=$spotIndex"
}

/**
 * The four timers and every rule of design stateModel.globalRules. Pure Kotlin with an injected clock (elapsedRealtime:
 * never frame-accumulated, so background / foreground keeps the real time, M10). The window, the 3D taps and the debug
 * broadcast all call these methods (build.md: debug goes through the same path as real input).
 */
class TimerBoard(private val clock: () -> Long) {
    val timers = List(SLOTS) { Timer(it) }
    /** The paused timer whose [+1] [收回] row is shown (the last one paused, E4). */
    var pauseFocus: Int? = null; private set
    private val lastTap = HashMap<String, Long>()

    val staged: Timer? get() = timers.firstOrNull { it.state == TState.STAGED }
    val ringing: List<Timer> get() = timers.filter { it.state == TState.RINGING }
    val allOut: Boolean get() = timers.none { it.state == TState.DOCKED }

    /** Same target tapped again within 300 ms is ignored (design input.rules 误触保护). */
    fun debounce(key: String): Boolean {
        val now = clock()
        val last = lastTap[key]
        if (last != null && now - last < DEBOUNCE_MS) return true
        lastTap[key] = now
        return false
    }

    private fun animating(t: Timer, now: Long): Boolean = t.anim?.let { now - it.startMs < it.durMs } == true

    /**
     * While a timer floats only it, its three buttons, the free rings and the window respond; the others are dimmed
     * (M16). Ringing timers still answer (到点优先). Timers in flight never answer.
     */
    fun timerTappable(slot: Int): Boolean {
        val t = timers[slot]; val now = clock()
        if (t.state == TState.FLYING || t.state == TState.RETURNING || t.state == TState.STOPPED) return false
        if (t.state == TState.STAGED) return !animating(t, now)
        if (t.state == TState.RINGING) return true
        return staged == null
    }

    fun dimmed(slot: Int): Boolean {
        val s = staged ?: return false
        val t = timers[slot]
        return t !== s && t.state != TState.RINGING && t.state != TState.RETURNING
    }

    fun plateTappable(): Boolean = staged == null

    // ------------------------------------------------------------------ staging

    /** Picks a docked timer up to the stage point with [seconds] (the rack default 5:00, or a step's time). */
    fun pick(slot: Int, seconds: Int = DEFAULT_SEC, label: Label? = null): List<Ev> {
        val t = timers[slot]
        if (t.state != TState.DOCKED) return emptyList()
        val out = ArrayList<Ev>()
        staged?.let { out += sendBack(it) }
        val now = clock()
        t.state = TState.STAGED
        t.label = label
        t.heldMs = seconds.coerceIn(0, MAX_SEC) * 1000L
        t.spotWorld = null; t.spotIndex = null
        t.anim = Anim(Place.Rack(slot), Place.Stage, now, PICK_MS, 0.06f, 0f)
        t.hint = Hint.LOOK_RING; t.firstTapMs = -1L; t.lastActionMs = now
        if (pauseFocus != null && timers[pauseFocus!!].state != TState.PAUSED) pauseFocus = null
        out += Ev.Picked(slot)
        return out
    }

    /**
     * A step's [⏱ N 分]: a timer floats with that time and name. One already floating takes the new time and name;
     * all four out = [Ev.Full] (the button is greyed「4 个都在用」).
     */
    fun stepTimer(seconds: Int, label: Label?): List<Ev> {
        staged?.let { s ->
            s.heldMs = seconds.coerceIn(0, MAX_SEC) * 1000L; s.label = label; s.lastActionMs = clock(); s.hint = Hint.LOOK_RING
            return listOf(Ev.Tick(s.slot))
        }
        val free = timers.firstOrNull { it.state == TState.DOCKED } ?: return listOf(Ev.Full)
        return pick(free.slot, seconds, label)
    }

    fun cancelStaged(): List<Ev> = staged?.let { sendBack(it) } ?: emptyList()

    private fun bump(t: Timer, deltaSec: Int?) {
        t.heldMs = if (deltaSec == null) 0L else (t.heldMs + deltaSec * 1000L).coerceIn(0L, MAX_SEC * 1000L)
        t.lastActionMs = clock()
    }

    /** 3D [+1] [+5] while floating (0–99 min), [清零] = 0. */
    fun stagedAdd(minutes: Int): List<Ev> { val s = staged ?: return emptyList(); bump(s, minutes * 60); s.hint = Hint.LOOK_RING; return listOf(Ev.Tick(s.slot)) }

    fun stagedClear(): List<Ev> { val s = staged ?: return emptyList(); bump(s, null); s.hint = Hint.LOOK_RING; return listOf(Ev.Tick(s.slot)) }

    /** Ring [index] at [spotWorld] tapped: fly there and start (time > 0), or wiggle +「先加点时间」. */
    fun tapSpot(index: Int, spotWorld: Vec3): List<Ev> {
        val s = staged ?: return emptyList()
        val now = clock()
        if (animating(s, now)) return emptyList()
        if (!spotFree(spotWorld)) return emptyList()
        if (s.heldMs <= 0L) {
            s.hint = Hint.ADD_TIME; s.hintUntilMs = now + 1800L; s.lastActionMs = now
            return listOf(Ev.Refused(index))
        }
        s.state = TState.FLYING
        s.spotWorld = spotWorld; s.spotIndex = index
        s.anim = Anim(Place.Stage, Place.At(spotWorld), now, FLY_MS, 0.12f, 0.004f)
        s.hint = Hint.NONE; s.firstTapMs = -1L
        return emptyList()
    }

    /** A ring is free when no placed timer stands within 0.15 m of it (M17, by position, also after a recall). */
    fun spotFree(spotWorld: Vec3): Boolean = timers.none { t ->
        t.state in PLACED && t.spotWorld?.let { it.distanceTo(spotWorld) < io.embodit.chefboard.domain.place.Layout.SPOT_OCCUPIED_M } == true
    }

    // ------------------------------------------------------------------ taps on a timer

    fun tapTimer(slot: Int, freeLabel: Label? = null): List<Ev> {
        val t = timers[slot]
        if (!timerTappable(slot)) return emptyList()
        val now = clock()
        return when (t.state) {
            TState.DOCKED -> pick(slot, DEFAULT_SEC, freeLabel)
            TState.STAGED -> {
                // two pinches within 2 s cancel (the first only wiggles and asks「再捏收回」) — design globalRules 7
                if (t.firstTapMs >= 0L && now - t.firstTapMs <= CANCEL_WINDOW_MS) sendBack(t)
                else { t.firstTapMs = now; t.wiggleAtMs = now; t.hint = Hint.TAP_AGAIN; t.hintUntilMs = now + CANCEL_WINDOW_MS; t.lastActionMs = now; listOf(Ev.AskAgain(slot)) }
            }
            TState.RUNNING -> { t.heldMs = (t.endAtMs - now).coerceAtLeast(0L); t.state = TState.PAUSED; pauseFocus = slot; listOf(Ev.Paused(slot)) }
            TState.PAUSED -> {
                if (t.heldMs <= 0L) { startRinging(t, now); return listOf(Ev.Ringing(slot)) }
                t.endAtMs = now + t.heldMs; t.state = TState.RUNNING; if (pauseFocus == slot) pauseFocus = null; listOf(Ev.Resumed(slot))
            }
            TState.RINGING -> stop(t)
            else -> emptyList()
        }
    }

    /** The paused row: [+1] (stays paused). */
    fun pausedAdd(): List<Ev> {
        val t = pauseFocus?.let { timers[it] }?.takeIf { it.state == TState.PAUSED && staged == null } ?: return emptyList()
        bump(t, 60); return listOf(Ev.Tick(t.slot))
    }

    /** The paused row: [收回] — back to its slot. */
    fun pausedRecall(): List<Ev> {
        val t = pauseFocus?.let { timers[it] }?.takeIf { it.state == TState.PAUSED && staged == null } ?: return emptyList()
        return sendBack(t)
    }

    /** The window's amber bar: every ringing timer stops (no need to turn round, I08). */
    fun stopAll(): List<Ev> = ringing.flatMap { stop(it) }

    private fun stop(t: Timer): List<Ev> {
        t.state = TState.STOPPED; t.stoppedAtMs = clock(); t.heldMs = 0L
        if (pauseFocus == t.slot) pauseFocus = null
        return listOf(Ev.Stopped(t.slot))
    }

    private fun startRinging(t: Timer, now: Long) {
        t.state = TState.RINGING; t.heldMs = 0L; t.ringAtMs = now; t.nextBellMs = now
        if (pauseFocus == t.slot) pauseFocus = null
    }

    private fun sendBack(t: Timer): List<Ev> {
        val now = clock()
        val from: Place = when (t.state) {
            TState.STAGED -> Place.Stage
            else -> t.spotWorld?.let { Place.At(it) } ?: Place.Stage
        }
        t.state = TState.RETURNING
        t.anim = Anim(from, Place.Rack(t.slot), now, RETURN_MS, 0.08f, 0.003f)
        t.hint = Hint.NONE; t.firstTapMs = -1L
        if (pauseFocus == t.slot) pauseFocus = null
        return listOf(Ev.Returning(t.slot))
    }

    // ------------------------------------------------------------------ time

    /** Every frame (or any time): lands flights, starts / rings / stops / returns by the clock. */
    fun tick(): List<Ev> {
        val now = clock()
        val out = ArrayList<Ev>()
        for (t in timers) {
            when (t.state) {
                TState.STAGED -> {
                    if (t.hint != Hint.LOOK_RING && t.hint != Hint.NONE && now > t.hintUntilMs) { t.hint = Hint.LOOK_RING; t.firstTapMs = -1L }
                    if (now - t.lastActionMs > STAGE_IDLE_MS) out += sendBack(t)
                }
                TState.FLYING -> if (!animating(t, now)) {
                    t.state = TState.RUNNING; t.endAtMs = now + t.heldMs; t.anim = null
                    out += Ev.Landed(t.slot)
                }
                TState.RUNNING -> if (now >= t.endAtMs) { startRinging(t, t.endAtMs.coerceAtMost(now)); out += Ev.Ringing(t.slot) }
                TState.RINGING -> if (now >= t.nextBellMs) {
                    out += Ev.Bell(t.slot)
                    t.nextBellMs = now + if (now - t.ringAtMs < BELL_FAST_FOR_MS) BELL_FAST_MS else BELL_SLOW_MS
                }
                TState.STOPPED -> if (now - t.stoppedAtMs >= STOPPED_SHOW_MS) out += sendBack(t)
                TState.RETURNING -> if (!animating(t, now)) {
                    t.state = TState.DOCKED; t.anim = null; t.spotWorld = null; t.spotIndex = null; t.label = null; t.heldMs = 0L
                    out += Ev.Docked(t.slot)
                }
                else -> Unit
            }
        }
        return out
    }

    // ------------------------------------------------------------------ debug / QA (same transitions, only time is set)

    /** debug timers=n: n timers placed on the given world points, running with [seconds] each. */
    fun placeRunning(slot: Int, spot: Int, spotWorld: Vec3, seconds: Int, label: Label?) {
        val t = timers[slot]
        t.state = TState.RUNNING; t.label = label; t.spotWorld = spotWorld; t.spotIndex = spot; t.anim = null
        t.heldMs = seconds * 1000L; t.endAtMs = clock() + t.heldMs; t.hint = Hint.NONE
    }

    /** debug ring=slot: the timer's remaining time ends now (it rings through the normal tick). */
    fun expire(slot: Int) {
        val t = timers[slot]
        when (t.state) {
            TState.RUNNING -> t.endAtMs = clock()
            TState.PAUSED -> { t.heldMs = 0L; startRinging(t, clock()) }
            else -> Unit
        }
    }

    /** Back to four docked timers (exit / wipe). */
    fun reset() {
        for (t in timers) { t.state = TState.DOCKED; t.anim = null; t.spotWorld = null; t.spotIndex = null; t.label = null; t.heldMs = 0L; t.hint = Hint.NONE }
        pauseFocus = null
    }

    fun summary(): String = timers.joinToString(" ") { "${it.slot}:${it.state}/${it.shownSeconds(clock())}s${it.spotIndex?.let { s -> "@$s" } ?: ""}" }

    companion object {
        const val SLOTS = 4
        const val DEFAULT_SEC = 300
        const val MAX_SEC = 99 * 60
        const val DEBOUNCE_MS = 300L
        const val PICK_MS = 380L
        const val FLY_MS = 600L
        const val RETURN_MS = 500L
        const val CANCEL_WINDOW_MS = 2000L
        const val STAGE_IDLE_MS = 60_000L
        const val STOPPED_SHOW_MS = 1500L
        const val BELL_FAST_MS = 1200L
        const val BELL_SLOW_MS = 5000L
        const val BELL_FAST_FOR_MS = 60_000L
        val PLACED = setOf(TState.FLYING, TState.RUNNING, TState.PAUSED, TState.RINGING, TState.STOPPED)
    }
}
