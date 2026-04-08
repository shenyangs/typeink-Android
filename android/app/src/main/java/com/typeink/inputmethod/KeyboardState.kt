package com.typeink.inputmethod

/**
 * 键盘状态机 - 参考 参考键盘 实现
 *
 * 职责：
 * - 定义键盘的所有可能状态
 * - 每个状态包含渲染所需的全部数据
 * - 支持状态转换和撤销/重做
 */
sealed class KeyboardState {
    
    /**
     * 空闲状态 - 初始状态，等待用户操作
     */
    data object Idle : KeyboardState()
    
    /**
     * 准备中状态 - 正在初始化录音或 ASR
     */
    data class Preparing(
        val message: String = "正在准备..."
    ) : KeyboardState()
    
    /**
     * 录音中状态 - 正在采集音频
     */
    data class Listening(
        val amplitudes: List<Float> = emptyList(),
        val currentAmplitude: Float = 0f,
        val audioDurationMs: Long = 0L,
        val hint: String = "请说话..."
    ) : KeyboardState()
    
    /**
     * 处理中状态 - ASR 返回中间结果
     */
    data class Processing(
        val partialText: String = "",
        val audioDurationMs: Long = 0L,
        val hint: String = "正在识别..."
    ) : KeyboardState()
    
    /**
     * 重写中状态 - LLM 正在后处理
     */
    data class Rewriting(
        val partialText: String = "",
        val finalText: String = "",
        val isStreaming: Boolean = true,
        val hint: String = "正在润色..."
    ) : KeyboardState()
    
    /**
     * 成功状态 - 识别和重写完成
     */
    data class Succeeded(
        val finalText: String,
        val originalText: String = "",
        val isRewritten: Boolean = false,
        val hint: String = "已完成"
    ) : KeyboardState()
    
    /**
     * 失败状态 - 发生错误
     */
    data class Failed(
        val error: String,
        val retryable: Boolean = true,
        val hint: String = "请重试"
    ) : KeyboardState()
    
    /**
     * 已取消状态 - 用户主动取消
     */
    data object Cancelled : KeyboardState()
    
    /**
     * 获取状态的人类可读名称
     */
    val name: String
        get() = when (this) {
            is Idle -> "IDLE"
            is Preparing -> "PREPARING"
            is Listening -> "LISTENING"
            is Processing -> "PROCESSING"
            is Rewriting -> "REWRITING"
            is Succeeded -> "SUCCEEDED"
            is Failed -> "FAILED"
            is Cancelled -> "CANCELLED"
        }
    
    /**
     * 判断当前是否正在录音（需要保持麦克风开启）
     */
    val isRecording: Boolean
        get() = this is Listening || this is Processing
    
    /**
     * 判断当前是否忙碌（禁止开始新的录音）
     */
    val isBusy: Boolean
        get() = this is Preparing || this is Rewriting
    
    /**
     * 判断是否可以开始录音
     */
    val canStartRecording: Boolean
        get() = this is Idle || this is Succeeded || this is Failed || this is Cancelled
    
    /**
     * 判断是否可以强制停止
     */
    val canForceStop: Boolean
        get() = this is Listening || this is Processing
}

/**
 * 键盘会话上下文 - 保存会话期间的状态数据
 */
data class KeyboardSessionContext(
    /**
     * 操作序列号，用于取消过期的异步操作
     */
    var opSeq: Long = 0L,
    
    /**
     * 会话开始时间
     */
    var sessionStartTimeMs: Long = 0L,
    
    /**
     * 音频时长（毫秒）
     */
    var audioDurationMs: Long = 0L,
    
    /**
     * 原始识别文本
     */
    var originalText: String = "",
    
    /**
     * 重写后的文本
     */
    var rewrittenText: String = "",
    
    /**
     * 是否已应用（写入输入框）
     */
    var isApplied: Boolean = false,
    
    /**
     * 撤销快照 - 用于撤销操作
     */
    var undoSnapshot: TextSnapshot? = null
) {
    /**
     * 递增操作序列号
     */
    fun nextOpSeq(): Long = ++opSeq
    
    /**
     * 重置上下文
     */
    fun reset() {
        nextOpSeq()
        sessionStartTimeMs = System.currentTimeMillis()
        audioDurationMs = 0L
        originalText = ""
        rewrittenText = ""
        isApplied = false
        undoSnapshot = null
    }
}

/**
 * 文本快照 - 用于撤销/重做
 */
data class TextSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 剪贴板预览数据
 */
data class ClipboardPreview(
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false
)
