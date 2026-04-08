package com.typeink.vad

import android.content.Context
import android.util.Log

/**
 * TEN-VAD 创建与预热入口。
 *
 * 目的：
 * 1. 把 sherpa-onnx 相关初始化集中收口；
 * 2. 让 `VadConfig` 不直接感知模型细节；
 * 3. 在加载失败时给调用方明确的回退机会。
 */
object TenVadFactory {
    private const val TAG = "TenVadFactory"
    private const val MODEL_PATH = "vad/ten-vad.onnx"
    private const val DEFAULT_SAMPLE_RATE = 16000

    data class Availability(
        val isAvailable: Boolean,
        val detail: String,
    )

    fun resolveAvailability(context: Context): Availability {
        if (!isRuntimeAvailable()) {
            return Availability(
                isAvailable = false,
                detail = "当前 APK 未包含 sherpa VAD 运行时",
            )
        }
        if (!isModelAvailable(context)) {
            return Availability(
                isAvailable = false,
                detail = "未发现 TEN-VAD 模型资源",
            )
        }
        return Availability(
            isAvailable = true,
            detail = "TEN-VAD 已就绪",
        )
    }

    fun isModelAvailable(context: Context): Boolean = try {
        context.assets.open(MODEL_PATH).use { input ->
            input.available() >= 0
        }
    } catch (_: Throwable) {
        false
    }

    fun createProcessorOrNull(
        context: Context,
        silenceTimeoutMs: Long,
    ): VadProcessor? {
        if (!isModelAvailable(context)) {
            Log.w(TAG, "TEN-VAD model missing: $MODEL_PATH")
            return null
        }

        return try {
            TenVadProcessor(
                context = context.applicationContext,
                sampleRate = DEFAULT_SAMPLE_RATE,
                silenceTimeoutMs = silenceTimeoutMs,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create TEN-VAD processor", t)
            null
        }
    }

    fun preloadIfPossible(
        context: Context,
        silenceTimeoutMs: Long,
    ) {
        if (!isModelAvailable(context)) return
        try {
            TenVadProcessor.preload(
                context = context.applicationContext,
                sampleRate = DEFAULT_SAMPLE_RATE,
                silenceTimeoutMs = silenceTimeoutMs,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to preload TEN-VAD", t)
        }
    }

    private fun isRuntimeAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.Vad")
            true
        } catch (_: Throwable) {
            false
        }
    }
}
