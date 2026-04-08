package com.typeink.inputmethod

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * 参考 参考键盘 的做法，把所有 InputConnection 交互收口到一个 helper。
 *
 * 目的：
 * 1. 避免在 IME 视图里直接裸调 InputConnection
 * 2. 让写回、草稿、选中文本读取都有统一日志和异常保护
 * 3. 为后续宿主兼容性排查留出单点入口
 */
class InputConnectionHelper(private val tag: String = "TypeinkInputConnection") {
    
    companion object {
        // 重试配置
        const val DEFAULT_RETRY_COUNT = 3
        const val DEFAULT_RETRY_DELAY_MS = 50L
    }
    fun beginBatchEdit(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "beginBatchEdit: InputConnection is null")
            return false
        }
        return try {
            ic.beginBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "beginBatchEdit failed", e)
            false
        }
    }

    fun endBatchEdit(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "endBatchEdit: InputConnection is null")
            return false
        }
        return try {
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "endBatchEdit failed", e)
            false
        }
    }

    fun commitText(ic: InputConnection?, text: CharSequence, newCursorPosition: Int = 1): Boolean {
        return commitTextWithRetry(ic, text, newCursorPosition, DEFAULT_RETRY_COUNT)
    }
    
    /**
     * 带重试机制的 commitText
     * 
     * @param ic InputConnection
     * @param text 要提交的文本
     * @param newCursorPosition 光标位置
     * @param maxRetries 最大重试次数
     * @param retryDelayMs 每次重试间隔（毫秒）
     * @return 是否成功
     */
    fun commitTextWithRetry(
        ic: InputConnection?, 
        text: CharSequence, 
        newCursorPosition: Int = 1,
        maxRetries: Int = DEFAULT_RETRY_COUNT,
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS
    ): Boolean {
        if (ic == null) {
            Log.w(tag, "commitText: InputConnection is null")
            return false
        }
        
        var lastException: Throwable? = null
        
        for (attempt in 0 until maxRetries) {
            try {
                val result = ic.commitText(text, newCursorPosition)
                if (result) {
                    if (attempt > 0) {
                        Log.d(tag, "commitText succeeded after ${attempt + 1} attempts")
                    }
                    return true
                } else {
                    Log.w(tag, "commitText returned false (attempt ${attempt + 1}/$maxRetries)")
                }
            } catch (e: Throwable) {
                lastException = e
                Log.w(tag, "commitText failed (attempt ${attempt + 1}/$maxRetries): text='$text'", e)
                
                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelayMs * (attempt + 1)) // 指数退避
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        
        Log.e(tag, "commitText failed after $maxRetries attempts: text='$text', pos=$newCursorPosition", lastException)
        return false
    }

    fun setComposingText(ic: InputConnection?, text: CharSequence, newCursorPosition: Int = 1): Boolean {
        if (ic == null) {
            Log.w(tag, "setComposingText: InputConnection is null")
            return false
        }
        return try {
            ic.setComposingText(text, newCursorPosition)
            true
        } catch (e: Throwable) {
            Log.e(tag, "setComposingText failed: text='$text', pos=$newCursorPosition", e)
            false
        }
    }

    fun finishComposingText(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "finishComposingText: InputConnection is null")
            return false
        }
        return try {
            ic.finishComposingText()
            true
        } catch (e: Throwable) {
            Log.e(tag, "finishComposingText failed", e)
            false
        }
    }

    fun getTextBeforeCursor(ic: InputConnection?, n: Int, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getTextBeforeCursor: InputConnection is null")
            return null
        }
        return try {
            ic.getTextBeforeCursor(n, flags)
        } catch (e: Throwable) {
            Log.e(tag, "getTextBeforeCursor failed: n=$n, flags=$flags", e)
            null
        }
    }

    fun getTextAfterCursor(ic: InputConnection?, n: Int, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getTextAfterCursor: InputConnection is null")
            return null
        }
        return try {
            ic.getTextAfterCursor(n, flags)
        } catch (e: Throwable) {
            Log.e(tag, "getTextAfterCursor failed: n=$n, flags=$flags", e)
            null
        }
    }

    fun getSelectedText(ic: InputConnection?, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getSelectedText: InputConnection is null")
            return null
        }
        return try {
            ic.getSelectedText(flags)
        } catch (e: Throwable) {
            Log.e(tag, "getSelectedText failed: flags=$flags", e)
            null
        }
    }

    fun setSelection(ic: InputConnection?, start: Int, end: Int): Boolean {
        if (ic == null) {
            Log.w(tag, "setSelection: InputConnection is null")
            return false
        }
        return try {
            ic.setSelection(start, end)
            true
        } catch (e: Throwable) {
            Log.e(tag, "setSelection failed: start=$start, end=$end", e)
            false
        }
    }

    fun clearComposingAndCommitText(
        ic: InputConnection?,
        text: CharSequence,
        newCursorPosition: Int = 1,
    ): Boolean {
        if (ic == null) {
            Log.w(tag, "clearComposingAndCommitText: InputConnection is null")
            return false
        }
        return try {
            ic.beginBatchEdit()
            ic.finishComposingText()
            ic.commitText(text, newCursorPosition)
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "clearComposingAndCommitText failed: text='$text'", e)
            try {
                ic.endBatchEdit()
            } catch (_: Throwable) {
            }
            false
        }
    }

    fun deleteSurroundingText(ic: InputConnection?, beforeLength: Int, afterLength: Int): Boolean {
        if (ic == null) {
            Log.w(tag, "deleteSurroundingText: InputConnection is null")
            return false
        }
        return try {
            ic.deleteSurroundingText(beforeLength, afterLength)
            true
        } catch (e: Throwable) {
            Log.e(tag, "deleteSurroundingText failed: before=$beforeLength after=$afterLength", e)
            false
        }
    }

    fun sendBackspace(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "sendBackspace: InputConnection is null")
            return false
        }
        return try {
            ic.finishComposingText()
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
                return true
            }
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            true
        } catch (e: Throwable) {
            Log.e(tag, "sendBackspace failed, fallback deleteSurroundingText", e)
            deleteSurroundingText(ic, 1, 0)
        }
    }
    
    /**
     * 删除前一个单词
     * 规则：删除到空格或标点或行首
     */
    fun deleteWordBeforeCursor(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "deleteWordBeforeCursor: InputConnection is null")
            return false
        }
        return try {
            ic.finishComposingText()
            
            // 获取当前位置之前的文本
            val textBefore = getTextBeforeCursor(ic, 100)?.toString() ?: return false
            if (textBefore.isEmpty()) return true
            
            // 从末尾查找单词边界（空格、标点、换行符）
            var deleteLength = 0
            var foundNonWhitespace = false
            
            for (i in textBefore.length - 1 downTo 0) {
                val char = textBefore[i]
                
                if (char.isWhitespace() || char in ",.!?;:。，！？；：") {
                    if (foundNonWhitespace) {
                        // 找到单词边界
                        break
                    }
                    // 跳过尾部的空白字符
                    deleteLength++
                } else {
                    foundNonWhitespace = true
                    deleteLength++
                }
            }
            
            if (deleteLength > 0) {
                deleteSurroundingText(ic, deleteLength, 0)
                Log.d(tag, "Deleted $deleteLength chars (word before cursor)")
            }
            true
        } catch (e: Throwable) {
            Log.e(tag, "deleteWordBeforeCursor failed", e)
            false
        }
    }

    fun sendEnter(ic: InputConnection?, editorInfo: EditorInfo? = null): Boolean {
        if (ic == null) {
            Log.w(tag, "sendEnter: InputConnection is null")
            return false
        }
        return try {
            val imeOptions = editorInfo?.imeOptions ?: 0
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val isMultiLine =
                ((editorInfo?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val shouldPerformAction =
                when {
                    isMultiLine && noEnterAction -> false
                    action == EditorInfo.IME_ACTION_SEND ||
                        action == EditorInfo.IME_ACTION_GO ||
                        action == EditorInfo.IME_ACTION_SEARCH ||
                        action == EditorInfo.IME_ACTION_DONE ||
                        action == EditorInfo.IME_ACTION_NEXT ||
                        action == EditorInfo.IME_ACTION_PREVIOUS -> true
                    else -> false
                }

            if (shouldPerformAction) {
                ic.performEditorAction(action)
            } else {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            true
        } catch (e: Throwable) {
            Log.e(tag, "sendEnter failed", e)
            false
        }
    }

    fun moveCursorLeft(ic: InputConnection?): Boolean {
        return sendArrowKey(ic, KeyEvent.KEYCODE_DPAD_LEFT, "moveCursorLeft")
    }

    fun moveCursorRight(ic: InputConnection?): Boolean {
        return sendArrowKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT, "moveCursorRight")
    }

    private fun sendArrowKey(ic: InputConnection?, keyCode: Int, actionName: String): Boolean {
        if (ic == null) {
            Log.w(tag, "$actionName: InputConnection is null")
            return false
        }
        return try {
            ic.finishComposingText()
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Throwable) {
            Log.e(tag, "$actionName failed", e)
            false
        }
    }
    
    // ===== 以下方法参考 参考键盘 实现 =====
    
    fun selectAll(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "selectAll: InputConnection is null")
            return false
        }
        return try {
            val beforeLen = getTextBeforeCursor(ic, 10000)?.length ?: 0
            val afterLen = getTextAfterCursor(ic, 10000)?.length ?: 0
            ic.setSelection(0, beforeLen + afterLen)
            true
        } catch (e: Throwable) {
            Log.e(tag, "selectAll failed", e)
            false
        }
    }
    
    fun moveToStart(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "moveToStart: InputConnection is null")
            return false
        }
        return try {
            ic.setSelection(0, 0)
            true
        } catch (e: Throwable) {
            Log.e(tag, "moveToStart failed", e)
            false
        }
    }
    
    fun moveToEnd(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "moveToEnd: InputConnection is null")
            return false
        }
        return try {
            val before = getTextBeforeCursor(ic, 10000)?.toString() ?: ""
            val after = getTextAfterCursor(ic, 10000)?.toString() ?: ""
            val total = before.length + after.length
            ic.setSelection(total, total)
            true
        } catch (e: Throwable) {
            Log.e(tag, "moveToEnd failed", e)
            false
        }
    }
    
    /**
     * 获取当前光标位置（相对输入框开头）
     */
    fun getCursorPosition(ic: InputConnection?): Int? {
        if (ic == null) return null
        return try {
            getTextBeforeCursor(ic, 10000)?.length
        } catch (e: Throwable) {
            Log.e(tag, "getCursorPosition failed", e)
            null
        }
    }
    
    /**
     * 清空所有文本
     */
    fun clearAllText(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "clearAllText: InputConnection is null")
            return false
        }
        return try {
            ic.beginBatchEdit()
            // 尝试删除所有文本
            val beforeLen = getTextBeforeCursor(ic, Int.MAX_VALUE)?.length ?: 0
            val afterLen = getTextAfterCursor(ic, Int.MAX_VALUE)?.length ?: 0
            if (beforeLen > 0 || afterLen > 0) {
                ic.deleteSurroundingText(beforeLen, afterLen)
            }
            ic.finishComposingText()
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "clearAllText failed", e)
            try {
                ic.endBatchEdit()
            } catch (_: Throwable) {}
            false
        }
    }
}
