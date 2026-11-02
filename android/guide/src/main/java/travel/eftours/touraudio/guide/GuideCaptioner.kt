package travel.eftours.touraudio.guide

import android.content.Context
import android.os.Process
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a copy of the broadcast audio into English captions.
 * The audio thread only drops a chunk into a short queue, so a slow caption
 * never delays the sound travelers hear.
 */
class GuideCaptioner(
    private val context: Context,
    private val onText: (String, Boolean) -> Unit,
    private val onStatus: (String, Boolean) -> Unit
) {
    private val active = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(32)
    private var worker: Thread? = null

    fun start() {
        val previous = worker
        if (previous?.isAlive == true) {
            if (active.get()) return
            previous.join(3000)
            if (previous.isAlive) return
        }
        active.set(true)
        worker = Thread(::runLoop, "tour-captions").also { it.start() }
    }

    fun offer(pcm: ByteArray) {
        if (!active.get()) return
        val copy = pcm.copyOf()
        if (!queue.offer(copy)) {
            queue.poll()
            queue.offer(copy)
        }
    }

    fun isRunning(): Boolean = worker?.isAlive == true

    fun stop() {
        active.set(false)
        queue.clear()
        worker?.interrupt()
        worker?.join(1500)
        if (worker?.isAlive != true) worker = null
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val files = context.assets.list(MODEL_DIR)
        if (files == null || !files.contains("tokens.txt")) {
            onStatus("CC could not start. Broadcast continues.", false)
            active.set(false)
            return
        }
        val recognizer = try {
            OnlineRecognizer(context.assets, config())
        } catch (_: Throwable) {
            onStatus("CC could not start. Broadcast continues.", false)
            active.set(false)
            return
        }
        onStatus("CC is currently on", true)
        val stream = recognizer.createStream()
        var last = ""
        try {
            while (active.get()) {
                val pcm = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                stream.acceptWaveform(samples(pcm), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (text.isNotBlank() && text != last) {
                    last = text
                    onText(text, false)
                }
                if (recognizer.isEndpoint(stream)) {
                    if (text.isNotBlank()) onText(text, true)
                    recognizer.reset(stream)
                    last = ""
                }
            }
        } catch (_: Throwable) {
            onStatus("CC stopped. Broadcast continues.", false)
        } finally {
            stream.release()
            recognizer.release()
        }
    }

    private fun samples(pcm: ByteArray): FloatArray {
        val count = pcm.size / 2
        val out = FloatArray(count)
        var i = 0
        var s = 0
        while (s < count) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            var sample = (hi shl 8) or lo
            if (sample > 32767) sample -= 65536
            out[s] = sample / 32768f
            i += 2
            s += 1
        }
        return out
    }

    private fun config(): OnlineRecognizerConfig {
        return OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.onnx"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                modelType = "zipformer2"
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 1.2f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f)
            ),
            enableEndpoint = true
        )
    }

    private companion object {
        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
        const val SAMPLE_RATE = 16000
    }
}
