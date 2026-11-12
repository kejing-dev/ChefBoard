package io.embodit.chefboard.domain.recipe

/**
 * The 8 built-in home recipes (design content.recipes; text written for this app, content.license). Every step is one
 * sentence ≤ 2 lines (中文 ≤ 26 字、English ≤ 48 chars, RecipeTextTest); the time that becomes the step's [⏱] is
 * wrapped in {braces} and drawn in amber. At most one timer per step.
 */
object Builtin {
    private fun s(zh: String, en: String) = Step(Tr(zh, en))
    private fun t(zh: String, en: String, sec: Int, nameZh: String, nameEn: String) = Step(Tr(zh, en), sec, Tr(nameZh, nameEn))
    private fun i(vararg p: Pair<String, String>) = p.map { Tr(it.first, it.second) }

    val all: List<Recipe> = listOf(
        Recipe(
            "R1", Tr("溏心蛋", "Jammy eggs"), Tr("六分半，蛋黄刚好流", "6½ minutes to a runny middle"), 12, "egg",
            i("鸡蛋" to "eggs", "冰块" to "ice", "盐" to "salt"),
            listOf(
                s("小锅水烧开，放一小勺盐。", "Bring a small pot to a boil, add salt."),
                t("鸡蛋轻轻放进去，煮{六分半}。", "Lower the eggs in gently. Boil {6½ minutes}.", 390, "煮蛋", "Eggs"),
                t("捞进冰水，泡{两分钟}。", "Into ice water for {two minutes}.", 120, "冰水", "Ice bath"),
                s("敲开剥壳，切开就是溏心。", "Crack, peel, slice. Runny middle."),
            ),
        ),
        Recipe(
            "R2", Tr("番茄炒蛋", "Tomato & egg"), Tr("家里最常见的那盘", "The one everyone grew up on"), 15, "egg",
            i("鸡蛋" to "eggs", "番茄" to "tomatoes", "葱" to "scallion", "糖" to "sugar", "盐" to "salt"),
            listOf(
                s("鸡蛋加点盐打散，番茄切块。", "Beat eggs with salt. Cut tomatoes."),
                s("热锅多放点油，鸡蛋炒到半熟盛出。", "Hot oil, scramble the eggs soft, set aside."),
                s("锅里留油，下番茄翻炒出汁。", "Same pan: tomatoes in, stir till juicy."),
                t("加一勺糖，盖上锅盖焖{两分钟}。", "A spoon of sugar. Lid on, {two minutes}.", 120, "焖番茄", "Tomatoes"),
                s("鸡蛋倒回去，加盐翻匀。", "Eggs back in, salt, toss to coat."),
                s("撒葱花，出锅。", "Scallions on top. Done."),
            ),
        ),
        Recipe(
            "R3", Tr("锅煮米饭", "Stovetop rice"), Tr("没电饭锅也能煮", "No rice cooker, no problem"), 30, "rice",
            i("大米" to "rice", "水" to "water"),
            listOf(
                s("米淘两遍，加水没过米一个指节。", "Rinse twice. Water a knuckle above the rice."),
                s("大火煮开，看到冒大泡。", "High heat until it bubbles hard."),
                t("盖盖转最小火，煮{十二分钟}。", "Lid on, lowest heat, {twelve minutes}.", 720, "小火", "Simmer"),
                t("关火别开盖，再焖{十分钟}。", "Heat off, lid stays on. Rest {ten minutes}.", 600, "焖饭", "Rest"),
                s("开盖翻松，开饭。", "Fluff it up and serve."),
            ),
        ),
        Recipe(
            "R4", Tr("番茄意面", "Tomato pasta"), Tr("两个锅同时走", "Two pots, one timer each"), 24, "pasta",
            i("意面" to "pasta", "番茄" to "tomatoes", "蒜" to "garlic", "橄榄油" to "olive oil", "盐" to "salt", "罗勒" to "basil"),
            listOf(
                s("大锅烧水，水多一点，放一勺盐。", "Big pot of water, plenty, a spoon of salt."),
                s("番茄切块，蒜拍碎。", "Chop the tomatoes, crush the garlic."),
                t("水开了？下面，{十分钟}。", "Water boiling? Pasta in. {Ten minutes}.", 600, "煮面", "Pasta"),
                s("另一口锅放油和蒜，小火炒香。", "Other pan: oil and garlic, low till fragrant."),
                t("下番茄，小火咕嘟{八分钟}。", "Tomatoes in. Simmer low, {eight minutes}.", 480, "酱汁", "Sauce"),
                s("面捞进酱里，翻两下。", "Lift the pasta into the sauce, toss twice."),
                s("撒罗勒，开饭。", "Basil on top. Dinner's ready."),
            ),
        ),
        Recipe(
            "R5", Tr("清蒸鲈鱼", "Steamed sea bass"), Tr("八分钟，嫩得刚好", "Eight minutes, just tender"), 20, "fish",
            i("鲈鱼" to "sea bass", "姜" to "ginger", "葱" to "scallion", "蒸鱼豉油" to "fish soy", "油" to "oil"),
            listOf(
                s("鱼洗净擦干，两面各划两刀。", "Rinse and dry the fish, score both sides."),
                s("盘底铺姜片，鱼放上面。", "Ginger on the plate, fish on top."),
                t("水开后上锅，大火蒸{八分钟}。", "Once boiling, steam on high {8 minutes}.", 480, "蒸鱼", "Fish"),
                t("关火别开盖，焖{两分钟}。", "Heat off, lid on. Rest {two minutes}.", 120, "焖鱼", "Rest"),
                s("倒掉盘里的汤，铺葱丝，淋豉油。", "Pour off the juice. Scallions, soy sauce."),
                s("烧一勺热油，浇上去，滋啦一声。", "Heat a spoon of oil, pour it on. Sizzle."),
            ),
        ),
        Recipe(
            "R6", Tr("香煎三文鱼", "Pan-seared salmon"), Tr("皮脆的秘诀是别动它", "Crispy skin? Don't touch it"), 15, "fish",
            i("三文鱼" to "salmon", "盐" to "salt", "黑胡椒" to "pepper", "柠檬" to "lemon", "油" to "oil"),
            listOf(
                s("鱼擦干，两面撒盐和黑胡椒。", "Pat the salmon dry. Salt and pepper."),
                t("中火热油，皮朝下，别动它，{四分钟}。", "Skin down, medium heat, hands off. {4 minutes}.", 240, "皮朝下", "Skin side"),
                t("翻面，再煎{三分钟}。", "Flip it. {Three minutes} more.", 180, "翻面", "Flip"),
                t("出锅放盘里，静置{三分钟}。", "Onto a plate. Let it rest {three minutes}.", 180, "静置", "Rest"),
                s("挤点柠檬汁，开吃。", "Squeeze some lemon. Eat."),
            ),
        ),
        Recipe(
            "R7", Tr("嫩蒸蛋羹", "Silky steamed egg"), Tr("盖个盘子，表面就平", "Cover it, keep it smooth"), 20, "egg",
            i("鸡蛋" to "eggs", "温水" to "warm water", "盐" to "salt", "生抽" to "soy sauce", "香油" to "sesame oil"),
            listOf(
                s("鸡蛋打散，加一倍半温水和盐。", "Beat eggs with 1½× warm water, salt."),
                s("过一遍筛，撇掉泡沫。", "Strain it and skim off the foam."),
                t("盖个盘子，水开后中小火蒸{十分钟}。", "Plate on top, steam medium-low {10 minutes}.", 600, "蒸蛋", "Steam"),
                t("关火再焖{两分钟}。", "Heat off. Rest {two minutes}.", 120, "焖蛋", "Rest"),
                s("淋生抽和香油，趁热吃。", "Soy sauce, sesame oil. Eat it warm."),
            ),
        ),
        Recipe(
            "R8", Tr("可乐鸡翅", "Cola chicken wings"), Tr("一罐可乐的事", "One can of cola does it"), 35, "wings",
            i("鸡翅" to "wings", "可乐" to "cola", "姜" to "ginger", "生抽" to "light soy", "老抽" to "dark soy"),
            listOf(
                s("鸡翅两面各划两刀。", "Score the wings twice on each side."),
                t("冷水下锅，开锅后焯{三分钟}。", "Cold water, bring to a boil, {3 minutes}.", 180, "焯水", "Blanch"),
                s("捞出擦干。", "Drain and pat dry."),
                s("少油把鸡翅煎到两面金黄。", "A little oil, fry till golden both sides."),
                s("加姜片、生抽、一点老抽。", "Ginger, light soy, a dash of dark soy."),
                t("倒一罐可乐，中小火收汁{二十分钟}。", "Pour in a can of cola. Reduce {20 minutes}.", 1200, "收汁", "Reduce"),
                s("汁浓了翻几下，出锅。", "Sauce thick? Toss and serve."),
            ),
        ),
    )

    fun byId(id: String): Recipe? = all.firstOrNull { it.id == id }
}
