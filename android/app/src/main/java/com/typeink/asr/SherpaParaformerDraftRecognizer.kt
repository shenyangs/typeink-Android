package com.typeink.asr

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 基于 sherpa-onnx Paraformer 的本地流式草稿识别器。
 *
 * 定位：
 * 1. 只负责“灰色草稿”这一路的本地转写；
 * 2. 录音数据由外部 `PcmRecorder` 提供；
 * 3. 云端 ASR 一旦返回 partial/final，外部会停止本识别器，避免双路结果互相打架。
 */
class SherpaParaformerDraftRecognizer(
    private val context: android.content.Context,
    private val modelFiles: SherpaDraftRecognizerFactory.ParaformerModelFiles,
) : DraftRecognizer {
    companion object {
        private const val TAG = "SherpaPfDraft"
        private const val MAX_DECODE_LOOPS_PER_CHUNK = 8
        private const val MAX_FLUSH_DECODE_LOOPS = 48
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "typeink-sherpa-draft").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var listener: DraftRecognizer.Listener? = null

    @Volatile
    private var running = false

    private var stream: OnlineStream? = null
    private var committedText: String = ""
    private var lastEmittedText: String = ""
    private var unavailableNotified = false

    override fun start(listener: DraftRecognizer.Listener) {
        if (!isAvailable()) {
            listener.onUnavailable()
            return
        }

        this.listener = listener
        running = true
        committedText = ""
        lastEmittedText = ""
        unavailableNotified = false

        submitSafely {
            val prepared = SherpaParaformerRecognizerManager.prepare(modelFiles)
            if (!prepared) {
                notifyUnavailableOnce()
                return@submitSafely
            }
            if (!running) return@submitSafely
            stream = SherpaParaformerRecognizerManager.createStreamOrNull(modelFiles)
            if (stream == null) {
                notifyUnavailableOnce()
            }
        }
    }

    override fun acceptAudioChunk(bytes: ByteArray) {
        if (!running) return
        val copy = bytes.copyOf()
        submitSafely {
            if (!running) return@submitSafely
            val currentStream = stream ?: return@submitSafely
            val samples = pcmToFloatArray(copy)
            if (samples.isEmpty()) return@submitSafely
            SherpaParaformerRecognizerManager.acceptWaveform(currentStream, samples)
            drainDecode(currentStream, MAX_DECODE_LOOPS_PER_CHUNK)
            maybeHandleEndpoint(currentStream)
        }
    }

    override fun stop() {
        if (!running && stream == null) {
            shutdownExecutor()
            return
        }
        running = false
        submitSafely {
            finalizeAndRelease()
        }
        shutdownExecutor()
    }

    override fun isAvailable(): Boolean {
        return SherpaDraftRecognizerFactory.resolveModelFiles(context) != null
    }

    private fun drainDecode(
        currentStream: OnlineStream,
        maxLoops: Int,
    ) {
        var loops = 0
        while (SherpaParaformerRecognizerManager.isReady(currentStream) && loops < maxLoops) {
            SherpaParaformerRecognizerManager.decode(currentStream)
            loops++
        }
        emitDraft(currentStream)
    }

    private fun maybeHandleEndpoint(currentStream: OnlineStream) {
        if (!SherpaParaformerRecognizerManager.isEndpoint(currentStream)) return
        val text = sanitizeText(SherpaParaformerRecognizerManager.getResultText(currentStream))
        if (text.isNotBlank()) {
            committedText = mergeCommitted(committedText, text)
            emitText(committedText)
        }
        SherpaParaformerRecognizerManager.reset(currentStream)
    }

    private fun emitDraft(currentStream: OnlineStream) {
        val text = sanitizeText(SherpaParaformerRecognizerManager.getResultText(currentStream))
        val merged = mergeDraft(committedText, text)
        if (merged.isBlank() || merged == lastEmittedText) return
        emitText(merged)
    }

    private fun emitText(text: String) {
        lastEmittedText = text
        val currentListener = listener ?: return
        mainHandler.post {
            currentListener.onDraftText(text)
        }
    }

    private fun finalizeAndRelease() {
        val currentStream = stream
        if (currentStream != null) {
            SherpaParaformerRecognizerManager.inputFinished(currentStream)
            drainDecode(currentStream, MAX_FLUSH_DECODE_LOOPS)
            val text = sanitizeText(SherpaParaformerRecognizerManager.getResultText(currentStream))
            val merged = mergeDraft(committedText, text)
            if (merged.isNotBlank() && merged != lastEmittedText) {
                emitText(merged)
            }
            SherpaParaformerRecognizerManager.releaseStream(currentStream)
            stream = null
        }
        listener = null
    }

    private fun notifyUnavailableOnce() {
        if (unavailableNotified) return
        unavailableNotified = true
        val currentListener = listener ?: return
        mainHandler.post {
            currentListener.onUnavailable()
        }
    }

    private fun submitSafely(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.e(TAG, "Sherpa draft task failed", t)
                    val currentListener = listener
                    if (currentListener != null) {
                        mainHandler.post {
                            currentListener.onError("Sherpa 草稿识别失败：${t.message ?: "未知错误"}")
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun shutdownExecutor() {
        executor.shutdown()
    }

    private fun mergeCommitted(
        committed: String,
        nextSegment: String,
    ): String {
        val cleanCommitted = committed.trim()
        val cleanNext = nextSegment.trim()
        if (cleanCommitted.isBlank()) return cleanNext
        if (cleanNext.isBlank()) return cleanCommitted
        return "$cleanCommitted $cleanNext".trim()
    }

    private fun mergeDraft(
        committed: String,
        partial: String,
    ): String {
        return mergeCommitted(committed, partial)
    }

    private fun sanitizeText(text: String): String {
        return text
            .replace("<sil>", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun pcmToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)
        val sampleCount = bytes.size / 2
        val out = FloatArray(sampleCount)
        var offset = 0
        var index = 0
        while (index < sampleCount) {
            val sample =
                ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF)).toShort()
                    .toInt()
            out[index] = (sample / 32768.0f).coerceIn(-1f, 1f)
            offset += 2
            index++
        }
        return out
    }
}
