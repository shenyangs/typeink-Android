package com.typeink.asr

import android.content.Context
import android.util.Log

/**
 * 本地 ASR 引擎接口
 * 
 * 支持离线语音识别，无需网络连接
 * 可以接入 Whisper、Paraformer 等本地模型
 */
interface LocalAsrEngine {
    
    /**
     * 是否可用（模型是否已下载/加载）
     */
    fun isAvailable(): Boolean
    
    /**
     * 初始化引擎
     */
    fun initialize(context: Context, callback: InitCallback)
    
    /**
     * 开始识别
     */
    fun startListening(listener: AsrListener)
    
    /**
     * 发送音频数据
     */
    fun sendAudio(audioData: ByteArray)
    
    /**
     * 停止识别
     */
    fun stopListening()
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 获取引擎名称
     */
    fun getEngineName(): String
    
    /**
     * 获取模型信息
     */
    fun getModelInfo(): ModelInfo
    
    /**
     * 初始化回调
     */
    interface InitCallback {
        fun onSuccess()
        fun onError(error: String)
        fun onProgress(progress: Float)
    }
    
    /**
     * ASR 监听器
     */
    interface AsrListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }
    
    /**
     * 模型信息
     */
    data class ModelInfo(
        val name: String,
        val version: String,
        val language: String,
        val sizeMB: Long,
        val supportsOffline: Boolean = true
    )
}

/**
 * 本地 ASR 引擎管理器
 */
class LocalAsrManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalAsrManager"
    }
    
    private val engines = mutableMapOf<String, LocalAsrEngine>()
    private var currentEngine: LocalAsrEngine? = null
    
    /**
     * 注册引擎
     */
    fun registerEngine(engine: LocalAsrEngine) {
        engines[engine.getEngineName()] = engine
        Log.d(TAG, "Registered engine: ${engine.getEngineName()}")
    }
    
    /**
     * 获取所有可用引擎
     */
    fun getAvailableEngines(): List<LocalAsrEngine> {
        return engines.values.filter { it.isAvailable() }.toList()
    }
    
    /**
     * 切换引擎
     */
    fun setEngine(engineName: String): Boolean {
        val engine = engines[engineName]
        return if (engine != null && engine.isAvailable()) {
            currentEngine = engine
            true
        } else {
            false
        }
    }
    
    /**
     * 获取当前引擎
     */
    fun getCurrentEngine(): LocalAsrEngine? = currentEngine
}
