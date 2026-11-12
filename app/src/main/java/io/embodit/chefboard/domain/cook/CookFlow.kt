package io.embodit.chefboard.domain.cook

import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.recipe.Builtin
import io.embodit.chefboard.domain.recipe.CookPage
import io.embodit.chefboard.domain.recipe.CookPages
import io.embodit.chefboard.domain.recipe.Parser
import io.embodit.chefboard.domain.recipe.Recipe
import io.embodit.chefboard.domain.timer.Ev
import io.embodit.chefboard.domain.timer.Label
import io.embodit.chefboard.domain.timer.TState
import io.embodit.chefboard.domain.timer.TimerBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Page { RECIPES, COOK, SETTINGS }
enum class Lang { SYSTEM, ZH, EN }

/** Persisted (prefs): volume stop 低 / 中 / 高, mute (remembered, D15), language, first-run hint shown. */
data class Settings(val volume: Int = 1, val muted: Boolean = false, val lang: Lang = Lang.SYSTEM, val firstRunDone: Boolean = false) {
    companion object { val VOLUME_PERCENT = intArrayOf(40, 70, 100) }
}

/** The paste card (S02): typing, or the preview after [拆成步骤]. */
sealed class Paste {
    data class Input(val text: String, val error: Boolean = false) : Paste()
    data class Preview(val text: String, val recipe: Recipe) : Paste()
}

/** What the window needs from the timer layer (updated only when it changes). */
data class TimerView(val stagedSlot: Int? = null, val allOut: Boolean = false, val ringing: List<Label?> = emptyList())

/** Page layer of design stateModel (one at a time) + the window-side view of the timers. */
data class UiState(
    val loading: Boolean = true,
    val page: Page = Page.RECIPES,
    val gridPage: Int = 0,
    val paste: Paste? = null,
    val recipeId: String? = null,
    val pageIndex: Int = 0,
    val done: Boolean = false,
    val exitAsk: Boolean = false,
    val timers: TimerView = TimerView(),
    val pasted: List<Recipe> = emptyList(),
) {
    /** design states[] id of the page layer (logs, QA). */
    val stateId: String get() = when {
        loading -> "S00"
        page == Page.RECIPES && paste != null -> "S02"
        page == Page.RECIPES -> "S01"
        page == Page.COOK && done -> "S09"
        page == Page.COOK -> "S03"
        exitAsk -> "S11"
        else -> "S10"
    }
}

/** Platform side of the flow: sound, storage, the window / Stage actions, the clock. */
interface FlowEffects {
    fun events(evs: List<Ev>)
    fun saveSettings(s: Settings)
    fun savePasted(list: List<Recipe>)
    fun saveProgress(recipeId: String?, pageIndex: Int)
    fun uiClick()
    fun recall()
    fun exit()
    fun log(line: String)
    fun now(): Long
}

/**
 * The whole app state machine: the window's pages and the four 3D timers ([board]). Window buttons, 3D taps (through
 * the Stage scene) and the debug broadcast all call these methods — one path (build.md).
 */
class CookFlow(private val fx: FlowEffects) {
    val board = TimerBoard { fx.now() }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val s: UiState get() = _state.value

    fun start(settings: Settings, pasted: List<Recipe>, recipeId: String?, pageIndex: Int) {
        _settings.value = settings
        val r = recipeId?.let { id -> (pasted + Builtin.all).firstOrNull { it.id == id } }
        _state.value = UiState(pasted = pasted, recipeId = r?.id, pageIndex = if (r != null) pageIndex.coerceIn(0, CookPages.of(r).size - 1) else 0)
    }

    private fun set(f: (UiState) -> UiState) {
        val before = _state.value
        val after = f(before)
        _state.value = after
        if (before.stateId != after.stateId || before.page != after.page) fx.log("STATE ${before.stateId} -> ${after.stateId} page=${after.page}")
    }

