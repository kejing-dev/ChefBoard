package io.embodit.chefboard.ui.stage

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Programmatic meshes (G3: the deck, pads, ring, knob, sticks are procedural PBR — positions must match the colliders to
 * the millimetre). Round parts are true lathe / torus meshes with smooth normals and ≥ 24 segments (M16, gate
 * ring-from-boxes), never boxes around a circle.
 */
internal object Meshes {
    private class B {
        val pos = ArrayList<Vector3>(); val nor = ArrayList<Vector3>(); val uv = ArrayList<Vector2>(); val idx = ArrayList<Int>()
        fun v(p: Vector3, n: Vector3, t: Vector2 = Vector2(0f, 0f)): Int { pos += p; nor += n; uv += t; return pos.size - 1 }
        fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
        fun build(name: String): MeshResource {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in pos) { minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z) }
            val bounds = BoundingBox(Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
                Vector3(max(1e-4f, (maxX - minX) / 2), max(1e-4f, (maxY - minY) / 2), max(1e-4f, (maxZ - minZ) / 2)))
            return MeshResource.createWithMeshModel(MeshModel(positions = pos, triangleIndices = idx, normals = nor, uv0 = uv), bounds, name)
        }
    }

    private fun norm(x: Float, y: Float, z: Float): Vector3 { val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9f); return Vector3(x / l, y / l, z / l) }

    /**
     * Lathe: [profile] (radius, y) from the axis bottom to the top, revolved about +Y with [segments] steps. Normals are
     * smooth around and follow the profile slope; consecutive equal points make a hard edge.
     */
    fun lathe(profile: List<Pair<Float, Float>>, name: String, segments: Int = 48): MeshResource {
        val b = B()
        for (k in 0 until profile.size - 1) {
            val (r0, y0) = profile[k]; val (r1, y1) = profile[k + 1]
            if (r0 == r1 && y0 == y1) continue
            val dr = r1 - r0; val dy = y1 - y0
            // outward normal of the segment in the (r, y) plane: (dy, -dr)
            val nr = dy; val ny = -dr
            val base = b.pos.size
            for (i in 0..segments) {
                val a = 2 * PI * i / segments
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                val n = norm(nr * c, ny, nr * s)
                b.v(Vector3(r0 * c, y0, r0 * s), n, Vector2(i.toFloat() / segments, k.toFloat() / profile.size))
                b.v(Vector3(r1 * c, y1, r1 * s), n, Vector2(i.toFloat() / segments, (k + 1f) / profile.size))
            }
            for (i in 0 until segments) {
                val a = base + i * 2
                // counter-clockwise seen from outside
                b.tri(a, a + 1, a + 3); b.tri(a, a + 3, a + 2)
            }
        }
        return b.build(name)
    }

    /** Torus about the local Z axis (lying in the XY plane, facing +Z): the beat ring frame. */
    fun torusZ(major: Float, minor: Float, name: String, around: Int = 96, tube: Int = 16): MeshResource {
        val b = B()
        for (i in 0..around) {
            val u = 2 * PI * i / around
            val cu = cos(u).toFloat(); val su = sin(u).toFloat()
            for (j in 0..tube) {
                val v = 2 * PI * j / tube
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                b.v(Vector3((major + minor * cv) * cu, (major + minor * cv) * su, minor * sv), Vector3(cv * cu, cv * su, sv))
            }
        }
        val row = tube + 1
        for (i in 0 until around) for (j in 0 until tube) {
            val a = i * row + j; val c = a + row
            b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1)
        }
        return b.build(name)
    }

    /** Disc in the XY plane facing +Z (ring backing, lane dots, glow). [inner] > 0 = annulus. UV maps the square. */
    fun discZ(radius: Float, name: String, inner: Float = 0f, segments: Int = 64): MeshResource {
        val b = B()
        val n = Vector3(0f, 0f, 1f)
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments; val a1 = 2 * PI * (i + 1) / segments
            fun p(r: Float, a: Double) = Vector3((cos(a) * r).toFloat(), (sin(a) * r).toFloat(), 0f)
            fun t(q: Vector3) = Vector2(0.5f + q.x / (2 * radius), 0.5f + q.y / (2 * radius))
            if (inner <= 0f) {
                val c = b.v(Vector3(0f, 0f, 0f), n, Vector2(0.5f, 0.5f)); val p0 = p(radius, a0); val p1 = p(radius, a1)
                b.tri(c, b.v(p0, n, t(p0)), b.v(p1, n, t(p1)))
            } else {
                val o0 = p(radius, a0); val o1 = p(radius, a1); val i0 = p(inner, a0); val i1 = p(inner, a1)
                val a = b.v(o0, n, t(o0)); val bb = b.v(o1, n, t(o1)); val c = b.v(i1, n, t(i1)); val d = b.v(i0, n, t(i0))
                b.tri(a, bb, c); b.tri(a, c, d)
            }
        }
        return b.build(name)
    }

    /** Flat annulus in the XZ plane facing +Y (pad glow ring, pick ring, deck outline). */
    fun ringY(inner: Float, outer: Float, name: String, segments: Int = 64, dashes: Int = 0): MeshResource {
        val b = B()
        val n = Vector3(0f, 1f, 0f)
        for (i in 0 until segments) {
            if (dashes > 0 && (i * dashes / segments) % 2 == 1) continue
            val a0 = 2 * PI * i / segments; val a1 = 2 * PI * (i + 1) / segments
            fun p(r: Float, a: Double) = Vector3((cos(a) * r).toFloat(), 0f, (sin(a) * r).toFloat())
            val o0 = b.v(p(outer, a0), n); val o1 = b.v(p(outer, a1), n); val i1 = b.v(p(inner, a1), n); val i0 = b.v(p(inner, a0), n)
            b.tri(o0, i1, o1); b.tri(o0, i0, i1)
        }
        return b.build(name)
    }

    /** Dashed annulus in the XY plane facing +Z (D5 target slot). */
    fun dashedZ(inner: Float, outer: Float, name: String, dashes: Int = 12, segments: Int = 48): MeshResource {
        val b = B()
        val n = Vector3(0f, 0f, 1f)
        for (i in 0 until segments) {
            if ((i * dashes * 2 / segments) % 2 == 1) continue
            val a0 = 2 * PI * i / segments; val a1 = 2 * PI * (i + 1) / segments
            fun p(r: Float, a: Double) = Vector3((cos(a) * r).toFloat(), (sin(a) * r).toFloat(), 0f)
            val o0 = b.v(p(outer, a0), n); val o1 = b.v(p(outer, a1), n); val i1 = b.v(p(inner, a1), n); val i0 = b.v(p(inner, a0), n)
            b.tri(o0, o1, i1); b.tri(o0, i1, i0)
        }
        return b.build(name)
    }

    /** Quad [w] × [h] in the XY plane facing +Z, uv (0,0) bottom-left (text plates). */
    fun quadZ(w: Float, h: Float, name: String): MeshResource {
        val b = B(); val n = Vector3(0f, 0f, 1f)
        val a = b.v(Vector3(-w / 2, -h / 2, 0f), n, Vector2(0f, 0f)); val c = b.v(Vector3(w / 2, -h / 2, 0f), n, Vector2(1f, 0f))
        val d = b.v(Vector3(w / 2, h / 2, 0f), n, Vector2(1f, 1f)); val e = b.v(Vector3(-w / 2, h / 2, 0f), n, Vector2(0f, 1f))
        b.tri(a, c, d); b.tri(a, d, e)
        return b.build(name)
    }

    /** Unit-length strip along +Z (0…1) of width 1 in X, facing +Y — scaled into rays and the playhead. */
    fun unitRod(name: String, segments: Int = 12): MeshResource =
        lathe(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), name, segments)

    /**
     * Flat annulus sector in the XZ plane facing +Y: angle φ measured from +Z toward +X (x = sin φ·r, z = cos φ·r), from
     * [startDeg] over [sweepDeg]. UV maps the square of radius [uvR] so that, seen from the front (+Z) and above, the
     * texture reads upright and unmirrored (v = 0.5 − z / 2R; checked with the「F↑」plate). Timer dial, progress arc,
     * rack wells, drop-ring fill.
     */
    fun annulusY(inner: Float, outer: Float, name: String, startDeg: Float = 0f, sweepDeg: Float = 360f, segments: Int = 96, uvR: Float = outer): MeshResource {
        val b = B(); val n = Vector3(0f, 1f, 0f)
        val segs = max(2, (segments * sweepDeg / 360f).toInt())
        fun p(r: Float, a: Double) = Vector3((sin(a) * r).toFloat(), 0f, (cos(a) * r).toFloat())
        fun t(q: Vector3) = Vector2(0.5f + q.x / (2 * uvR), 0.5f - q.z / (2 * uvR))
        for (i in 0 until segs) {
            val a0 = Math.toRadians((startDeg + sweepDeg * i / segs).toDouble()); val a1 = Math.toRadians((startDeg + sweepDeg * (i + 1) / segs).toDouble())
            val o0 = p(outer, a0); val o1 = p(outer, a1); val i0 = p(inner, a0); val i1 = p(inner, a1)
            val A = b.v(o0, n, t(o0)); val B1 = b.v(o1, n, t(o1)); val C = b.v(i1, n, t(i1)); val D = b.v(i0, n, t(i0))
            // counter-clockwise seen from above (+Y)
            b.tri(A, B1, C); b.tri(A, C, D)
        }
        return b.build(name)
    }

    /**
     * A rounded-edge slab w × h × d centred on the origin (rack board, name plate, arrow strokes): a lathe would be round,
     * this is a box with bevelled vertical edges, planar UVs on top (wood grain) and the front (+Z, text / grain).
     */
    fun slab(w: Float, h: Float, d: Float, name: String, bevel: Float = 0.004f): MeshResource {
        val b = B()
        val x0 = -w / 2; val x1 = w / 2; val y0 = -h / 2; val y1 = h / 2; val z0 = -d / 2; val z1 = d / 2
        fun quad(p0: Vector3, p1: Vector3, p2: Vector3, p3: Vector3, n: Vector3, uv: (Vector3) -> Vector2) {
            val a = b.v(p0, n, uv(p0)); val c = b.v(p1, n, uv(p1)); val e = b.v(p2, n, uv(p2)); val f = b.v(p3, n, uv(p3))
            b.tri(a, c, e); b.tri(a, e, f)
        }
        val bv = bevel.coerceAtMost(minOf(w, d) / 4)
        // top / bottom (inset by the bevel on x / z)
        quad(Vector3(x0 + bv, y1, z1), Vector3(x1 - bv, y1, z1), Vector3(x1 - bv, y1, z0), Vector3(x0 + bv, y1, z0), Vector3(0f, 1f, 0f)) { Vector2(it.x / w + 0.5f, 0.5f - it.z / w) }
        quad(Vector3(x0 + bv, y0, z0), Vector3(x1 - bv, y0, z0), Vector3(x1 - bv, y0, z1), Vector3(x0 + bv, y0, z1), Vector3(0f, -1f, 0f)) { Vector2(it.x / w + 0.5f, it.z / w + 0.5f) }
        // front / back
        quad(Vector3(x0 + bv, y0, z1), Vector3(x1 - bv, y0, z1), Vector3(x1 - bv, y1, z1), Vector3(x0 + bv, y1, z1), Vector3(0f, 0f, 1f)) { Vector2((it.x - x0) / w, (it.y - y0) / h) }
        quad(Vector3(x1 - bv, y0, z0), Vector3(x0 + bv, y0, z0), Vector3(x0 + bv, y1, z0), Vector3(x1 - bv, y1, z0), Vector3(0f, 0f, -1f)) { Vector2((x1 - it.x) / w, (it.y - y0) / h) }
        // sides
        quad(Vector3(x1, y0, z1 - bv), Vector3(x1, y0, z0 + bv), Vector3(x1, y1, z0 + bv), Vector3(x1, y1, z1 - bv), Vector3(1f, 0f, 0f)) { Vector2((z1 - it.z) / d, (it.y - y0) / h) }
        quad(Vector3(x0, y0, z0 + bv), Vector3(x0, y0, z1 - bv), Vector3(x0, y1, z1 - bv), Vector3(x0, y1, z0 + bv), Vector3(-1f, 0f, 0f)) { Vector2((it.z - z0) / d, (it.y - y0) / h) }
        // bevels (45° faces on the four vertical edges)
        val r2 = 0.70710677f
        for ((sx, sz) in listOf(1f to 1f, -1f to 1f, -1f to -1f, 1f to -1f)) {
            val ax = if (sx > 0) x1 else x0; val az = if (sz > 0) z1 else z0
            val pA = Vector3(ax - sx * bv, 0f, az); val pB = Vector3(ax, 0f, az - sz * bv)
            val n = Vector3(sx * r2, 0f, sz * r2)
            val first = if (sx * sz > 0) pA else pB; val second = if (sx * sz > 0) pB else pA
            quad(Vector3(first.x, y0, first.z), Vector3(second.x, y0, second.z), Vector3(second.x, y1, second.z), Vector3(first.x, y1, first.z), n) { Vector2(0.5f, (it.y - y0) / h) }
        }
        return b.build(name)
    }
}
