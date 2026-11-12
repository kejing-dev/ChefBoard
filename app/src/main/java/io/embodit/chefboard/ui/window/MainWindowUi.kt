package io.embodit.chefboard.ui.window

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.TextArea
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.chefboard.BuildConfig
import io.embodit.chefboard.R
import io.embodit.chefboard.domain.cook.CookFlow
import io.embodit.chefboard.domain.cook.Page
import io.embodit.chefboard.domain.cook.Paste
import io.embodit.chefboard.domain.cook.Settings
import io.embodit.chefboard.domain.cook.UiState
import io.embodit.chefboard.domain.recipe.Recipe
import io.embodit.chefboard.platform.AppLog
import io.embodit.chefboard.platform.Graph
import io.embodit.chefboard.ui.Containers
import io.embodit.chefboard.ui.theme.ChefColors
import kotlin.system.exitProcess

/** Layout constants of the 640 × 560 dp window (design panels P1–P4; heights in ui-audit §空白). */
private object W {
    val PAD = 24.dp
    val HEADER = 64.dp
    val GAP = 16.dp
    val ROW = 96.dp
    val MAIN = 128.dp
    val CELL = 200.dp
    val CARD = 208.dp
    val SENTENCE = 40.sp
}

/**
 * The one system window (windowModel: a Planar control window, native TabBar 菜谱 / 做菜 / 设置). Every page ≤ 7 tappable
 * (3 tabs included, the mute key not counted, B31), main buttons 96–128 dp, gaps 16 dp, content fills ≥ 75 % (D44). The
 * paste card, the exit confirmation and the result card open inside the page. While a timer rings the page's bottom
 * row becomes the amber bar (S06 / M15).
 */
@Composable
fun MainWindowUi() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        graph.exitFn = { exitApp(context.applicationContext, navigator, graph) }
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.stateId, state.pageIndex, state.gridPage) { AppLog.i("PAGE ${state.page} ${state.stateId} step=${state.pageIndex} grid=${state.gridPage}") }
    val zh = context.resources.configuration.locales[0].language == "zh"
    val flow = graph.flow

    Box(Modifier.fillMaxSize().padding(W.PAD), contentAlignment = Alignment.Center) {
        when (state.page) {
            Page.RECIPES -> if (state.paste != null) PastePage(state.paste!!, state, settings, flow, zh) else RecipesPage(state, settings, flow, zh)
            Page.COOK -> CookPage(state, settings, flow, zh)
            Page.SETTINGS -> if (state.exitAsk) ExitPage(state, settings, flow) else SettingsPage(state, settings, flow, zh)
        }
    }

    // The TabBar is its own system window: labels resolved here in the app language and passed in (tabbar-lang gate).
    val tabs = listOf(
        Triple(Page.RECIPES, R.drawable.ic_menu_book, stringResource(R.string.tab_recipes)),
        Triple(Page.COOK, R.drawable.ic_local_fire_department, stringResource(R.string.tab_cook)),
        Triple(Page.SETTINGS, R.drawable.ic_settings, stringResource(R.string.tab_settings)),
    )
    key(settings.lang) { MainTabBar(tabs, state.page) { flow.page(it) } }
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun Header(title: String, tag: String, settings: Settings, flow: CookFlow, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(W.HEADER), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), modifier = Modifier.weight(1f), maxLines = 1)
        extra?.invoke()
        MuteKey(settings.muted) { flow.toggleMute() }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(W.GAP, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

/** A rounded card on the window glass (sentence, result, confirm text). */
@Composable
private fun Card(height: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(28.dp)).background(ChefColors.CardFill).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally, content = content,
    )
}

/** S06 / M15: one amber bar replaces the page's bottom row on every page while a timer rings. */
@Composable
private fun AmberBar(state: UiState, flow: CookFlow, zh: Boolean, height: Dp = W.ROW) {
    val r = state.timers.ringing
    val text = if (r.size > 1) stringResource(R.string.amber_many, r.size)
    else stringResource(R.string.amber_one, r.firstOrNull()?.let { if (zh) it.zh else it.en } ?: "")
    BigButton(text, "amber", onClick = { flow.amberStop() }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_clock_fill, kind = Kind.AMBER, height = height)
}

private fun ringing(state: UiState) = state.timers.ringing.isNotEmpty()

/** "10 分" / "10 min"; 6:30 for times that are not whole minutes. */
@Composable
internal fun timeLabel(sec: Int): String = if (sec % 60 == 0) stringResource(R.string.min_fmt, sec / 60) else "%d:%02d".format(sec / 60, sec % 60)

