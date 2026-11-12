package io.embodit.chefboard.domain.recipe

/** Text in both languages; a pasted recipe has one text in both. */
data class Tr(val zh: String, val en: String) {
    fun of(zhUi: Boolean) = if (zhUi) zh else en
}

/** A step: one sentence (≤ 2 lines at 40 sp), at most one timer button (the first time in it). */
data class Step(val text: Tr, val timerSec: Int? = null, val timerName: Tr? = null)

/** design dataModel.recipe; [icon] picks the card's Material Symbol. */
data class Recipe(
    val id: String,
    val title: Tr,
    val tag: Tr,
    val minutes: Int,
    val icon: String,
    val ingredients: List<Tr>,
    val steps: List<Step>,
    val pasted: Boolean = false,
) {
    val timerCount: Int get() = steps.count { it.timerSec != null }
}

/**
 * The cook page's pages: prep page(s) first (ingredient names only, ≤ 2 lines, split into「备料 1/2」「备料 2/2」when
 * they do not fit), then one page per step. [stepNo] is 1-based for real steps, 0 for prep.
 */
data class CookPage(val text: Tr, val stepNo: Int, val prepPart: Int = 0, val prepParts: Int = 0, val timerSec: Int? = null, val timerName: Tr? = null)

object CookPages {
    /** Characters that fit in two lines of the 40 sp sentence (content.stepRule: 中文 ≤ 28 字、英文 ≤ 50 字符). */
    const val ZH_MAX = 26
    const val EN_MAX = 48

    fun of(r: Recipe): List<CookPage> {
        val prep = prepPages(r)
        return prep + r.steps.mapIndexed { i, s -> CookPage(s.text, i + 1, timerSec = s.timerSec, timerName = s.timerName) }
    }

    private fun prepPages(r: Recipe): List<CookPage> {
        if (r.ingredients.isEmpty()) return emptyList()
        // greedily pack names so both languages stay within two lines
        val chunks = ArrayList<List<Tr>>()
        var cur = ArrayList<Tr>()
        fun zhLen(l: List<Tr>) = "备料：".length + l.joinToString("、") { it.zh }.length
        fun enLen(l: List<Tr>) = "Prep: ".length + l.joinToString(", ") { it.en }.length
        for (ing in r.ingredients) {
            val next = cur + ing
            if (cur.isNotEmpty() && (zhLen(next) > ZH_MAX || enLen(next) > EN_MAX)) { chunks += cur; cur = arrayListOf(ing) } else cur = ArrayList(next)
        }
        if (cur.isNotEmpty()) chunks += cur
        return chunks.mapIndexed { k, c ->
            CookPage(Tr("备料：" + c.joinToString("、") { it.zh }, "Prep: " + c.joinToString(", ") { it.en }), 0, k + 1, chunks.size)
        }
    }
}
