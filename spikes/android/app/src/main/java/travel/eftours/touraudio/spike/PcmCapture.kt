package travel.eftours.touraudio.spike

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * Captures 16 kHz mono PCM Int16 in 2048-frame chunks, matching the
 * router's field-tested guide buffer. Sends every chunk, including silence,
 * so a locked-screen test can tell a stall from the web guide's silence gate.
 */
class PcmCapture(
    private val voiceProcessing: Boolean,
    private val onChunk: (ByteArray) -> Unit,
    private val log: (String) -> Unit
) {
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile var maxReadGapMs: Long = 0L
        private set

    @Volatile var captureGaps: Int = 0
        private set

    @Volatile var sentChunks: Long = 0L
        private set

    fun start() {
        if (running) return
        val source = if (voiceProcessing) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) {
            log("this phone cannot record at 16000 Hz (minBuffer=$min)")
            return
        }
        val chunkBytes = CHUNK_FRAMES * 2
        val bufferBytes = maxOf(min, chunkBytes * 4)
        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            log("AudioRecord init failed: ${e.message}")
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            log("microphone did not initialize")
            audioRecord.release()
            return
        }
        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            log("microphone start failed: ${e.message}")
            audioRecord.release()
            return
        }
        record = audioRecord
        running = true
        maxReadGapMs = 0L
        captureGaps = 0
        sentChunks = 0L
        log("capture started (${if (voiceProcessing) "voice processing" else "raw mic"}, ${CHUNK_FRAMES} frames)")
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(chunkBytes)
            var last = System.currentTimeMillis()
            while (running) {
                val n = audioRecord.read(buf, 0, chunkBytes)
                val now = System.currentTimeMillis()
                val gap = now - last
                last = now
                if (gap > maxReadGapMs) maxReadGapMs = gap
                if (gap >= GAP_MS) {
                    captureGaps++
                    log("capture gap ${gap}ms")
                }
                if (n > 0) {
                    onChunk(buf.copyOf(n))
                    sentChunks++
                } else if (n < 0) {
                    log("AudioRecord read error $n")
                    break
                }
            }
        }, "pcm-capture")
        thread?.start()
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
        val audioRecord = record
        record = null
        if (audioRecord != null) {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            audioRecord.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_FRAMES = 2048
        const val GAP_MS = 1000L
    }
}
