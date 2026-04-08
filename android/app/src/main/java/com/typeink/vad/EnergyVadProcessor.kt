package com.typeink.vad

import android.util.Log

/**
 * 基于能量的 VAD 处理器 - 轻量级实现
 * 
 * 作为 Silero VAD 的轻量级替代方案，基于音频能量检测语音活动
 * 适合作为 VAD 功能的初步实现，后续可无缝替换为 Silero VAD
 */
class EnergyVadProcessor(
    private val speechThreshold: Float = 0.02f,      // 语音能量阈值
    private val silenceThreshold: Float = 0.01f,     // 静音能量阈值
    private val silenceTimeoutMs: Long = 1500L       // 静音超时时间（毫秒）
) : VadProcessor {
    
    companion object {
        private const val TAG = "EnergyVadProcessor"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_MS = 20      // 每块音频 20ms
    }
    
    private var listener: VadProcessor.Listener? = null
    
    // VAD 状态
    private var currentState: VadState = VadState.IDLE
    private var silenceStartTime: Long = 0
    private var lastSpeechTime: Long = 0
    private var totalProcessedMs: Long = 0
    
    // 平滑处理
    private val energyBuffer = ArrayDeque<Float>(10)
    private val bufferSize = 5
    
    private enum class VadState {
        IDLE,
        SPEECH_START,
        SPEECH_ONGOING,
        SILENCE_START,
        AUTO_STOP
    }
    
    override fun setListener(listener: VadProcessor.Listener) {
        this.listener = listener
    }
    
    override fun process(audioChunk: ByteArray): VadProcessor.VadResult {
        val energy = calculateEnergy(audioChunk)
        val smoothedEnergy = smoothEnergy(energy)
        
        totalProcessedMs += CHUNK_DURATION_MS
        
        return when (currentState) {
            VadState.IDLE -> handleIdleState(smoothedEnergy)
            VadState.SPEECH_START, VadState.SPEECH_ONGOING -> handleSpeechState(smoothedEnergy)
            VadState.SILENCE_START -> handleSilenceState(smoothedEnergy)
            VadState.AUTO_STOP -> {
                currentState = VadState.IDLE
                VadProcessor.VadResult.SILENCE
            }
        }
    }
    
    private fun handleIdleState(energy: Float): VadProcessor.VadResult {
        return if (energy > speechThreshold) {
            Log.d(TAG, "Speech detected, energy=$energy")
            currentState = VadState.SPEECH_START
            lastSpeechTime = totalProcessedMs
            listener?.onSpeechStart()
            VadProcessor.VadResult.SPEECH
        } else {
            VadProcessor.VadResult.SILENCE
        }
    }
    
    private fun handleSpeechState(energy: Float): VadProcessor.VadResult {
        return if (energy < silenceThreshold) {
            // 检测到可能的静音开始
            if (currentState != VadState.SILENCE_START) {
                Log.d(TAG, "Silence start detected, energy=$energy")
                currentState = VadState.SILENCE_START
                silenceStartTime = totalProcessedMs
            }
            
            // 检查静音超时
            val silenceDuration = totalProcessedMs - silenceStartTime
            if (silenceDuration > silenceTimeoutMs) {
                Log.d(TAG, "Auto stop triggered, silenceDuration=$silenceDuration")
                currentState = VadState.AUTO_STOP
                listener?.onSpeechEnd()
                VadProcessor.VadResult.SILENCE
            } else {
                listener?.onSilenceDetected(silenceDuration)
                VadProcessor.VadResult.SILENCE
            }
        } else {
            // 语音继续
            if (currentState == VadState.SILENCE_START) {
                // 从静音恢复
                Log.d(TAG, "Speech resumed, energy=$energy")
                currentState = VadState.SPEECH_ONGOING
            } else {
                currentState = VadState.SPEECH_ONGOING
            }
            lastSpeechTime = totalProcessedMs
            VadProcessor.VadResult.SPEECH
        }
    }
    
    private fun handleSilenceState(energy: Float): VadProcessor.VadResult {
        return if (energy > speechThreshold) {
            // 语音恢复
            Log.d(TAG, "Speech resumed from silence, energy=$energy")
            currentState = VadState.SPEECH_ONGOING
            lastSpeechTime = totalProcessedMs
            VadProcessor.VadResult.SPEECH
        } else {
            // 继续静音
            val silenceDuration = totalProcessedMs - silenceStartTime
            if (silenceDuration > silenceTimeoutMs) {
                Log.d(TAG, "Auto stop triggered, silenceDuration=$silenceDuration")
                currentState = VadState.AUTO_STOP
                listener?.onSpeechEnd()
            } else {
                listener?.onSilenceDetected(silenceDuration)
            }
            VadProcessor.VadResult.SILENCE
        }
    }
    
    /**
     * 计算音频能量
     */
    private fun calculateEnergy(audioChunk: ByteArray): Float {
        if (audioChunk.size < 2) return 0f
        
        var sum = 0.0
        // PCM 16bit 小端格式
        for (i in audioChunk.indices step 2) {
            if (i + 1 < audioChunk.size) {
                val sample = (audioChunk[i + 1].toInt() shl 8) or (audioChunk[i].toInt() and 0xFF)
                val normalized = sample / 32768.0
                sum += normalized * normalized
            }
        }
        
        val samples = audioChunk.size / 2
        return if (samples > 0) {
            Math.sqrt(sum / samples).toFloat()
        } else {
            0f
        }
    }
    
    /**
     * 平滑能量值
     */
    private fun smoothEnergy(energy: Float): Float {
        energyBuffer.addLast(energy)
        if (energyBuffer.size > bufferSize) {
            energyBuffer.removeFirst()
        }
        return energyBuffer.average().toFloat()
    }
    
    override fun reset() {
        Log.d(TAG, "Resetting VAD state")
        currentState = VadState.IDLE
        silenceStartTime = 0
        lastSpeechTime = 0
        totalProcessedMs = 0
        energyBuffer.clear()
    }
    
    override fun release() {
        Log.d(TAG, "Releasing VAD resources")
        listener = null
        energyBuffer.clear()
    }
    
    /**
     * 获取当前状态描述
     */
    fun getStateDescription(): String {
        return when (currentState) {
            VadState.IDLE -> "空闲"
            VadState.SPEECH_START -> "语音开始"
            VadState.SPEECH_ONGOING -> "语音持续"
            VadState.SILENCE_START -> "静音检测"
            VadState.AUTO_STOP -> "自动停止"
        }
    }
    
    /**
     * 是否正在录音中（检测到语音）
     */
    fun isInSpeech(): Boolean {
        return currentState == VadState.SPEECH_START || 
               currentState == VadState.SPEECH_ONGOING
    }
}
