package io.embodit.chefboard.domain.recipe

/**
 * v0.1.4 (门 C 真机反馈「菜谱的每一步，都要有实体的食物模型」, profile B12 步骤模型): every page of a built-in recipe shows
 * real food on the counter beside the timers. Each model is one Tripo GLB in assets/models/food/<id>.glb, decimated and
 * calibrated offline by tools/prep_food.py (D25: bottom centre at the origin, y up, front +Z, longest side = [sizeM] metres,
 * every material OPAQUE). The same food is reused across steps and recipes; a step can show up to three models side by
 * side (the vessel + what goes in). Prep pages show every ingredient of the dish in one row. A pasted recipe has no
 * models: it gets the generic basket. Pure Kotlin so StepModelsTest checks every page → existing, loadable files.
 */
object StepModels {
    /** A Tripo model: [id] = file name, [sizeM] = longest side after calibration (real size), [prompt] = the Tripo text prompt. */
    data class Model(val id: String, val sizeM: Float, val prompt: String)

    private const val STYLE = "photorealistic food photography style, realistic PBR materials, isolated single object, no text, no logo"
    private fun m(id: String, size: Float, what: String) = Model(id, size, "$what, $STYLE")

    val catalog: List<Model> = listOf(
        // ---- ingredients (prep rows; also shown beside a vessel when they go in)
        m("egg", 0.057f, "a single whole raw brown chicken egg lying on its side"),
        m("tomato", 0.075f, "a single ripe red tomato with a green stem"),
        m("scallion", 0.26f, "a small bunch of fresh green scallions, spring onions with white roots, lying flat"),
        m("salt", 0.085f, "a small white ceramic dish filled with coarse white salt"),
        m("ice", 0.13f, "a small glass bowl full of clear ice cubes"),
        m("rice", 0.13f, "a ceramic bowl full of uncooked white rice grains"),
        m("water", 0.19f, "a clear glass pitcher of water"),
        m("pasta", 0.26f, "a bundle of dry uncooked spaghetti lying flat"),
        m("garlic", 0.062f, "a whole white garlic bulb"),
        m("oil", 0.24f, "a tall glass bottle of golden olive oil with a cork"),
        m("basil", 0.12f, "a fresh sprig of green basil leaves lying flat"),
        m("seabass", 0.36f, "a whole raw sea bass fish lying flat, silver scales"),
        m("ginger", 0.10f, "a knob of fresh ginger root with three thin slices"),
        m("soy", 0.20f, "a glass bottle of dark soy sauce with a red cap, plain label"),
        m("salmon", 0.16f, "a raw salmon fillet with skin, lying flat"),
        m("pepper", 0.15f, "a wooden pepper grinder standing upright"),
        m("lemon", 0.08f, "a yellow lemon with one half cut open"),
        m("wings", 0.17f, "three raw chicken wings on a small white plate"),
        m("cola", 0.122f, "a plain red aluminium soda can without any logo"),
        // ---- states (what the step looks like)
        m("pot_boil", 0.24f, "a stainless steel saucepan full of boiling water with bubbles"),
        m("pot_eggs", 0.24f, "a stainless steel saucepan of boiling water with three whole eggs inside"),
        m("ice_bath", 0.18f, "a glass bowl of ice water with ice cubes and three boiled eggs"),
        m("egg_jammy", 0.075f, "a peeled soft boiled egg cut in half with a runny jammy orange yolk"),
        m("egg_beaten", 0.15f, "a ceramic bowl of beaten raw eggs, smooth yellow liquid, with a pair of chopsticks"),
        m("tomato_cut", 0.25f, "a wooden cutting board with red tomato wedges"),
        m("egg_scrambled", 0.20f, "a white plate of soft fluffy yellow scrambled eggs"),
        m("wok_tomato", 0.30f, "a black wok with stir-fried tomato wedges in red juice"),
        m("tomato_egg", 0.22f, "a white plate of chinese tomato and scrambled egg stir-fry"),
        m("pot_lid", 0.24f, "a stainless steel cooking pot with its lid closed"),
        m("rice_bowl", 0.13f, "a bowl of fluffy cooked white rice"),
        m("pasta_boil", 0.26f, "a large stainless steel pot of boiling water with spaghetti inside"),
        m("pan_garlic", 0.28f, "a frying pan with olive oil and sliced garlic"),
        m("pan_sauce", 0.28f, "a frying pan of simmering chunky red tomato sauce"),
        m("pasta_plate", 0.24f, "a white plate of spaghetti with tomato sauce and fresh basil leaves"),
        m("steamer", 0.26f, "a stainless steel steamer pot with its lid closed"),
        m("fish_steamed", 0.34f, "a chinese steamed whole sea bass on an oval white plate with shredded scallions and soy sauce"),
        m("salmon_pan", 0.28f, "a salmon fillet searing skin side down in a black frying pan"),
        m("salmon_plate", 0.22f, "a pan-seared salmon fillet with crispy skin on a white plate with a lemon wedge"),
        m("egg_custard", 0.13f, "a bowl of chinese steamed egg custard with a smooth glossy surface and a drizzle of soy sauce"),
        m("wings_pan", 0.28f, "golden pan-fried chicken wings in a black frying pan"),
        m("wings_cola", 0.28f, "glossy dark brown cola braised chicken wings in a pan with thick sauce"),
        // ---- pasted recipes
        m("basket", 0.30f, "a wicker basket of assorted fresh vegetables and groceries"),
    )

