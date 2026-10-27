package travel.eftours.touraudio.spike

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.ArrayDeque

/**
 * Plays 16 kHz mono PCM Int16 the same way the web listener does:
 * hold about 100 ms, then write chunks to an AudioTrack.
 * A dry stretch longer than 1 second is logged as a playback gap.
 */
class PcmPlayer(private val log: (String) -> Unit) {
    private val queue = ArrayDeque<ByteArray>()
    private val lock = Object()
    private var queuedBytes = 0
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var flushRequested = false

    @Volatile var playbackGaps: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        playbackGaps = 0
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val audioTrack = buildTrack()
            if (audioTrack == null) {
                running = false
                return@Thread
            }
            track = audioTrack
            runLoop(audioTrack)
        }, "pcm-play")
        thread?.start()
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            queue.addLast(pcm)
            queuedBytes += pcm.size
            val maxBytes = SAMPLE_RATE * 2
            var dropped = 0
            while (queuedBytes > maxBytes && queue.size > 1) {
                val old = queue.removeFirst()
                queuedBytes -= old.size
                dropped++
            }
            if (dropped > 0) log("dropped $dropped stale playback chunks")
            lock.notifyAll()
        }
    }

    fun flush() {
        synchronized(lock) {
            queue.clear()
            queuedBytes = 0
            flushRequested = true
            lock.notifyAll()
        }
    }

    fun stop() {
        running = false
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
        var drySince = 0L
        while (running) {
            if (flushRequested) {
                flushRequested = false
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                } catch (e: IllegalStateException) {
                    log("playback flush failed: ${e.message}")
                }
                playing = false
                drySince = 0L
            }

            val chunk = nextChunk(playing, prebufferBytes)
            if (chunk == null) {
                if (playing && drySince == 0L) drySince = System.currentTimeMillis()
                if (!running) break
                if (!sleepBriefly()) break
                continue
            }
            if (drySince != 0L) {
                val dry = System.currentTimeMillis() - drySince
                drySince = 0L
                if (dry >= GAP_MS) {
                    playbackGaps++
                    log("playback gap ${dry}ms")
                }
            }
            if (!playing) {
                audioTrack.play()
                playing = true
                log("playback started")
            }
            val wrote = audioTrack.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (wrote < 0) {
                log("AudioTrack write error $wrote")
                break
            }
        }
    }

    private fun nextChunk(playing: Boolean, prebufferBytes: Int): ByteArray? {
        synchronized(lock) {
            if (!playing && queuedBytes < prebufferBytes && running && !flushRequested) {
                return null
            }
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

    private fun buildTrack(): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) {
            log("this phone cannot play 16000 Hz PCM (minBuffer=$min)")
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
            log("AudioTrack init failed: ${e.message}")
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
        const val PREBUFFER_MS = 100
        const val GAP_MS = 1000L
    }
}
