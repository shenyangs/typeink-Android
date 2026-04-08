package com.typeink.inputmethod

import com.typeink.prototype.DashScopeService
import com.typeink.prototype.SessionInputSnapshot
import com.typeink.prototype.TypeinkStyleMode

/**
 * 输入法宿主接口。
 *
 * 目的：
 * 1. 让 UI 只依赖最小宿主能力，而不是直接依赖 InputMethodService 实现细节；
 * 2. 为后续状态机收口和 Compose 画布接管预留稳定边界；
 * 3. 保持当前行为不变，仅收紧依赖方向。
 */
interface TypeinkImeHost {
    interface EditCommandCallback {
        fun onEditCommand(command: String)

        fun onError(message: String)
    }

    interface EditResultCallback {
        fun onEditResult(newText: String)

        fun onError(message: String)
    }

    fun requestHideIme()

    fun resolveCurrentSnapshot(): SessionInputSnapshot

    fun commitText(text: CharSequence, newCursorPosition: Int = 1): Boolean

    fun deleteText(beforeLength: Int = 1, afterLength: Int = 0): Boolean

    fun performEditorAction(actionId: Int): Boolean

    fun performEnterAction(): Boolean

    fun updatePreviewText(
        snapshot: SessionInputSnapshot,
        previousPreviewText: String,
        newPreviewText: String,
    ): Boolean

    fun commitPreviewText(
        snapshot: SessionInputSnapshot,
        previewText: String,
    ): Boolean

    fun discardPreviewText(
        snapshot: SessionInputSnapshot,
        previewText: String,
    ): Boolean

    fun startManagedVoiceSession(
        styleMode: TypeinkStyleMode,
        snapshot: SessionInputSnapshot,
    )

    fun clearManagedVoiceSession()

    fun startVoiceInput(
        styleMode: TypeinkStyleMode,
        snapshot: SessionInputSnapshot,
        listener: DashScopeService.Listener,
    )

    fun startEditCommandInput(listener: EditCommandCallback)

    fun stopVoiceInput()

    fun editByInstruction(
        currentText: String,
        instruction: String,
        listener: EditResultCallback,
    )
}
