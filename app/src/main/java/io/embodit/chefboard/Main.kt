package io.embodit.chefboard

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.ui.Containers
import io.embodit.chefboard.ui.stage.StageScreen
import io.embodit.chefboard.ui.theme.ChefTheme
import io.embodit.chefboard.ui.window.MainWindowUi

/**
 * design container STAGE_MIXED: one Mixed Stage (manifest pico.spatial.stage.style = 1: passthrough, Full Space) holds the
 * rack, the four 3D timers, the drop rings and the off-view arrow. All 2D UI lives in one Planar WindowContainer
 * 640 × 560 dp with the native TabBar (菜谱 / 做菜 / 设置), opened from the Stage through the level gate (WindowCtl).
 * Dragging / closing are the system window controls (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { ChefTheme { StageScreen() } }
        WindowContainer(
            Containers.MAIN,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = Layout.WINDOW_W_DP.dp, height = Layout.WINDOW_H_DP.dp),
        ) {
            ChefTheme { Box(Modifier.windowConstraints(minWidth = Layout.WINDOW_W_DP.dp, minHeight = Layout.WINDOW_H_DP.dp, maxWidth = 760.dp, maxHeight = 680.dp)) { MainWindowUi() } }
        }
    }
