package com.typeink.core.session

enum class TypeinkEditPhase {
    INACTIVE,
    PRIMED,
    LISTENING,
    REWRITING,
    READY,
    FAILED,
}

data class TypeinkEditSessionState(
    val phase: TypeinkEditPhase = TypeinkEditPhase.INACTIVE,
    val lastInstruction: String = "",
    val errorMessage: String = "",
) {
    val isActive: Boolean
        get() = phase != TypeinkEditPhase.INACTIVE

    val isListening: Boolean
        get() = phase == TypeinkEditPhase.LISTENING

    val isProcessing: Boolean
        get() = phase == TypeinkEditPhase.REWRITING

    val canRetry: Boolean
        get() = phase == TypeinkEditPhase.FAILED || phase == TypeinkEditPhase.READY || phase == TypeinkEditPhase.PRIMED
}
