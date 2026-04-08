package com.typeink.inputmethod

import android.view.inputmethod.InputConnection
import android.util.Log

/**
 * 插入点管理器
 * 
 * 功能：
 * 1. 追踪当前光标位置
 * 2. 支持在光标位置插入文本（而非始终追加到末尾）
 * 3. 处理composition文本的定位
 */
class InsertionPointManager {
    
    companion object {
        private const val TAG = "InsertionPointManager"
    }
    
    /**
     * 插入模式
     */
    enum class InsertMode {
        AT_CURSOR,      // 在光标位置插入
        REPLACE_SELECTION,  // 替换选中文本
        APPEND          // 追加到末尾（默认）
    }
    
    // 当前插入模式
    private var currentMode: InsertMode = InsertMode.AT_CURSOR
    
    // 记录插入前的光标位置
    private var insertionPosition: Int = -1
    
    // 是否有选中的文本
    private var hasSelection: Boolean = false
    
    // 选中文本的起止位置
    private var selectionStart: Int = 0
    private var selectionEnd: Int = 0
    
    /**
     * 准备插入 - 记录当前光标位置和选区
     */
    fun prepareInsertion(inputHelper: InputConnectionHelper, ic: InputConnection?) {
        if (ic == null) {
            Log.w(TAG, "prepareInsertion: InputConnection is null")
            currentMode = InsertMode.APPEND
            return
        }
        
        try {
            // 检查是否有选中的文本
            val selectedText = inputHelper.getSelectedText(ic)
            hasSelection = !selectedText.isNullOrEmpty()
            
            if (hasSelection) {
                currentMode = InsertMode.REPLACE_SELECTION
                // 获取选区位置
                val beforeText = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                selectionStart = beforeText.length
                selectionEnd = selectionStart + (selectedText?.length ?: 0)
                Log.d(TAG, "Selection mode: $selectionStart-$selectionEnd")
            } else {
                // 获取光标位置
                insertionPosition = inputHelper.getCursorPosition(ic) ?: 0
                currentMode = InsertMode.AT_CURSOR
                Log.d(TAG, "Cursor mode: position=$insertionPosition")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing insertion", e)
            currentMode = InsertMode.APPEND
        }
    }
    
    /**
     * 执行文本插入
     * 
     * @return 是否成功
     */
    fun performInsertion(
        inputHelper: InputConnectionHelper,
        ic: InputConnection?,
        text: String
    ): Boolean {
        if (ic == null) return false
        
        return when (currentMode) {
            InsertMode.REPLACE_SELECTION -> {
                // 替换选中的文本
                inputHelper.commitText(ic, text, 1)
            }
            
            InsertMode.AT_CURSOR -> {
                // 在光标位置插入
                if (insertionPosition >= 0) {
                    // 使用 setComposingRegion + setComposingText 在指定位置插入
                    // 但更简单的方法是直接 commitText，它会替换当前的 composition
                    inputHelper.commitText(ic, text, 1)
                } else {
                    inputHelper.commitText(ic, text, 1)
                }
            }
            
            InsertMode.APPEND -> {
                // 追加到末尾
                inputHelper.commitText(ic, text, 1)
            }
        }
    }
    
    /**
     * 在光标位置设置 composing text
     * 
     * @param inputHelper InputConnectionHelper
     * @param ic InputConnection
     * @param text 要设置的文本
     * @return 是否成功
     */
    fun setComposingTextAtCursor(
        inputHelper: InputConnectionHelper,
        ic: InputConnection?,
        text: String
    ): Boolean {
        if (ic == null) return false
        
        return try {
            // 首先获取当前光标位置
            val cursorPos = inputHelper.getCursorPosition(ic) ?: 0
            
            // 设置 composing text，InputConnection 会自动处理位置
            // 注意：setComposingText 会在当前光标位置创建或替换 composition
            inputHelper.setComposingText(ic, text, 1)
            
            Log.d(TAG, "Set composing text at cursor position: $cursorPos")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting composing text", e)
            false
        }
    }
    
    /**
     * 重置状态
     */
    fun reset() {
        currentMode = InsertMode.AT_CURSOR
        insertionPosition = -1
        hasSelection = false
        selectionStart = 0
        selectionEnd = 0
    }
    
    /**
     * 获取当前插入模式
     */
    fun getCurrentMode(): InsertMode = currentMode
}
