package travel.eftours.touraudio.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.ArrayDeque

/** Plays 16 kHz mono PCM Int16 with a 100 ms start buffer. */
class PcmPlayer(private val log: (String) -> Unit) {
    private val queue = ArrayDeque<ByteArray>()
    private val lock = Object()
    private var queuedBytes = 0
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var flushRequested = false
    private var gain = 0.8f

    @Volatile var receiving: Boolean = false
        private set

    fun start() {
        if (running) return
        running = true
        receiving = false
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val audioTrack = buildTrack()
            if (audioTrack == null) {
                running = false
                return@Thread
            }
            track = audioTrack
            applyGain()
            runLoop(audioTrack)
        }, "pcm-play")
        thread?.start()
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1f)
        applyGain()
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        receiving = true
        synchronized(lock) {
            queue.addLast(pcm)
            queuedBytes += pcm.size
            val maxBytes = SAMPLE_RATE * 2
            while (queuedBytes > maxBytes && queue.size > 1) {
                queuedBytes -= queue.removeFirst().size
            }
            lock.notifyAll()
        }
    }

    fun flush() {
        receiving = false
        synchronized(lock) {
            queue.clear()
            queuedBytes = 0
            flushRequested = true
            lock.notifyAll()
        }
    }

    fun stop() {
        running = false
        receiving = false
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1500)
        thread = null
        releaseTrack()
    }

    private fun runLoop(audioTrack: AudioTrack) {
        val prebufferBytes = SAMPLE_RATE * 2 * PREBUFFER_MS / 1000
        var playing = false
        while (running) {
            if (flushRequested) {
                flushRequested = false
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                } catch (_: IllegalStateException) {
                }
                playing = false
            }
            val chunk = nextChunk(playing, prebufferBytes)
            if (chunk == null) {
                if (!running) break
                if (!sleepBriefly()) break
                continue
            }
            if (!playing) {
                audioTrack.play()
                applyGain()
                playing = true
                log("playback started")
            }
            val wrote = audioTrack.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (wrote < 0) {
                log("playback write error $wrote")
                break
            }
        }
    }

    private fun nextChunk(playing: Boolean, prebufferBytes: Int): ByteArray? {
        synchronized(lock) {
            if (!playing && queuedBytes < prebufferBytes && running && !flushRequested) return null
            return if (queue.isEmpty()) null else {
                val chunk = queue.removeFirst()
                queuedBytes -= chunk.size
                chunk
            }
        }
    }

    private fun sleepBriefly(): Boolean {
        synchronized(lock) {
            if (!running) return false
            try {
                lock.wait(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return running
    }

    private fun applyGain() {
        try {
            track?.setVolume(gain)
        } catch (_: Exception) {
        }
    }

    private fun buildTrack(): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) {
            log("this phone cannot play 16000 Hz audio")
            return null
        }
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            log("playback init failed: ${e.message}")
            null
        }
    }

    private fun releaseTrack() {
        synchronized(lock) {
            val audioTrack = track ?: return
            track = null
            try {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val PREBUFFER_MS = 100
    }
}
