package com.typeink.vad

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.ArrayDeque

/**
 * 基于 sherpa-onnx TEN-VAD 的判停实现。
 *
 * 设计目标：
 * 1. 先替换掉当前能量阈值判停主路径；
 * 2. 对外仍保持 `VadProcessor` 这一层语义不变；
 * 3. 通过轻量池化减少首次录音之外的重复初始化成本。
 */
class TenVadProcessor internal constructor(
    private val context: Context,
    private val sampleRate: Int,
    private val silenceTimeoutMs: Long,
) : VadProcessor {

    companion object {
        private const val TAG = "TenVadProcessor"
        private const val MODEL_PATH = "vad/ten-vad.onnx"
        private const val MAX_POOL_SIZE = 2

        private data class PoolKey(
            val sampleRate: Int,
            val threshold: Float,
            val minSilenceDuration: Float,
        )

        private val poolLock = Any()

        @Volatile
        private var poolKey: PoolKey? = null
        private val vadPool: ArrayDeque<Vad> = ArrayDeque()

        private fun buildRuntimeConfig(sampleRate: Int, silenceTimeoutMs: Long): RuntimeConfig {
            val timeoutSeconds = (silenceTimeoutMs / 1000f).coerceIn(0.3f, 3.0f)
            val threshold =
                when {
                    timeoutSeconds <= 0.8f -> 0.60f
                    timeoutSeconds <= 1.5f -> 0.50f
                    else -> 0.40f
                }
            val minSilenceDuration = (timeoutSeconds * 0.35f).coerceIn(0.08f, 0.35f)
            return RuntimeConfig(
                threshold = threshold,
                minSilenceDuration = minSilenceDuration,
                speechHangoverMs = (silenceTimeoutMs / 4L).coerceIn(160L, 360L).toInt(),
                initialDebounceMs = silenceTimeoutMs.coerceIn(1000L, 2500L).toInt(),
            )
        }

        private fun buildVadModelConfig(
            sampleRate: Int,
            runtimeConfig: RuntimeConfig,
        ): VadModelConfig {
            val tenConfig =
                TenVadModelConfig(
                    model = MODEL_PATH,
                    threshold = runtimeConfig.threshold,
                    minSilenceDuration = runtimeConfig.minSilenceDuration,
                    minSpeechDuration = 0.25f,
                    windowSize = 256,
                )
            return VadModelConfig(
                tenVadModelConfig = tenConfig,
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
        }

        private fun createVad(
            context: Context,
            sampleRate: Int,
            silenceTimeoutMs: Long,
        ): Vad {
            val runtimeConfig = buildRuntimeConfig(sampleRate, silenceTimeoutMs)
            return Vad(
                assetManager = context.assets,
                config = buildVadModelConfig(sampleRate, runtimeConfig),
            )
        }

        private fun acquireFromPool(
            context: Context,
            sampleRate: Int,
            silenceTimeoutMs: Long,
        ): Vad {
            val runtimeConfig = buildRuntimeConfig(sampleRate, silenceTimeoutMs)
            val key =
                PoolKey(
                    sampleRate = sampleRate,
                    threshold = runtimeConfig.threshold,
                    minSilenceDuration = runtimeConfig.minSilenceDuration,
                )

            var take: Vad? = null
            var toRelease: List<Vad> = emptyList()
            synchronized(poolLock) {
                if (poolKey != null && poolKey != key && vadPool.isNotEmpty()) {
                    toRelease = vadPool.toList()
                    vadPool.clear()
                }
                if (poolKey == null || poolKey != key) {
                    poolKey = key
                }
                if (vadPool.isNotEmpty()) {
                    take = vadPool.removeFirst()
                }
            }

            toRelease.forEach { releaseVad(it, "release_stale_pool") }

            val vad = take ?: createVad(context, sampleRate, silenceTimeoutMs)
            resetVad(vad, "acquire")
            return vad
        }

        private fun recycleToPool(
            key: PoolKey,
            vad: Vad,
        ) {
            val shouldPool =
                synchronized(poolLock) {
                    poolKey == key && vadPool.size < MAX_POOL_SIZE
                }
            if (!shouldPool) {
                releaseVad(vad, "pool_full_or_mismatch")
                return
            }
            resetVad(vad, "recycle")
            synchronized(poolLock) {
                if (poolKey == key && vadPool.size < MAX_POOL_SIZE) {
                    vadPool.addLast(vad)
                    return
                }
            }
            releaseVad(vad, "late_pool_mismatch")
        }

        internal fun preload(
            context: Context,
            sampleRate: Int,
            silenceTimeoutMs: Long,
        ) {
            try {
                val runtimeConfig = buildRuntimeConfig(sampleRate, silenceTimeoutMs)
                val key =
                    PoolKey(
                        sampleRate = sampleRate,
                        threshold = runtimeConfig.threshold,
                        minSilenceDuration = runtimeConfig.minSilenceDuration,
                    )
                synchronized(poolLock) {
                    if (poolKey == key && vadPool.isNotEmpty()) return
                }
                val vad = createVad(context, sampleRate, silenceTimeoutMs)
                recycleToPool(key, vad)
                Log.i(TAG, "TEN-VAD preloaded (sr=$sampleRate, timeoutMs=$silenceTimeoutMs)")
            } catch (t: Throwable) {
                Log.w(TAG, "TEN-VAD preload failed", t)
            }
        }

        private fun resetVad(
            vad: Vad,
            stage: String,
        ) {
            try {
                vad.reset()
                while (!vad.empty()) {
                    vad.pop()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to reset TEN-VAD at stage=$stage", t)
            }
        }

        private fun releaseVad(
            vad: Vad,
            stage: String,
        ) {
            try {
                vad.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to release TEN-VAD at stage=$stage", t)
            }
        }
    }

    private data class RuntimeConfig(
        val threshold: Float,
        val minSilenceDuration: Float,
        val speechHangoverMs: Int,
        val initialDebounceMs: Int,
    )

    private var listener: VadProcessor.Listener? = null
    private val runtimeConfig = buildRuntimeConfig(sampleRate, silenceTimeoutMs)
    private val currentPoolKey =
        PoolKey(
            sampleRate = sampleRate,
            threshold = runtimeConfig.threshold,
            minSilenceDuration = runtimeConfig.minSilenceDuration,
        )
    private var vad: Vad? = acquireFromPool(context, sampleRate, silenceTimeoutMs)
    private var silentMsAcc: Long = 0L
    private var speechHangoverRemainingMs: Int = 0
    private var initialDebounceRemainingMs: Int = runtimeConfig.initialDebounceMs
    private var hasDetectedSpeech: Boolean = false
    private var autoStopped: Boolean = false

    override fun setListener(listener: VadProcessor.Listener) {
        this.listener = listener
    }

    override fun process(audioChunk: ByteArray): VadProcessor.VadResult {
        val vadInstance = vad ?: return VadProcessor.VadResult.UNKNOWN
        if (audioChunk.isEmpty()) return VadProcessor.VadResult.UNKNOWN
        if (autoStopped) return VadProcessor.VadResult.SILENCE

        return try {
            val frameMs = (((audioChunk.size / 2) * 1000) / sampleRate).coerceAtLeast(0)
            val samples = pcmToFloatArray(audioChunk)
            if (samples.isEmpty()) return VadProcessor.VadResult.UNKNOWN

            vadInstance.acceptWaveform(samples)
            val isSpeech = vadInstance.isSpeechDetected()
            while (!vadInstance.empty()) {
                vadInstance.pop()
            }

            if (isSpeech) {
                val isFirstSpeech = !hasDetectedSpeech
                hasDetectedSpeech = true
                silentMsAcc = 0L
                speechHangoverRemainingMs = runtimeConfig.speechHangoverMs
                autoStopped = false
                if (isFirstSpeech) {
                    listener?.onSpeechStart()
                }
                VadProcessor.VadResult.SPEECH
            } else {
                if (!hasDetectedSpeech && initialDebounceRemainingMs > 0) {
                    initialDebounceRemainingMs = (initialDebounceRemainingMs - frameMs).coerceAtLeast(0)
                    return VadProcessor.VadResult.UNKNOWN
                }

                if (speechHangoverRemainingMs > 0) {
                    speechHangoverRemainingMs = (speechHangoverRemainingMs - frameMs).coerceAtLeast(0)
                    return VadProcessor.VadResult.UNKNOWN
                }

                silentMsAcc += frameMs.toLong()
                listener?.onSilenceDetected(silentMsAcc)
                if (hasDetectedSpeech && silentMsAcc >= silenceTimeoutMs) {
                    autoStopped = true
                    listener?.onSpeechEnd()
                }
                VadProcessor.VadResult.SILENCE
            }
        } catch (t: Throwable) {
            Log.e(TAG, "TEN-VAD process failed", t)
            VadProcessor.VadResult.UNKNOWN
        }
    }

    override fun reset() {
        silentMsAcc = 0L
        speechHangoverRemainingMs = 0
        initialDebounceRemainingMs = runtimeConfig.initialDebounceMs
        hasDetectedSpeech = false
        autoStopped = false
        vad?.let { resetVad(it, "processor_reset") }
    }

    override fun release() {
        listener = null
        val current = vad ?: return
        vad = null
        recycleToPool(currentPoolKey, current)
    }

    private fun pcmToFloatArray(audioChunk: ByteArray): FloatArray {
        val numSamples = audioChunk.size / 2
        if (numSamples <= 0) return FloatArray(0)

        val samples = FloatArray(numSamples)
        var index = 0
        var sampleIndex = 0
        while (index + 1 < audioChunk.size && sampleIndex < numSamples) {
            val low = audioChunk[index].toInt() and 0xFF
            val high = audioChunk[index + 1].toInt() and 0xFF
            val pcmValue = (high shl 8) or low
            val signed = if (pcmValue < 0x8000) pcmValue else pcmValue - 0x10000
            samples[sampleIndex] = (signed / 32768.0f).coerceIn(-1.0f, 1.0f)
            index += 2
            sampleIndex++
        }
        return samples
    }
}
