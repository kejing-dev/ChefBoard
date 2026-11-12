package io.embodit.chefboard.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import io.embodit.chefboard.platform.AppLog

/**
 * One AudioTrack stream fed by [Mixer] on its own thread. Event sounds only (no ambience, B24); timing is not critical,
 * so a normal-latency track is enough. The bell is played on the timer entity through the SDK's spatial audio
 * (Stage scene) and falls back to this mixer with a pan from the timer's direction when that fails.
 */
class AudioEngine(private val context: Context) {
    val mixer = Mixer(SR)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val minBytes = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = runCatching {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBytes, BURST * 4 * 2 * 4)).build()
        }.getOrElse { AppLog.e("AUDIO track failed", it); return }
        track = t
        running = true
        AppLog.i("AUDIO start sr=$SR burst=$BURST minBuf=${minBytes / 8}f")
        thread = Thread({ loop(t) }, "ChefBoardAudio").apply { start() }
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = FloatArray(BURST * 2)
        t.play()
        while (running) {
            mixer.render(buf, BURST)
            val w = t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            if (w < 0) { AppLog.w("AUDIO write error $w"); break }
        }
        runCatching { t.pause(); t.flush() }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(300) }
        runCatching { track?.release() }
        track = null; thread = null
    }

    /** Decodes every asset under sounds/ into the mixer (background thread). */
    fun loadAssets(): Int {
        val names = context.assets.list("sounds").orEmpty().filter { it.endsWith(".wav") }
        val t0 = SystemClock.elapsedRealtime()
        for (n in names) {
            runCatching { context.assets.open("sounds/$n").use { Pcm.readWav(it, SR) } }
                .onSuccess { mixer.putSample(n.removeSuffix(".wav"), it) }
                .onFailure { AppLog.e("AUDIO decode $n", it) }
        }
        AppLog.i("AUDIO loaded ${names.size} samples in ${SystemClock.elapsedRealtime() - t0} ms")
        return names.size
    }

    companion object {
        const val SR = 48000
        const val BURST = 480
    }
}
