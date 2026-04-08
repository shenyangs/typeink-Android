package com.typeink.core.session

object TypeinkEditSessionReducer {
    fun inactive(): TypeinkEditSessionState = TypeinkEditSessionState()

    fun primed(): TypeinkEditSessionState =
        TypeinkEditSessionState(
            phase = TypeinkEditPhase.PRIMED,
        )

    fun listening(previous: TypeinkEditSessionState): TypeinkEditSessionState =
        previous.copy(
            phase = TypeinkEditPhase.LISTENING,
            errorMessage = "",
        )

    fun rewriting(
        previous: TypeinkEditSessionState,
        instruction: String,
    ): TypeinkEditSessionState =
        previous.copy(
            phase = TypeinkEditPhase.REWRITING,
            lastInstruction = instruction,
            errorMessage = "",
        )

    fun ready(previous: TypeinkEditSessionState): TypeinkEditSessionState =
        previous.copy(
            phase = TypeinkEditPhase.READY,
            errorMessage = "",
        )

    fun failed(
        previous: TypeinkEditSessionState,
        message: String,
    ): TypeinkEditSessionState =
        previous.copy(
            phase = TypeinkEditPhase.FAILED,
            errorMessage = message,
        )
}