/** Step sentence with {times} drawn in deep amber, bold. */
internal fun sentence(text: String): AnnotatedString = buildAnnotatedString {
    var k = 0
    while (k < text.length) {
        val a = text.indexOf('{', k)
        if (a < 0) { append(text.substring(k)); break }
        val b = text.indexOf('}', a).takeIf { it > a } ?: text.length
        append(text.substring(k, a))
        withStyle(SpanStyle(color = ChefColors.TimeInk, fontWeight = FontWeight.Bold)) { append(text.substring(a + 1, b)) }
        k = b + 1
    }
}

// ------------------------------------------------------------------ P1 Recipes (S01)

@Composable
private fun RecipesPage(state: UiState, settings: Settings, flow: CookFlow, zh: Boolean) {
    val cells = flow.gridPageCells(state.gridPage)
    PageColumn {
        Header(stringResource(R.string.title_recipes, state.gridPage + 1, flow.gridPageCount), "recipes", settings, flow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
            Cell(cells.getOrNull(0), flow, zh, Modifier.weight(1f))
            Cell(cells.getOrNull(1), flow, zh, Modifier.weight(1f))
        }
        if (ringing(state)) AmberBar(state, flow, zh, W.CELL)
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
            Cell(cells.getOrNull(2), flow, zh, Modifier.weight(1f), present = cells.size > 2)
            BigButton(stringResource(R.string.recipes_more), "recipes/more", onClick = { flow.more() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_chevron_right, height = W.CELL, stacked = true)
        }
    }
}

