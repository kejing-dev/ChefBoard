package io.embodit.chefboard.platform

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.chefboard.data.RecipeCodec
import io.embodit.chefboard.domain.cook.CookFlow
import io.embodit.chefboard.domain.cook.FlowEffects
import io.embodit.chefboard.domain.cook.Lang
import io.embodit.chefboard.domain.cook.Settings
import io.embodit.chefboard.domain.recipe.Recipe
import io.embodit.chefboard.domain.timer.Ev
import io.embodit.chefboard.mainApp
import io.embodit.chefboard.platform.audio.AudioEngine
import io.embodit.chefboard.platform.audio.SoundCues
import io.embodit.chefboard.ui.stage.input.BoardInputLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/**
 * Process-wide objects (native-window-move.md §3.4): the Stage and the window share them — the audio engine, the flow,
 * the window controller, storage.
 */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val audio = AudioEngine(context)
    val window = WindowCtl()
    private val recipesFile = File(context.filesDir, "recipes.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Stereo pan (−1…1) of timer [slot] seen from the head, set by the Stage (fallback for sounds). */
    @Volatile var panOf: ((slot: Int) -> Float)? = null
    /** Plays the bell on the timer entity through the SDK spatial audio; false = not available (mixer fallback). */
    @Volatile var spatialBell: ((slot: Int, volume: Float) -> Boolean)? = null
    /** [叫回面前] (the Stage samples the stove direction, then reopens the window). */
    @Volatile var recallFn: (() -> Unit)? = null
    /** Stops trackers and sound (exit, D26). */
    @Volatile var stageRelease: (() -> Unit)? = null
    /** Exit: set by the window (closes both containers, finishes the task). */
    @Volatile var exitFn: (() -> Unit)? = null

    private val effects = object : FlowEffects {
        override fun events(evs: List<Ev>) = evs.forEach { sound(it) }
        override fun saveSettings(s: Settings) = writeSettings(s)
        override fun savePasted(list: List<Recipe>) = writeRecipes(list)
        override fun saveProgress(recipeId: String?, pageIndex: Int) { prefs.edit().putString("recipe", recipeId).putInt("step", pageIndex).apply() }
        override fun uiClick() = play("tick", 0.30f, 0f)
        override fun recall() { recallFn?.invoke() ?: window.closeThen("recall", null) }
        override fun exit() { exitFn?.invoke() }
        override fun log(line: String) = AppLog.i(line)
        override fun now(): Long = this@Graph.now()
    }

    val flow = CookFlow(effects)

    // debug `freeze`: the whole app clock (timers, flights, bells, animations) stands still so short states can be
    // photographed (S06s「好了 ✓」, S04 pop); real use never freezes, timers run on elapsedRealtime (M10)
    @Volatile private var frozenAt = -1L
    @Volatile private var pausedTotal = 0L
    val frozen: Boolean get() = frozenAt >= 0L
    fun now(): Long = if (frozenAt >= 0L) frozenAt else SystemClock.elapsedRealtime() - pausedTotal
    fun freeze(on: Boolean) {
        if (on && frozenAt < 0L) frozenAt = now()
        else if (!on && frozenAt >= 0L) { pausedTotal = SystemClock.elapsedRealtime() - frozenAt; frozenAt = -1L }
        AppLog.i("FREEZE $on")
    }

    init {
        flow.start(readSettings(), readRecipes(), prefs.getString("recipe", null), prefs.getInt("step", 0))
        applyVolume(flow.settings.value)
        audio.start()
        scope.launch(Dispatchers.IO) { audio.loadAssets() }
        scope.launch { flow.settings.collect { applyVolume(it) } }
    }

    // ------------------------------------------------------------------ sound (design sound.events)

    private fun applyVolume(s: Settings) {
        audio.mixer.master = if (s.muted) 0f else Settings.VOLUME_PERCENT[s.volume.coerceIn(0, 2)] / 100f
    }

    /** Linear volume the bell plays at on the entity (0 when muted): volume stop × [BELL_GAIN], ≤ 1. */
    val bellVolume: Float get() = flow.settings.value.let { if (it.muted) 0f else (Settings.VOLUME_PERCENT[it.volume.coerceIn(0, 2)] / 100f * BELL_GAIN).coerceAtMost(1f) }

    private fun play(kind: String, gain: Float, pan: Float) { audio.mixer.play(kind, gain, pan) }

    private fun pan(slot: Int) = runCatching { panOf?.invoke(slot) }.getOrNull() ?: 0f

    private fun sound(ev: Ev) {
        when (ev) {
            is Ev.Bell -> {
                val spatial = runCatching { spatialBell?.invoke(ev.slot, bellVolume) }.getOrNull() == true
                if (!spatial) play("bell", BELL_GAIN, pan(ev.slot))
                AppLog.i("BELL slot=${ev.slot} played ${if (spatial) "spatial" else "stereo pan=${"%.2f".format(pan(ev.slot))}"} volume=$bellVolume")
            }
            else -> SoundCues.of(ev).forEach { c -> play(c.kind, c.gain, c.slot?.let { pan(it) } ?: 0f) }
        }
    }

    // ------------------------------------------------------------------ storage

    private fun readRecipes(): List<Recipe> =
        runCatching { if (recipesFile.exists()) RecipeCodec.decode(recipesFile.readText()) else emptyList() }
            .onFailure { AppLog.e("RECIPES load failed", it) }.getOrDefault(emptyList())

    private fun writeRecipes(list: List<Recipe>) {
        val text = RecipeCodec.encode(list)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(recipesFile.parentFile, "recipes.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(recipesFile)) { recipesFile.delete(); tmp.renameTo(recipesFile) }
            }.onFailure { AppLog.e("RECIPES save failed", it) }
        }
    }

    private fun readSettings(): Settings = runCatching {
        Settings(
            volume = prefs.getInt("volume", 1).coerceIn(0, 2),
            muted = prefs.getBoolean("muted", false),
            lang = Lang.valueOf(prefs.getString("lang", Lang.SYSTEM.name)!!),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
        )
    }.getOrElse { Settings() }

    private fun writeSettings(s: Settings) {
        prefs.edit().putInt("volume", s.volume).putBoolean("muted", s.muted).putString("lang", s.lang.name).putBoolean("firstRunDone", s.firstRunDone).apply()
    }

    /** Debug `wipe`: first-run state on the next launch. */
    fun wipe() {
        recipesFile.delete()
        prefs.edit().clear().commit()
    }

    /** Chinese UI right now (settings language, else the system locale). */
    fun zh(): Boolean = when (flow.settings.value.lang) {
        Lang.ZH -> true
        Lang.EN -> false
        Lang.SYSTEM -> context.resources.configuration.locales[0].language == "zh"
    }

    fun release() {
        runCatching { stageRelease?.invoke() }
        runCatching { audio.stop() }
        window.detach()
    }

    companion object {
        /** Bell gain over the volume stop: at 中 (70 %) the bell peaks ≈ −2.2 dBFS, voiced RMS ≈ −19 dBFS (qa/loudness-v0.1.0.wav). */
        const val BELL_GAIN = 1.25f
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}