    private val byId = catalog.associateBy { it.id }
    fun model(id: String): Model? = byId[id]

    /** Ingredient (Chinese name in Builtin) → model. Sugar shares the white-granules dish with salt. */
    val ingredient: Map<String, String> = mapOf(
        "鸡蛋" to "egg", "冰块" to "ice", "盐" to "salt", "番茄" to "tomato", "葱" to "scallion", "糖" to "salt",
        "大米" to "rice", "水" to "water", "温水" to "water", "意面" to "pasta", "蒜" to "garlic", "橄榄油" to "oil",
        "罗勒" to "basil", "鲈鱼" to "seabass", "姜" to "ginger", "蒸鱼豉油" to "soy", "油" to "oil", "三文鱼" to "salmon",
        "黑胡椒" to "pepper", "柠檬" to "lemon", "鸡翅" to "wings", "可乐" to "cola", "生抽" to "soy", "老抽" to "soy",
        "香油" to "oil",
    )

    /** Built-in steps (1-based) → models, main one first (the vessel / dish, then what goes in). */
    val steps: Map<String, List<List<String>>> = mapOf(
        "R1" to listOf(listOf("pot_boil", "salt"), listOf("pot_eggs"), listOf("ice_bath"), listOf("egg_jammy")),
        "R2" to listOf(
            listOf("egg_beaten", "tomato_cut"), listOf("egg_scrambled"), listOf("wok_tomato"), listOf("wok_tomato", "salt"),
            listOf("tomato_egg"), listOf("tomato_egg", "scallion"),
        ),
        "R3" to listOf(listOf("rice", "water"), listOf("pot_boil"), listOf("pot_lid"), listOf("pot_lid"), listOf("rice_bowl")),
        "R4" to listOf(
            listOf("pot_boil", "salt"), listOf("tomato_cut", "garlic"), listOf("pasta_boil"), listOf("pan_garlic"),
            listOf("pan_sauce"), listOf("pasta_plate"), listOf("pasta_plate", "basil"),
        ),
        "R5" to listOf(
            listOf("seabass"), listOf("seabass", "ginger"), listOf("steamer"), listOf("steamer"), listOf("fish_steamed", "soy"),
            listOf("fish_steamed", "oil"),
        ),
        "R6" to listOf(listOf("salmon", "salt", "pepper"), listOf("salmon_pan"), listOf("salmon_pan"), listOf("salmon_plate"), listOf("salmon_plate", "lemon")),
        "R7" to listOf(listOf("egg_beaten", "water"), listOf("egg_beaten"), listOf("steamer"), listOf("steamer"), listOf("egg_custard", "soy")),
        "R8" to listOf(
            listOf("wings"), listOf("pot_boil", "wings"), listOf("wings"), listOf("wings_pan"), listOf("wings_pan", "ginger", "soy"),
            listOf("wings_cola", "cola"), listOf("wings_cola"),
        ),
    )

    const val BASKET = "basket"

    /** Models shown for [page] of [recipe] (prep = every ingredient, distinct, in recipe order). */
    fun forPage(recipe: Recipe, page: CookPage): List<String> {
        if (recipe.pasted || steps[recipe.id] == null) return listOf(BASKET)
        if (page.stepNo == 0) return recipe.ingredients.mapNotNull { ingredient[it.zh] }.distinct().ifEmpty { listOf(BASKET) }
        return steps[recipe.id]?.getOrNull(page.stepNo - 1) ?: listOf(BASKET)
    }

    /** Every model id a recipe can show (for preloading when it opens). */
    fun forRecipe(recipe: Recipe): List<String> = CookPages.of(recipe).flatMap { forPage(recipe, it) }.distinct()

    fun asset(id: String) = "models/food/$id.glb"
}
