package com.typeink.core.input

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
        val sharedPhase = when (phase) {
            TypeinkUiPhase.IDLE -> TypeinkInputPhase.IDLE
            TypeinkUiPhase.CONNECTING -> TypeinkInputPhase.PREPARING
            TypeinkUiPhase.LISTENING -> {
                if (isRecording) TypeinkInputPhase.LISTENING else TypeinkInputPhase.PROCESSING
            }
            TypeinkUiPhase.REWRITING -> TypeinkInputPhase.REWRITING
            TypeinkUiPhase.APPLIED -> TypeinkInputPhase.APPLIED
            TypeinkUiPhase.FAILED -> TypeinkInputPhase.FAILED
        }

        return TypeinkInputState(
            phase = sharedPhase,
            hint = hint,
            isRecording = isRecording,
            partialText = partialText,
            finalText = finalText,
                isStreaming = sharedPhase == TypeinkInputPhase.REWRITING,
                hasPendingCommit = sharedPhase == TypeinkInputPhase.PREVIEW_READY,
                errorMessage = errorMessage,
            )
    }

    fun fromKeyboardState(state: KeyboardState): TypeinkInputState {
        return when (state) {
            is KeyboardState.Idle -> TypeinkInputState(
                phase = TypeinkInputPhase.IDLE,
            )

            is KeyboardState.Preparing -> TypeinkInputState(
                phase = TypeinkInputPhase.PREPARING,
                hint = state.message,
            )

            is KeyboardState.Listening -> TypeinkInputState(
                phase = TypeinkInputPhase.LISTENING,
                hint = state.hint,
                isRecording = true,
            )

            is KeyboardState.Processing -> TypeinkInputState(
                phase = TypeinkInputPhase.PROCESSING,
                hint = state.hint,
                isRecording = true,
                partialText = state.partialText,
            )

            is KeyboardState.Rewriting -> TypeinkInputState(
                phase = TypeinkInputPhase.REWRITING,
                hint = state.hint,
                partialText = state.partialText,
                finalText = state.finalText,
                isStreaming = state.isStreaming,
            )

            is KeyboardState.Succeeded -> TypeinkInputState(
                phase = TypeinkInputPhase.PREVIEW_READY,
                hint = state.hint,
                partialText = state.originalText,
                finalText = state.finalText,
                isStreaming = false,
                hasPendingCommit = true,
            )

            is KeyboardState.Failed -> TypeinkInputState(
                phase = TypeinkInputPhase.FAILED,
                hint = state.hint,
                errorMessage = state.error,
            )

            is KeyboardState.Cancelled -> TypeinkInputState(
                phase = TypeinkInputPhase.CANCELLED,
            )
        }
    }
}
