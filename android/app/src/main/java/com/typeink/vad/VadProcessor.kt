package com.typeink.vad

/**
 * VAD（语音活动检测）处理器 - 对标 参考键盘 的智能判停
 * 
 * 使用 Silero VAD 模型检测语音开始和结束
 * 支持可配置的静音超时自动停止
 */
interface VadProcessor {
    
    /**
     * VAD 处理结果
     */
    sealed class VadResult {
        data object SPEECH : VadResult()
        data object SILENCE : VadResult()
        data object UNKNOWN : VadResult()
    }
    
    /**
     * VAD 监听器
     */
    interface Listener {
        /**
         * 检测到语音开始
         */
        fun onSpeechStart()
        
        /**
         * 检测到静音，返回静音持续时间（毫秒）
         */
        fun onSilenceDetected(durationMs: Long)
        
        /**
         * 检测到语音结束（静音超时）
         */
        fun onSpeechEnd()
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: Listener)
    
    /**
     * 处理音频块
     * @param audioChunk PCM 16bit 音频数据
     * @return VAD 结果
     */
    fun process(audioChunk: ByteArray): VadResult
    
    /**
     * 重置 VAD 状态
     */
    fun reset()
    
    /**
     * 释放资源
     */
    fun release()
}
