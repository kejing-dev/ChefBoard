package io.embodit.chefboard.ui.stage

import android.content.Context
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelFormat
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import io.embodit.chefboard.domain.math.Quat4
import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.recipe.StepModels
import io.embodit.chefboard.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.atan2

/**
 * E7 step food (v0.1.4): the Tripo food models of the current cook page, standing at real size on the counter beside the
 * timers ([Layout.foodSpots]). GLBs are calibrated offline (tools/prep_food.py, D25), loaded once per id as disabled
 * templates (every material forced OPAQUE, opacity components removed), cloned per page. A page change fades the old set
 * out (180 ms) and the new one in (300 ms, 70 ms apart, settling 1.5 cm down: D51 smooth); at full opacity the
 * OpacityControllerComponent is removed again (factory: a component at 1.0 still renders blended). No colliders: the food
 * is never a pointer target, so it cannot steal a pinch from a timer, a ring or a button.
 */
internal class FoodShelf(private val context: Context, private val scope: CoroutineScope) {
    private val templates = HashMap<String, Entity>()
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    val feet: Map<String, Layout.Foot> by lazy { readFeet() }

    private class Item(val id: String, val e: Entity, val base: Vec3, val bornMs: Long, var fadingOutAt: Long = -1L, var opaque: Boolean = false, val k: Float = 1f, val yawDeg: Float = 0f)

    /**
     * Debug `foodsolo <id> [yawDeg]` (v0.1.5 白模排查): one model alone, enlarged so its longest side is [SOLO_SIZE_M],
     * 0.55 m in front of the eye on the window side, turned [yawDeg] from facing the eye — a close-up for every model.
     * null = the normal cook-page food.
     */
    var solo: Pair<String, Float>? = null
    private val live = ArrayList<Item>()
    private var shownKey: String? = null
    private var root: Entity? = null
    /** Last log line per key so the log shows each page once. */
    var lastShown: String = ""
        private set

    fun attach(kitchenRoot: Entity) {
        root = Entity().apply { setName("foodRoot") }.also { kitchenRoot.addChild(it) }
    }

    private fun readFeet(): Map<String, Layout.Foot> = runCatching {
        val j = JSONObject(context.assets.open("models/food/index.json").bufferedReader().use { it.readText() })
        j.keys().asSequence().associateWith { k -> j.getJSONObject(k).let { Layout.Foot(it.getDouble("w").toFloat(), it.getDouble("d").toFloat(), it.getDouble("h").toFloat()) } }
    }.onFailure { AppLog.w("FOOD index.json unreadable: $it") }.getOrDefault(emptyMap())

    /** Start loading [ids] (a recipe opening) so the first page change is instant. */
    fun preload(ids: Collection<String>) { ids.forEach { load(it) } }

