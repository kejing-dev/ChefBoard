package io.embodit.chefboard.ui.stage

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.chefboard.R
import io.embodit.chefboard.domain.math.Quat4
import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Arrange
import io.embodit.chefboard.domain.place.ColdStart
import io.embodit.chefboard.domain.place.HeadSampler
import io.embodit.chefboard.domain.place.Layout
import io.embodit.chefboard.domain.recipe.StepModels
import io.embodit.chefboard.domain.timer.Hint
import io.embodit.chefboard.domain.timer.Place
import io.embodit.chefboard.domain.timer.TState
import io.embodit.chefboard.domain.timer.Timer
import io.embodit.chefboard.platform.AppLog
import io.embodit.chefboard.platform.Graph
import io.embodit.chefboard.platform.HeadPose
import io.embodit.chefboard.platform.HeadTracker
import io.embodit.chefboard.ui.stage.input.InputSource
import io.embodit.chefboard.ui.theme.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin

/** What a pointer hit resolves to (names only from [Names], replayed in HitReplayTest). */
sealed class Hit {
    data class TimerHit(val slot: Int) : Hit()
    data class Spot(val i: Int) : Hit()
    data class Btn(val b: Button3D) : Hit()
    data object Plate : Hit()
}

/** The five 3D big buttons (E4): three while a timer floats, two on the last paused timer. */
enum class Button3D(val id: String) { PLUS1("plus1"), PLUS5("plus5"), CLEAR("clear"), P_PLUS1("pplus1"), P_BACK("back") }

object Names {
    fun timer(i: Int) = "timer:$i"
    fun spot(i: Int) = "spot:$i"
    fun btn(b: Button3D) = "btn:${b.id}"
    const val PLATE = "plate"

    fun resolve(name: String): Hit? = when {
        name.startsWith("timer:") -> name.removePrefix("timer:").toIntOrNull()?.takeIf { it in 0..3 }?.let { Hit.TimerHit(it) }
        name.startsWith("spot:") -> name.removePrefix("spot:").toIntOrNull()?.takeIf { it in 0..4 }?.let { Hit.Spot(it) }
        name.startsWith("btn:") -> Button3D.entries.firstOrNull { it.id == name.removePrefix("btn:") }?.let { Hit.Btn(it) }
        name == PLATE -> Hit.Plate
        else -> null
    }
}

class ChefFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { ActiveScene.scene?.onFrame() }
}

internal object ActiveScene { @Volatile var scene: StageScene? = null }

/** Ids of the Stage attachment panels (only the HUD: loading / level / look-here hints). */
object PanelIds { const val HUD = "hud"; val all = listOf(HUD) }

/** What the HUD attachment says right now. */
enum class HudMsg { NONE, LOADING, LEVEL, LOOK_WINDOW, LOOK_STOVE }

/**
 * The Stage side: rack, four timers, five drop rings, 3D buttons, tags, the off-view arrow, the light. One root whose
 * local space is Stage metres; the kitchen root carries the [Layout.Pose] (rack, rings, plate); timers / tags / buttons
 * are placed in Stage space every frame (placed timers keep their world point when the kitchen is recalled). State →
 * entities in [onFrame] (one ECS system, main thread). Pointer input arrives through the board-input template ([onTap]).
 */
class StageScene(private val graph: Graph, private val context: Context) {
    private val flow get() = graph.flow
    private val board get() = graph.flow.board
    val head = HeadTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var res: SceneRes? = null
    private var content: SpatialViewContent? = null
    private var root: Entity? = null
    private var kitchenRoot: Entity? = null
    var pose: Layout.Pose? = null
        private set
    val ready = mutableStateOf(false)
    val hud = mutableStateOf(HudMsg.LOADING)
    val panelShown = mutableStateMapOf<String, Boolean>()
    private var hudHolder: Entity? = null
    private var hudBound = false
    private var startedMs = 0L
    private var lastPose: HeadPose? = null
    private var animMs = 0L
    /** elapsedRealtime for visuals; stands still while [freeze]. */
    private var visNow = 0L
    private var lastFrameMs = 0L
    private val sampler = HeadSampler(dwellMs = 300L)
    private var recallPending = false
    private val coldStart = ColdStart()
    private var arrangedSeq = 0
    /** debug `state S00`: keep showing「准备中…」with the window closed. */
    var forceLoading = false

    // entities
    private val timerHolders = ArrayList<Entity>()
    private val timerVis = ArrayList<Entity>()
    private val dials = ArrayList<Entity>()
    private val arcs = ArrayList<Entity>()
    private val arcMats = ArrayList<PhysicallyBasedMaterial>()
    private val shadows = ArrayList<Entity>()
    private val tags = ArrayList<Entity>()
    private val tagMats = ArrayList<UnlitMaterial>()
    private val tagTex = arrayOfNulls<TextureResource>(4)
    private val tagWanted = arrayOfNulls<String>(4)
    private val dialAngle = FloatArray(4)
    private val arcShown = IntArray(4) { -1 }
    private val faded = BooleanArray(4)
    private val buttons = HashMap<Button3D, Entity>()
    private val btnMats = HashMap<Button3D, UnlitMaterial>()
    private val spotHolders = ArrayList<Entity>()
    private val spotShownAt = LongArray(5) { -1L }
    private var spotsShown = false
    private var plate: Entity? = null
    private var plateFaceMat: UnlitMaterial? = null
    private var plateHint: Entity? = null
    private var plateHintMat: UnlitMaterial? = null
    private var firstRun: Entity? = null
    private var firstRunMat: UnlitMaterial? = null
    private var firstRunUntil = 0L
    private var arrowRoot: Entity? = null
    private var arrowText: Entity? = null
    private var arrowTextMat: UnlitMaterial? = null
    private val slotWell = ArrayList<Entity>()
    private val slotDash = ArrayList<Entity>()
    private var arcMeshes = HashMap<Int, MeshResource>()
    private var bellRes: List<AudioResource> = emptyList()
    private var bellCtl: AudioPlayerController? = null
    private var stagedPopAt = 0L
    private var pausedPopAt = 0L
    private var lastStaged: Int? = null
    private var lastPauseFocus: Int? = null
    /** E7 step food (v0.1.4). */
    private val food = FoodShelf(context, scope)
    private var foodRecipe: String? = null

