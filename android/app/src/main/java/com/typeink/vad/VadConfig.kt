package com.typeink.vad

import android.content.Context
import android.content.SharedPreferences

enum class VadBackend(val storageValue: String) {
    ENERGY("energy"),
    TEN("ten"),
    ;

    companion object {
        @JvmStatic
        fun fromStorage(value: String?): VadBackend {
            val normalized = value?.trim()?.lowercase()
            return values().firstOrNull { it.storageValue == normalized } ?: ENERGY
        }
    }
}

data class VadRuntimeStatus(
    val isEnabled: Boolean,
    val requestedBackend: VadBackend?,
    val actualBackend: VadBackend?,
    val detail: String,
) {
    fun toBadgeLabel(): String {
        if (!isEnabled || requestedBackend == null || actualBackend == null) {
            return "关闭"
        }
        return if (requestedBackend == actualBackend) {
            actualBackend.name
        } else {
            "${requestedBackend.name}->${actualBackend.name}"
        }
    }

    fun toSummaryText(): String = detail
}

/**
 * VAD 配置管理
 * 
 * 对标 参考键盘 的可配置停顿时间
 */
class VadConfig(context: Context) {
    private val appContext: Context = context.applicationContext
    
    companion object {
        private const val PREFS_NAME = "vad_config"
        
        @JvmStatic
        fun load(context: Context): VadConfig {
            return VadConfig(context)
        }
        private const val KEY_VAD_ENABLED = "vad_enabled"
        private const val KEY_VAD_BACKEND = "vad_backend"
        private const val KEY_SILENCE_TIMEOUT = "silence_timeout_ms"
        private const val KEY_SPEECH_THRESHOLD = "speech_threshold"
        private const val KEY_SILENCE_THRESHOLD = "silence_threshold"
        
        // 默认值
        const val DEFAULT_SILENCE_TIMEOUT_MS = 1500L
        const val MIN_SILENCE_TIMEOUT_MS = 300L
        const val MAX_SILENCE_TIMEOUT_MS = 3000L
        const val DEFAULT_SPEECH_THRESHOLD = 0.02f
        const val DEFAULT_SILENCE_THRESHOLD = 0.01f
        val DEFAULT_VAD_BACKEND = VadBackend.TEN
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 是否启用 VAD 智能判停
     */
    var isVadEnabled: Boolean
        get() = prefs.getBoolean(KEY_VAD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VAD_ENABLED, value).apply()

    /**
     * 当前请求使用的 VAD 后端。
     *
     * 阶段 A 先只把配置边界收出来；在 `TEN-VAD` 真正接入前，运行时仍会回落到 Energy 实现。
     */
    var backend: VadBackend
        get() = VadBackend.fromStorage(prefs.getString(KEY_VAD_BACKEND, DEFAULT_VAD_BACKEND.storageValue))
        set(value) = prefs.edit().putString(KEY_VAD_BACKEND, value.storageValue).apply()
    
    /**
     * 静音超时时间（毫秒）
     * 用户停止说话超过此时间后自动停止录音
     */
    var silenceTimeoutMs: Long
        get() = prefs.getLong(KEY_SILENCE_TIMEOUT, DEFAULT_SILENCE_TIMEOUT_MS)
        set(value) {
            val clamped = value.coerceIn(MIN_SILENCE_TIMEOUT_MS, MAX_SILENCE_TIMEOUT_MS)
            prefs.edit().putLong(KEY_SILENCE_TIMEOUT, clamped).apply()
        }
    
    /**
     * 语音能量阈值
     */
    var speechThreshold: Float
        get() = prefs.getFloat(KEY_SPEECH_THRESHOLD, DEFAULT_SPEECH_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_THRESHOLD, value).apply()
    
    /**
     * 静音能量阈值
     */
    var silenceThreshold: Float
        get() = prefs.getFloat(KEY_SILENCE_THRESHOLD, DEFAULT_SILENCE_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_SILENCE_THRESHOLD, value).apply()
    
    /**
     * 创建 VAD 处理器
     */
    fun createVadProcessor(): VadProcessor {
        return createVadProcessorOrNull() ?: EnergyVadProcessor(
            speechThreshold = speechThreshold,
            silenceThreshold = silenceThreshold,
            silenceTimeoutMs = silenceTimeoutMs
        )
    }

    /**
     * 创建启用中的 VAD 处理器；若 VAD 关闭则返回 null。
     */
    fun createVadProcessorOrNull(): VadProcessor? {
        if (!isVadEnabled) return null
        return when (backend) {
            VadBackend.TEN ->
                TenVadFactory.createProcessorOrNull(
                    context = appContext,
                    silenceTimeoutMs = silenceTimeoutMs,
                ) ?: createEnergyProcessor()
            VadBackend.ENERGY -> createEnergyProcessor()
        }
    }

    fun preloadPreferredBackendIfPossible() {
        if (!isVadEnabled) return
        if (backend == VadBackend.TEN) {
            TenVadFactory.preloadIfPossible(
                context = appContext,
                silenceTimeoutMs = silenceTimeoutMs,
            )
        }
    }

    private fun createEnergyProcessor(): VadProcessor {
        return EnergyVadProcessor(
            speechThreshold = speechThreshold,
            silenceThreshold = silenceThreshold,
            silenceTimeoutMs = silenceTimeoutMs
        )
    }

    fun resolveRuntimeStatus(): VadRuntimeStatus {
        if (!isVadEnabled) {
            return VadRuntimeStatus(
                isEnabled = false,
                requestedBackend = null,
                actualBackend = null,
                detail = "VAD 已关闭",
            )
        }

        return when (backend) {
            VadBackend.ENERGY ->
                VadRuntimeStatus(
                    isEnabled = true,
                    requestedBackend = VadBackend.ENERGY,
                    actualBackend = VadBackend.ENERGY,
                    detail = "当前使用 Energy 能量阈值判停",
                )

            VadBackend.TEN -> {
                val availability = TenVadFactory.resolveAvailability(appContext)
                if (availability.isAvailable) {
                    VadRuntimeStatus(
                        isEnabled = true,
                        requestedBackend = VadBackend.TEN,
                        actualBackend = VadBackend.TEN,
                        detail = availability.detail,
                    )
                } else {
                    VadRuntimeStatus(
                        isEnabled = true,
                        requestedBackend = VadBackend.TEN,
                        actualBackend = VadBackend.ENERGY,
                        detail = "${availability.detail}，已回退 Energy",
                    )
                }
            }
        }
    }
    
    /**
     * 重置为默认配置
     */
    fun resetToDefaults() {
        prefs.edit().apply {
            putBoolean(KEY_VAD_ENABLED, true)
            putString(KEY_VAD_BACKEND, DEFAULT_VAD_BACKEND.storageValue)
            putLong(KEY_SILENCE_TIMEOUT, DEFAULT_SILENCE_TIMEOUT_MS)
            putFloat(KEY_SPEECH_THRESHOLD, DEFAULT_SPEECH_THRESHOLD)
            putFloat(KEY_SILENCE_THRESHOLD, DEFAULT_SILENCE_THRESHOLD)
            apply()
        }
    }
    
    /**
     * 获取配置描述
     */
    fun getConfigDescription(): String {
        val runtimeStatus = resolveRuntimeStatus()
        return if (runtimeStatus.isEnabled) {
            "VAD已启用，当前后端 ${runtimeStatus.toBadgeLabel()}，静音超时 ${silenceTimeoutMs}ms；${runtimeStatus.detail}"
        } else {
            "VAD已禁用"
        }
    }
}
