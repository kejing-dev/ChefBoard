package io.embodit.chefboard.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.chefboard.domain.cook.Lang
import io.embodit.chefboard.domain.cook.Page
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.recipe.Parser
import io.embodit.chefboard.domain.timer.Label
import io.embodit.chefboard.domain.timer.TState
import io.embodit.chefboard.ui.stage.ActiveScene
import io.embodit.chefboard.ui.stage.Button3D
import io.embodit.chefboard.ui.stage.Hit
import io.embodit.chefboard.ui.stage.input.InputSource

/**
 * Debug builds only (design verification.emulator). Every command goes through the same flow / scene entry as real
 * input (window buttons call the flow, 3D taps go through StageScene.onTap like a system tap).
 *
 *   adb shell am broadcast -a io.embodit.chefboard.DEBUG -p io.embodit.chefboard --es cmd <cmd> [--es a "<arg>"]
 *
 *   state S00|S01|S02|S03|S04|S05|S05p|S06|S06s|S09|S10|S11  (states[] shortcuts, see AGENTS.md)
 *   page recipes|cook|settings · more · recipe R4 [stepNo] · step N (stepNo, 0 = prep) · next · prev · finish · again
 *   stage SEC (the cook page [⏱] path with SEC seconds) · cancel · tap plus1|plus5|clear|pplus1|back|plate|timer N|spot N
 *   timers N (N timers placed on rings 1,3,0,4 running) · pause SLOT · ring SLOT · stop (amber bar) · freeze on|off
 *   paste sample|<text> · split · save · edit · pasteclose · mute on|off · volume 0-2 · lang zh|en|system
 *   recall · exitask · exitcancel · exit · closewindow · openwindow · wipe · dump · where
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val board = flow.board
        val scene = ActiveScene.scene
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val p = a.split(' ', ',').filter { it.isNotBlank() }
        val on = a == "on" || a == "true" || a == "1"
        AppLog.i("CMD $cmd $a")
        fun n(i: Int) = p.getOrNull(i)?.toIntOrNull()
        fun tap(h: Hit) = scene?.onTap(h, InputSource.UNKNOWN)
        fun placeTimers(count: Int) {
            val spots = listOf(1, 3, 0, 4)
            val names = listOf(Label("煮面", "Pasta"), Label("酱汁", "Sauce"), Label("计时器 3", "Timer 3"), Label("计时器 4", "Timer 4"))
            val secs = listOf(372, 195, 540, 75)
            for (k in 0 until count.coerceIn(0, 4)) {
                if (board.timers[k].state != TState.DOCKED) continue
                val w = scene?.spotWorld(spots[k]) ?: continue
                board.placeRunning(k, spots[k], w, secs[k], names[k])
            }
            flow.tick()
        }
        when (cmd) {
            "state" -> when (p.getOrNull(0)?.uppercase() ?: "") {
                "S00" -> { scene?.forceLoading = true; graph.window.closeOnly("debug S00") }
                "S01" -> { scene?.forceLoading = false; flow.page(Page.RECIPES); flow.pasteClose() }
                "S02" -> { flow.openPaste() }
                "S03" -> { flow.openRecipe("R4"); flow.gotoStepNo(n(1) ?: 3) }
                "S04" -> { flow.openRecipe("R4"); flow.gotoStepNo(3); flow.stepTimerButton() }
                "S05" -> placeTimers(2)
                "S05P" -> { placeTimers(2); tap(Hit.TimerHit(0)) }
                "S06", "S07" -> { placeTimers(1); board.expire(0); flow.tick() }
                "S06S" -> { placeTimers(1); board.expire(0); flow.tick(); flow.amberStop(); graph.freeze(true) }
                "S09" -> { flow.openRecipe("R4"); flow.gotoStep(99); flow.next() }
                "S10" -> flow.page(Page.SETTINGS)
                "S11" -> flow.exitAsk()
                else -> AppLog.i("CMD state: unknown $a")
            }
            "page" -> flow.page(when (a) { "cook" -> Page.COOK; "settings" -> Page.SETTINGS; else -> Page.RECIPES })
            "more" -> flow.more()
            "recipe" -> { flow.openRecipe(p.getOrNull(0) ?: "R4"); n(1)?.let { flow.gotoStepNo(it) } }
            "step" -> flow.gotoStepNo(n(0) ?: 0)
            "next" -> flow.next()
            "prev" -> flow.prev()
            "finish" -> { flow.gotoStep(99); flow.next() }
            "again" -> flow.again()
            "stage" -> {
                val sec = n(0) ?: 600
                if (board.staged != null) flow.board.stepTimer(sec, null) else { flow.board.stepTimer(sec, Label("煮面", "Pasta")) }
                flow.tick()
            }
            "cancel" -> flow.stepTimerButton()
            "tap" -> when (p.getOrNull(0)) {
                "plus1" -> tap(Hit.Btn(Button3D.PLUS1)); "plus5" -> tap(Hit.Btn(Button3D.PLUS5)); "clear" -> tap(Hit.Btn(Button3D.CLEAR))
                "pplus1" -> tap(Hit.Btn(Button3D.P_PLUS1)); "back" -> tap(Hit.Btn(Button3D.P_BACK)); "plate" -> tap(Hit.Plate)
                "timer" -> tap(Hit.TimerHit(n(1) ?: 0)); "spot" -> tap(Hit.Spot(n(1) ?: 2))
                else -> Unit
            }
            "timers" -> placeTimers(n(0) ?: 2)
            "pause" -> tap(Hit.TimerHit(n(0) ?: 0))
            "ring" -> { board.expire(n(0) ?: 0); flow.tick() }
            "stop" -> flow.amberStop()
            "freeze" -> graph.freeze(on)
            "paste" -> { flow.openPaste(); flow.pasteText(if (a == "sample" || a.isEmpty()) Parser.SAMPLE else a.replace("\\n", "\n")) }
            "split" -> flow.pasteSplit()
            "save" -> flow.pasteSave()
            "edit" -> flow.pasteEdit()
            "pasteclose" -> flow.pasteClose()
            "mute" -> flow.setMuted(on)
            "volume" -> flow.setVolume(n(0) ?: 1)
            "lang" -> flow.setLang(when (a) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.SYSTEM })
            "recall" -> flow.recall()
            "exitask" -> flow.exitAsk()
            "exitcancel" -> flow.exitCancel()
            "howto" -> if (a == "close") flow.howToClose() else flow.howToOpen()
            "exit" -> flow.exitConfirm()
            "closewindow" -> graph.window.close()
            "openwindow" -> graph.window.request("debug", null)
            "wipe" -> graph.wipe()
            "dump" -> AppLog.i("DUMP state=${flow.s.stateId} page=${flow.s.page} recipe=${flow.s.recipeId} step=${flow.s.pageIndex} settings=${flow.settings.value} ${scene?.dumpLine()} dp=${Layout.mPerDp} dist=${Layout.windowDist}")
            "where" -> AppLog.i("WHERE ${scene?.whereLine()}")
            "food" -> AppLog.i("FOODDUMP ${scene?.foodLine()}")
            "foodsolo" -> { val parts = a.trim().split(" "); scene?.foodSolo(parts[0].takeIf { it.isNotEmpty() && it != "off" }, parts.getOrNull(1)?.toFloatOrNull() ?: 0f); AppLog.i("FOODSOLO $a") }
            else -> AppLog.i("CMD unknown $cmd")
        }
    }
}
