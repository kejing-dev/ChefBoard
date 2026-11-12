package io.embodit.chefboard.data

import io.embodit.chefboard.domain.recipe.Recipe
import io.embodit.chefboard.domain.recipe.Step
import io.embodit.chefboard.domain.recipe.Tr
import org.json.JSONArray
import org.json.JSONObject

/** Pasted recipes ↔ files/recipes.json (design dataModel.persist: 本机应用私有 JSON). */
object RecipeCodec {
    private fun tr(t: Tr) = JSONObject().put("zh", t.zh).put("en", t.en)
    private fun tr(o: JSONObject) = Tr(o.getString("zh"), o.getString("en"))

    fun encode(list: List<Recipe>): String = JSONObject().put("v", 1).put("recipes", JSONArray().apply {
        list.forEach { r ->
            put(JSONObject().put("id", r.id).put("title", tr(r.title)).put("tag", tr(r.tag)).put("minutes", r.minutes).put("icon", r.icon)
                .put("ingredients", JSONArray().apply { r.ingredients.forEach { put(tr(it)) } })
                .put("steps", JSONArray().apply {
                    r.steps.forEach { s ->
                        put(JSONObject().put("text", tr(s.text)).apply {
                            s.timerSec?.let { put("sec", it) }
                            s.timerName?.let { put("name", tr(it)) }
                        })
                    }
                }))
        }
    }).toString()

    fun decode(text: String): List<Recipe> {
        val arr = JSONObject(text).getJSONArray("recipes")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ing = o.getJSONArray("ingredients"); val st = o.getJSONArray("steps")
            Recipe(
                o.getString("id"), tr(o.getJSONObject("title")), tr(o.getJSONObject("tag")), o.getInt("minutes"), o.optString("icon", "pasted"),
                List(ing.length()) { tr(ing.getJSONObject(it)) },
                List(st.length()) { k ->
                    val s = st.getJSONObject(k)
                    Step(tr(s.getJSONObject("text")), if (s.has("sec")) s.getInt("sec") else null, s.optJSONObject("name")?.let { tr(it) })
                },
                pasted = true,
            )
        }
    }
}
