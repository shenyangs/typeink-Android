package com.typeink.prototype

enum class TypeinkStyleMode(val wireValue: String) {
    NATURAL("natural"),
    FORMAL("formal"),
}

enum class SessionRewriteSource {
    DIRECT_INPUT,
    SELECTED_TEXT,
    RECENT_COMMIT,
}

enum class TypeinkUiPhase {
    IDLE,
    CONNECTING,
    LISTENING,
    REWRITING,
    APPLIED,
    FAILED,
}

data class SessionInputSnapshot(
    val originalText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val rewriteMode: Boolean,
    val source: SessionRewriteSource =
        if (rewriteMode) {
            SessionRewriteSource.SELECTED_TEXT
        } else {
            SessionRewriteSource.DIRECT_INPUT
        },
) {
    private val safeStart: Int
        get() = selectionStart.coerceIn(0, originalText.length)

    private val safeEnd: Int
        get() = selectionEnd.coerceIn(safeStart, originalText.length)

    val selectedText: String
        get() = originalText.substring(safeStart, safeEnd)

    val preText: String
        get() = originalText.substring(0, safeStart)

    val postText: String
        get() = originalText.substring(safeEnd)
}

data class RecentCommitSnapshot(
    val fullText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val appliedText: String,
    val createdAtMs: Long,
)

sealed class ServerFrame {
    data class SessionReady(val sessionId: String) : ServerFrame()

    data class AsrPartial(val text: String) : ServerFrame()

    data class AsrFinal(val text: String) : ServerFrame()

    data class LlmDelta(val token: String) : ServerFrame()

    data class LlmCompleted(val finalText: String) : ServerFrame()
}
