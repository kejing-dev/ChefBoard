package io.embodit.chefboard.platform.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Small event-sound mixer (design sound.principle): CC0 recordings with 2–4 variants and ±5 % pitch jitter (D51), one
 * instance per kind at a time (a new 咔 cuts the previous 咔 with a 3 ms fade, profile D51 补), an equal-power pan from
 * the sound's direction (the stereo fallback when the entity's spatial audio is not used), a master gain (volume stop ×
 * mute) and a soft limiter that keeps the sum ≤ −1 dBFS. Pure Kotlin: runs in the AudioTrack thread and offline in unit
 * tests / tools (loudness evidence).
 */
class Mixer(val sr: Int = 48000, seed: Long = 7L) {
    private val samples = HashMap<String, FloatArray>()
    private val rng = Random(seed)
    @Volatile var master = 0.7f
    var rendered = 0L; private set

    fun putSample(key: String, data: FloatArray) = synchronized(this) { samples[key] = data }
    fun hasSample(key: String) = synchronized(this) { samples.containsKey(key) }
    fun variants(kind: String): List<String> = synchronized(this) { samples.keys.filter { it == kind || it.startsWith("${kind}_") }.sorted() }

    private class Voice(val kind: String, val data: FloatArray, val rate: Double, val gl: Float, val gr: Float) {
        var pos = 0.0; var fadeLeft = -1; var fadeLen = 1
        /** Set when the voice has run past its data or its cut-fade has ended; the render loop removes it. */
        var done = false
    }

    private val voices = ArrayList<Voice>()

    /**
     * Plays one variant of [kind] at [gain] (linear), panned by [pan] (−1 left … +1 right). Returns the chosen key.
     * A voice of the same kind still sounding fades out over 3 ms first (同一事件同时最多 1 个实例).
     */
    fun play(kind: String, gain: Float, pan: Float = 0f, jitter: Boolean = true): String? = synchronized(this) {
        val keys = variants(kind)
        if (keys.isEmpty()) return null
        val key = keys[rng.nextInt(keys.size)]
        val data = samples[key] ?: return null
        voices.filter { it.kind == kind && it.fadeLeft < 0 }.forEach { it.fadeLeft = sr * 3 / 1000; it.fadeLen = it.fadeLeft }
        val rate = if (jitter) 0.95 + rng.nextDouble() * 0.10 else 1.0
        val a = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
        // equal-power pan, normalised so the centre is 1.0 on both sides
        val gl = (cos(a.toDouble()) * 1.4142135).toFloat() * gain
        val gr = (sin(a.toDouble()) * 1.4142135).toFloat() * gain
        voices += Voice(kind, data, rate, gl, gr)
        key
    }

    fun stopKind(kind: String) = synchronized(this) { voices.filter { it.kind == kind }.forEach { if (it.fadeLeft < 0) { it.fadeLeft = sr * 3 / 1000; it.fadeLen = it.fadeLeft } } }

    fun active(): Int = synchronized(this) { voices.size }

    private var env = 1f

    /** Fills [out] with [frames] interleaved stereo frames. */
    fun render(out: FloatArray, frames: Int) = synchronized(this) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            var f = 0
            while (!v.done && f < frames) {
                val p = v.pos.toInt()
                if (p < 0 || p + 1 >= v.data.size) { v.done = true; break }
                var s = (v.data[p] + (v.data[p + 1] - v.data[p]) * (v.pos - p)).toFloat()
                if (v.fadeLeft >= 0) {
                    s *= v.fadeLeft.toFloat() / v.fadeLen; v.fadeLeft--
                    // q3-1: the cut-fade has ended → drop the voice outright (it used to park pos at Double.MAX_VALUE / 4,
                    // whose toInt() is Int.MAX_VALUE, so `p + 1` overflowed past the bounds check → AIOOBE index=2147483647).
                    if (v.fadeLeft < 0) v.done = true
                }
                out[f * 2] += s * v.gl; out[f * 2 + 1] += s * v.gr
                v.pos += v.rate; f++
            }
            if (v.done || v.pos.toInt() + 1 >= v.data.size) it.remove()
        }
        // master + soft limiter (attack instant, release ~80 ms) to a −1 dBFS ceiling
        val ceil = LIMIT
        val rel = 1f - 1f / (sr * 0.08f)
        for (f in 0 until frames) {
            val l = out[f * 2] * master; val r = out[f * 2 + 1] * master
            val peak = maxOf(abs(l), abs(r))
            val want = if (peak > ceil) ceil / peak else 1f
            env = minOf(env * rel + (1f - rel), want) // release toward 1, instant attack
            out[f * 2] = (l * env).coerceIn(-ceil, ceil); out[f * 2 + 1] = (r * env).coerceIn(-ceil, ceil)
        }
        rendered += frames
    }

    companion object {
        /** −1 dBFS. */
        const val LIMIT = 0.891f
    }
}
