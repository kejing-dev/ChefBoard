package io.embodit.chefboard.domain.recipe

/**
 * Pasted recipe text → steps + times (design dataModel.parseTimes, content.importOwn), offline, pure Kotlin.
 *  - first line = the name; lines before the first numbered step = prep (ingredient names);
 *  - steps split at「1. / 1、/ 一、/ Step 1 / 第一步」or, with no numbers at all, one step per line;
 *  - a step longer than two lines is split at sentence ends; at most 30 steps;
 *  - times:「N 分钟 / N 分 / N 秒 / 半小时 / 一个半小时 / 1 小时」「N min / sec / hr / an hour」, ranges「8–10 分钟」take
 *    the larger, Chinese numerals 一…九十九 → digits; every time is wrapped in {braces} (drawn amber), the first one in a
 *    step becomes its [⏱] named「第 n 步」/「Step n」.
 */
object Parser {
    const val MAX_STEPS = 30

    data class TimeHit(val start: Int, val end: Int, val seconds: Int)

    sealed class Result {
        data class Ok(val recipe: Recipe) : Result()
        data object NoSteps : Result()
    }

    private val CN = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val EN = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0,
        "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0,
        "thirty" to 30.0, "forty" to 40.0, "forty-five" to 45.0, "fifty" to 50.0, "sixty" to 60.0, "ninety" to 90.0,
    )

    /** Chinese numeral or digits → number (一 → 1, 十二 → 12, 二十五 → 25, 1.5 → 1.5); null when not a number. */
    fun number(s: String): Double? {
        val t = s.trim()
        t.replace("½", ".5").toDoubleOrNull()?.let { return it }
        if (t.endsWith("½")) t.dropLast(1).toDoubleOrNull()?.let { return it + 0.5 }
        if (t == "半") return 0.5
        if (t.isEmpty() || t.any { it != '十' && it != '百' && it !in CN }) return null
        if (t.contains('百')) return null
        val ten = t.indexOf('十')
        if (ten < 0) return if (t.length == 1) CN[t[0]]!!.toDouble() else t.fold(0) { acc, c -> acc * 10 + CN[c]!! }.toDouble()
        val tens = if (ten == 0) 1 else CN[t[ten - 1]] ?: return null
        val ones = if (ten == t.length - 1) 0 else CN[t[ten + 1]] ?: return null
        return (tens * 10 + ones).toDouble()
    }

    private const val NUM_ZH = "(\\d+(?:\\.\\d+)?½?|[零〇一二两三四五六七八九十]+)"
    private const val RANGE = "(?:\\s*(?:[-–—~～到至]|to)\\s*$NUM_ZH)?"
    private const val NUM_EN = "(\\d+(?:\\.\\d+)?½?|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty-five|forty|fifty|sixty|ninety)"
    private const val RANGE_EN = "(?:\\s*(?:-|–|to)\\s*(\\d+(?:\\.\\d+)?))?"

    private val patterns: List<Pair<Regex, (MatchResult) -> Int?>> = listOf(
        Regex("$NUM_ZH\\s*个?半\\s*(?:小时|钟头)") to { m -> number(m.groupValues[1])?.let { ((it * 60 + 30) * 60).toInt() } },
        Regex("半\\s*个?\\s*(?:小时|钟头)") to { _ -> 30 * 60 },
        Regex("(?i)half\\s+an\\s+hour") to { _ -> 30 * 60 },
        Regex("$NUM_ZH$RANGE\\s*个?\\s*(?:小时|钟头)") to { m -> maxOf(m, 1, 2)?.let { (it * 3600).toInt() } },
        Regex("$NUM_ZH$RANGE\\s*分(?:钟)?(半)?") to { m -> maxOf(m, 1, 2)?.let { (it * 60).toInt() + if (m.groupValues[3].isNotEmpty()) 30 else 0 } },
        Regex("$NUM_ZH$RANGE\\s*秒(?:钟)?") to { m -> maxOf(m, 1, 2)?.toInt() },
        Regex("(?i)\\b$NUM_EN$RANGE_EN\\s*(?:hours?|hrs?)\\b") to { m -> maxOfEn(m)?.let { (it * 3600).toInt() } },
        Regex("(?i)\\b$NUM_EN$RANGE_EN\\s*(?:more\\s+)?(?:minutes?|mins?)\\b") to { m -> maxOfEn(m)?.let { (it * 60).toInt() } },
        Regex("(?i)\\b$NUM_EN$RANGE_EN\\s*(?:seconds?|secs?)\\b") to { m -> maxOfEn(m)?.toInt() },
    )

    private fun maxOf(m: MatchResult, a: Int, b: Int): Double? {
        val x = number(m.groupValues[a]) ?: return null
        val y = m.groupValues.getOrNull(b)?.takeIf { it.isNotEmpty() }?.let { number(it) }
        return if (y != null) kotlin.math.max(x, y) else x
    }

    private fun enNum(s: String): Double? = number(s) ?: EN[s.lowercase()]

    private fun maxOfEn(m: MatchResult): Double? {
        val x = enNum(m.groupValues[1]) ?: return null
        val y = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return if (y != null) kotlin.math.max(x, y) else x
    }

    /** All times in [text], left to right, not overlapping. */
    fun findTimes(text: String): List<TimeHit> {
        val hits = ArrayList<TimeHit>()
        for ((rx, f) in patterns) for (m in rx.findAll(text)) {
            val sec = f(m) ?: continue
            if (sec <= 0) continue
            val r = m.range
            if (hits.any { r.first <= it.end - 1 && it.start <= r.last }) continue
            hits += TimeHit(r.first, r.last + 1, sec)
        }
        return hits.sortedBy { it.start }
    }

    /** [text] with every time wrapped in {braces}. */
    fun mark(text: String): String {
        val hits = findTimes(text)
        if (hits.isEmpty()) return text
        val sb = StringBuilder(); var k = 0
        for (h in hits) { sb.append(text, k, h.start).append('{').append(text, h.start, h.end).append('}'); k = h.end }
        sb.append(text.substring(k))
        return sb.toString()
    }

    /** Visible length (braces are markup). */
    fun visibleLength(s: String): Int = s.count { it != '{' && it != '}' }

    /** CJK text counts 1 per char against 26; Latin text 1 per char against 48 (both = two lines at 40 sp). */
    fun fitsTwoLines(s: String): Boolean {
        val cjk = s.count { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        return if (cjk > 0) visibleLength(s) <= CookPages.ZH_MAX + (visibleLength(s) - cjk) / 2 else visibleLength(s) <= CookPages.EN_MAX
    }

    private val STEP_MARK = Regex("^\\s*(?:(?:第\\s*[\\d一二三四五六七八九十]+\\s*步)|(?:[Ss]tep\\s*\\d+)|(?:\\d{1,2}\\s*[.、)）．:：])|(?:[一二三四五六七八九十]+\\s*[、.．]))\\s*[:：.、]?\\s*")
    private val PREP_HEAD = Regex("^\\s*(?:配料|材料|用料|食材|备料|[Ii]ngredients?)\\s*[:：]?\\s*")

    /** Splits a too-long step at sentence ends (keeps the punctuation). */
    fun splitLong(step: String): List<String> {
        if (fitsTwoLines(step)) return listOf(step)
        val parts = Regex("(?<=[。！？；!?;])\\s*|(?<=\\.)\\s+").split(step).filter { it.isNotBlank() }
        if (parts.size <= 1) return listOf(step)
        val out = ArrayList<String>(); var cur = ""
        for (p in parts) {
            val next = if (cur.isEmpty()) p else cur + (if (p.first().code < 0x3000 && cur.last().code < 0x3000) " " else "") + p
            if (cur.isNotEmpty() && !fitsTwoLines(next)) { out += cur; cur = p } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun parse(raw: String, id: String = "P${System.currentTimeMillis()}"): Result {
        val lines = raw.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return Result.NoSteps
        val title = lines[0].replace(PREP_HEAD, "").trim().take(24)
        val body = lines.drop(1)
        val firstNumbered = body.indexOfFirst { STEP_MARK.containsMatchIn(it) && STEP_MARK.find(it)!!.range.first == 0 }
        val prepLines: List<String>
        val stepTexts = ArrayList<String>()
        if (firstNumbered < 0) {
            val prepIdx = body.indexOfFirst { PREP_HEAD.containsMatchIn(it) }
            prepLines = if (prepIdx == 0) listOf(body[0]) else emptyList()
            stepTexts += body.drop(prepLines.size)
        } else {
            prepLines = body.take(firstNumbered)
            for (l in body.drop(firstNumbered)) {
                val m = STEP_MARK.find(l)
                if (m != null && m.range.first == 0) stepTexts += l.substring(m.range.last + 1).trim()
                else if (stepTexts.isNotEmpty()) stepTexts[stepTexts.size - 1] = stepTexts.last() + (if (l.first().code < 0x3000) " " else "") + l
            }
        }
        val ingredients = prepLines.flatMap { it.replace(PREP_HEAD, "").split(Regex("[、，,;；/\\s]+")) }
            .map { it.trim().replace(Regex("^[-•·*]+"), "").trim() }
            .map { it.replace(Regex("\\s*[\\d½.]+\\s*(?:g|kg|ml|克|毫升|个|只|根|瓣|勺|片|块|斤|两|杯|cups?|tbsp|tsp)?$"), "").trim() }
            .filter { it.isNotEmpty() }.distinct().take(12)
        val steps = stepTexts.filter { it.isNotBlank() }.flatMap { splitLong(it) }.take(MAX_STEPS)
        if (steps.isEmpty()) return Result.NoSteps
        val built = steps.mapIndexed { i, s ->
            val hit = findTimes(s).firstOrNull()
            Step(Tr(mark(s), mark(s)), hit?.seconds, hit?.let { Tr("第 ${i + 1} 步", "Step ${i + 1}") })
        }
        val minutes = (built.sumOf { it.timerSec ?: 0 } / 60).coerceAtLeast(built.size * 2)
        return Result.Ok(Recipe(id, Tr(title, title), Tr("你粘贴的", "Pasted by you"), minutes, "pasted", ingredients.map { Tr(it, it) }, built, pasted = true))
    }

    /** design S02 qaCapture / M11: the sample pasted by QA (adb input text) and the debug `paste sample`. */
    const val SAMPLE = "外婆的红烧排骨\n排骨 冰糖 姜 生抽 老抽\n1. 排骨冷水下锅，焯3分钟，捞出洗净。\n2. 锅里放冰糖，小火炒到冒小泡。\n3. 排骨下锅翻炒上色。\n4. 加姜片、生抽、老抽，加水没过排骨。\n5. 大火烧开转小火，炖40分钟。\n6. 开盖大火收汁5分钟，出锅。"
}
