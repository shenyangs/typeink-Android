package com.typeink.inputmethod

import android.content.Context
import android.util.Log
import com.typeink.prototype.DashScopeService
import com.typeink.prototype.TypeinkStyleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 键盘动作处理器 - 参考 参考键盘 实现
 *
 * 职责：
 * - 管理键盘状态机（使用 KeyboardState）
 * - 处理所有用户操作（麦克风、AI编辑、后处理等）
 * - 协调各个组件（ASR、输入连接、后处理）
 * - 处理 ASR 回调并触发状态转换
 * - 管理会话上下文（撤销快照、最后提交的文本等）
 * - 触发 UI 更新
 *
 * @param context 上下文
 * @param dashScopeService ASR 服务
 * @param inputHelper 输入连接辅助
 */
class KeyboardActionHandler(
    private val context: Context,
    private val dashScopeService: DashScopeService,
    private val inputHelper: InputConnectionHelper
) {
    companion object {
        private const val TAG = "KeyboardActionHandler"
        private const val TIMEOUT_PROCESSING_MS = 30000L // 30秒超时
    }
    
    /**
     * UI 回调接口 - 通知 UI 更新
     */
    interface UiListener {
        /**
         * 状态变化回调
         */
        fun onStateChanged(state: KeyboardState)
        
        /**
         * 状态消息回调
         */
        fun onStatusMessage(message: String)
        
        /**
         * 振动反馈
         */
        fun onVibrate()
        
        /**
         * 振幅更新
         */
        fun onAmplitude(amplitude: Float)
        
        /**
         * 显示剪贴板预览
         */
        fun onShowClipboardPreview(preview: ClipboardPreview)
        
        /**
         * 隐藏剪贴板预览
         */
        fun onHideClipboardPreview()
    }
    
    private var uiListener: UiListener? = null
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 当前键盘状态
    private var currentState: KeyboardState = KeyboardState.Idle
    
    // 会话上下文
    private val sessionContext = KeyboardSessionContext()
    
    // 强制停止标记
    private var dropPendingFinal: Boolean = false
    
    // 振幅历史记录（用于波形显示）
    private val amplitudeHistory = mutableListOf<Float>()
    private val maxAmplitudeHistory = 20
    
    // 超时控制任务
    private var timeoutJob: kotlinx.coroutines.Job? = null
    
    init {
        Log.d(TAG, "KeyboardActionHandler initialized")
    }
    
    /**
     * 设置 UI 监听器
     */
    fun setUiListener(listener: UiListener?) {
        this.uiListener = listener
    }
    
    /**
     * 获取当前状态
     */
    fun getCurrentState(): KeyboardState = currentState
    
    /**
     * 状态转换
     */
    private fun transitionTo(newState: KeyboardState) {
        Log.d(TAG, "State transition: ${currentState.name} -> ${newState.name}")
        currentState = newState
        uiListener?.onStateChanged(newState)
        
        // 根据状态处理超时
        when (newState) {
            is KeyboardState.Processing, is KeyboardState.Rewriting -> {
                startTimeout()
            }
            else -> {
                cancelTimeout()
            }
        }
    }
    
    /**
     * 开始超时计时
     */
    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_PROCESSING_MS)
            Log.w(TAG, "Processing timeout, transitioning to failed")
            transitionTo(
                KeyboardState.Failed(
                    error = "处理超时，请重试",
                    retryable = true
                )
            )
        }
    }
    
    /**
     * 取消超时计时
     */
    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
    
    // ==================== 用户操作 ====================
    
    /**
     * 开始录音 - 由 AsrSessionManager 驱动，只管理状态
     */
    fun startRecording(styleMode: TypeinkStyleMode = TypeinkStyleMode.NATURAL) {
        if (!currentState.canStartRecording) {
            Log.w(TAG, "Cannot start recording in state: ${currentState.name}")
            return
        }
        
        Log.d(TAG, "Starting recording (state only)")
        
        // 重置会话上下文
        sessionContext.reset()
        dropPendingFinal = false
        amplitudeHistory.clear()
        
        // 设置样式模式
        dashScopeService.setStyleMode(styleMode)
        
        // 转换到准备状态
        transitionTo(KeyboardState.Preparing("正在打开麦克风..."))
        
        // 注意：实际的 ASR 启动由 AsrSessionManager 处理
        // 这里只管理状态机
        
        uiListener?.onVibrate()
    }
    
    /**
     * ASR 已就绪（从 AsrSessionManager 回调）
     */
    fun onAsrReady() {
        if (currentState is KeyboardState.Preparing) {
            transitionTo(KeyboardState.Listening(
                hint = "请说话...",
                audioDurationMs = 0L
            ))
        }
    }
    
    /**
     * 停止录音 - 只更新状态，实际 ASR 停止由 AsrSessionManager 处理
     */
    fun stopRecording() {
        when (val state = currentState) {
            is KeyboardState.Listening -> {
                Log.d(TAG, "Stopping recording from listening, waiting for ASR final")
                transitionTo(
                    KeyboardState.Processing(
                        partialText = sessionContext.originalText,
                        audioDurationMs = System.currentTimeMillis() - sessionContext.sessionStartTimeMs,
                        hint = "正在结束收音...",
                    ),
                )
            }

            is KeyboardState.Processing -> {
                Log.d(TAG, "Stopping recording from processing, waiting for ASR final")
                transitionTo(
                    state.copy(
                        audioDurationMs = System.currentTimeMillis() - sessionContext.sessionStartTimeMs,
                        hint = "正在结束收音...",
                    ),
                )
            }

            is KeyboardState.Rewriting -> {
                Log.d(TAG, "stopRecording ignored because rewrite is already running")
                return
            }

            else -> {
                Log.w(TAG, "Cannot stop recording in state: ${currentState.name}")
                return
            }
        }

        uiListener?.onVibrate()
    }
    
    /**
     * 强制停止 - 取消当前会话
     */
    fun forceStop() {
        Log.d(TAG, "Force stopping")
        
        // 标记丢弃后续回调
        dropPendingFinal = true
        sessionContext.nextOpSeq()
        
        // 取消当前链路并丢弃所有晚到结果
        dashScopeService.cancelActiveSession()
        
        // 取消超时
        cancelTimeout()
        
        // 转换到空闲状态
        transitionTo(KeyboardState.Cancelled)
        
        // 短暂延迟后回到 Idle
        scope.launch {
            delay(100)
            transitionTo(KeyboardState.Idle)
        }
    }
    
    /**
     * 清除所有内容
     */
    fun clearAll() {
        Log.d(TAG, "Clearing all")
        forceStop()
        sessionContext.reset()
        transitionTo(KeyboardState.Idle)
    }
    
    /**
     * 更新振幅 - 由外部调用（如 PcmRecorder）
     */
    fun updateAmplitude(amplitude: Float) {
        if (currentState !is KeyboardState.Listening) return
        
        // 更新振幅历史
        amplitudeHistory.add(amplitude)
        if (amplitudeHistory.size > maxAmplitudeHistory) {
            amplitudeHistory.removeAt(0)
        }
        
        // 更新状态
        val current = currentState as KeyboardState.Listening
        transitionTo(current.copy(
            currentAmplitude = amplitude,
            amplitudes = amplitudeHistory.toList(),
            audioDurationMs = System.currentTimeMillis() - sessionContext.sessionStartTimeMs
        ))
        
        uiListener?.onAmplitude(amplitude)
    }
    
    // ==================== ASR 回调处理 ====================
    
    internal fun onAsrPartialInternal(text: String) {
        Log.d(TAG, "ASR partial: $text")
        
        sessionContext.originalText = text
        
        transitionTo(KeyboardState.Processing(
            partialText = text,
            audioDurationMs = System.currentTimeMillis() - sessionContext.sessionStartTimeMs,
            hint = "正在识别..."
        ))
    }
    
    internal fun onAsrFinalInternal(text: String) {
        Log.d(TAG, "ASR final: $text")
        
        sessionContext.originalText = text
        sessionContext.audioDurationMs = System.currentTimeMillis() - sessionContext.sessionStartTimeMs
        
        transitionTo(KeyboardState.Rewriting(
            partialText = text,
            hint = "正在润色...",
            isStreaming = true
        ))
    }
    
    internal fun onLlmDeltaInternal(token: String) {
        Log.d(TAG, "LLM delta: $token")
        
        sessionContext.rewrittenText += token
        
        val current = currentState
        if (current is KeyboardState.Rewriting) {
            transitionTo(current.copy(
                finalText = sessionContext.rewrittenText,
                isStreaming = true
            ))
        }
    }
    
    internal fun onLlmCompletedInternal(finalText: String) {
        Log.d(TAG, "LLM completed: $finalText")
        
        sessionContext.rewrittenText = finalText
        sessionContext.isApplied = true
        
        cancelTimeout()
        
        transitionTo(KeyboardState.Succeeded(
            finalText = finalText,
            originalText = sessionContext.originalText,
            isRewritten = true,
            hint = "已完成"
        ))
        
        uiListener?.onVibrate()
    }
    
    internal fun onErrorInternal(message: String) {
        Log.e(TAG, "ASR error: $message")
        
        cancelTimeout()
        
        transitionTo(KeyboardState.Failed(
            error = message,
            retryable = true,
            hint = "识别失败，请重试"
        ))
    }
    
    // ==================== 生命周期 ====================
    
    /**
     * 释放资源
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up")
        cancelTimeout()
        scope.cancel()
        forceStop()
    }
}
