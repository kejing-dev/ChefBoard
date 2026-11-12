package io.embodit.chefboard.domain

import io.embodit.chefboard.domain.recipe.Builtin
import io.embodit.chefboard.domain.recipe.CookPages
import io.embodit.chefboard.domain.recipe.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTest {
    /** design content.recipes: step counts and timers per recipe. */
    @Test fun builtinsMatchTheDesign() {
        val steps = mapOf("R1" to 4, "R2" to 6, "R3" to 5, "R4" to 7, "R5" to 6, "R6" to 5, "R7" to 5, "R8" to 7)
        val timers = mapOf("R1" to listOf(390, 120), "R2" to listOf(120), "R3" to listOf(720, 600), "R4" to listOf(600, 480),
            "R5" to listOf(480, 120), "R6" to listOf(240, 180, 180), "R7" to listOf(600, 120), "R8" to listOf(180, 1200))
        assertEquals(8, Builtin.all.size)
        for (r in Builtin.all) {
            assertEquals(r.id, steps[r.id], r.steps.size)
            assertEquals(r.id, timers[r.id], r.steps.mapNotNull { it.timerSec })
        }
    }

    /** M8 / content.stepRule: every page ≤ 2 lines (中文 ≤ 26 字, English ≤ 48 chars), prep pages too. */
    @Test fun everyPageFitsTwoLines() {
        for (r in Builtin.all) for (p in CookPages.of(r)) {
            assertTrue("${r.id} zh: ${p.text.zh}", Parser.visibleLength(p.text.zh) <= CookPages.ZH_MAX)
            assertTrue("${r.id} en: ${p.text.en}", Parser.visibleLength(p.text.en) <= CookPages.EN_MAX)
        }
    }

    /** The amber {time} in each sentence is the step's [⏱] (zh and en parse to the same seconds). */
    @Test fun markedTimesAreTheTimers() {
        for (r in Builtin.all) for (s in r.steps) {
            val zh = Parser.findTimes(s.text.zh.replace("{", "").replace("}", "")).firstOrNull()?.seconds
            val en = Parser.findTimes(s.text.en.replace("{", "").replace("}", "")).firstOrNull()?.seconds
            assertEquals("${r.id} zh ${s.text.zh}", s.timerSec, zh)
            assertEquals("${r.id} en ${s.text.en}", s.timerSec, en)
            assertEquals(s.timerSec != null, s.text.zh.contains('{'))
        }
    }

    @Test fun numbersAndRanges() {
        assertEquals(25.0, Parser.number("二十五")!!, 0.0)
        assertEquals(10.0, Parser.number("十")!!, 0.0)
        assertEquals(12.0, Parser.number("十二")!!, 0.0)
        assertEquals(2.0, Parser.number("两")!!, 0.0)
        assertEquals(600, Parser.findTimes("小火炖8–10分钟").single().seconds)
        assertEquals(90 * 60, Parser.findTimes("炖一个半小时").single().seconds)
        assertEquals(30 * 60, Parser.findTimes("腌半小时").single().seconds)
        assertEquals(390, Parser.findTimes("煮六分半").single().seconds)
        assertEquals(45, Parser.findTimes("煎45秒").single().seconds)
        assertEquals(3600, Parser.findTimes("bake for 1 hour").single().seconds)
        assertEquals(30 * 60, Parser.findTimes("rest half an hour").single().seconds)
        assertEquals(15 * 60, Parser.findTimes("simmer 10-15 minutes").single().seconds)
        assertEquals(3 * 60, Parser.findTimes("three more minutes").single().seconds)
        assertTrue(Parser.findTimes("2 个鸡蛋，1 勺盐").isEmpty())
    }

    /** M11: the sample paste → 6 steps, 3 timers, no step over 2 lines; prep names kept. */
    @Test fun pasteSample() {
        val r = (Parser.parse(Parser.SAMPLE, "P1") as Parser.Result.Ok).recipe
        assertEquals("外婆的红烧排骨", r.title.zh)
        assertEquals(6, r.steps.size)
        assertEquals(listOf(180, 2400, 300), r.steps.mapNotNull { it.timerSec })
        assertEquals("第 1 步", r.steps[0].timerName!!.zh)
        assertTrue(r.ingredients.map { it.zh }.containsAll(listOf("排骨", "冰糖", "姜")))
        assertTrue(r.steps.all { Parser.fitsTwoLines(it.text.zh) })
    }

    @Test fun pasteVariants() {
        val en = Parser.parse("Garlic noodles\nnoodles, garlic, butter\nStep 1: Boil the noodles for 8 minutes.\nStep 2: Melt butter, fry garlic 2 min.\nStep 3: Toss and serve.") as Parser.Result.Ok
        assertEquals(3, en.recipe.steps.size); assertEquals(listOf(480, 120), en.recipe.steps.mapNotNull { it.timerSec })
        val plain = Parser.parse("煎蛋\n锅里放油\n打一个蛋，煎2分钟\n出锅") as Parser.Result.Ok
        assertEquals(3, plain.recipe.steps.size)
        val cn = Parser.parse("凉拌黄瓜\n一、黄瓜拍碎\n二、加蒜和醋，腌十分钟") as Parser.Result.Ok
        assertEquals(listOf(600), cn.recipe.steps.mapNotNull { it.timerSec })
        assertTrue(Parser.parse("只有一行") is Parser.Result.NoSteps)
        // a long step is split at sentence ends so no step exceeds two lines
        val long = Parser.parse("长菜\n1. 把鸡腿洗干净擦干水分。用刀在两面各划三刀，方便入味。加盐和黑胡椒抓匀，腌二十分钟。") as Parser.Result.Ok
        assertTrue(long.recipe.steps.size >= 2)
        assertTrue(long.recipe.steps.all { Parser.fitsTwoLines(it.text.zh) })
        assertNotNull(long.recipe.steps.firstOrNull { it.timerSec == 1200 })
    }

    @Test fun prepPagesSplitWhenLong() {
        val r4 = Builtin.byId("R4")!!
        val pages = CookPages.of(r4)
        assertEquals(0, pages.first().stepNo)
        assertEquals(r4.steps.size, pages.count { it.stepNo > 0 })
    }
}