    /** Scene ready + head pose: S00 → S01 (or S03 when a recipe was left half-done). */
    fun loaded() = set { it.copy(loading = false, page = if (it.recipeId != null) Page.COOK else it.page) }

    // ------------------------------------------------------------------ recipes

    val allRecipes: List<Recipe> get() = s.pasted + Builtin.all
    fun recipe(id: String?): Recipe? = id?.let { i -> allRecipes.firstOrNull { it.id == i } }
    val current: Recipe? get() = recipe(s.recipeId)
    val pages: List<CookPage> get() = current?.let { CookPages.of(it) } ?: emptyList()

    /** Grid cells: recipes then the [＋ 粘贴] cell, 3 per page (the 4th cell is [更多 ›]). */
    fun gridCells(): List<String?> = allRecipes.map { it.id } + listOf(null)
    val gridPageCount: Int get() = (gridCells().size + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE
    fun gridPageCells(page: Int = s.gridPage): List<String?> = gridCells().drop(page * CARDS_PER_PAGE).take(CARDS_PER_PAGE)

    fun page(p: Page) { fx.uiClick(); set { it.copy(page = p, exitAsk = false) } }

    fun more() { fx.uiClick(); set { it.copy(gridPage = (it.gridPage + 1) % gridPageCount) } }

    /** A card = start cooking it right away (step 0). */
    fun openRecipe(id: String) {
        if (recipe(id) == null) return
        fx.uiClick()
        set { it.copy(recipeId = id, pageIndex = 0, done = false, page = Page.COOK, paste = null) }
        fx.saveProgress(id, 0)
    }

    fun prev() { if (s.pageIndex > 0) { fx.uiClick(); set { it.copy(pageIndex = it.pageIndex - 1) }; fx.saveProgress(s.recipeId, s.pageIndex) } }

    fun next() {
        val n = pages.size
        if (n == 0) return
        fx.uiClick()
        if (s.pageIndex >= n - 1) { set { it.copy(done = true) }; fx.saveProgress(null, 0) }
        else { set { it.copy(pageIndex = it.pageIndex + 1) }; fx.saveProgress(s.recipeId, s.pageIndex) }
    }

    /** S09 [换一道]. */
    fun again() { fx.uiClick(); set { it.copy(done = false, recipeId = null, pageIndex = 0, page = Page.RECIPES, gridPage = 0) } }

    fun gotoStep(index: Int) { if (pages.isNotEmpty()) set { it.copy(pageIndex = index.coerceIn(0, pages.size - 1), done = false, page = Page.COOK) } }

    // ------------------------------------------------------------------ paste (S02)

    fun openPaste() { fx.uiClick(); set { it.copy(paste = Paste.Input(""), page = Page.RECIPES) } }
    fun pasteText(t: String) = set { it.copy(paste = Paste.Input(t), page = Page.RECIPES) }
    fun pasteClose() { fx.uiClick(); set { it.copy(paste = null) } }

    fun pasteSplit() {
        val p = s.paste as? Paste.Input ?: return
        fx.uiClick()
        when (val r = Parser.parse(p.text)) {
            is Parser.Result.Ok -> { fx.log("PASTE ok steps=${r.recipe.steps.size} timers=${r.recipe.timerCount} title=${r.recipe.title.zh}"); set { it.copy(paste = Paste.Preview(p.text, r.recipe)) } }
            Parser.Result.NoSteps -> { fx.log("PASTE nosteps"); set { it.copy(paste = Paste.Input(p.text, error = true)) } }
        }
    }

    fun pasteEdit() { val p = s.paste as? Paste.Preview ?: return; fx.uiClick(); set { it.copy(paste = Paste.Input(p.text)) } }

    fun pasteSave() {
        val p = s.paste as? Paste.Preview ?: return
        fx.uiClick()
        val list = listOf(p.recipe) + s.pasted
        set { it.copy(paste = null, pasted = list, gridPage = 0) }
        fx.savePasted(list)
        fx.log("PASTE saved ${p.recipe.id}")
    }

    // ------------------------------------------------------------------ settings

    fun toggleMute() { fx.uiClick(); val n = _settings.value.copy(muted = !_settings.value.muted); _settings.value = n; fx.saveSettings(n); fx.log("SOUND muted=${if (n.muted) 1 else 0}") }
    fun setMuted(on: Boolean) { if (_settings.value.muted != on) toggleMute() }
    fun cycleVolume() { val n = _settings.value.copy(volume = (_settings.value.volume + 1) % 3); _settings.value = n; fx.saveSettings(n); fx.uiClick(); fx.log("SOUND volume=${n.volume}") }
    fun setVolume(v: Int) { val n = _settings.value.copy(volume = v.coerceIn(0, 2)); _settings.value = n; fx.saveSettings(n) }
    fun setLang(l: Lang) { val n = _settings.value.copy(lang = l); _settings.value = n; fx.saveSettings(n); fx.log("LANG $l") }
    fun toggleLang(zhNow: Boolean) { fx.uiClick(); setLang(if (zhNow) Lang.EN else Lang.ZH) }
    fun firstRunShown() { if (!_settings.value.firstRunDone) { val n = _settings.value.copy(firstRunDone = true); _settings.value = n; fx.saveSettings(n) } }

    fun recall() { fx.uiClick(); fx.recall() }
    fun exitAsk() { fx.uiClick(); set { it.copy(page = Page.SETTINGS, exitAsk = true) } }
    fun exitCancel() { fx.uiClick(); set { it.copy(exitAsk = false) } }
    fun exitConfirm() { fx.log("EXIT confirm"); board.reset(); fx.exit() }

    // ------------------------------------------------------------------ timers (3D + window)

    /** Cook page [⏱ N 分] / [取消]. */
    fun stepTimerButton() {
        val pg = pages.getOrNull(s.pageIndex) ?: return
        if (board.staged != null) { emit(board.cancelStaged()); return }
        val sec = pg.timerSec ?: return
        val name = pg.timerName?.let { Label(it.zh, it.en) }
        emit(board.stepTimer(sec, name))
    }

    /** Window amber bar: stop every ringing timer. */
    fun amberStop() = emit(board.stopAll())

    fun tapTimer(slot: Int) = emit(board.tapTimer(slot, Label("计时器 ${slot + 1}", "Timer ${slot + 1}")))
    fun tapSpot(index: Int, world: Vec3) = emit(board.tapSpot(index, world))
    fun stagedAdd(min: Int) = emit(board.stagedAdd(min))
    fun stagedClear() = emit(board.stagedClear())
    fun pausedAdd() = emit(board.pausedAdd())
    fun pausedRecall() = emit(board.pausedRecall())

    private fun emit(evs: List<Ev>) {
        if (evs.isNotEmpty()) { fx.events(evs); fx.log("TIMER ${evs.joinToString(",")} | ${board.summary()}") }
        refreshTimerView()
    }

    /** Every frame. */
    fun tick() {
        val evs = board.tick()
        if (evs.isNotEmpty()) { fx.events(evs); if (evs.any { it !is Ev.Bell }) fx.log("TIMER ${evs.joinToString(",")} | ${board.summary()}") }
        refreshTimerView()
    }

    private fun refreshTimerView() {
        val v = TimerView(board.staged?.slot, board.allOut, board.ringing.map { it.label })
        if (v != s.timers) set { it.copy(timers = v) }
    }

    fun windowClosed() { /* timers keep running; the rack's name plate offers「捏我开菜谱」 */ }

    /** Debug: the board state (timer layer of states[]). */
    fun timerStates(): String = board.timers.joinToString(" ") { "${it.slot}:${it.state}" } + (if (board.timers.any { it.state == TState.RINGING }) " ringing" else "")

    companion object { const val CARDS_PER_PAGE = 3 }
}
