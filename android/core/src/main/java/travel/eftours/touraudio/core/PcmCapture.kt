package travel.eftours.touraudio.core

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.sqrt

/** Captures 16 kHz mono PCM Int16 in 2048-frame chunks, including silence. */
class PcmCapture(
    private val voiceProcessing: Boolean,
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val log: (String) -> Unit
) {
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

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
            log("this phone cannot record at 16000 Hz")
            return
        }
        val chunkBytes = CHUNK_FRAMES * 2
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
                .setBufferSizeInBytes(maxOf(min, chunkBytes * 4))
                .build()
        } catch (e: Exception) {
            log("microphone init failed: ${e.message}")
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            log("microphone did not initialize")
            return
        }
        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            audioRecord.release()
            log("microphone start failed: ${e.message}")
            return
        }
        record = audioRecord
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(chunkBytes)
            while (running) {
                val n = audioRecord.read(buf, 0, chunkBytes)
                if (n > 0) {
                    onLevel(level(buf, n))
                    onChunk(buf.copyOf(n))
                } else if (n < 0) {
                    log("microphone read error $n")
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
        onLevel(0f)
    }

    private fun level(buf: ByteArray, n: Int): Float {
        val samples = n / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val lo = buf[i * 2].toInt() and 0xff
            val hi = buf[i * 2 + 1].toInt()
            var sample = (hi shl 8) or lo
            if (sample > 32767) sample -= 65536
            sum += sample * sample
        }
        return (sqrt(sum / samples) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_FRAMES = 2048
    }
}
