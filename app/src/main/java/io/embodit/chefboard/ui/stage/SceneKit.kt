package io.embodit.chefboard.ui.stage

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.chefboard.domain.math.Quat4
import io.embodit.chefboard.domain.math.Vec3
import io.embodit.chefboard.domain.place.Layout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Vec3.sdk() = Vector3(x, y, z)
internal fun Vector3.domain() = Vec3(x, y, z)
internal fun Quat4.sdk() = Quat(x, y, z, w)
internal fun Color.c4(alpha: Float = this.alpha) = Color4(red, green, blue, alpha)
internal fun hex(rgb: Long) = Color(0xFF000000 or rgb)
internal fun Color.argb(): Int = android.graphics.Color.argb(255, (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

internal fun Entity.place(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = components[TransformComponent::class.java] ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.scaleAll(k: Float) = place(scale = Vec3(k, k, k))
internal fun Entity.setMaterial(m: Material) { components[ModelComponent::class.java]?.materials?.set(0, m) }
internal fun Entity.show(on: Boolean) { if (enabled != on) enabled = on }

/** design theme / entities3d colours. */
internal object Pal {
    val WALNUT = hex(0x5A3E2B); val BRASS = hex(0xB08D57); val CREAM = hex(0xF3EDE0); val GLAZE_RED = hex(0xC8402F)
    val INK = hex(0x2B2622); val OLIVE = hex(0x5E6B3A); val OLIVE_DEEP = hex(0x4F5A2E); val AMBER = hex(0xE39A2A)
    val GREY = hex(0x9A968E); val TAG_BG = hex(0xFBF7EE); val TAG_INK = hex(0x1F1B17); val SPOT = hex(0xF1EADB)
    val WELL = hex(0x3E2A1D); val DASH = hex(0xD9CBB0)
    /** The four colour dots: tomato red / sage green / mustard / mist blue (E2). */
    val DOTS = listOf(hex(0xD2533F), hex(0x8FA58A), hex(0xD8B04A), hex(0x8AA3BE))
}

/**
 * Shared meshes / materials / textures of the Stage, made concurrently off the main thread (resource creation waits on
 * the renderer) and kept until [close]. Every visible part is PBR (walnut, glazed ceramic, cream bezel, brass); unlit
 * only for text plates, the drop-ring fill, shadows and the arrow (assets: 钮和圈是不透明实体，承载 HoverEffect).
 */
internal class SceneRes private constructor() {
    private val all = ArrayList<Resource>()
    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }
    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    lateinit var physics: PhysicsMaterialResource
    // rack
    lateinit var rack: MeshResource; lateinit var well: MeshResource; lateinit var dashed: MeshResource; lateinit var plate: MeshResource; lateinit var plateFace: MeshResource
    // timer
    lateinit var bodyLow: MeshResource; lateinit var bodyHigh: MeshResource; lateinit var bezel: MeshResource; lateinit var dial: MeshResource
    lateinit var knob: MeshResource; lateinit var pointer: MeshResource; lateinit var shadow: MeshResource
    // tag, buttons, rings, arrow, hint plates
    lateinit var tag: MeshResource; lateinit var btnBody: MeshResource; lateinit var btnFace: MeshResource
    lateinit var spotRing: MeshResource; lateinit var spotDot: MeshResource; lateinit var spotFill: MeshResource
    lateinit var arrowStroke: MeshResource; lateinit var hintPlate: MeshResource; lateinit var arrowText: MeshResource

    lateinit var walnut: PhysicallyBasedMaterial; lateinit var wellMat: PhysicallyBasedMaterial; lateinit var brass: PhysicallyBasedMaterial
    lateinit var glaze: PhysicallyBasedMaterial; lateinit var cream: PhysicallyBasedMaterial; lateinit var dialMat: PhysicallyBasedMaterial
    lateinit var ink: PhysicallyBasedMaterial; lateinit var dots: List<PhysicallyBasedMaterial>
    lateinit var olive: PhysicallyBasedMaterial; lateinit var spotMat: PhysicallyBasedMaterial; lateinit var spotFillMat: UnlitMaterial
    lateinit var dashMat: UnlitMaterial; lateinit var shadowMat: UnlitMaterial; lateinit var arrowMat: UnlitMaterial
    lateinit var arcGrey: PhysicallyBasedMaterial

    companion object {
        const val ARC_IN = 0.0474f
        const val ARC_OUT = 0.0532f
        const val DIAL_OUT = 0.0600f
        /** Bezel top (timer base = 0): the dial and the arc lie on it. */
        const val BEZEL_TOP = 0.040f
        const val BEZEL_BOTTOM = 0.024f

        fun pbr(c: Color, rough: Float, metal: Float = 0f, tex: TextureResource? = null, glow: Color? = null): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                if (tex != null) { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(tex) } else setBaseColor(c.c4(1f))
                setRoughness(rough); setMetallic(metal)
                if (glow != null) setEmissiveColor(glow.c4(1f))
            }

        fun unlit(c: Color, opacity: Float = 1f, tex: TextureResource? = null): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(c.c4(1f))
                tex?.let { setBaseColorTexture(it) }
                if (opacity < 1f) { setOpacity(opacity); setDepthWrite(false) }
                setCullingMode(MaterialCullingMode.NONE)
                runCatching { setApplyToneMapping(false) }
            }

        /** Timer housing profile (E2: Ø 0.09 × 0.07, lower half cream, upper half glazed red, rounded). */
        private fun housingLow() = listOf(0f to 0f, 0.040f to 0f, 0.0440f to 0.0025f, 0.0450f to 0.008f, 0.0450f to 0.035f, 0f to 0.035f)
        private fun housingHigh(): List<Pair<Float, Float>> {
            val pts = arrayListOf(0f to 0.035f, 0.0450f to 0.035f, 0.0450f to 0.050f)
            // dome: quarter ellipse from (0.045, 0.050) to (0, 0.070)
            for (i in 1..12) { val a = Math.PI / 2 * i / 12; pts += (0.045f * kotlin.math.cos(a).toFloat()) to (0.050f + 0.020f * kotlin.math.sin(a).toFloat()) }
            return pts
        }

        suspend fun create(): SceneRes = coroutineScope {
            val r = SceneRes()
            fun <T : Resource> T.k(): T = r.keep(this)
            val io = Dispatchers.IO
            val woodTex = async(io) { TextureResource.create(Textures.walnut()).k() }
            val dialTex = async(io) { TextureResource.create(Textures.dial(512, ARC_OUT, DIAL_OUT, DIAL_OUT, Pal.CREAM.argb(), Pal.INK.argb())).k() }
            val shadowTex = async(io) { TextureResource.create(Textures.shadow()).k() }
            listOf(
                async(io) { r.physics = PhysicsMaterialResource().k() },
                async(io) { r.rack = Meshes.slab(Layout.RACK_LEN, Layout.RACK_THICK, Layout.RACK_DEPTH, "rack", bevel = 0.008f).k() },
                async(io) { r.well = Meshes.annulusY(0f, 0.050f, "well", segments = 64).k() },
                async(io) { r.dashed = Meshes.ringY(0.046f, 0.050f, "slotDash", segments = 64, dashes = 16).k() },
                async(io) { r.plate = Meshes.slab(Layout.PLATE_W, 0.026f, 0.006f, "plate", bevel = 0.002f).k() },
                async(io) { r.plateFace = Meshes.quadZ(Layout.PLATE_W - 0.006f, 0.020f, "plateFace").k() },
                async(io) { r.bodyLow = Meshes.lathe(housingLow(), "bodyLow", 64).k() },
                async(io) { r.bodyHigh = Meshes.lathe(housingHigh(), "bodyHigh", 64).k() },
                // bezel ring (外圈 Ø 0.12, 0.016 tall, flat top so the dial and the arc lie on it): a true lathe ring
                async(io) { r.bezel = Meshes.lathe(listOf(0.0450f to BEZEL_BOTTOM, 0.0575f to BEZEL_BOTTOM, 0.0600f to BEZEL_BOTTOM + 0.003f, 0.0600f to BEZEL_TOP - 0.002f, 0.0590f to BEZEL_TOP, 0.0450f to BEZEL_TOP), "bezel", 64).k() },
                async(io) { r.dial = Meshes.annulusY(ARC_OUT, DIAL_OUT, "dial", segments = 96, uvR = DIAL_OUT).k() },
                async(io) { r.knob = Meshes.lathe(listOf(0f to 0f, 0.015f to 0f, 0.015f to 0.007f, 0.012f to 0.010f, 0f to 0.0105f), "knob", 32).k() },
                async(io) { r.pointer = Meshes.slab(0.004f, 0.003f, 0.012f, "pointer", bevel = 0.0008f).k() },
                async(io) { r.shadow = Meshes.annulusY(0f, 0.075f, "shadow", segments = 48).k() },
                async(io) { r.tag = Meshes.quadZ(Layout.TAG_W, Layout.TAG_H, "tag").k() },
                async(io) { r.btnBody = Meshes.lathe(listOf(0f to -Layout.BTN_T / 2, Layout.BTN_D / 2 - 0.004f to -Layout.BTN_T / 2, Layout.BTN_D / 2 to -Layout.BTN_T / 2 + 0.004f, Layout.BTN_D / 2 to Layout.BTN_T / 2 - 0.004f, Layout.BTN_D / 2 - 0.004f to Layout.BTN_T / 2, 0f to Layout.BTN_T / 2), "btnBody", 48).k() },
                async(io) { r.btnFace = Meshes.discZ(Layout.BTN_D / 2 - 0.006f, "btnFace", segments = 48).k() },
                // drop ring E5: flat torus Ø 0.12 (tube 0.010) + centre dot Ø 0.02 + 15 % fill
                async(io) { r.spotRing = Meshes.torusZ(Layout.SPOT_RING_D / 2 - 0.005f, 0.005f, "spotRing", 64, 12).k() },
                async(io) { r.spotDot = Meshes.lathe(listOf(0f to 0f, 0.010f to 0f, 0.010f to 0.003f, 0.008f to 0.005f, 0f to 0.005f), "spotDot", 24).k() },
                async(io) { r.spotFill = Meshes.annulusY(0f, Layout.SPOT_RING_D / 2 - 0.006f, "spotFill", segments = 48).k() },
                async(io) { r.arrowStroke = Meshes.slab(0.045f, 0.010f, 0.006f, "arrowStroke", bevel = 0.002f).k() },
                async(io) { r.hintPlate = Meshes.quadZ(0.30f, 0.045f, "hintPlate").k() },
                async(io) { r.arrowText = Meshes.quadZ(0.14f, 0.030f, "arrowText").k() },
                async(io) { r.wellMat = pbr(Pal.WELL, 0.8f).k() },
                async(io) { r.brass = pbr(Pal.BRASS, 0.38f, 0.6f).k() },
                async(io) { r.glaze = pbr(Pal.GLAZE_RED, 0.25f).k() },
                async(io) { r.cream = pbr(Pal.CREAM, 0.55f).k() },
                async(io) { r.ink = pbr(Pal.INK, 0.6f).k() },
                async(io) { r.dots = Pal.DOTS.map { pbr(it, 0.4f).k() } },
                async(io) { r.olive = pbr(Pal.OLIVE, 0.55f).k() },
                async(io) { r.spotMat = pbr(Pal.SPOT, 0.5f).k() },
                async(io) { r.arcGrey = pbr(Pal.GREY, 0.6f).k() },
                async(io) { r.spotFillMat = unlit(Pal.SPOT, 0.15f).k() },
                async(io) { r.dashMat = unlit(Pal.DASH).k() },
                async(io) { r.arrowMat = unlit(Pal.AMBER).k() },
            ).awaitAll()
            r.walnut = pbr(Pal.WALNUT, 0.55f, tex = woodTex.await()).k()
            r.dialMat = pbr(Pal.CREAM, 0.6f, tex = dialTex.await()).k()
            r.shadowMat = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(shadowTex.await()); setDepthWrite(false); runCatching { setApplyToneMapping(false) } }.k()
            r
        }
    }
}
