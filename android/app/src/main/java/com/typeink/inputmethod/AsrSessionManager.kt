package com.typeink.inputmethod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.typeink.asr.DraftRecognizer
import com.typeink.prototype.DashScopeService
import com.typeink.prototype.PcmRecorder
import com.typeink.prototype.TypeinkStyleMode
import com.typeink.vad.VadConfig
import com.typeink.vad.VadProcessor

/**
 * ASR 会话管理器 - 修复版
 *
 * 职责：
 * - 管理 ASR 引擎生命周期
 * - 协调音频采集和 ASR 识别
 * - 使用原有的 DashScopeService 链路
 */
class AsrSessionManager(
    private val context: Context,
    private val dashScopeService: DashScopeService,
    private val recorder: PcmRecorder,
    private val localDraftRecognizer: DraftRecognizer,
    private val actionHandler: KeyboardActionHandler? = null
) {
    companion object {
        private const val TAG = "AsrSessionManager"
    }
    
    /**
     * 会话监听器 - 供 View 使用
     */
    interface Listener {
        fun onDraftText(text: String)
        fun onAsrPartial(text: String)
        fun onAsrFinal(text: String)
        fun onLlmDelta(token: String)
        fun onLlmCompleted(finalText: String)
        fun onError(message: String)
        fun onAmplitude(amplitude: Float)
        fun onSilenceDetected(durationMs: Long)
    }
    
    private var listener: Listener? = null
    private var isSessionActive: Boolean = false
    private var isCapturingAudio: Boolean = false
    private var isAwaitingRemoteResult: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vadConfig = VadConfig.load(context)
    private var vadProcessor: VadProcessor? = null
    
    /**
     * 设置监听器
     */
    fun setListener(listener: Listener?) {
        this.listener = listener
    }
    
    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = isSessionActive
    
    /**
     * 开始 ASR 会话
     */
    fun startSession(
        styleMode: TypeinkStyleMode = TypeinkStyleMode.NATURAL,
        snapshot: com.typeink.prototype.SessionInputSnapshot
    ) {
        if (isSessionActive) {
            Log.w(TAG, "Session already running, stopping first")
            forceStop()
        }
        
        Log.d(TAG, "Starting ASR session")
        isSessionActive = true
        isCapturingAudio = false
        isAwaitingRemoteResult = false
        
        try {
            setupVadProcessor()

            // 通知 actionHandler 开始录音（只更新状态）
            actionHandler?.startRecording(styleMode)

            // 设置样式模式
            dashScopeService.setStyleMode(styleMode)

            // 启动本地草稿识别
            localDraftRecognizer.start(
                object : DraftRecognizer.Listener {
                    override fun onDraftText(text: String) {
                        if (!isSessionActive || !isCapturingAudio) return
                        listener?.onDraftText(text)
                    }

                    override fun onError(message: String) {
                        if (!isSessionActive || !isCapturingAudio) return
                        Log.w(TAG, "Local draft error: $message")
                    }

                    override fun onUnavailable() {
                        Log.d(TAG, "Local draft unavailable")
                    }
                }
            )

            // 启动 DashScope ASR
            dashScopeService.startAsr(
                listener = object : DashScopeService.Listener {
                    override fun onAsrPartial(text: String) {
                        if (!isSessionActive) return
                        localDraftRecognizer.stop()
                        listener?.onAsrPartial(text)
                        actionHandler?.onAsrPartialInternal(text)
                    }

                    override fun onAsrFinal(text: String) {
                        if (!isSessionActive) return
                        isAwaitingRemoteResult = false
                        localDraftRecognizer.stop()
                        listener?.onAsrFinal(text)
                        actionHandler?.onAsrFinalInternal(text)
                    }

                    override fun onLlmDelta(token: String) {
                        if (!isSessionActive) return
                        listener?.onLlmDelta(token)
                        actionHandler?.onLlmDeltaInternal(token)
                    }

                    override fun onLlmCompleted(finalText: String) {
                        if (!isSessionActive) return
                        isSessionActive = false
                        isCapturingAudio = false
                        isAwaitingRemoteResult = false
                        listener?.onLlmCompleted(finalText)
                        actionHandler?.onLlmCompletedInternal(finalText)
                    }

                    override fun onError(message: String) {
                        if (!isSessionActive) return
                        isSessionActive = false
                        isCapturingAudio = false
                        isAwaitingRemoteResult = false
                        listener?.onError(message)
                        actionHandler?.onErrorInternal(message)
                    }
                },
                snapshot = snapshot
            )

            // 启动录音
            recorder.start(
                object : PcmRecorder.Listener {
                    override fun onAudioChunk(bytes: ByteArray) {
                        if (!isSessionActive || !isCapturingAudio) return
                        vadProcessor?.process(bytes)
                        dashScopeService.sendAudioChunk(bytes)
                    }

                    override fun onError(message: String) {
                        if (!isSessionActive) return
                        isSessionActive = false
                        isCapturingAudio = false
                        isAwaitingRemoteResult = false
                        val errorMsg = "麦克风采集失败: $message"
                        listener?.onError(errorMsg)
                        actionHandler?.onErrorInternal(errorMsg)
                    }

                    override fun onAmplitude(level: Float) {
                        if (!isSessionActive || !isCapturingAudio) return
                        listener?.onAmplitude(level)
                        actionHandler?.updateAmplitude(level)
                    }
                }
            )
            isCapturingAudio = true

            actionHandler?.onAsrReady()
        } catch (t: Throwable) {
            isSessionActive = false
            isCapturingAudio = false
            isAwaitingRemoteResult = false
            recorder.stop()
            localDraftRecognizer.stop()
            releaseVadProcessor()
            dashScopeService.stopAsr()
            val errorMsg = "启动语音会话失败: ${t.message ?: "未知错误"}"
            Log.e(TAG, errorMsg, t)
            listener?.onError(errorMsg)
            actionHandler?.onErrorInternal(errorMsg)
        }
    }
    
    /**
     * 停止 ASR 会话
     */
    fun stopSession() {
        if (!isSessionActive) {
            return
        }
        if (!isCapturingAudio && isAwaitingRemoteResult) {
            Log.d(TAG, "stopSession ignored: already waiting for ASR/LLM completion")
            return
        }
        
        Log.d(TAG, "Stopping ASR session")
        isCapturingAudio = false
        isAwaitingRemoteResult = true
        
        // 停止录音
        recorder.stop()
        releaseVadProcessor()
        
        // 停止本地草稿
        localDraftRecognizer.stop()
        
        // 停止 DashScope ASR
        dashScopeService.stopAsr()
        
        // 通知 actionHandler
        actionHandler?.stopRecording()
    }
    
    /**
     * 强制停止 - 取消当前会话并丢弃结果
     */
    fun forceStop() {
        Log.d(TAG, "Force stopping ASR session")
        isSessionActive = false
        isCapturingAudio = false
        isAwaitingRemoteResult = false
        
        recorder.stop()
        localDraftRecognizer.stop()
        releaseVadProcessor()
        dashScopeService.cancelActiveSession()
        
        // 通知 actionHandler
        actionHandler?.forceStop()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up")
        forceStop()
        listener = null
    }

    private fun setupVadProcessor() {
        releaseVadProcessor()
        vadProcessor =
            vadConfig.createVadProcessorOrNull()?.also { processor ->
                processor.reset()
                processor.setListener(
                    object : VadProcessor.Listener {
                        override fun onSpeechStart() = Unit

                        override fun onSilenceDetected(durationMs: Long) {
                            if (!isSessionActive) return
                            listener?.onSilenceDetected(durationMs)
                        }

                        override fun onSpeechEnd() {
                            if (!isSessionActive) return
                            mainHandler.post {
                                if (isSessionActive) {
                                    Log.d(TAG, "VAD detected speech end, auto stopping session")
                                    stopSession()
                                }
                            }
                        }
                    },
                )
            }
    }

    private fun releaseVadProcessor() {
        vadProcessor?.release()
        vadProcessor = null
    }
}
