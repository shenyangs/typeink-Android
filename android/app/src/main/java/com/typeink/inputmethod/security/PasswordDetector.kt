package com.typeink.inputmethod.security

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 密码框和敏感输入检测器
 * 
 * 参考：Android InputType 文档
 * 安全要求：密码框必须禁用云 ASR，防止敏感信息泄露
 */
object PasswordDetector {
    
    /**
     * 检测是否为密码输入框
     */
    fun isPasswordField(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        
        val inputType = editorInfo.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        
        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }
    
    /**
     * 检测是否为数字密码输入框（如 PIN 码）
     */
    fun isNumberPasswordField(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        
        val inputType = editorInfo.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass != InputType.TYPE_CLASS_NUMBER) {
            return false
        }
        
        return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    
    /**
     * 检测是否为敏感输入框（包括密码、信用卡等）
     */
    fun isSensitiveField(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        
        return isPasswordField(editorInfo) || 
               isNumberPasswordField(editorInfo) ||
               isCreditCardField(editorInfo)
    }
    
    /**
     * 检测是否为信用卡号输入框
     */
    private fun isCreditCardField(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        
        val hint = editorInfo.hintText?.toString()?.lowercase() ?: ""
        return hint.contains("card") || 
               hint.contains("credit") || 
               hint.contains("cvv") ||
               hint.contains("卡号") ||
               hint.contains("信用卡")
    }
    
    /**
     * 获取字段安全级别
     */
    fun getSecurityLevel(editorInfo: EditorInfo?): SecurityLevel {
        return when {
            isPasswordField(editorInfo) || isNumberPasswordField(editorInfo) -> 
                SecurityLevel.HIGH
            isSensitiveField(editorInfo) -> 
                SecurityLevel.MEDIUM
            else -> 
                SecurityLevel.NORMAL
        }
    }
    
    /**
     * 检测是否应该禁用语音输入
     */
    fun shouldDisableVoiceInput(editorInfo: EditorInfo?): Boolean {
        return getSecurityLevel(editorInfo) != SecurityLevel.NORMAL
    }
    
    /**
     * 获取禁用语音时的提示文本
     */
    fun getDisabledReason(editorInfo: EditorInfo?): String {
        return when (getSecurityLevel(editorInfo)) {
            SecurityLevel.HIGH -> "密码框不支持语音输入"
            SecurityLevel.MEDIUM -> "敏感输入已禁用语音"
            SecurityLevel.NORMAL -> ""
        }
    }
    
    enum class SecurityLevel {
        /** 正常输入框，可使用所有功能 */
        NORMAL,
        /** 中等敏感，建议使用本地识别或手动输入 */
        MEDIUM,
        /** 高敏感（密码等），必须禁用云 ASR */
        HIGH
    }
}
