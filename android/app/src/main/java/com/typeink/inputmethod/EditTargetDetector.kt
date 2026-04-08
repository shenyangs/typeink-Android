package com.typeink.inputmethod

import android.view.inputmethod.InputConnection

/**
 * 智能编辑目标检测器 - 对标 参考键盘
 * 
 * 自动检测当前编辑目标：
 * 1. 选中的文本（优先级最高）
 * 2. 上次识别的内容
 * 3. 输入框中的全文
 */
class EditTargetDetector {
    
    /**
     * 编辑目标类型
     */
    sealed class EditTarget {
        /**
         * 选中的文本
         */
        data class SelectedText(
            val text: String,
            val selectionStart: Int,
            val selectionEnd: Int,
            val beforeText: String,
            val afterText: String
        ) : EditTarget()
        
        /**
         * 上次识别的内容
         */
        data class LastRecognition(
            val text: String
        ) : EditTarget()
        
        /**
         * 全文
         */
        data class FullText(
            val text: String
        ) : EditTarget()
        
        /**
         * 无内容
         */
        data object Empty : EditTarget()
    }
    
    /**
     * 改写模式
     */
    enum class RewriteMode {
        /** 改写选中文本 */
        SELECTED_TEXT,
        /** 续写模式（在现有文本后追加） */
        CONTINUE_WRITE,
        /** 全文替换 */
        FULL_REPLACE
    }
    
    companion object {
        private const val TAG = "EditTargetDetector"
    }
    
    /**
     * 检测编辑目标
     * 
     * @param inputConnection 输入连接
     * @param lastRecognizedText 上次识别的文本
     * @return 检测到的编辑目标
     */
    fun detectTarget(
        inputConnection: InputConnection?,
        lastRecognizedText: String = ""
    ): EditTarget {
        if (inputConnection == null) {
            return if (lastRecognizedText.isNotBlank()) {
                EditTarget.LastRecognition(lastRecognizedText)
            } else {
                EditTarget.Empty
            }
        }
        
        // 1. 检查是否有选中的文本（优先级最高）
        val selectedText = inputConnection.getSelectedText(0)?.toString()
        if (!selectedText.isNullOrBlank()) {
            // 获取选区前后的文本
            val beforeCursor = inputConnection.getTextBeforeCursor(1000, 0)?.toString() ?: ""
            val afterCursor = inputConnection.getTextAfterCursor(1000, 0)?.toString() ?: ""
            
            // 计算选区在全文中的位置
            val selectionStart = beforeCursor.length
            val selectionEnd = selectionStart + selectedText.length
            
            return EditTarget.SelectedText(
                text = selectedText,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                beforeText = beforeCursor,
                afterText = afterCursor
            )
        }
        
        // 2. 检查是否有上次识别的内容
        if (lastRecognizedText.isNotBlank()) {
            return EditTarget.LastRecognition(lastRecognizedText)
        }
        
        // 3. 获取全文
        val fullText = getFullText(inputConnection)
        if (fullText.isNotBlank()) {
            return EditTarget.FullText(fullText)
        }
        
        return EditTarget.Empty
    }
    
    /**
     * 确定改写模式
     */
    fun determineRewriteMode(target: EditTarget): RewriteMode {
        return when (target) {
            is EditTarget.SelectedText -> RewriteMode.SELECTED_TEXT
            is EditTarget.LastRecognition -> RewriteMode.CONTINUE_WRITE
            is EditTarget.FullText -> RewriteMode.FULL_REPLACE
            is EditTarget.Empty -> RewriteMode.CONTINUE_WRITE
        }
    }
    
    /**
     * 获取编辑目标描述
     */
    fun getTargetDescription(target: EditTarget): String {
        return when (target) {
            is EditTarget.SelectedText -> "选中文本: ${target.text.take(20)}${if (target.text.length > 20) "..." else ""}"
            is EditTarget.LastRecognition -> "上次识别: ${target.text.take(20)}${if (target.text.length > 20) "..." else ""}"
            is EditTarget.FullText -> "全文: ${target.text.take(20)}${if (target.text.length > 20) "..." else ""}"
            is EditTarget.Empty -> "无内容"
        }
    }
    
    /**
     * 获取改写模式描述
     */
    fun getModeDescription(mode: RewriteMode): String {
        return when (mode) {
            RewriteMode.SELECTED_TEXT -> "改写选中文本"
            RewriteMode.CONTINUE_WRITE -> "续写模式"
            RewriteMode.FULL_REPLACE -> "全文替换"
        }
    }
    
    /**
     * 获取全文
     */
    private fun getFullText(inputConnection: InputConnection): String {
        val before = inputConnection.getTextBeforeCursor(10000, 0)?.toString() ?: ""
        val after = inputConnection.getTextAfterCursor(10000, 0)?.toString() ?: ""
        return before + after
    }
}
