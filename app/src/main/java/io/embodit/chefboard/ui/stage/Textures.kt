package io.embodit.chefboard.ui.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural textures (budget: ≤ 4 maps of 1024²; no downloaded texture files — the design suggested an ambientCG CC0
 * walnut map, downloads need the user's OK, so the grain is generated: deviation logged) and the text plates for the
 * 3D words (timer tags, button faces, hints). Text is drawn into a bitmap, uv v = 0 at the bitmap's bottom row
 * (factory finding, checked with the「F↑」test plate: debug `testtex on`).
 */
internal object Textures {
    /** Walnut: dark brown with flowing rings and fine pores (palette walnut #5A3E2B). */
    fun walnut(size: Int = 512, seed: Int = 11): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        val ph = FloatArray(6) { rng.nextFloat() * 6.28f }
        val px = IntArray(size * size)
        val r0 = 0x5A; val g0 = 0x3E; val b0 = 0x2B
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x.toFloat() / size; val fy = y.toFloat() / size
            val ring = sin(fx * 26f + sin(fy * 3.2f + ph[0]) * 1.8f + sin(fy * 9f + ph[1]) * 0.35f + ph[2]) * 0.5f + 0.5f
            val streak = sin(fx * 310f + sin(fy * 6f + ph[3]) * 4f + ph[4]) * 0.5f + 0.5f
            val pore = if (rng.nextFloat() < 0.012f) -0.10f else 0f
            val k = 0.84f + 0.16f * ring * ring + 0.05f * streak + pore
            px[y * size + x] = Color.rgb((r0 * k).toInt().coerceIn(0, 255), (g0 * k).toInt().coerceIn(0, 255), (b0 * k).toInt().coerceIn(0, 255))
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /**
     * One line of text on an opaque plate of [wPx] × [hPx] (aspect = the plate's metres). The size shrinks only to keep
     * the whole string on the plate; [minSizeFrac] is logged by the caller if it is reached (a plate too short = shorten
     * the copy, not the font, design D45).
     */
    fun textPlate(text: String, wPx: Int, hPx: Int, bg: Int, fg: Int, bold: Boolean = true, radiusPx: Float = 0f, sizeFrac: Float = 0.72f): Pair<Bitmap, Boolean> {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (radiusPx > 0f) {
            c.drawColor(Color.TRANSPARENT)
            c.drawRoundRect(RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()), radiusPx, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        } else c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = hPx * sizeFrac
        }
        var fits = true
        val maxW = wPx * 0.94f
        while (p.measureText(text) > maxW && p.textSize > hPx * 0.3f) { p.textSize *= 0.95f; fits = false }
        val fm = p.fontMetrics
        c.drawText(text, wPx / 2f, hPx / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp to fits
    }

    /** "F↑" orientation test (profile D10). */
    fun orientationTest(w: Int = 256, h: Int = 128): Bitmap = textPlate("F↑", w, h, Color.WHITE, Color.BLACK).first

    /** Two lines on an opaque plate: [big] (mm:ss, 55 % of the height) over [small] (a word or a hint). Returns fits. */
    fun twoLines(big: String, small: String, wPx: Int, hPx: Int, bg: Int, fg: Int, fg2: Int = fg): Pair<Bitmap, Boolean> {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val maxW = wPx * 0.94f
        var fits = true
        fun line(t: String, size: Float, cy: Float, bold: Boolean, color: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL); textSize = size }
            while (p.measureText(t) > maxW && p.textSize > size * 0.5f) { p.textSize *= 0.95f; fits = false }
            val fm = p.fontMetrics
            c.drawText(t, wPx / 2f, cy - (fm.ascent + fm.descent) / 2f, p)
        }
        if (small.isEmpty()) line(big, hPx * 0.62f, hPx / 2f, true, fg)
        else { line(big, hPx * 0.48f, hPx * 0.33f, true, fg); line(small, hPx * 0.36f, hPx * 0.76f, false, fg2) }
        return bmp to fits
    }

    /**
     * The timer dial (E2): 60 ticks, long every 5 minutes with the number, on a cream bezel, drawn for [Meshes.annulusY]
     * (uv radius [uvR]): minute m sits at φ = m·6° from the front (+Z) toward +X, numbers upright for a viewer in front.
     */
    fun dial(size: Int, inner: Float, outer: Float, uvR: Float, bg: Int, ink: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val k = size / (2f * uvR)
        val cx = size / 2f; val cy = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeCap = Paint.Cap.ROUND }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = (outer - inner) * k * 0.42f }
        for (m in 0 until 60) {
            val a = Math.toRadians(m * 6.0)
            // bitmap: x right = +X, y down = +Z (front at the bottom)
            val dx = kotlin.math.sin(a).toFloat(); val dy = kotlin.math.cos(a).toFloat()
            val long = m % 5 == 0
            val r1 = outer * k * 0.985f; val r0 = (if (long) outer - (outer - inner) * 0.30f else outer - (outer - inner) * 0.16f) * k
            p.strokeWidth = if (long) size * 0.006f else size * 0.003f
            c.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, p)
            if (long) {
                val rt = (inner + (outer - inner) * 0.36f) * k
                c.save(); c.translate(cx + dx * rt, cy + dy * rt); c.rotate(-(m * 6f))
                val fm = tp.fontMetrics
                c.drawText("${m}", 0f, -(fm.ascent + fm.descent) / 2f, tp)
                c.restore()
            }
        }
        return bmp
    }

    /** Soft round contact shadow (alpha falls off to the rim) for the timers' bases. */
    fun shadow(size: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x + 0.5f) / size * 2 - 1; val dy = (y + 0.5f) / size * 2 - 1
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            val a = ((1f - r).coerceIn(0f, 1f).let { it * it } * 150).toInt()
            px[y * size + x] = Color.argb(a, 20, 14, 8)
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }
}
