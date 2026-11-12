package io.embodit.chefboard.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.chefboard.R
import io.embodit.chefboard.platform.AppLog
import io.embodit.chefboard.platform.Graph
import io.embodit.chefboard.ui.Containers
import io.embodit.chefboard.ui.stage.input.BoardInputListener
import io.embodit.chefboard.ui.stage.input.InputSource
import io.embodit.chefboard.ui.stage.input.TargetResolver
import io.embodit.chefboard.ui.stage.input.boardInput
import io.embodit.chefboard.ui.theme.localized
import kotlinx.coroutines.launch

/** The Mixed Stage: one full-size SpatialView with the rack, timers, rings and buttons; the HUD is the only attachment. */
@Composable
fun StageScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val navigator = LocalSpatialNavigator.current
    val scene = remember(graph) { StageScene(graph, context.applicationContext) }
    val stageScope = rememberCoroutineScope()

    DisposableEffect(scene) {
        registerSystem<ChefFrameSystem>()
        graph.window.attach(
            open = {
                if (graph.window.panelState == "ON_STOP") runCatching { navigator.restoreWindowContainer(Containers.MAIN, Containers.MAIN_TAG) }.onFailure { AppLog.w("WINDOW restore: $it") }
                navigator.openWindowContainer(Containers.MAIN, Containers.MAIN_TAG, android.os.Bundle())
            },
            close = { navigator.closeWindowContainer(Containers.MAIN, Containers.MAIN_TAG) },
        )
        graph.window.onClosed = { graph.flow.windowClosed() }
        graph.window.closeStage = { stageScope.launch { runCatching { navigator.closeStage() } } }
        AppLog.i("STAGE enter")
        onDispose {
            unregisterSystem<ChefFrameSystem>()
            graph.window.detach()
            scene.dispose()
            AppLog.i("STAGE disposed")
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                // timers keep their elapsedRealtime end; coming back recomputes and a timer that ended rings at once (M10)
                Lifecycle.Event.ON_STOP -> AppLog.i("LIFECYCLE background timers=${graph.flow.board.summary()}")
                Lifecycle.Event.ON_START -> { AppLog.i("LIFECYCLE foreground timers=${graph.flow.board.summary()}"); graph.flow.tick() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    SpatialView(
        modifier = Modifier.fillMaxSize().boardInput(
            TargetResolver.byName(Names::resolve),
            object : BoardInputListener<Hit> {
                override fun onTap(target: Hit?, source: InputSource, position: com.pico.spatial.ui.geometry.Offset3D?) = scene.onTap(target, source)
                // the system HoverEffect on timers / rings / 3D buttons / the plate is the visible feedback (D50: hover never changes state)
                override fun onHover(target: Hit?, source: InputSource) = Unit
            },
        ),
        attachments = { PanelIds.all.forEach { id -> AttachmentPanel(id) { HudRoot(scene, graph) } } },
        initial = { content, attachments -> scene.attach(content, attachments) },
        update = { _, attachments -> scene.bind(attachments) },
    )
}

@Composable
private fun HudRoot(scene: StageScene, graph: Graph) {
    val base = LocalContext.current
    val s by graph.flow.settings.collectAsStateWithLifecycle()
    val shown = scene.panelShown[PanelIds.HUD] == true
    key(s.lang) {
        CompositionLocalProvider(LocalContext provides base.localized(s.lang)) {
            PicoTheme { if (!shown) Box(Modifier.size(8.dp)) else Hud(scene) }
        }
    }
}

@Composable
private fun HudText(text: String, tag: String) {
    androidx.compose.foundation.text.BasicText(
        text, style = TextStyle(fontSize = 24.sp, color = Color(0xFF1B1F24), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), maxLines = 2,
        onTextLayout = { if (it.hasVisualOverflow) AppLog.w("OVERFLOW hud/$tag \"$text\"") },
    )
}

/** S00「准备中…」, the level gate「平视前方」, recall「看向灶台那边 · 停一下」, window target「菜谱窗在这 · 看过来」. */
@Composable
private fun Hud(scene: StageScene) {
    val msg = scene.hud.value
    Box(Modifier.size(420.dp, 110.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xF2FBF8F2)).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        when (msg) {
            HudMsg.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Box(Modifier.width(12.dp))
                HudText(stringResource(R.string.loading), "loading")
            }
            HudMsg.LEVEL -> HudText(stringResource(R.string.level_hint), "level")
            HudMsg.LOOK_WINDOW -> HudText(stringResource(R.string.look_window), "lookWindow")
            HudMsg.LOOK_STOVE -> HudText(stringResource(R.string.look_stove), "lookStove")
            HudMsg.NONE -> Unit
        }
    }
}
