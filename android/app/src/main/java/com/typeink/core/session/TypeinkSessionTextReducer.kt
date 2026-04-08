package com.typeink.core.session

object TypeinkSessionTextReducer {
    fun idle(): TypeinkSessionTextState = TypeinkSessionTextState()

    fun beginVoiceInput(previousText: String = ""): TypeinkSessionTextState {
        return TypeinkSessionTextState(
            stableText = previousText,
            draftText = "",
            streamingText = "",
        )
    }

    fun beginEdit(baseText: String): TypeinkSessionTextState {
        return TypeinkSessionTextState(
            stableText = baseText,
            draftText = "",
            streamingText = baseText,
        )
    }

    fun updateDraft(state: TypeinkSessionTextState, text: String): TypeinkSessionTextState {
        return state.copy(draftText = text)
    }

    fun beginRewriting(state: TypeinkSessionTextState): TypeinkSessionTextState {
        return state.copy(
            draftText = "",
            streamingText = state.displayFinalText,
        )
    }

    fun appendStreamingToken(state: TypeinkSessionTextState, token: String): TypeinkSessionTextState {
        return state.copy(streamingText = state.streamingText + token)
    }

    fun applyFinalResult(state: TypeinkSessionTextState, text: String): TypeinkSessionTextState {
        return state.copy(
            stableText = text,
            draftText = "",
            streamingText = text,
        )
    }

    fun keepStableText(state: TypeinkSessionTextState): TypeinkSessionTextState {
        return state.copy(
            draftText = "",
            streamingText = state.displayFinalText,
        )
    }
}
