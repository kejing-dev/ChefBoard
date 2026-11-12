package io.embodit.chefboard.domain

import io.embodit.chefboard.data.RecipeCodec
import io.embodit.chefboard.domain.cook.CookFlow
import io.embodit.chefboard.domain.cook.FlowEffects
import io.embodit.chefboard.domain.cook.Page
import io.embodit.chefboard.domain.cook.Paste
import io.embodit.chefboard.domain.cook.Settings
import io.embodit.chefboard.domain.recipe.Parser
import io.embodit.chefboard.domain.recipe.Recipe
import io.embodit.chefboard.domain.timer.Ev
import io.embodit.chefboard.domain.timer.TState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookFlowTest {
    private var now = 5_000L
    private var exited = false
    private val events = ArrayList<Ev>()
    private var saved: List<Recipe> = emptyList()
    private val f = CookFlow(object : FlowEffects {
        override fun events(evs: List<Ev>) { events += evs }
        override fun saveSettings(s: Settings) {}
        override fun savePasted(list: List<Recipe>) { saved = list }
        override fun saveProgress(recipeId: String?, pageIndex: Int) {}
        override fun uiClick() {}
        override fun recall() {}
        override fun exit() { exited = true }
        override fun log(line: String) {}
        override fun now(): Long = now
    })

    init { f.start(Settings(), emptyList(), null, 0) }

    @Test fun pageStates() {
        assertEquals("S00", f.s.stateId)
        f.loaded(); assertEquals("S01", f.s.stateId)
        f.openRecipe("R4"); assertEquals("S03", f.s.stateId); assertEquals(0, f.s.pageIndex)
        repeat(20) { f.next() }
        assertEquals("S09", f.s.stateId)
        f.again(); assertEquals("S01", f.s.stateId)
        f.page(Page.SETTINGS); assertEquals("S10", f.s.stateId)
        f.exitAsk(); assertEquals("S11", f.s.stateId)
        f.exitCancel(); assertEquals("S10", f.s.stateId)
        f.exitAsk(); f.exitConfirm(); assertTrue(exited)
    }

    /** P1: 3 cards + [更多] per page, no scrolling; the last cell is [＋ 粘贴]; ≤ 7 tappable with 3 tabs. */
    @Test fun gridPages() {
        assertEquals(3, f.gridPageCount)
        assertEquals(listOf("R1", "R2", "R3"), f.gridPageCells(0))
        assertEquals(listOf("R7", "R8", null), f.gridPageCells(2))
        f.more(); f.more(); f.more(); assertEquals(0, f.s.gridPage)
        assertTrue(3 + 3 + 1 <= 7)
    }

    /** The cook page [⏱ 10 分] floats a timer named after the step; pressing it again (now [取消]) sends it back. */
    @Test fun stepTimerButton() {
        f.loaded(); f.openRecipe("R4")
        val idx = f.pages.indexOfFirst { it.timerSec == 600 }
        f.gotoStep(idx)
        f.stepTimerButton()
        assertEquals(0, f.s.timers.stagedSlot)
        assertEquals("煮面", f.board.staged!!.label!!.zh)
        assertEquals(600, f.board.staged!!.shownSeconds(now))
        now += 400; f.tick()
        f.stepTimerButton()
        assertEquals(TState.RETURNING, f.board.timers[0].state)
    }

    /** M15: the window's amber bar lists ringing timers and stops them all. */
    @Test fun amberBar() {
        f.loaded()
        f.board.pick(0, 60); now += 400; f.tick()
        f.tapSpot(1, io.embodit.chefboard.domain.place.Layout.spotCenter(1)); now += 700; f.tick()
        now += 61_000; f.tick()
        assertEquals(1, f.s.timers.ringing.size)
        f.amberStop()
        assertTrue(f.s.timers.ringing.isEmpty())
        assertTrue(events.any { it is Ev.Stopped })
    }

    /** S02: paste → split → preview → save; the new card is first on page 1; a bad paste stays with the error. */
    @Test fun pasteFlow() {
        f.loaded(); f.openPaste(); assertEquals("S02", f.s.stateId)
        f.pasteText("只有一行"); f.pasteSplit()
        assertTrue((f.s.paste as Paste.Input).error)
        f.pasteText(Parser.SAMPLE); f.pasteSplit()
        val pv = f.s.paste as Paste.Preview
        assertEquals(6, pv.recipe.steps.size); assertEquals(3, pv.recipe.timerCount)
        f.pasteEdit(); assertEquals(Parser.SAMPLE, (f.s.paste as Paste.Input).text)
        f.pasteSplit()
        val id = (f.s.paste as Paste.Preview).recipe.id
        f.pasteSave()
        assertEquals("S01", f.s.stateId)
        assertEquals(id, f.gridPageCells(0).first())
        assertEquals(1, saved.size)
        // the stored JSON round-trips
        val back = RecipeCodec.decode(RecipeCodec.encode(saved))
        assertEquals(saved, back)
    }

    @Test fun settingsCycle() {
        assertEquals(1, f.settings.value.volume)
        f.cycleVolume(); assertEquals(2, f.settings.value.volume)
        f.cycleVolume(); assertEquals(0, f.settings.value.volume)
        assertFalse(f.settings.value.muted); f.toggleMute(); assertTrue(f.settings.value.muted)
        f.toggleLang(zhNow = true); assertNotNull(f.settings.value.lang)
    }
}