/** A recipe card (name + time + timers) or the [＋ 粘贴] cell (id null). */
@Composable
private fun Cell(id: String?, flow: CookFlow, zh: Boolean, modifier: Modifier, present: Boolean = true) {
    if (!present) { Spacer(modifier.height(W.CELL)); return }
    if (id == null) {
        BigButton(stringResource(R.string.recipes_paste), "recipes/paste", onClick = { flow.openPaste() }, modifier = modifier, icon = R.drawable.ic_add, height = W.CELL, stacked = true)
        return
    }
    val r = flow.recipe(id) ?: return
    Button(
        onClick = { flow.openRecipe(id) }, modifier = modifier,
        size = ButtonDefaults.Regular.copy(height = W.CELL, minWidth = 96.dp), shape = RoundedCornerShape(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // design-style: inherited-content-color Button
            Txt(r.title.of(zh), "recipes/card/${r.id}", style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp, lineHeight = 36.sp), color = PicoTheme.colorScheme.labelPrimary, align = TextAlign.Center, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.min_fmt, r.minutes), "recipes/card/${r.id}/min", style = PicoTheme.typography.labelLarge.copy(fontSize = 22.sp), color = PicoTheme.colorScheme.labelSecondary)
                if (r.timerCount > 0) {
                    Spacer(Modifier.width(14.dp))
                    Icon(painter = painterResource(R.drawable.ic_clock_fill), contentDescription = null, tint = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(4.dp))
                    Txt("×${r.timerCount}", "recipes/card/${r.id}/timers", style = PicoTheme.typography.labelLarge.copy(fontSize = 22.sp), color = PicoTheme.colorScheme.labelSecondary)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ P4 paste (S02)

@Composable
private fun PastePage(p: Paste, state: UiState, settings: Settings, flow: CookFlow, zh: Boolean) {
    PageColumn {
        when (p) {
            is Paste.Input -> {
                Header(stringResource(R.string.paste_title), "paste", settings, flow) {
                    BigButton(stringResource(R.string.paste_close), "paste/close", onClick = { flow.pasteClose() }, icon = R.drawable.ic_close, height = 64.dp, textStyle = PicoTheme.typography.labelLarge.copy(fontSize = 22.sp))
                }
                TextArea(
                    value = p.text,
                    onValueChange = { flow.pasteText(it) },
                    modifier = Modifier.fillMaxWidth().height(if (ringing(state)) 160.dp else 208.dp),
                    placeholder = { Txt(stringResource(R.string.paste_hint), "paste/hint", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2) },
                )
                Txt(stringResource(if (p.error) R.string.paste_err else R.string.paste_rule), "paste/rule", style = PicoTheme.typography.bodyMedium.copy(fontSize = 20.sp),
                    color = if (p.error) ChefColors.Danger else PicoTheme.colorScheme.labelSecondary, modifier = Modifier.fillMaxWidth().height(40.dp), maxLines = 1)
                if (ringing(state)) AmberBar(state, flow, zh)
                else BigButton(stringResource(R.string.paste_split), "paste/split", onClick = { flow.pasteSplit() }, modifier = Modifier.fillMaxWidth(), kind = Kind.PRIMARY, enabled = p.text.isNotBlank())
            }
            is Paste.Preview -> {
                Header(stringResource(R.string.preview_title), "preview", settings, flow) {
                    BigButton(stringResource(R.string.paste_close), "preview/close", onClick = { flow.pasteClose() }, icon = R.drawable.ic_close, height = 64.dp, textStyle = PicoTheme.typography.labelLarge.copy(fontSize = 22.sp))
                }
                val r = p.recipe
                Card(W.CARD + W.GAP + W.HEADER) {
                    Txt(r.title.zh, "preview/name", style = PicoTheme.typography.headlineMedium.copy(fontSize = 36.sp), color = ChefColors.CardInk, align = TextAlign.Center, maxLines = 1)
                    Txt(stringResource(R.string.preview_line, r.steps.size, r.timerCount), "preview/line", style = PicoTheme.typography.titleMedium.copy(fontSize = 26.sp), color = ChefColors.CardInk, align = TextAlign.Center)
                }
                if (ringing(state)) AmberBar(state, flow, zh)
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
                    BigButton(stringResource(R.string.paste_edit), "preview/edit", onClick = { flow.pasteEdit() }, modifier = Modifier.weight(1f))
                    BigButton(stringResource(R.string.paste_save), "preview/save", onClick = { flow.pasteSave() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_check, kind = Kind.PRIMARY)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ P2 Cook (S03 / S09)

@Composable
private fun CookPage(state: UiState, settings: Settings, flow: CookFlow, zh: Boolean) {
    val r: Recipe? = flow.current
    PageColumn {
        when {
            r == null -> {
                Header(stringResource(R.string.tab_cook), "cook", settings, flow)
                Card(W.CARD + W.ROW) {
                    Icon(painter = painterResource(R.drawable.ic_menu_book), contentDescription = null, tint = ChefColors.CardInk, modifier = Modifier.size(48.dp))
                    Txt(stringResource(R.string.cook_empty), "cook/empty", style = PicoTheme.typography.headlineSmall.copy(fontSize = 32.sp, lineHeight = 42.sp), color = ChefColors.CardInk, align = TextAlign.Center, maxLines = 2)
                }
                if (ringing(state)) AmberBar(state, flow, zh)
                else BigButton(stringResource(R.string.cook_go), "cook/go", onClick = { flow.page(Page.RECIPES) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_menu_book, kind = Kind.PRIMARY)
            }
            state.done -> {
                Header(r.title.of(zh), "done", settings, flow)
                Card(288.dp) {
                    Txt(stringResource(R.string.done_big), "done/big", style = PicoTheme.typography.displaySmall.copy(fontSize = 64.sp), color = ChefColors.CardInk, align = TextAlign.Center)
                    Txt(stringResource(R.string.done_line, r.title.of(zh), r.minutes), "done/line", style = PicoTheme.typography.bodyLarge.copy(fontSize = 22.sp), color = ChefColors.CardInk, align = TextAlign.Center, maxLines = 1)
                }
                if (ringing(state)) AmberBar(state, flow, zh, W.MAIN)
                else BigButton(stringResource(R.string.done_again), "done/again", onClick = { flow.again() }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_menu_book, kind = Kind.PRIMARY, height = W.MAIN)
            }
            else -> {
                val pages = flow.pages
                val pg = pages[state.pageIndex.coerceIn(0, pages.size - 1)]
                val steps = r.steps.size
                val sub = when {
                    pg.stepNo == 0 && pg.prepParts > 1 -> stringResource(R.string.cook_prep_part, pg.prepPart, pg.prepParts)
                    pg.stepNo == 0 -> stringResource(R.string.cook_prep)
                    else -> "${pg.stepNo}/$steps"
                }
                Header("${r.title.of(zh)} · $sub", "cook", settings, flow)
                Card(W.CARD) {
                    RichTxt(sentence(pg.text.of(zh)), "cook/step${pg.stepNo}", style = PicoTheme.typography.headlineLarge.copy(fontSize = W.SENTENCE, lineHeight = 52.sp), color = ChefColors.CardInk, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
                }
                val hasTimer = pg.timerSec != null
                if (hasTimer) {
                    val staged = state.timers.stagedSlot != null
                    val full = state.timers.allOut && !staged
                    BigButton(
                        when { staged -> stringResource(R.string.cook_cancel); full -> stringResource(R.string.cook_full); else -> timeLabel(pg.timerSec!!) },
                        "cook/timer", onClick = { flow.stepTimerButton() }, modifier = Modifier.fillMaxWidth(),
                        icon = if (staged) R.drawable.ic_close else R.drawable.ic_clock_fill, kind = if (staged) Kind.SECONDARY else Kind.PRIMARY, enabled = !full,
                    )
                }
                val rowH = if (hasTimer) W.ROW else W.MAIN
                if (ringing(state)) AmberBar(state, flow, zh, rowH)
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
                    Button(
                        onClick = { flow.prev() }, enabled = state.pageIndex > 0,
                        size = ButtonDefaults.Regular.copy(height = rowH, minWidth = 96.dp), shape = RoundedCornerShape(if (rowH > 112.dp) 40.dp else rowH / 2),
                        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
                    ) { Icon(painter = painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.cook_prev), tint = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.size(40.dp)) }
                    val last = state.pageIndex >= pages.size - 1
                    BigButton(
                        stringResource(if (last) R.string.cook_serve else R.string.cook_next), "cook/next", onClick = { flow.next() }, modifier = Modifier.weight(1f),
                        icon = if (last) R.drawable.ic_check else R.drawable.ic_chevron_right, kind = Kind.PRIMARY, height = rowH,
                        textStyle = PicoTheme.typography.headlineSmall.copy(fontSize = 32.sp, lineHeight = 42.sp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ P3 Settings (S10) / exit confirm (S11)

@Composable
private fun SettingsPage(state: UiState, settings: Settings, flow: CookFlow, zh: Boolean) {
    val volName = stringResource(listOf(R.string.vol_low, R.string.vol_mid, R.string.vol_high)[settings.volume.coerceIn(0, 2)])
    PageColumn {
        Header(stringResource(R.string.tab_settings), "settings", settings, flow)
        if (ringing(state)) AmberBar(state, flow, zh, 184.dp)
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
            BigButton(stringResource(R.string.vol_fmt, volName), "settings/volume", onClick = { flow.cycleVolume() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_volume_up, height = 184.dp, stacked = true)
            BigButton(stringResource(R.string.lang_btn), "settings/lang", onClick = { flow.toggleLang(zh) }, modifier = Modifier.weight(1f), icon = R.drawable.ic_language, height = 184.dp, stacked = true)
        }
        // D44: [叫回面前] and [退出] always on the first screen, never behind「更多」
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
            BigButton(stringResource(R.string.recall), "settings/recall", onClick = { flow.recall() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_my_location, height = 184.dp, stacked = true)
            BigButton(stringResource(R.string.exit), "settings/exit", onClick = { flow.exitAsk() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_logout, height = 184.dp, stacked = true)
        }
        Txt(stringResource(R.string.version_line, BuildConfig.VERSION_NAME), "settings/version", style = PicoTheme.typography.bodySmall.copy(fontSize = 18.sp, lineHeight = 22.sp), color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.heightIn(min = 24.dp))
    }
}

@Composable
private fun ExitPage(state: UiState, settings: Settings, flow: CookFlow) {
    PageColumn {
        Header(stringResource(R.string.exit), "exit", settings, flow)
        Card(288.dp) {
            Icon(painter = painterResource(R.drawable.ic_logout), contentDescription = null, tint = ChefColors.CardInk, modifier = Modifier.size(44.dp))
            Txt(stringResource(R.string.exit_q), "exit/q", style = PicoTheme.typography.headlineSmall.copy(fontSize = 32.sp, lineHeight = 42.sp), color = ChefColors.CardInk, align = TextAlign.Center, maxLines = 2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(W.GAP)) {
            BigButton(stringResource(R.string.exit_stay), "exit/stay", onClick = { flow.exitCancel() }, modifier = Modifier.weight(1f), height = W.MAIN)
            BigButton(stringResource(R.string.exit_go), "exit/go", onClick = { flow.exitConfirm() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_logout, kind = Kind.DANGER, height = W.MAIN)
        }
    }
}

/**
 * D26 / D44 exit: stop audio and trackers, close the window and the Stage (both containers), then remove every task
 * and end the process.
 */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    AppLog.i("EXIT release + close window + closeStage + finishAndRemoveTask + exitProcess")
    graph.window.exiting = true
    runCatching { graph.release() }
    runCatching { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) }.onFailure { AppLog.w("EXIT close window: $it") }
    runCatching { graph.window.closeStage?.invoke() }.onFailure { AppLog.w("EXIT close stage: $it") }
    val main = Handler(Looper.getMainLooper())
    main.postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        AppLog.i("EXIT finishAndRemoveTask")
        main.postDelayed({ exitProcess(0) }, 300)
    }, 400)
}

/** The native TabBar with labels already resolved in the app language (no string lookups inside). */
@Composable
private fun MainTabBar(tabs: List<Triple<Page, Int, String>>, current: Page, onPick: (Page) -> Unit) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = current == page,
                onClick = { onPick(page) },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
