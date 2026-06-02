/**
 * whisper-worker.js — on-device speech recognition via Whisper (Transformers.js)
 *
 * MODULE worker (created with { type: 'module' } in guide.html).
 * Static import at the top is more reliable than dynamic import() on iOS Safari.
 * The file is same-origin so no cross-origin module restrictions apply.
 *
 * WASM files (ort-wasm.wasm, ort-wasm-simd.wasm) are self-hosted on the router
 * so this works even when the tour router has no internet pass-through.
 *
 * Message protocol (main → worker):
 *   { type: 'load' }
 *   { type: 'transcribe', audio: Float32Array, sampleRate: number }
 *
 * Message protocol (worker → main):
 *   { type: 'status',   status: 'loading' }
 *   { type: 'progress', progress: { file, progress (0-100), loaded, total } }
 *   { type: 'ready' }
 *   { type: 'result',  text: string }
 *   { type: 'error',   message: string }
 */

import { pipeline, env } from '/transformers.min.js';

// ── ONNX / WASM configuration ─────────────────────────────────────────────────
// Single-threaded WASM — no SharedArrayBuffer required.
// (ort-wasm-simd-threaded.wasm needs SharedArrayBuffer which iOS Safari blocks
// without Cross-Origin-Opener-Policy / Cross-Origin-Embedder-Policy headers.)
env.backends.onnx.wasm.numThreads = 1;

// Use self-hosted WASM files served from the router.
// Falls back to ort-wasm-simd.wasm → ort-wasm.wasm automatically.
env.backends.onnx.wasm.wasmPaths = '/';

// ── Model hosting ─────────────────────────────────────────────────────────────
// Model files are self-hosted at /public/models/ on the router so CC works
// with no internet connection.  If the local path is missing (first-run before
// the model has been copied to the router), fall back to remote HuggingFace.
env.localModelPath  = '/models/';
env.allowLocalModels  = true;
env.allowRemoteModels = false;   // tour routers have no internet — block remote fetch

// ── Helpers ───────────────────────────────────────────────────────────────────

let transcriber = null;

/** Linear-interpolation downsampler — normalises to 16 kHz for Whisper. */
function resample(audio, fromRate, toRate) {
    if (fromRate === toRate) return audio;
    const ratio     = fromRate / toRate;
    const newLength = Math.floor(audio.length / ratio);
    const out       = new Float32Array(newLength);
    for (let i = 0; i < newLength; i++) {
        const pos  = i * ratio;
        const idx  = Math.floor(pos);
        const frac = pos - idx;
        out[i] = (idx + 1 < audio.length)
            ? audio[idx] * (1 - frac) + audio[idx + 1] * frac
            : audio[idx];
    }
    return out;
}

// ── Message handler ───────────────────────────────────────────────────────────

function handleMessage(event) {
    const { type, audio, sampleRate } = event.data;

    if (type === 'load') {
        if (transcriber) { self.postMessage({ type: 'ready' }); return Promise.resolve(); }

        self.postMessage({ type: 'status', status: 'loading' });

        return pipeline(
            'automatic-speech-recognition',
            'Xenova/whisper-tiny.en',
            {
                progress_callback: (p) => {
                    if (p.status === 'progress') {
                        self.postMessage({ type: 'progress', progress: p });
                    }
                }
            }
        ).then((pipe) => {
            transcriber = pipe;
            self.postMessage({ type: 'ready' });
        });
    }

    if (type === 'transcribe') {
        if (!transcriber) {
            self.postMessage({ type: 'error', message: 'model not loaded' });
            return Promise.resolve();
        }
        if (!audio || audio.length === 0) return Promise.resolve();

        const inputAudio = resample(audio, sampleRate || 16000, 16000);

        // NOTE: do NOT pass task or language to English-only (.en) models.
        // whisper-tiny.en has no multilingual tokens; injecting <|en|> or a
        // task token into the prompt breaks inference and returns empty text.
        return transcriber(inputAudio, {
            return_timestamps: false,
        }).then((result) => {
            let text = (result && result.text) ? result.text.trim() : '';

            // ── Hallucination filtering ───────────────────────────────────────
            // Step 1: Remove Whisper annotation tags like [BLANK_AUDIO], [Music],
            // [Applause], (laughter), etc. These are square/round-bracket spans
            // up to 50 chars that Whisper inserts when it hears non-speech audio.
            text = text.replace(/[\[(][^\])\n]{1,50}[\])]/g, '').trim();

            // Step 2: Suppress if only punctuation/whitespace remains after tag removal.
            const stripped = text.replace(/[\s.,!?;:\-'"…]+/g, '');
            if (stripped.length < 2) text = '';

            self.postMessage({ type: 'result', text });
        });
    }

    return Promise.resolve();
}

self.onmessage = (event) => {
    handleMessage(event).catch((err) => {
        self.postMessage({ type: 'error', message: String(err && err.message ? err.message : err) });
    });
};
