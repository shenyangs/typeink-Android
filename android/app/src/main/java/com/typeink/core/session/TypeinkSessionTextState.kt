package com.typeink.core.session

/**
 * 平台无关的文本会话状态。
 *
 * stableText:
 * 最终确认或当前可继续编辑的文本。
 *
 * draftText:
 * ASR 草稿或中间识别文本。
 *
 * streamingText:
 * LLM 正在流式生成中的文本。
 */
data class TypeinkSessionTextState(
    val stableText: String = "",
    val draftText: String = "",
    val streamingText: String = "",
) {
    val displayFinalText: String
        get() = when {
            streamingText.isNotBlank() -> streamingText
            stableText.isNotBlank() -> stableText
            else -> ""
        }
}
