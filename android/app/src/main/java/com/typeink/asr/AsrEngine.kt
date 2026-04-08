package com.typeink.asr

/**
 * ASR 引擎抽象接口 - 参考 参考键盘 实现
 *
 * 职责：
 * - 定义 ASR 引擎的标准接口
 * - 支持流式识别和文件识别
 * - 统一的错误处理
 */
interface AsrEngine {
    
    /**
     * 引擎厂商
     */
    val vendor: AsrVendor
    
    /**
     * 引擎是否可用（API Key 是否配置）
     */
    val isAvailable: Boolean
    
    /**
     * 引擎是否正在运行
     */
    fun isRunning(): Boolean
    
    /**
     * 开始识别
     */
    fun start(listener: Listener)
    
    /**
     * 停止识别
     */
    fun stop()
    
    /**
     * 发送音频数据（用于流式识别）
     */
    fun sendAudioChunk(chunk: ByteArray)
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 监听器接口
     */
    interface Listener {
        /**
         * 中间识别结果
         */
        fun onPartialResult(text: String)
        
        /**
         * 最终识别结果
         */
        fun onFinalResult(text: String)
        
        /**
         * 错误回调
         */
        fun onError(error: AsrError)
    }
}

/**
 * ASR 厂商枚举
 */
enum class AsrVendor {
    DASHSCOPE,      // 阿里云 DashScope
    FIRERED,        // 火山引擎 FireRedASR
    PARAFORMER,     // 阿里 Paraformer 本地模型
    SENSEVOICE,     // SenseVoice 本地模型
    OPENAI,         // OpenAI Whisper
    ELEVENLABS;     // ElevenLabs
    
    val displayName: String
        get() = when (this) {
            DASHSCOPE -> "阿里云 DashScope"
            FIRERED -> "火山引擎"
            PARAFORMER -> "Paraformer 本地"
            SENSEVOICE -> "SenseVoice 本地"
            OPENAI -> "OpenAI"
            ELEVENLABS -> "ElevenLabs"
        }
}

/**
 * ASR 错误
 */
data class AsrError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true
) {
    companion object {
        fun networkError(message: String) = AsrError("NETWORK", message, true)
        fun authError(message: String) = AsrError("AUTH", message, false)
        fun serverError(message: String) = AsrError("SERVER", message, true)
        fun clientError(message: String) = AsrError("CLIENT", message, false)
    }
}

/**
 * ASR 配置
 */
data class AsrConfig(
    val apiKey: String = "",
    val apiSecret: String = "",
    val appId: String = "",
    val endpoint: String = "",
    val language: String = "zh-CN",
    val sampleRate: Int = 16000,
    val enablePunctuation: Boolean = true,
    val enableITN: Boolean = true
)
