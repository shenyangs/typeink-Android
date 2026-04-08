package com.typeink.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeinkEditSessionReducerTest {
    @Test
    fun `entering edit mode creates primed active state`() {
        val state = TypeinkEditSessionReducer.primed()

        assertEquals(TypeinkEditPhase.PRIMED, state.phase)
        assertTrue(state.isActive)
    }

    @Test
    fun `rewriting stores last instruction and marks processing`() {
        val state =
            TypeinkEditSessionReducer.rewriting(
                previous = TypeinkEditSessionReducer.listening(TypeinkEditSessionReducer.primed()),
                instruction = "更正式一些",
            )

        assertEquals(TypeinkEditPhase.REWRITING, state.phase)
        assertEquals("更正式一些", state.lastInstruction)
        assertTrue(state.isProcessing)
    }

    @Test
    fun `failed keeps edit session active for retry`() {
        val state =
            TypeinkEditSessionReducer.failed(
                previous = TypeinkEditSessionReducer.primed(),
                message = "没听清",
            )

        assertEquals(TypeinkEditPhase.FAILED, state.phase)
        assertEquals("没听清", state.errorMessage)
        assertTrue(state.canRetry)
    }
}
