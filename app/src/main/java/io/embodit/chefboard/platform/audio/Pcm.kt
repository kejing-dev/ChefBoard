package io.embodit.chefboard.platform.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** 16-bit PCM wav read / write and the import clean-up (T13: trim silence, ≤ 4 s, peak −1 dBFS, mono 48 kHz). */
object Pcm {
    /** Reads a PCM16 wav (any channel count, any rate) as mono float at [targetSr]. */
    fun readWav(input: InputStream, targetSr: Int = 48000): FloatArray {
        val bytes = input.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a wav" }
        var pos = 12
        var channels = 1; var rate = targetSr; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") { channels = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
            if (id == "data") { dataOff = pos + 8; dataLen = minOf(len, bytes.size - dataOff); break }
            pos += 8 + len + (len and 1)
        }
        require(dataOff > 0 && bits == 16) { "unsupported wav" }
        val frames = dataLen / (2 * channels)
        val mono = FloatArray(frames) { f ->
            var acc = 0f
            for (c in 0 until channels) acc += bb.getShort(dataOff + (f * channels + c) * 2) / 32768f
            acc / channels
        }
        return if (rate == targetSr) mono else resample(mono, rate, targetSr)
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        val n = (x.size.toLong() * to / from).toInt()
        val r = from.toDouble() / to
        return FloatArray(n) { i -> val p = i * r; val k = p.toInt(); if (k + 1 >= x.size) x.last() else (x[k] + (x[k + 1] - x[k]) * (p - k)).toFloat() }
    }

    data class Cleaned(val data: FloatArray, val seconds: Float, val trimmed: Boolean)

    /** Trim leading / trailing silence (−50 dB re peak), cap at [maxSec] with a 20 ms fade, normalise to −1 dBFS. */
    fun clean(x: FloatArray, sr: Int = 48000, maxSec: Float = 4f): Cleaned? {
        if (x.isEmpty()) return null
        val peak = x.maxOf { abs(it) }
        if (peak < 1e-4f) return null
        val thr = peak * 0.00316f
        val a = x.indexOfFirst { abs(it) > thr }.coerceAtLeast(0)
        val b = x.indexOfLast { abs(it) > thr }.coerceAtLeast(a)
        var y = x.copyOfRange(maxOf(0, a - 48), minOf(x.size, b + 1))
        val max = (maxSec * sr).toInt()
        val trimmed = y.size > max
        if (trimmed) {
            y = y.copyOf(max)
            val f = sr / 50
            for (i in 0 until f) y[max - 1 - i] *= i.toFloat() / f
        }
        val g = 0.891f / y.maxOf { abs(it) }
        for (i in y.indices) y[i] *= g
        return Cleaned(y, y.size.toFloat() / sr, trimmed)
    }

    fun writeWav(file: File, mono: FloatArray, sr: Int = 48000) = writeWav(file, mono, 1, sr)

    /** Interleaved float → 16-bit wav. */
    fun writeWav(file: File, data: FloatArray, channels: Int, sr: Int) {
        val out = ByteArrayOutputStream()
        val n = data.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(channels.toShort()); bb.putInt(sr)
        bb.putInt(sr * channels * 2); bb.putShort((channels * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (v in data) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(bb.array())
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}
