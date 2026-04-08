package com.typeink.prototype

import com.typeink.core.input.TypeinkInputPhase
import com.typeink.core.input.TypeinkInputState

data class TypeinkStatusPresentation(
    val title: String,
    val hint: String,
    val isError: Boolean = false,
    val isProcessing: Boolean = false,
)

object TypeinkUiText {
    fun present(state: TypeinkInputState): TypeinkStatusPresentation {
        return when (state.phase) {
            TypeinkInputPhase.IDLE ->
                TypeinkStatusPresentation(
                    title = "准备好了",
                    hint = if (state.hint.isNotBlank()) state.hint else "点一下麦克风，开始说话。",
                )

            TypeinkInputPhase.PREPARING ->
                TypeinkStatusPresentation(
                    title = "正在准备",
                    hint = if (state.hint.isNotBlank()) state.hint else "麦克风和识别通道正在启动。",
                    isProcessing = true,
                )

            TypeinkInputPhase.LISTENING ->
                TypeinkStatusPresentation(
                    title = if (state.isRecording) "请说话..." else "正在转写",
                    hint = if (state.hint.isNotBlank()) state.hint else "语音草稿会先出现，润色结果随后跟上。",
                )

            TypeinkInputPhase.PROCESSING ->
                TypeinkStatusPresentation(
                    title = "正在转写",
                    hint = if (state.hint.isNotBlank()) state.hint else "系统正在整理刚刚的语音内容。",
                    isProcessing = true,
                )

            TypeinkInputPhase.REWRITING ->
                TypeinkStatusPresentation(
                    title = "正在润色",
                    hint = if (state.hint.isNotBlank()) state.hint else "系统正在把口语整理成更清晰的表达。",
                    isProcessing = true,
                )

            TypeinkInputPhase.PREVIEW_READY ->
                TypeinkStatusPresentation(
                    title = "等待确认",
                    hint = if (state.hint.isNotBlank()) state.hint else "结果已进入预编辑区，确认后再正式提交。",
                )

            TypeinkInputPhase.APPLIED ->
                TypeinkStatusPresentation(
                    title = "已整理完成",
                    hint = if (state.hint.isNotBlank()) state.hint else "结果已经写回，你可以继续补充。",
                )

            TypeinkInputPhase.FAILED ->
                TypeinkStatusPresentation(
                    title = "请稍后重试",
                    hint = if (state.hint.isNotBlank()) state.hint else "连接有点不稳定，请再试一次。",
                    isError = true,
                )

            TypeinkInputPhase.CANCELLED ->
                TypeinkStatusPresentation(
                    title = "已取消",
                    hint = if (state.hint.isNotBlank()) state.hint else "这次输入已经取消，你可以重新开始。",
                )
        }
    }

    fun present(
        phase: TypeinkUiPhase,
        hint: String,
        isRecording: Boolean,
    ): TypeinkStatusPresentation {
        return present(
            TypeinkInputState(
                phase = when (phase) {
                    TypeinkUiPhase.IDLE -> TypeinkInputPhase.IDLE
                    TypeinkUiPhase.CONNECTING -> TypeinkInputPhase.PREPARING
                    TypeinkUiPhase.LISTENING -> if (isRecording) TypeinkInputPhase.LISTENING else TypeinkInputPhase.PROCESSING
                    TypeinkUiPhase.REWRITING -> TypeinkInputPhase.REWRITING
                    TypeinkUiPhase.APPLIED -> TypeinkInputPhase.APPLIED
                    TypeinkUiPhase.FAILED -> TypeinkInputPhase.FAILED
                },
                hint = hint,
                isRecording = isRecording,
            ),
        )
    }

    fun friendlyBackendError(raw: String): String {
        val normalized = raw.lowercase()
        return when {
            normalized.contains("permission") || raw.contains("权限") -> "麦克风权限还没准备好，请先授权。"
            normalized.contains("timeout") || raw.contains("超时") -> "连接有点慢，请再试一次。"
            normalized.contains("network") || raw.contains("网络") -> "网络开了点小差，请稍后重试。"
            normalized.contains("connect") || raw.contains("连接") -> "连接暂时不稳定，请稍后再试。"
            else -> "刚刚那次没有顺利完成，请再试一次。"
        }
    }

    fun friendlyLocalDraftIssue(raw: String): String {
        val normalized = raw.lowercase()
        return when {
            normalized.contains("权限") -> "本地草稿没有拿到麦克风权限。"
            normalized.contains("不支持") -> "这台设备暂时不支持本地草稿，继续等待云端识别。"
            normalized.contains("网络") -> "本地草稿有点不稳定，继续等待云端识别。"
            else -> "本地草稿暂时断了一下，系统会继续听你说。"
        }
    }

    fun styleLabel(mode: TypeinkStyleMode): String {
        return when (mode) {
            TypeinkStyleMode.NATURAL -> "原声直出"
            TypeinkStyleMode.FORMAL -> "书面润色"
        }
    }
}
