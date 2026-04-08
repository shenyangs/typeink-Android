package com.typeink.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 音频采集管理器 - 参考 参考键盘 实现
 *
 * 职责：
 * - 统一管理音频采集
 * - 振幅计算和回调
 * - 音频格式转换
 * - VAD 自动停录支持
 */
class AudioCaptureManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioCaptureManager"
        
        // 音频参数
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE = 3200 // 100ms @ 16kHz, 16bit, mono
    }
    
    /**
     * 监听器接口
     */
    interface Listener {
        /**
         * 音频数据回调
         */
        fun onAudioChunk(chunk: ByteArray)
        
        /**
         * 振幅回调（dB，范围约 -90 到 0）
         */
        fun onAmplitude(amplitudeDb: Float)
        
        /**
         * 静音检测回调
         */
        fun onSilenceDetected(durationMs: Long)
        
        /**
         * 错误回调
         */
        fun onError(error: String)
    }
    
    private var audioRecord: AudioRecord? = null
    private var listener: Listener? = null
    private var isRunning = false
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    
    // VAD 相关
    private var vadProcessor: VadProcessor? = null
    private var silenceStartTime: Long = 0L
    private var isSpeaking = false
    
    /**
     * 开始采集
     */
    fun start(listener: Listener, enableVad: Boolean = true) {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }
        
        if (!checkPermission()) {
            listener.onError("没有录音权限")
            return
        }
        
        this.listener = listener
        
        // 初始化 VAD
        if (enableVad) {
            vadProcessor = VadProcessor()
        }
        
        // 初始化 AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            maxOf(minBufferSize, BUFFER_SIZE * 2)
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("音频录制初始化失败")
            return
        }
        
        isRunning = true
        audioRecord?.startRecording()
        
        // 启动采集协程
        captureJob = scope.launch {
            captureLoop()
        }
        
        Log.d(TAG, "Audio capture started")
    }
    
    /**
     * 停止采集
     */
    fun stop() {
        Log.d(TAG, "Stopping audio capture")
        isRunning = false
        
        captureJob?.cancel()
        captureJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        vadProcessor = null
        silenceStartTime = 0L
        isSpeaking = false
        
        Log.d(TAG, "Audio capture stopped")
    }
    
    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = isRunning
    
    /**
     * 检查录音权限
     */
    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 采集循环
     */
    private suspend fun captureLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        
        while (isRunning && coroutineContext.isActive) {
            try {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (readSize > 0) {
                    // 复制数据（避免引用被修改）
                    val chunk = buffer.copyOf(readSize)
                    
                    // 计算振幅
                    val amplitudeDb = calculateAmplitude(chunk)
                    
                    // VAD 处理
                    processVad(amplitudeDb)
                    
                    // 回调
                    withContext(Dispatchers.Main) {
                        listener?.onAudioChunk(chunk)
                        listener?.onAmplitude(amplitudeDb)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture error", e)
                withContext(Dispatchers.Main) {
                    listener?.onError(e.message ?: "采集错误")
                }
            }
        }
    }
    
    /**
     * 计算音频振幅（dB）
     */
    private fun calculateAmplitude(buffer: ByteArray): Float {
        if (buffer.size < 2) return -90f
        
        // 将 byte[] 转换为 short[]（16bit PCM）
        val samples = ShortArray(buffer.size / 2)
        for (i in samples.indices) {
            samples[i] = ((buffer[i * 2 + 1].toInt() shl 8) or 
                          (buffer[i * 2].toInt() and 0xFF)).toShort()
        }
        
        // 计算 RMS（均方根）
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        val rms = sqrt(sum / samples.size)
        
        // 转换为 dB
        return if (rms > 0) {
            (20 * log10(rms / 32768.0)).toFloat()
        } else {
            -90f
        }
    }
    
    /**
     * VAD 处理
     */
    private fun processVad(amplitudeDb: Float) {
        val vad = vadProcessor ?: return
        
        val isVoice = vad.process(amplitudeDb)
        val now = System.currentTimeMillis()
        
        when {
            isVoice && !isSpeaking -> {
                // 开始说话
                isSpeaking = true
                silenceStartTime = 0L
                Log.d(TAG, "Speech started")
            }
            !isVoice && isSpeaking -> {
                // 可能停止说话
                if (silenceStartTime == 0L) {
                    silenceStartTime = now
                } else {
                    val silenceDuration = now - silenceStartTime
                    if (silenceDuration > vad.silenceTimeoutMs) {
                        // 确认停止说话
                        isSpeaking = false
                        silenceStartTime = 0L
                        Log.d(TAG, "Speech ended, silence duration: $silenceDuration ms")
                        
                        // 回调静音检测
                        listener?.onSilenceDetected(silenceDuration)
                    }
                }
            }
            isVoice -> {
                // 持续说话，重置静音计时
                silenceStartTime = 0L
            }
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        scope.cancel()
        listener = null
    }
}

/**
 * VAD（语音活动检测）处理器
 */
class VadProcessor {
    
    companion object {
        // 默认阈值（dB）
        private const val DEFAULT_VOICE_THRESHOLD = -40f
        private const val DEFAULT_SILENCE_THRESHOLD = -45f
        
        // 默认静音超时（ms）
        private const val DEFAULT_SILENCE_TIMEOUT_MS = 1500L
    }
    
    /**
     * 语音阈值（超过此值认为是语音）
     */
    var voiceThresholdDb: Float = DEFAULT_VOICE_THRESHOLD
    
    /**
     * 静音阈值（低于此值认为是静音）
     */
    var silenceThresholdDb: Float = DEFAULT_SILENCE_THRESHOLD
    
    /**
     * 静音超时（超过此时长认为说话结束）
     */
    var silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS
    
    // 平滑处理（避免抖动）
    private val amplitudeHistory = ArrayDeque<Float>(5)
    
    /**
     * 处理振幅，返回是否为语音
     */
    fun process(amplitudeDb: Float): Boolean {
        // 添加到历史记录
        amplitudeHistory.addLast(amplitudeDb)
        if (amplitudeHistory.size > 5) {
            amplitudeHistory.removeFirst()
        }
        
        // 计算平滑后的振幅（使用中位数）
        val smoothedAmplitude = amplitudeHistory.sorted()[amplitudeHistory.size / 2]
        
        return smoothedAmplitude > voiceThresholdDb
    }
    
    /**
     * 重置状态
     */
    fun reset() {
        amplitudeHistory.clear()
    }
}
