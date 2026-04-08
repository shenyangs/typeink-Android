package com.typeink.core.input

/**
 * 平台无关的输入阶段定义。
 *
 * 这层只放未来可能被 Android / iOS / Windows / macOS 复用的纯 Kotlin 逻辑，
 * 不直接依赖 Android View、Service 或 InputConnection。
 */
enum class TypeinkInputPhase {
    IDLE,
    PREPARING,
    LISTENING,
    PROCESSING,
    REWRITING,
    PREVIEW_READY,
    APPLIED,
    FAILED,
    CANCELLED,
}

data class TypeinkInputState(
    val phase: TypeinkInputPhase,
    val hint: String = "",
    val isRecording: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val isStreaming: Boolean = false,
    val errorMessage: String = "",
    val voiceInputEnabled: Boolean = true,
    val blockedReason: String = "",
    val hasPendingCommit: Boolean = false,
) {
    val isError: Boolean
        get() = phase == TypeinkInputPhase.FAILED || errorMessage.isNotBlank()

    val isProcessing: Boolean
        get() = phase == TypeinkInputPhase.PREPARING ||
            phase == TypeinkInputPhase.PROCESSING ||
            phase == TypeinkInputPhase.REWRITING
}
