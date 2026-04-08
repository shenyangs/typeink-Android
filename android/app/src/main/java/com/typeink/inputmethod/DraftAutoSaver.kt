package com.typeink.inputmethod

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 草稿自动保存管理器
 * 
 * 功能：
 * 1. 定时自动保存 ASR 和 LLM 文本到 SharedPreferences
 * 2. 应用启动时恢复草稿
 * 3. 成功提交后清除草稿
 */
class DraftAutoSaver(context: Context) {
    
    companion object {
        private const val TAG = "DraftAutoSaver"
        private const val PREFS_NAME = "typeink_draft"
        private const val KEY_ASR_TEXT = "asr_text"
        private const val KEY_LLM_TEXT = "llm_text"
        private const val KEY_STYLE_MODE = "style_mode"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_IS_REWRITTEN = "is_rewritten"
        
        // 自动保存间隔（毫秒）
        const val AUTO_SAVE_INTERVAL_MS = 3000L
        // 草稿过期时间（毫秒）- 30分钟
        const val DRAFT_EXPIRY_MS = 30 * 60 * 1000L
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 草稿数据类
     */
    data class Draft(
        val asrText: String,
        val llmText: String,
        val styleMode: String,
        val timestamp: Long,
        val isRewritten: Boolean
    ) {
        /**
         * 检查草稿是否过期
         */
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - timestamp > DRAFT_EXPIRY_MS
        }
        
        /**
         * 获取可恢复的文本
         */
        fun getRecoverableText(): String {
            return llmText.takeIf { it.isNotBlank() } ?: asrText
        }
        
        /**
         * 是否有可恢复的内容
         */
        fun hasContent(): Boolean {
            return asrText.isNotBlank() || llmText.isNotBlank()
        }
    }
    
    /**
     * 保存草稿
     */
    fun saveDraft(asrText: String, llmText: String, styleMode: String = "NATURAL", isRewritten: Boolean = false) {
        try {
            prefs.edit().apply {
                putString(KEY_ASR_TEXT, asrText)
                putString(KEY_LLM_TEXT, llmText)
                putString(KEY_STYLE_MODE, styleMode)
                putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                putBoolean(KEY_IS_REWRITTEN, isRewritten)
                apply()
            }
            Log.d(TAG, "Draft saved: asr=${asrText.take(20)}, llm=${llmText.take(20)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save draft", e)
        }
    }
    
    /**
     * 加载草稿
     */
    fun loadDraft(): Draft? {
        return try {
            val asrText = prefs.getString(KEY_ASR_TEXT, "") ?: ""
            val llmText = prefs.getString(KEY_LLM_TEXT, "") ?: ""
            val styleMode = prefs.getString(KEY_STYLE_MODE, "NATURAL") ?: "NATURAL"
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)
            val isRewritten = prefs.getBoolean(KEY_IS_REWRITTEN, false)
            
            if (asrText.isBlank() && llmText.isBlank()) {
                Log.d(TAG, "No draft found")
                return null
            }
            
            Draft(asrText, llmText, styleMode, timestamp, isRewritten).also {
                Log.d(TAG, "Draft loaded: timestamp=$timestamp, expired=${it.isExpired()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load draft", e)
            null
        }
    }
    
    /**
     * 清除草稿
     */
    fun clearDraft() {
        try {
            prefs.edit().clear().apply()
            Log.d(TAG, "Draft cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear draft", e)
        }
    }
    
    /**
     * 检查是否有可恢复的草稿
     */
    fun hasRecoverableDraft(): Boolean {
        val draft = loadDraft()
        return draft != null && draft.hasContent() && !draft.isExpired()
    }
    
    /**
     * 获取草稿保存时间描述
     */
    fun getDraftSaveTimeDescription(): String {
        val draft = loadDraft() ?: return ""
        val elapsedMs = System.currentTimeMillis() - draft.timestamp
        
        return when {
            elapsedMs < 60_000 -> "刚刚"
            elapsedMs < 60 * 60_000 -> "${elapsedMs / 60_000}分钟前"
            else -> "${elapsedMs / (60 * 60_000)}小时前"
        }
    }
}