    private fun load(id: String) {
        if (templates.containsKey(id) || id in loading || id in failed) return
        loading += id
        scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val e = runCatching {
                val bytes = withContext(Dispatchers.IO) { context.assets.open(StepModels.asset(id)).use { it.readBytes() } }
                Entity.loadSuspend(java.io.ByteArrayInputStream(bytes), ModelFormat.GLTF).also { it.enabled = false; it.setName("foodTpl:$id") }
            }.onFailure { AppLog.w("FOOD $id failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            loading -= id
            if (e == null) { failed += id; return@launch }
            val forced = opaque(e)
            templates[id] = e
            AppLog.i("FOOD loaded $id ms=${android.os.SystemClock.elapsedRealtime() - t0} forcedOpaque=$forced")
        }
    }

    private fun opaque(root: Entity): Int {
        var forced = 0
        fun walk(e: Entity) {
            if (e.components[OpacityControllerComponent::class.java] != null) e.components.remove(OpacityControllerComponent::class.java)
            e.components[ModelComponent::class.java]?.let { mc ->
                runCatching { mc.materials.forEach { m -> if (m is PhysicallyBasedMaterial && m.getBlendingMode() != BlendingMode.OPAQUE) { m.setBlendingMode(BlendingMode.OPAQUE); forced++ } } }
            }
            runCatching { e.getChildren() }.getOrNull()?.forEach { walk(it) }
        }
        walk(root)
        return forced
    }

    /**
     * Every frame: [ids] is what the current page wants ([key] changes when the page does; null = nothing), [eye] the
     * head in kitchen frame (models turn to it, yaw only), [now] the visual clock.
     */
    fun frame(key: String?, ids: List<String>, eyeLocal: Vec3, now: Long) {
        val r = root ?: return
        ids.forEach { load(it) }
        val ready = ids.all { templates.containsKey(it) || it in failed }
        if (key != shownKey && ready) {
            shownKey = key
            live.forEach { if (it.fadingOutAt < 0) it.fadingOutAt = now }
            val use = ids.map { if (templates.containsKey(it)) it else StepModels.BASKET }.filter { templates.containsKey(it) }
            val soloYaw = solo?.second
            val spots = if (key?.startsWith("solo/") == true) listOf(Layout.dir(Layout.FOOD_AZ) * SOLO_DIST + Vec3(0f, SOLO_Y, 0f))
                else Layout.foodSpots(use.map { foot(it) })
            use.forEachIndexed { i, id ->
                if (i >= spots.size) return@forEachIndexed
                val e = templates[id]?.let { runCatching { it.clone(Entity.CloneOptions(true, true)) }.getOrNull() } ?: return@forEachIndexed
                e.setName("food:$id"); e.enabled = true
                e.components.set(OpacityControllerComponent(0f))
                r.addChild(e)
                val f = foot(id)
                val k = if (soloYaw != null && key?.startsWith("solo/") == true) SOLO_SIZE_M / maxOf(f.w, f.d, f.h) else 1f
                live += Item(id, e, spots[i], now + i * STAGGER_MS, k = k, yawDeg = if (k != 1f) soloYaw ?: 0f else 0f)
            }
            lastShown = "key=$key ids=${use.joinToString(",")}"
            AppLog.i("FOOD show $lastShown")
        }
        val it = live.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val rot = Quat4.axisAngle(Vec3.UP, atan2(eyeLocal.x - m.base.x, eyeLocal.z - m.base.z) + Math.toRadians(m.yawDeg.toDouble()).toFloat())
            val sc = Vec3(m.k, m.k, m.k)
            if (m.fadingOutAt >= 0) {
                val u = ((now - m.fadingOutAt).toFloat() / OUT_MS).coerceIn(0f, 1f)
                if (m.opaque) { m.e.components.set(OpacityControllerComponent(1f)); m.opaque = false }
                m.e.components[OpacityControllerComponent::class.java]?.opacity = 1f - u
                m.e.place(position = m.base + Vec3(0f, -0.01f * u, 0f), rotation = rot, scale = sc)
                if (u >= 1f) { runCatching { m.e.destroy(true) }; it.remove() }
                continue
            }
            val u = ((now - m.bornMs).toFloat() / IN_MS).coerceIn(0f, 1f)
            val e = 1f - (1f - u) * (1f - u) * (1f - u)
            if (!m.opaque) {
                if (u >= 1f) { m.e.components.remove(OpacityControllerComponent::class.java); m.opaque = true }
                else m.e.components[OpacityControllerComponent::class.java]?.opacity = e
            }
            m.e.place(position = m.base + Vec3(0f, 0.015f * (1f - e), 0f), rotation = rot, scale = sc)
        }
    }

    private fun foot(id: String) = feet[id] ?: StepModels.model(id)?.let { Layout.Foot(it.sizeM, it.sizeM, it.sizeM) } ?: Layout.Foot(0.1f, 0.1f, 0.1f)

    /** debug `food`: what stands on the counter now (id, kitchen-frame base). */
    fun dump(): String = live.filter { it.fadingOutAt < 0 }.joinToString(" ") { "${it.id}@(%.2f,%.2f,%.2f)".format(it.base.x, it.base.y, it.base.z) } +
        " loaded=${templates.size} failed=${failed.joinToString(",")}"

    fun close() {
        live.forEach { runCatching { it.e.destroy(true) } }; live.clear()
        templates.values.forEach { runCatching { it.destroy(true) } }; templates.clear()
        shownKey = null
    }

    companion object {
        const val IN_MS = 300f
        const val OUT_MS = 180f
        const val STAGGER_MS = 70L
        const val SOLO_SIZE_M = 0.22f
        const val SOLO_DIST = 0.55f
        const val SOLO_Y = -0.22f
    }
}
