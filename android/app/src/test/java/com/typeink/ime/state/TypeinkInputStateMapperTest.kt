package com.typeink.core.input

import com.typeink.inputmethod.KeyboardState
import com.typeink.prototype.TypeinkUiPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeinkInputStateMapperTest {
    @Test
    fun app_connecting_maps_to_preparing() {
        val state = TypeinkInputStateMapper.fromAppState(
            phase = TypeinkUiPhase.CONNECTING,
            hint = "正在打开麦克风",
            isRecording = false,
        )

        assertEquals(TypeinkInputPhase.PREPARING, state.phase)
        assertTrue(state.isProcessing)
    }

    @Test
    fun app_listening_without_recording_maps_to_processing() {
        val state = TypeinkInputStateMapper.fromAppState(
            phase = TypeinkUiPhase.LISTENING,
            hint = "等待最终结果",
            isRecording = false,
        )

        assertEquals(TypeinkInputPhase.PROCESSING, state.phase)
        assertTrue(state.isProcessing)
    }

    @Test
    fun keyboard_rewriting_maps_to_shared_rewriting() {
        val state = TypeinkInputStateMapper.fromKeyboardState(
            KeyboardState.Rewriting(
                partialText = "草稿",
                finalText = "润色中",
                isStreaming = true,
                hint = "正在润色...",
            ),
        )

        assertEquals(TypeinkInputPhase.REWRITING, state.phase)
        assertEquals("草稿", state.partialText)
        assertEquals("润色中", state.finalText)
        assertTrue(state.isStreaming)
    }

    @Test
    fun keyboard_failed_maps_to_error_state() {
        val state = TypeinkInputStateMapper.fromKeyboardState(
            KeyboardState.Failed(
                error = "网络异常",
                retryable = true,
                hint = "请重试",
            ),
        )

        assertEquals(TypeinkInputPhase.FAILED, state.phase)
        assertTrue(state.isError)
        assertFalse(state.isRecording)
    }
}
