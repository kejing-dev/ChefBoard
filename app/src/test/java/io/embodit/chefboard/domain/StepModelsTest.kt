package io.embodit.chefboard.domain

import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.recipe.Builtin
import io.embodit.chefboard.domain.recipe.CookPages
import io.embodit.chefboard.domain.recipe.Parser
import io.embodit.chefboard.domain.recipe.StepModels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** v0.1.4 E7 step food (门 C「每一步都要有实体的食物模型」, profile B12 步骤模型). */
class StepModelsTest {
    private val dir = File("src/main/assets/models/food")

    /** The GLB as the app ships it: header, JSON chunk, meshes, triangle count, POSITION bounds, alpha modes. */
    private data class Glb(val tris: Int, val min: FloatArray, val max: FloatArray, val blend: Boolean, val meshes: Int)

    private fun readGlb(f: File): Glb {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("${f.name} magic", 0x46546C67, b.getInt(0))
        assertEquals("${f.name} version", 2, b.getInt(4))
        assertEquals("${f.name} length", f.length().toInt(), b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals("${f.name} JSON chunk", 0x4E4F534A, b.getInt(16))
        val j = JSONObject(String(f.readBytes(), 20, jsonLen, Charsets.UTF_8))
        val acc = j.getJSONArray("accessors")
        val meshes = j.getJSONArray("meshes")
        var tris = 0
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        for (i in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(i).getJSONArray("primitives")
            for (k in 0 until prims.length()) {
                val p = prims.getJSONObject(k)
                tris += if (p.has("indices")) acc.getJSONObject(p.getInt("indices")).getInt("count") / 3
                else acc.getJSONObject(p.getJSONObject("attributes").getInt("POSITION")).getInt("count") / 3
                val pos = acc.getJSONObject(p.getJSONObject("attributes").getInt("POSITION"))
                for (a in 0 until 3) { mn[a] = minOf(mn[a], pos.getJSONArray("min").getDouble(a).toFloat()); mx[a] = maxOf(mx[a], pos.getJSONArray("max").getDouble(a).toFloat()) }
            }
        }
        val mats = j.optJSONArray("materials")
        val blend = mats != null && (0 until mats.length()).any { mats.getJSONObject(it).optString("alphaMode", "OPAQUE") == "BLEND" }
        return Glb(tris, mn, mx, blend, meshes.length())
    }

    @Test fun everyBuiltinPageHasModelsFromTheCatalog() {
        var pages = 0
        for (r in Builtin.all) {
            assertEquals(r.id, r.steps.size, StepModels.steps[r.id]?.size)
            for (p in CookPages.of(r)) {
                val ids = StepModels.forPage(r, p)
                assertTrue("${r.id}/${p.stepNo} has a model", ids.isNotEmpty())
                assertTrue("${r.id}/${p.stepNo} ≤ 6 models", ids.size <= 6)
                ids.forEach { assertNotNull("${r.id}/${p.stepNo} → $it in catalog", StepModels.model(it)) }
                if (p.stepNo > 0) assertFalse("${r.id}/${p.stepNo} is a real model, not the basket", StepModels.BASKET in ids)
                pages++
            }
        }
        assertTrue(pages >= 45 + 8)
    }

    /** Prep pages show every ingredient of the dish (each ingredient maps to a model). */
    @Test fun prepShowsEveryIngredient() {
        for (r in Builtin.all) {
            r.ingredients.forEach { assertNotNull("${r.id} ingredient ${it.zh}", StepModels.ingredient[it.zh]) }
            val prep = CookPages.of(r).first { it.stepNo == 0 }
            assertEquals(r.id, r.ingredients.map { StepModels.ingredient[it.zh] }.distinct(), StepModels.forPage(r, prep))
        }
    }

    @Test fun pastedRecipeGetsTheBasket() {
        val r = (Parser.parse(Parser.SAMPLE, "P1") as Parser.Result.Ok).recipe
        for (p in CookPages.of(r)) assertEquals(listOf(StepModels.BASKET), StepModels.forPage(r, p))
    }

    /** Every model a page can show exists as a loadable, calibrated GLB (D25) and in index.json. */
    @Test fun everyModelIsAnExistingCalibratedGlb() {
        val index = JSONObject(File(dir, "index.json").readText())
        val used = Builtin.all.flatMap { StepModels.forRecipe(it) }.toSet() + StepModels.BASKET
        assertEquals("catalog = used", StepModels.catalog.map { it.id }.toSet(), used)
        for (m in StepModels.catalog) {
            val f = File(dir, "${m.id}.glb")
            assertTrue("${f.path} exists", f.isFile)
            val g = readGlb(f)
            assertTrue("${m.id} meshes", g.meshes >= 1)
            assertTrue("${m.id} ≤ 15k triangles (${g.tris})", g.tris in 500..15000)
            assertFalse("${m.id} OPAQUE", g.blend)
            val ext = FloatArray(3) { g.max[it] - g.min[it] }
            assertEquals("${m.id} bottom at 0", 0f, g.min[1], 0.01f)
            assertEquals("${m.id} centre x", 0f, (g.min[0] + g.max[0]) / 2, 0.01f)
            assertEquals("${m.id} centre z", 0f, (g.min[2] + g.max[2]) / 2, 0.01f)
            assertEquals("${m.id} real size", m.sizeM, ext.max(), m.sizeM * 0.1f)
            assertTrue("${m.id} longest horizontal side along x", ext[0] >= ext[2] * 0.85f)
            val e = index.getJSONObject(m.id)
            assertEquals("${m.id} index w", ext[0], e.getDouble("w").toFloat(), 0.005f)
            assertEquals("${m.id} index h", ext[1], e.getDouble("h").toFloat(), 0.005f)
        }
        // the brief's scale anchor: an egg ≈ 5.5 cm
        assertEquals(0.055f, StepModels.model("egg")!!.sizeM, 0.005f)
    }

    private fun feet(): Map<String, Layout.Foot> {
        val f = File(dir, "index.json")
        if (!f.isFile) return StepModels.catalog.associate { it.id to Layout.Foot(it.sizeM, it.sizeM, it.sizeM) }
        val j = JSONObject(f.readText())
        return StepModels.catalog.associate { m ->
            m.id to (j.optJSONObject(m.id)?.let { Layout.Foot(it.getDouble("w").toFloat(), it.getDouble("d").toFloat(), it.getDouble("h").toFloat()) } ?: Layout.Foot(m.sizeM, m.sizeM, m.sizeM))
        }
    }

    /**
     * D20 / D35: on every page the food stands on the counter, does not overlap itself, the rack or a timer, and no
     * sight line from the eye to the window, the rack, the floating timer + buttons or a timer on any ring passes through it.
     */
    @Test fun foodNeverCoversTheWindowTimersOrButtons() {
        val ft = feet()
        val eye = Vec3.ZERO
        val targets = ArrayList<Pair<String, List<Vec3>>>()
        targets += "window" to Layout.windowRect().corners
        targets += "rack" to boxCorners(Layout.rackBox())
        targets += "stage" to boxCorners(Layout.stageBox())
        for (i in 0 until 5) targets += "ring$i" to boxCorners(Layout.spotTimerBox(i))
        val solids = listOf(Layout.rackBox(), Layout.stageBox()) + (0 until 5).map { Layout.spotTimerBox(it) }
        for (r in Builtin.all) for (p in CookPages.of(r)) {
            val ids = StepModels.forPage(r, p)
            val fs = ids.map { ft.getValue(it) }
            val spots = Layout.foodSpots(fs)
            val boxes = spots.zip(fs).map { (b, f) -> Layout.foodBox(b, f) }
            spots.forEach { assertEquals("${r.id}/${p.stepNo} on the counter", Layout.FOOD_Y, it.y, 1e-4f) }
            for ((name, corners) in targets) for (c in corners) for ((k, b) in boxes.withIndex())
                assertFalse("${r.id}/${p.stepNo} ${ids[k]} covers $name", b.hitBy(eye, c))
            for ((k, b) in boxes.withIndex()) for (s in solids) for (c in boxCorners(b)) assertFalse("${r.id}/${p.stepNo} ${ids[k]} inside a timer / rack", s.contains(c))
            for (a in boxes.indices) for (b in boxes.indices) if (a < b) for (c in boxCorners(boxes[a])) assertFalse("${r.id}/${p.stepNo} ${ids[a]} overlaps ${ids[b]}", shrink(boxes[b]).contains(c))
            // seen from the eye, below the window's caption bar and left of the rack's left end
            for ((k, b) in boxes.withIndex()) {
                val top = boxCorners(b).maxOf { Layout.elOf(it) }
                assertTrue("${r.id}/${p.stepNo} ${ids[k]} top ${top}° below the window bottom", top < Layout.elOf(Layout.windowRect().corners[0]) - 2f)
            }
        }
    }

    private fun shrink(b: Layout.Box) = b.copy(hx = b.hx * 0.98f, hy = b.hy * 0.98f, hz = b.hz * 0.98f)

    private fun boxCorners(b: Layout.Box): List<Vec3> = buildList {
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) add(b.center + b.ax * (sx * b.hx) + b.ay * (sy * b.hy) + b.az * (sz * b.hz))
    }

