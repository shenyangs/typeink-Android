package com.typeink.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeinkRewriteSupportTest {
    @Test
    fun `选中文本替换规则会直接返回完整文本`() {
        val snapshot =
            SessionInputSnapshot(
                originalText = "今天先发给客户新海岸这个版本",
                selectionStart = 7,
                selectionEnd = 10,
                rewriteMode = true,
                source = SessionRewriteSource.SELECTED_TEXT,
            )

        val result = TypeinkRewriteSupport.tryApplyDirectEdit("把新海岸改成心海岸", snapshot)

        assertEquals("今天先发给客户心海岸这个版本", result)
    }

    @Test
    fun `修最近一句删除指令会删除选中范围`() {
        val snapshot =
            SessionInputSnapshot(
                originalText = "第一句。第二句。",
                selectionStart = 4,
                selectionEnd = 8,
                rewriteMode = true,
                source = SessionRewriteSource.RECENT_COMMIT,
            )

        val result = TypeinkRewriteSupport.tryApplyDirectEdit("上一句删掉", snapshot)

        assertEquals("第一句。", result)
    }

    @Test
    fun `选中文本提示词会要求输出完整文本`() {
        val snapshot =
            SessionInputSnapshot(
                originalText = "这是前文选中文本这是后文",
                selectionStart = 4,
                selectionEnd = 8,
                rewriteMode = true,
                source = SessionRewriteSource.RECENT_COMMIT,
            )

        val messages = TypeinkRewriteSupport.buildMessages("把它改短一点", snapshot, TypeinkStyleMode.NATURAL)
        val userPrompt = messages[1].content

        assertTrue(userPrompt.contains("最近一次语音输入内容"))
        assertTrue(userPrompt.contains("输出最终完整文本"))
        assertTrue(userPrompt.contains("选中文字前文"))
    }

    @Test
    fun `SSE 解析支持字符串 content`() {
        val payload =
            """
            {
              "choices": [
                {
                  "delta": {
                    "content": "你好"
                  }
                }
              ]
            }
            """.trimIndent()

        assertEquals("你好", TypeinkRewriteSupport.extractDeltaText(payload))
    }

    @Test
    fun `SSE 解析支持数组 content`() {
        val rawPayload =
            """
            {
              "choices": [
                {
                  "delta": {
                    "content": [
                      { "text": "第一段" },
                      { "text": "第二段" }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()

        assertEquals("第一段第二段", TypeinkRewriteSupport.extractDeltaText(rawPayload))
    }
}
