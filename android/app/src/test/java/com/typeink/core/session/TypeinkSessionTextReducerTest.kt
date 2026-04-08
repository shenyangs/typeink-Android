package com.typeink.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TypeinkSessionTextReducerTest {
    @Test
    fun begin_voice_input_preserves_previous_stable_text() {
        val state = TypeinkSessionTextReducer.beginVoiceInput("已有文本")

        assertEquals("已有文本", state.stableText)
        assertEquals("", state.draftText)
        assertEquals("", state.streamingText)
    }

    @Test
    fun append_streaming_token_builds_incremental_result() {
        val afterBegin = TypeinkSessionTextReducer.beginRewriting(
            TypeinkSessionTextState(stableText = "底稿")
        )
        val afterOne = TypeinkSessionTextReducer.appendStreamingToken(afterBegin, "第")
        val afterTwo = TypeinkSessionTextReducer.appendStreamingToken(afterOne, "一句")

        assertEquals("底稿第一句", afterTwo.streamingText)
        assertEquals("底稿第一句", afterTwo.displayFinalText)
    }

    @Test
    fun apply_final_result_clears_draft_and_promotes_stable_text() {
        val state = TypeinkSessionTextState(
            stableText = "旧文本",
            draftText = "草稿",
            streamingText = "流式结果"
        )

        val updated = TypeinkSessionTextReducer.applyFinalResult(state, "最终结果")

        assertEquals("最终结果", updated.stableText)
        assertEquals("", updated.draftText)
        assertEquals("最终结果", updated.streamingText)
    }
}