    /** apps/chef-board/build/step-models.md source: 菜谱 / 步骤 → 模型文件 → Tripo 提示词 (written to app/build). */
    @Test fun writeStepModelsTable() {
        val ft = feet()
        val sb = StringBuilder("# ChefBoard v0.1.4 · 每步食物模型（E7）\n\n")
        sb.append("由 `StepModelsTest.writeStepModelsTable` 从 `StepModels.kt` 生成。模型文件都在 `app/src/main/assets/models/food/`，原始 Tripo GLB 在 `~/Downloads/chef-board-tripo/`。\n\n")
        sb.append("## 菜谱 / 步骤 → 模型\n\n| 菜谱 | 步骤 | 文案 | 模型文件 |\n|---|---|---|---|\n")
        for (r in Builtin.all) for (p in CookPages.of(r)) {
            val ids = StepModels.forPage(r, p)
            sb.append("| ${r.id} ${r.title.zh} | ${if (p.stepNo == 0) "备料" else p.stepNo.toString()} | ${p.text.zh} | ${ids.joinToString("、") { "`${it}.glb`" }} |\n")
        }
        sb.append("| 粘贴的菜谱 | 每一步 | — | `basket.glb` |\n")
        sb.append("\n## 模型 → Tripo 提示词\n\n| 模型文件 | 真实尺寸（最长边） | 用在 | Tripo 提示词 |\n|---|---|---|---|\n")
        for (m in StepModels.catalog) {
            val uses = Builtin.all.flatMap { r -> CookPages.of(r).filter { m.id in StepModels.forPage(r, it) }.map { "${r.id}·${if (it.stepNo == 0) "备料" else it.stepNo}" } }.distinct()
            val f = ft[m.id]
            sb.append("| `${m.id}.glb` | ${"%.1f".format(m.sizeM * 100)} cm${f?.let { " (宽 %.1f × 高 %.1f)".format(it.w * 100, it.h * 100) } ?: ""} | ${uses.joinToString(" ").ifEmpty { "粘贴的菜谱" }} | ${m.prompt} |\n")
        }
        File("build").mkdirs()
        File("build/step-models.md").writeText(sb.toString())
        assertTrue(abs(sb.length) > 100)
    }
}
