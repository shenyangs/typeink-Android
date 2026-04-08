package com.typeink.ime.state

import com.typeink.inputmethod.KeyboardState
import com.typeink.prototype.TypeinkUiPhase

object TypeinkInputStateMapper {
    fun fromAppState(
        phase: TypeinkUiPhase,
        hint: String,
        isRecording: Boolean,
        partialText: String = "",
        finalText: String = "",
        errorMessage: String = "",
    ): TypeinkInputState {
        return com.typeink.core.input.TypeinkInputStateMapper.fromAppState(
            phase = phase,
            hint = hint,
            isRecording = isRecording,
            partialText = partialText,
            finalText = finalText,
            errorMessage = errorMessage,
        )
    }

    fun fromKeyboardState(state: KeyboardState): TypeinkInputState {
        return com.typeink.core.input.TypeinkInputStateMapper.fromKeyboardState(state)
    }
}