    // ------------------------------------------------------------------ lifecycle

    suspend fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        this.content = content
        startedMs = graph.now()
        val root = Entity().apply { setName("stageRoot") }
        content.addEntity(root)
        // an entity added to a full-size SpatialView is not at the Stage origin: move the root so its local space = Stage
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rot = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply { setPosition(offset); setQuaternion(rot) }
        this.root = root
        AppLog.i("STAGE root offset=(${offset.x}, ${offset.y}, ${offset.z})")
        ActiveScene.scene = this
        head.start()
        hudHolder = Entity().apply { setName("holder:hud") }.also { root.addChild(it); it.place(position = Vec3(0f, -5f, 0f), scale = Vec3(0.001f, 0.001f, 0.001f)) }
        bind(attachments)
        val t0 = graph.now()
        val r = SceneRes.create()
        res = r
        build(r, root)
        loadBell()
        graph.stageRelease = { release() }
        graph.panOf = { slot -> panOf(slot) }
        graph.spatialBell = { slot, vol -> playBell(slot, vol) }
        graph.recallFn = { startRecall() }
        ready.value = true
        AppLog.i("SCENE ready resources=${graph.now() - t0} ms total=${graph.now() - startedMs} ms")
    }

    fun bind(attachments: SpatialViewAttachments) {
        if (hudBound) return
        val h = hudHolder ?: return
        val panel = attachments.entity(PanelIds.HUD) ?: return
        h.addChild(panel); hudBound = true
    }

    fun release() {
        runCatching { head.stop() }
        runCatching { bellCtl?.stop(); bellCtl?.close() }
        bellCtl = null
    }

    fun dispose() {
        release()
        scope.cancel()
        graph.panOf = null; graph.spatialBell = null; graph.recallFn = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        runCatching { hudHolder?.removeAllChildren() }
        runCatching { food.close() }
        runCatching { root?.destroy(true) }
        res?.close(); res = null
    }

    // ------------------------------------------------------------------ build

    private fun model(mesh: MeshResource, mat: com.pico.spatial.core.ecs.resource.Material, name: String, parent: Entity, at: Vec3 = Vec3.ZERO, rot: Quat4? = null, scale: Vec3? = null): Entity =
        ModelEntity(mesh, mat).apply { setName(name); parent.addChild(this); place(at, rot, scale) }

    private fun holder(name: String, parent: Entity, at: Vec3 = Vec3.ZERO, rot: Quat4? = null): Entity =
        Entity().apply { setName(name); parent.addChild(this); place(at, rot) }

    /** Collider + interactable + system hover on a holder; its visible opaque children get the highlight (board-input 通用模式). */
    private fun pickable(e: Entity, size: Vec3, r: SceneRes, offset: Vec3 = Vec3.ZERO) {
        val shape = ShapeResource.createBox(size.sdk()).let { if (offset == Vec3.ZERO) it else it.offsetByTranslation(offset.sdk()) }
        e.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        e.components.set(InteractableComponent())
        e.components.set(HoverEffectComponent())
    }

    private fun yawQ(rad: Float) = Quat4.axisAngle(Vec3.UP, rad)

    private fun build(r: SceneRes, root: Entity) {
        val k = Entity().apply { setName("kitchenRoot") }
        root.addChild(k)
        k.place(position = Vec3(0f, -20f, 0f)) // hidden until the kitchen pose is known
        kitchenRoot = k
        food.attach(k)
        runCatching { root.components.set(EnvironmentLightingSettingsComponent(1.0f)) }.onFailure { AppLog.w("ENV LIGHT unavailable: $it") }
        // B24: one moderate warm key light from above front-right (intensity: see ui-audit 灯光)
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.94f, 0.86f, 1f), KEY_LIGHT, true))
            light.place(rotation = Quat4.axisAngle(Vec3.UP, Math.toRadians(-30.0).toFloat()) * Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(-60.0).toFloat()))
            k.addChild(light)
        }.onFailure { AppLog.w("LIGHT unavailable: $it") }

        // E1 rack: walnut shelf (top at RACK_TOP_Y), four wells, dashed empty-slot circles, brass name plate
        val rc = Layout.rackTopCenter
        val rack = holder("rackRoot", k, rc, yawQ(Layout.yawToEye(rc)))
        model(r.rack, r.walnut, "rackBoard", rack, Vec3(0f, -Layout.RACK_THICK / 2f, 0f))
        for (s in 0 until 4) {
            slotWell += model(r.well, r.wellMat, "well:$s", rack, Vec3(Layout.SLOT_X[s], 0.0006f, 0f))
            slotDash += model(r.dashed, r.dashMat, "dash:$s", rack, Vec3(Layout.SLOT_X[s], 0.0012f, 0f)).also { it.show(false) }
        }
        val p = holder(Names.PLATE, rack, Vec3(Layout.PLATE_X, -Layout.RACK_THICK / 2f, Layout.RACK_DEPTH / 2f + 0.004f))
        pickable(p, Vec3(Layout.PLATE_W + 0.02f, 0.06f, 0.04f), r)
        model(r.plate, r.brass, "plateBody", p)
        plateFaceMat = SceneRes.unlit(Pal.BRASS).also { r.keep(it) }
        model(r.plateFace, plateFaceMat!!, "plateFace", p, Vec3(0f, 0f, 0.0032f))
        plate = p
        plateHintMat = SceneRes.unlit(Pal.TAG_BG).also { r.keep(it) }
        // q1-2: just past the rack end, level with the plate, pointing at it (never over a timer)
        plateHint = model(r.hintPlate, plateHintMat!!, "plateHint", rack, Layout.plateHintLocal, scale = Vec3(Layout.PLATE_HINT_W / 0.30f, Layout.PLATE_HINT_H / 0.045f, 1f)).also { it.show(false) }
        firstRunMat = SceneRes.unlit(Pal.TAG_BG).also { r.keep(it) }
        firstRun = model(r.hintPlate, firstRunMat!!, "firstRun", rack, Vec3(-0.04f, 0.20f, 0f)).also { it.show(false) }

        // E5 drop rings (kitchen frame, flat on the counter height)
        for (i in 0 until 5) {
            val h = holder(Names.spot(i), k, Layout.spotCenter(i))
            pickable(h, Vec3(Layout.SPOT_COLLIDER_D, Layout.SPOT_COLLIDER_H, Layout.SPOT_COLLIDER_D), r, offset = Vec3(0f, Layout.SPOT_COLLIDER_H / 2f, 0f))
            model(r.spotRing, r.spotMat, "spotRing:$i", h, Vec3(0f, 0.005f, 0f), Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()))
            model(r.spotDot, r.spotMat, "spotDot:$i", h)
            model(r.spotFill, r.spotFillMat, "spotFill:$i", h, Vec3(0f, 0.001f, 0f))
            h.show(false)
            spotHolders += h
        }

        // E2 timers (Stage space; placed every frame)
        for (s in 0 until 4) {
            val h = holder(Names.timer(s), root, Vec3(0f, -20f, 0f))
            pickable(h, Vec3(Layout.COLLIDER_D, Layout.COLLIDER_H, Layout.COLLIDER_D), r, offset = Vec3(0f, Layout.COLLIDER_H / 2f, 0f))
            runCatching { h.components.set(ObjectAudioComponent()) }.onFailure { AppLog.w("AUDIO object component: $it") }
            val vis = holder("timerVis:$s", h)
            model(r.bodyLow, r.cream, "bodyLow:$s", vis)
            model(r.bodyHigh, r.glaze, "bodyHigh:$s", vis)
            model(r.bezel, r.cream, "bezel:$s", vis)
            dials += model(r.dial, r.dialMat, "dial:$s", vis, Vec3(0f, SceneRes.BEZEL_TOP + 0.0006f, 0f))
            val am = SceneRes.pbr(Pal.ARCS[s], 0.45f).also { r.keep(it) }
            arcMats += am
            arcs += holder("arcHolder:$s", vis, Vec3(0f, SceneRes.BEZEL_TOP + 0.0009f, 0f))
            model(r.pointer, r.ink, "pointer:$s", vis, Vec3(0f, SceneRes.BEZEL_TOP + 0.0015f, SceneRes.DIAL_OUT - 0.004f))
            model(r.knob, r.dots[s], "knob:$s", vis, Vec3(0f, Layout.TIMER_H - 0.001f, 0f))
            shadows += model(r.shadow, r.shadowMat, "shadow:$s", root, Vec3(0f, -20f, 0f))
            timerHolders += h; timerVis += vis
            val tm = SceneRes.unlit(Pal.TAG_BG).also { r.keep(it) }
            tagMats += tm
            tags += model(r.tag, tm, "tag:$s", root, Vec3(0f, -20f, 0f)).also { it.show(false) }
        }

        // E4 3D big buttons (olive body, cream face with the word)
        for (b in Button3D.entries) {
            val h = holder(Names.btn(b), root, Vec3(0f, -20f, 0f))
            pickable(h, Vec3(Layout.BTN_D, Layout.BTN_D, 0.04f), r)
            model(r.btnBody, r.olive, "btnBody:${b.id}", h, rot = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()))
            val fm = SceneRes.unlit(Pal.OLIVE_DEEP).also { r.keep(it) }
            btnMats[b] = fm
            model(r.btnFace, fm, "btnFace:${b.id}", h, Vec3(0f, 0f, Layout.BTN_T / 2f + 0.0008f))
            h.show(false)
            buttons[b] = h
        }

        // E6 off-view arrow: a V of two amber strokes + the timer's name
        val ar = holder("arrowRoot", root, Vec3(0f, -20f, 0f))
        model(r.arrowStroke, r.arrowMat, "arrowA", ar, Vec3(-0.012f, 0.012f, 0f), Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(40.0).toFloat()))
        model(r.arrowStroke, r.arrowMat, "arrowB", ar, Vec3(-0.012f, -0.012f, 0f), Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(-40.0).toFloat()))
        arrowTextMat = SceneRes.unlit(Pal.TAG_BG).also { r.keep(it) }
        arrowText = model(r.arrowText, arrowTextMat!!, "arrowText", root, Vec3(0f, -20f, 0f))
        ar.show(false); arrowText?.show(false)
        arrowRoot = ar
        refreshStaticTexts()
        AppLog.i("SCENE built")
    }

    private fun loadBell() {
        scope.launch(Dispatchers.IO) {
            val list = listOf("bell_1", "bell_2").mapNotNull { n ->
                runCatching { AudioResource.load(n, "asset://sounds/$n.wav") }.onFailure { AppLog.w("PROBE spatial audio: load $n failed: $it") }.getOrNull()
            }
            withContext(Dispatchers.Main) { bellRes = list; AppLog.i("PROBE spatial audio: ${list.size} bell resources loaded") }
        }
    }

    /** Bell on the timer entity (ObjectAudioComponent + playAudio): spatial at the timer. One bell at a time (D51). */
    private fun playBell(slot: Int, volume: Float): Boolean {
        if (bellRes.isEmpty()) return false
        val h = timerHolders.getOrNull(slot) ?: return false
        return runCatching {
            bellCtl?.let { runCatching { it.stop(); it.close() } }
            val c = h.playAudio(bellRes[(graph.now() / 1200 % bellRes.size).toInt()]) ?: return false
            c.setVolume(volume)
            bellCtl = c
            true
        }.onFailure { AppLog.w("PROBE spatial audio: play failed: $it") }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ placement (one function: Arrange)

    private fun placeKitchen(p: Layout.Pose, why: String) {
        pose = p
        kitchenRoot?.place(position = p.origin, rotation = p.rotation)
        val wy = graph.window.openYaw
        AppLog.i("PLACE kitchen why=$why origin=${fmt(p.origin)} stoveYaw=${"%.1f".format(p.yawDeg)} windowAz=${wy?.let { "%.1f".format(Arrange.windowAz(p, it)) }}")
    }

    /** Settings [叫回面前]: close the window, wait for the user to face the stove (D29), lay out, reopen at az −20°. */
    fun startRecall() {
        graph.window.closeOnly("recall")
        recallPending = true
        sampler.start(graph.now(), lastPose?.forward)
        AppLog.i("PLACE recall requested pressYaw=${lastPose?.let { "%.1f".format(HeadSampler.yawDeg(it.forward)) }}")
    }

    private fun arrangeForWindow() {
        val w = graph.window
        if (w.openSeq == arrangedSeq) return
        val wy = w.openYaw ?: return
        val head = w.openHead ?: lastPose?.position ?: Vec3(0f, 1.6f, 0f)
        arrangedSeq = w.openSeq
        val before = pose
        val after = Arrange.onWindowOpen(before, head, wy)
        if (after != before) placeKitchen(after, "window-open#${w.openSeq} ${w.openWhy}") else AppLog.i("PLACE kitchen kept window-open#${w.openSeq} windowAz=${"%.1f".format(Arrange.windowAz(after, wy))}")
        if (flow.s.loading) flow.loaded()
    }

    // ------------------------------------------------------------------ frame

    fun onFrame() {
        val now = graph.now()
        val dt = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceAtMost(100L)
        lastFrameMs = now
        if (!graph.frozen) { animMs += dt; visNow = now }
        val hp = head.pose()
        if (hp != null) lastPose = hp
        val yawDeg = hp?.let { HeadSampler.yawDeg(it.forward) }
        graph.window.stepFrame(hp?.let { it.position to it.forward }, hp?.pitchDeg, yawDeg)
        val r = res ?: return
        // cold start (QA #1 q1-1 / q1-3): first steady head → kitchen at once (yaw only, any pitch / roll), window asked
        // for straight ahead with no glance target, so it opens as soon as the head is level (≤ 3 s if it stays pitched)
        if (pose == null && !forceLoading) {
            coldStart.step(now - startedMs, hp?.position, hp?.forward, hp != null && head.steady, lastPose?.position, lastPose?.forward)?.let { c ->
                placeKitchen(c.pose, c.why + " pitch=" + (hp?.let { "%.0f".format(it.pitchDeg) } ?: "-"))
                graph.window.request(c.why, null)
                flow.loaded()
                if (!flow.settings.value.firstRunDone) { firstRunUntil = now + 3000L; flow.firstRunShown() }
            }
        }
        if (graph.window.openSeq != arrangedSeq) arrangeForWindow()
        if (recallPending && hp != null) {
            sampler.step(now, hp.position, hp.forward)?.let { s ->
                recallPending = false
                val p = Arrange.fromSample(s.position, Layout.yawOf(s.forward))
                placeKitchen(p, "recall sampleYaw=${"%.1f".format(s.yawDeg)}")
                graph.window.request("recall", Arrange.windowTargetYawDeg(p))
            }
        }
        flow.tick()
        logPlacement(now)
        renderTimers(r, now, hp)
        renderSpots(now)
        renderButtons(r, now)
        renderHints(now, hp)
        renderFood(hp)
        hudFrame(hp)
    }

    /** E7: the food of the cook page (prep row / step), also while the window shows another page mid-recipe; the
     *  finished page keeps the last step's dish. Nothing before a recipe is opened. */
    private fun renderFood(hp: HeadPose?) {
        val p = pose ?: return
        val st = flow.s
        val r = flow.current
        if (r?.id != foodRecipe) { foodRecipe = r?.id; r?.let { food.preload(StepModels.forRecipe(it)) } }
        val pages = flow.pages
        val pg = if (r == null || st.loading || forceLoading) null else pages.getOrNull(if (st.done) pages.size - 1 else st.pageIndex)
        val ids = if (r != null && pg != null) StepModels.forPage(r, pg) else emptyList()
        val key = if (pg != null) "${r?.id}/${pg.stepNo}" else null
        val eye = hp?.position ?: lastPose?.position
        val eyeLocal = eye?.let { p.toLocal(it) } ?: Vec3.ZERO
        food.frame(key, ids, eyeLocal, SystemClock.elapsedRealtime())
    }

    /** debug `food`. */
    fun foodLine(): String = food.dump()

    private val logged = BooleanArray(3)
    private fun logPlacement(now: Long) {
        val t = now - startedMs
        for ((i, s) in listOf(1000L, 3000L, 5000L).withIndex()) if (!logged[i] && t >= s) {
            logged[i] = true
            val h = lastPose; val p = pose
            val rackW = p?.toWorld(Layout.rackTopCenter)
            val az = if (h != null && rackW != null) Layout.diffDeg(Math.toDegrees(atan2(-(rackW.x - h.position.x), -(rackW.z - h.position.z)).toDouble()).toFloat(), HeadSampler.yawDeg(h.forward)) else null
            AppLog.i("DIAG t=${s / 1000}s head=${h?.let { fmt(it.position) + " yaw=" + "%.0f".format(HeadSampler.yawDeg(it.forward)) + " pitch=" + "%.0f".format(it.pitchDeg) }} kitchen=${p?.let { fmt(it.origin) + " yaw=" + "%.0f".format(it.yawDeg) }} rack=${rackW?.let { fmt(it) }} rackYawFromGaze(+left)=${az?.let { "%.0f".format(it) }} window=${graph.window.panelState}")
        }
    }

    /** World point of a [Place] for timer [slot] (null until the kitchen pose is known). */
    private fun worldOf(place: Place, slot: Int): Vec3? = when (place) {
        is Place.Rack -> pose?.toWorld(Layout.slotBase(place.slot) + Vec3(0f, -0.002f, 0f))
        Place.Stage -> pose?.toWorld(Layout.stageBase)
        is Place.At -> place.world + Vec3(0f, Layout.LAND_LIFT, 0f)
    }

    private fun ease(u: Float, kind: Int): Float = when (kind) {
        0 -> 1f - (1f - u).let { it * it * it }                                  // easeOutCubic (pick up)
        else -> if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).let { it * it * it } / 2f // easeInOutCubic
    }

    /** Where timer [t] is drawn now (animation along an arc, landing dip). */
    private fun timerWorld(t: Timer, now: Long): Vec3? {
        val a = t.anim
        val rest: Place = when (t.state) {
            TState.DOCKED -> Place.Rack(t.slot)
            TState.STAGED -> Place.Stage
            TState.RETURNING -> Place.Rack(t.slot)
            else -> t.spotWorld?.let { Place.At(it) } ?: Place.Stage
        }
        if (a == null) return worldOf(rest, t.slot)
        val u = ((now - a.startMs).toFloat() / a.durMs).coerceIn(0f, 1f)
        if (u >= 1f) return worldOf(a.to, t.slot)
        val from = worldOf(a.from, t.slot) ?: return null
        val to = worldOf(a.to, t.slot) ?: return null
        val e = ease(u, if (a.to == Place.Stage) 0 else 1)
        var p = from.lerp(to, e) + Vec3(0f, a.arc * 4f * e * (1f - e), 0f)
        if (u > 0.9f) p += Vec3(0f, -a.dip * sin(((u - 0.9f) / 0.1f) * PI.toFloat()), 0f)
        return p
    }

    private fun faceEye(p: Vec3, eye: Vec3): Quat4 = Quat4.axisAngle(Vec3.UP, atan2(eye.x - p.x, eye.z - p.z))

    private fun renderTimers(r: SceneRes, now: Long, hp: HeadPose?) {
        val eye = hp?.position ?: pose?.origin ?: Vec3(0f, 1.6f, 0f)
        val zh = graph.zh()
        for (t in board.timers) {
            val s = t.slot
            val h = timerHolders[s]
            val p = timerWorld(t, now)
            if (p == null) { h.place(position = Vec3(0f, -20f, 0f)); tags[s].show(false); shadows[s].show(false); continue }
            var rot = faceEye(p, eye)
            // E2 到点晃: ±8° about the facing axis, 6 Hz decaying, 0.4 s every 1.2 s (aligned with the bell)
            if (t.state == TState.RINGING) {
                val ph = ((visNow - t.ringAtMs).mod(1200L)).toFloat() / 1000f
                if (ph < 0.4f) rot = rot * Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(8.0 * exp(-ph * 6.0) * sin(ph * 2 * PI * 6)).toFloat())
            }
            // the first pinch on a floating timer wiggles it (「再捏收回」)
            val wig = (now - t.wiggleAtMs).toFloat() / 1000f
            if (t.state == TState.STAGED && wig in 0f..0.3f) rot = rot * Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(6.0 * sin(wig * 2 * PI * 8) * (1 - wig / 0.3)).toFloat())
            h.place(position = p, rotation = rot)
            // collider size: Ø 0.11 on the rack, 0.12 elsewhere (handled by scale of the holder's shape is not possible; the docked gap test uses 0.11)
            // dial: remaining minutes at the pointer (60 min = 360°), smoothed 0.2 s; arc = remaining (2° steps)
            val remMin = t.remainingMs(now) / 60000f
            val target = -(remMin.coerceAtMost(60f) * 6f)
            dialAngle[s] = if (abs(dialAngle[s] - target) > 90f) target else dialAngle[s] + (target - dialAngle[s]) * (1f - exp(-(dtFrame()) / 0.2f))
            dials[s].place(rotation = Quat4.axisAngle(Vec3.UP, Math.toRadians(dialAngle[s].toDouble()).toFloat()))
            updateArc(r, s, t, remMin, now)
            // dimming (M16) and the body-near fade (D14: within 0.35 m → 30 %)
            val near = hp != null && Vec3(p.x - eye.x, 0f, p.z - eye.z).length < 0.35f
            val wantFade = board.dimmed(s) || near
            if (wantFade != faded[s]) {
                faded[s] = wantFade
                if (wantFade) h.components.set(OpacityControllerComponent(0.3f)) else h.components.remove(OpacityControllerComponent::class.java)
            }
            // contact shadow under timers standing on something
            val onSurface = t.state == TState.DOCKED || t.state in setOf(TState.RUNNING, TState.PAUSED, TState.RINGING, TState.STOPPED)
            shadows[s].show(onSurface && t.anim == null)
            if (onSurface) shadows[s].place(position = p + Vec3(0f, 0.0008f, 0f))
            // tag E3 (not on the rack)
            val showTag = t.state != TState.DOCKED && t.state != TState.RETURNING
            tags[s].show(showTag)
            if (showTag) {
                val tc = Layout.tagCenter(p)
                tags[s].place(position = tc, rotation = faceEye(tc, eye))
                setTag(s, tagLines(t, now, zh))
            }
            slotDash[s].show(t.state != TState.DOCKED)
        }
    }

    private fun dtFrame(): Float = 1f / 72f

    private fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    private fun str(id: Int): String = context.localized(flow.settings.value.lang).getString(id)

    /** E3 tag lines by state (design: 浮起「看个圈 · 捏一下」、暂停「停着」、到点「好了 · 捏我停」、停了「好了 ✓」). */
    private fun tagLines(t: Timer, now: Long, zh: Boolean): Pair<String, String> {
        val name = t.label?.let { if (zh) it.zh else it.en } ?: ""
        val time = mmss(t.shownSeconds(now))
        return when (t.state) {
            TState.STAGED -> time to when (t.hint) { Hint.ADD_TIME -> str(R.string.tag_add); Hint.TAP_AGAIN -> str(R.string.tag_again); else -> str(R.string.tag_look) }
            TState.PAUSED -> time to str(R.string.tag_paused)
            TState.RINGING -> "0:00" to str(R.string.tag_ring)
            TState.STOPPED -> str(R.string.tag_done) to name
            else -> time to name
        }
    }

    private fun setTag(s: Int, lines: Pair<String, String>) {
        val key = "${lines.first}|${lines.second}"
        if (tagWanted[s] == key) return
        tagWanted[s] = key
        val r = res ?: return
        scope.launch {
            val tex = withContext(Dispatchers.IO) {
                runCatching {
                    val (bmp, fits) = Textures.twoLines(lines.first, lines.second, 640, 220, Pal.TAG_BG.argb(), Pal.TAG_INK.argb(), hex(0x4A4038).argb())
                    if (!fits) AppLog.w("OVERFLOW 3d/tag$s \"$key\"")
                    TextureResource.create(bmp)
                }.getOrNull()
            } ?: return@launch
            if (tagWanted[s] != key || res !== r) { runCatching { tex.close() }; return@launch }
            tagMats[s].setBaseColor(Color4(1f, 1f, 1f, 1f)); tagMats[s].setBaseColorTexture(tex)
            tagTex[s]?.let { old -> runCatching { old.close() } }
            tagTex[s] = tex
        }
    }

    /** E2 progress arc: a sector of the bezel, 2° steps, colour dot / grey when paused / amber pulse when ringing. */
    private fun updateArc(r: SceneRes, s: Int, t: Timer, remMin: Float, now: Long) {
        val steps = when (t.state) {
            TState.RINGING -> 180
            TState.DOCKED, TState.RETURNING, TState.STOPPED -> 0
            else -> (remMin.coerceAtMost(60f) * 3f).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, 180)
        }
        val holderE = arcs[s]
        if (steps != arcShown[s]) {
            arcShown[s] = steps
            holderE.removeAllChildren()
            if (steps > 0) {
                val mesh = arcMeshes.getOrPut(steps) { r.keep(Meshes.annulusY(SceneRes.ARC_IN, SceneRes.ARC_OUT, "arc$steps", startDeg = -steps * 2f, sweepDeg = steps * 2f, segments = 180)) }
                model(mesh, arcMats[s], "arc:$s", holderE)
            }
        }
        val c = when (t.state) {
            TState.PAUSED -> Pal.GREY
            TState.RINGING -> Pal.AMBER
            else -> Pal.ARCS[s]
        }
        arcMats[s].setBaseColor(c.c4(1f))
        if (t.state == TState.RINGING) {
            val ph = ((visNow - t.ringAtMs).mod(1200L)).toFloat() / 1200f
            val k = 0.4f + 0.6f * (0.5f + 0.5f * kotlin.math.cos(ph * 2 * PI).toFloat())
            arcMats[s].setEmissiveColor(Color4(0.89f * k, 0.60f * k, 0.16f * k, 1f))
        } else arcMats[s].setEmissiveColor(Color4(0f, 0f, 0f, 1f))
    }

    private fun renderSpots(now: Long) {
        val p = pose ?: return
        val staged = board.staged
        val show = staged != null
        if (show && !spotsShown) for (i in 0 until 5) spotShownAt[i] = animMs + abs(i - 2) * 40L
        spotsShown = show
        for (i in 0 until 5) {
            val w = p.toWorld(Layout.spotCenter(i))
            val vis = show && board.spotFree(w)
            spotHolders[i].show(vis)
            if (vis) {
                // fade-in: scale 0.8 → 1 over 150 ms, staggered from the middle; refused = wiggle
                val u = ((animMs - spotShownAt[i]).toFloat() / 150f).coerceIn(0f, 1f)
                var k = 0.8f + 0.2f * (1f - (1f - u) * (1f - u))
                if (refusedSpot == i) { val q = (now - refusedAt) / 1000f; if (q < 0.4f) k *= 1f + 0.08f * sin(q * 2 * PI.toFloat() * 7f) * (1f - q / 0.4f) }
                spotHolders[i].scaleAll(k)
            }
        }
    }

    private var refusedSpot = -1
    private var refusedAt = 0L

    private fun renderButtons(r: SceneRes, now: Long) {
        val eye = lastPose?.position ?: pose?.origin ?: Vec3(0f, 1.6f, 0f)
        val staged = board.staged
        val stagedReady = staged != null && staged.anim?.let { now - it.startMs >= it.durMs } != false
        if (staged?.slot != lastStaged) { lastStaged = staged?.slot; stagedPopAt = animMs + 380L }
        val focus = board.pauseFocus?.takeIf { staged == null && board.timers[it].state == TState.PAUSED }
        if (focus != lastPauseFocus) { lastPauseFocus = focus; pausedPopAt = animMs }
        val stageBtns = listOf(Button3D.PLUS1, Button3D.PLUS5, Button3D.CLEAR)
        val pauseBtns = listOf(Button3D.P_PLUS1, Button3D.P_BACK)
        val sp = staged?.let { timerWorld(it, now) }
        val stagedCenters = if (stagedReady && sp != null) Layout.buttonCenters(sp, 3) else null
        stageBtns.forEachIndexed { k, b -> placeBtn(b, stagedCenters?.get(k), eye, stagedPopAt) }
        val pp = focus?.let { timerWorld(board.timers[it], now) }
        val pauseCenters = pp?.let { Layout.buttonCenters(it, 2) }
        pauseBtns.forEachIndexed { k, b -> placeBtn(b, pauseCenters?.get(k), eye, pausedPopAt) }
    }

    private fun placeBtn(b: Button3D, at: Vec3?, eye: Vec3, popAt: Long) {
        val e = buttons[b] ?: return
        if (at == null) { e.show(false); return }
        e.show(true)
        val u = ((animMs - popAt).toFloat() / 120f).coerceIn(0f, 1f)
        val k = 0.6f + 0.4f * (1f - (1f - u) * (1f - u))
        e.place(position = at, rotation = faceEye(at, eye), scale = Vec3(k, k, k))
    }

    private fun refreshStaticTexts() {
        val zh = graph.zh()
        setStatic(btnMats[Button3D.PLUS1], "+1", 256, 256, Pal.OLIVE_DEEP, Pal.CREAM, "btn/plus1", 0.55f)
        setStatic(btnMats[Button3D.PLUS5], "+5", 256, 256, Pal.OLIVE_DEEP, Pal.CREAM, "btn/plus5", 0.55f)
        setStatic(btnMats[Button3D.CLEAR], str(R.string.btn_clear), 256, 256, Pal.OLIVE_DEEP, Pal.CREAM, "btn/clear", if (zh) 0.36f else 0.28f)
        setStatic(btnMats[Button3D.P_PLUS1], "+1", 256, 256, Pal.OLIVE_DEEP, Pal.CREAM, "btn/pplus1", 0.55f)
        setStatic(btnMats[Button3D.P_BACK], str(R.string.btn_back), 256, 256, Pal.OLIVE_DEEP, Pal.CREAM, "btn/back", if (zh) 0.36f else 0.24f)
        setStatic(plateFaceMat, str(R.string.plate_name), 512, 128, Pal.BRASS, hex(0x2A1E12), "plate", 0.62f)
        setStatic(plateHintMat, str(R.string.plate_open), 960, 144, Pal.TAG_BG, Pal.TAG_INK, "plateHint", 0.54f)
        setStatic(firstRunMat, str(R.string.first_run), 960, 144, Pal.TAG_BG, Pal.TAG_INK, "firstRun", 0.62f)
        lastLang = flow.settings.value.lang
    }

    private var lastLang = flow.settings.value.lang
    private var arrowName = ""

    /** Plate hint「捏我开菜谱」, first-run「捏我计时」, the E6 off-view arrow; language changes refresh the static words. */
    private fun renderHints(now: Long, hp: HeadPose?) {
        if (flow.settings.value.lang != lastLang) { refreshStaticTexts(); tagWanted.fill(null) }
        val w = graph.window
        plateHint?.show(pose != null && !w.open && w.everOpened && !w.gate.waiting && !w.exiting && board.plateTappable())
        firstRun?.show(now < firstRunUntil)
        // E6: ringing timer outside ±40° of the gaze → amber V at the edge of view (0.9 m, 33° toward it, ×1.5; the name 5° further in), yaw only
        // (QA #1 known issue: it sat at ~70 % of the view and the name covered its lower half)
        val ar = arrowRoot ?: return
        val ringing = board.ringing.firstOrNull()
        if (ringing == null || hp == null) { ar.show(false); arrowText?.show(false); return }
        val tp = timerWorld(ringing, now) ?: run { ar.show(false); arrowText?.show(false); return }
        val headYaw = HeadSampler.yawDeg(hp.forward)
        val tYaw = Math.toDegrees(atan2(-(tp.x - hp.position.x).toDouble(), -(tp.z - hp.position.z).toDouble())).toFloat()
        val d = Layout.diffDeg(tYaw, headYaw) // + = timer to the left
        val out = abs(d) > 40f
        ar.show(out); arrowText?.show(out)
        if (!out) return
        val side = if (d > 0f) 1f else -1f
        val yaw = Math.toRadians((headYaw + side * ARROW_AZ).toDouble())
        val at = hp.position + Vec3(-sin(yaw).toFloat(), 0f, -kotlin.math.cos(yaw).toFloat()) * 0.9f + Vec3(0f, -0.08f, 0f)
        // the V points toward the timer: local −X is the tip; flip for the right side
        val face = faceEye(at, hp.position)
        val pointLeft = side > 0f
        ar.place(position = at, rotation = if (pointLeft) face else face * Quat4.axisAngle(Vec3.UP, PI.toFloat()), scale = Vec3(1.5f, 1.5f, 1.5f))
        val ty = Math.toRadians((headYaw + side * (ARROW_AZ - 5f)).toDouble())
        val tt = hp.position + Vec3(-sin(ty).toFloat(), 0f, -kotlin.math.cos(ty).toFloat()) * 0.9f + Vec3(0f, -0.08f - 0.075f, 0f)
        arrowText?.place(position = tt, rotation = faceEye(tt, hp.position), scale = Vec3(1.3f, 1.3f, 1.3f))
        val name = ringing.label?.let { if (graph.zh()) it.zh else it.en } ?: ""
        if (name != arrowName) { arrowName = name; setStatic(arrowTextMat, name, 560, 120, Pal.TAG_BG, Pal.TAG_INK, "arrow", 0.66f) }
    }

    private val textCache = HashMap<String, TextureResource>()
    private val textWanted = HashMap<UnlitMaterial, String>()

    /** Static words (cached): button faces, plate, hints. */
    private fun setStatic(mat: UnlitMaterial?, text: String, w: Int, h: Int, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, tag: String, sizeFrac: Float = 0.72f) {
        mat ?: return
        val key = "$text|$w|$h|${bg.value}|${fg.value}|$sizeFrac"
        if (textWanted[mat] == key) return
        textWanted[mat] = key
        textCache[key]?.let { mat.setBaseColor(Color4(1f, 1f, 1f, 1f)); mat.setBaseColorTexture(it); return }
        scope.launch {
            val tex = withContext(Dispatchers.IO) {
                runCatching {
                    val (bmp, fits) = Textures.textPlate(text, w, h, bg.argb(), fg.argb(), sizeFrac = sizeFrac)
                    if (!fits) AppLog.w("OVERFLOW 3d/$tag \"$text\"")
                    TextureResource.create(bmp).also { res?.keep(it) }
                }.getOrNull()
            } ?: return@launch
            textCache[key] = tex
            if (textWanted[mat] == key) { mat.setBaseColor(Color4(1f, 1f, 1f, 1f)); mat.setBaseColorTexture(tex) }
        }
    }

    // ------------------------------------------------------------------ HUD (Stage attachment: loading / level / look here)

    private fun hudFrame(hp: HeadPose?) {
        val h = hudHolder ?: return
        val w = graph.window
        val msg = when {
            w.exiting -> HudMsg.NONE
            forceLoading || !ready.value || (pose == null && flow.s.loading) -> HudMsg.LOADING
            recallPending -> HudMsg.LOOK_STOVE
            w.levelHint -> HudMsg.LEVEL
            w.lookHint -> HudMsg.LOOK_WINDOW
            else -> HudMsg.NONE
        }
        if (hud.value != msg) { hud.value = msg; AppLog.i("HUD $msg") }
        val want = msg != HudMsg.NONE
        if (panelShown[PanelIds.HUD] != want) panelShown[PanelIds.HUD] = want
        val eye = hp?.position ?: lastPose?.position
        val at: Vec3? = when (msg) {
            HudMsg.NONE -> null
            HudMsg.LOOK_WINDOW -> w.gate.target?.let { t ->
                val y = Math.toRadians(t.toDouble()); val e = eye ?: pose?.origin ?: return@let null
                e + Vec3(-sin(y).toFloat(), 0f, -kotlin.math.cos(y).toFloat()) * 1.0f + Vec3(0f, -0.05f, 0f)
            }
            // on the sight line (pitch clamped ±40°), 0.75 m, yaw-only facing (D9)
            else -> hp?.let { val f = it.forward; val fl = Vec3(f.x, 0f, f.z).normalized(); val pitch = Math.toRadians(it.pitchDeg.coerceIn(-40f, 40f).toDouble())
                it.position + fl * (0.75f * kotlin.math.cos(pitch).toFloat()) + Vec3(0f, 0.75f * sin(pitch).toFloat() - 0.04f, 0f) }
        }
        if (want && at != null && eye != null) h.place(position = at, rotation = faceEye(at, eye), scale = Vec3(1f, 1f, 1f))
        else h.place(position = Vec3(0f, -20f, 0f), scale = Vec3(0.001f, 0.001f, 0.001f))
    }

    // ------------------------------------------------------------------ sound helpers

    /** Stereo pan (−1 left … +1 right) of timer [slot] seen from the head (mixer fallback for the bell and the clicks). */
    fun panOf(slot: Int): Float {
        val hp = lastPose ?: return 0f
        val p = timerWorld(board.timers[slot], graph.now()) ?: return 0f
        val d = Layout.diffDeg(Math.toDegrees(atan2(-(p.x - hp.position.x).toDouble(), -(p.z - hp.position.z).toDouble())).toFloat(), HeadSampler.yawDeg(hp.forward))
        return (-sin(Math.toRadians(d.toDouble())).toFloat()).coerceIn(-1f, 1f)
    }

    // ------------------------------------------------------------------ pointer input (board-input listener)

    fun onTap(hit: Hit?, source: InputSource) {
        AppLog.i("TAP3D src=${source.label} hit=$hit state=${flow.s.stateId} timers=${board.summary()}")
        if (hit == null) return
        val key = when (hit) { is Hit.TimerHit -> Names.timer(hit.slot); is Hit.Spot -> Names.spot(hit.i); is Hit.Btn -> Names.btn(hit.b); Hit.Plate -> Names.PLATE }
        if (board.debounce(key)) { AppLog.i("TAP3D ignored: same target < 300 ms"); return }
        when (hit) {
            is Hit.TimerHit -> flow.tapTimer(hit.slot)
            is Hit.Spot -> {
                val p = pose ?: return
                val w = p.toWorld(Layout.spotCenter(hit.i))
                if (board.staged == null || !board.spotFree(w)) return
                if ((board.staged?.remainingMs(graph.now()) ?: 0L) <= 0L) { refusedSpot = hit.i; refusedAt = graph.now() }
                flow.tapSpot(hit.i, w)
            }
            is Hit.Btn -> if (buttons[hit.b]?.enabled == true) when (hit.b) {
                Button3D.PLUS1 -> flow.stagedAdd(1)
                Button3D.PLUS5 -> flow.stagedAdd(5)
                Button3D.CLEAR -> flow.stagedClear()
                Button3D.P_PLUS1 -> flow.pausedAdd()
                Button3D.P_BACK -> flow.pausedRecall()
            }
            Hit.Plate -> if (board.plateTappable() && !graph.window.open) {
                val p = pose
                graph.window.request("plate", p?.let { Arrange.windowTargetYawDeg(it) })
            }
        }
    }

    // ------------------------------------------------------------------ debug hooks (same paths as real input)

    fun spotWorld(i: Int): Vec3? = pose?.toWorld(Layout.spotCenter(i))

    fun dumpLine(): String {
        val p = pose; val h = lastPose
        return "kitchen=${p?.let { fmt(it.origin) + " yaw=" + "%.1f".format(it.yawDeg) }} head=${h?.let { fmt(it.position) + " yaw=" + "%.1f".format(HeadSampler.yawDeg(it.forward)) + " pitch=" + "%.1f".format(it.pitchDeg) }} window=${graph.window.panelState} openYaw=${graph.window.openYaw?.let { "%.1f".format(Math.toDegrees(it.toDouble())) }} hud=${hud.value} timers=${board.summary()}"
    }

    /** debug `where`: world positions of the rack, rings and timers (for D35 checks against screenshots). */
    fun whereLine(): String {
        val p = pose ?: return "no pose"
        val spots = (0 until 5).joinToString(" ") { "s$it=${fmt(p.toWorld(Layout.spotCenter(it)))}" }
        val ts = board.timers.joinToString(" ") { "t${it.slot}=${fmt(timerWorld(it, graph.now()))}" }
        return "rack=${fmt(p.toWorld(Layout.rackTopCenter))} stage=${fmt(p.toWorld(Layout.stageBase))} $spots $ts"
    }

    companion object {
        /** Key light (lux-scale SDK value, build.md 2026-11-12: 0.8–1.2 = no light). Tuned on the emulator, ui-audit 灯光. */
        var KEY_LIGHT = 1200f
        /** E6 arrow: degrees from the gaze toward the ringing timer (edge of view; it shows when the timer is > 40° away). */
        const val ARROW_AZ = 33f
        fun fmt(v: Vec3?) = v?.let { "(%.3f, %.3f, %.3f)".format(it.x, it.y, it.z) } ?: "null"
    }
}
