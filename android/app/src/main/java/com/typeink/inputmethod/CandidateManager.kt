package com.typeink.inputmethod

import android.util.Log

/**
 * 候选列表管理器
 * 
 * 功能：
 * 1. 管理多个候选文本（原文 + 多个改写版本）
 * 2. 支持候选切换
 * 3. 支持候选预览
 */
class CandidateManager {
    
    companion object {
        private const val TAG = "CandidateManager"
        const val MAX_CANDIDATES = 3  // 最多3个候选
    }
    
    /**
     * 候选项数据类
     */
    data class Candidate(
        val id: Int,
        val text: String,
        val type: CandidateType,
        val isSelected: Boolean = false
    ) {
        val displayLabel: String
            get() = when (type) {
                CandidateType.ORIGINAL -> "原"
                CandidateType.REWRITE_1 -> "改1"
                CandidateType.REWRITE_2 -> "改2"
                CandidateType.REWRITE_3 -> "改3"
            }
    }
    
    enum class CandidateType {
        ORIGINAL,      // 原始 ASR 文本
        REWRITE_1,     // 改写版本1
        REWRITE_2,     // 改写版本2
        REWRITE_3      // 改写版本3
    }
    
    // 候选列表
    private val candidates = mutableListOf<Candidate>()
    
    // 当前选中的候选索引
    private var selectedIndex: Int = 0
    
    // 原始文本
    private var originalText: String = ""
    
    /**
     * 是否有候选
     */
    val hasCandidates: Boolean get() = candidates.isNotEmpty()
    
    /**
     * 获取当前选中的候选
     */
    fun getSelectedCandidate(): Candidate? {
        return candidates.getOrNull(selectedIndex)
    }
    
    /**
     * 获取所有候选
     */
    fun getAllCandidates(): List<Candidate> = candidates.toList()
    
    /**
     * 获取候选数量
     */
    fun getCandidateCount(): Int = candidates.size
    
    /**
     * 设置原始文本（ASR结果）
     */
    fun setOriginalText(text: String) {
        originalText = text
        candidates.clear()
        selectedIndex = 0
        
        if (text.isNotBlank()) {
            candidates.add(Candidate(0, text, CandidateType.ORIGINAL, true))
        }
        
        Log.d(TAG, "Original text set: ${text.take(30)}")
    }
    
    /**
     * 添加改写候选
     */
    fun addRewriteCandidate(text: String) {
        if (text.isBlank() || text == originalText) return
        
        // 检查是否已存在
        if (candidates.any { it.text == text }) return
        
        val type = when (candidates.size) {
            1 -> CandidateType.REWRITE_1
            2 -> CandidateType.REWRITE_2
            3 -> CandidateType.REWRITE_3
            else -> return
        }
        
        if (candidates.size < MAX_CANDIDATES) {
            candidates.add(Candidate(candidates.size, text, type))
            Log.d(TAG, "Rewrite candidate added: ${text.take(30)}, total=${candidates.size}")
        }
    }
    
    /**
     * 选择候选
     */
    fun selectCandidate(index: Int): Boolean {
        if (index < 0 || index >= candidates.size) return false
        
        selectedIndex = index
        
        // 更新选中状态
        candidates.replaceAll { candidate ->
            candidate.copy(isSelected = candidate.id == index)
        }
        
        Log.d(TAG, "Candidate selected: index=$index, text=${candidates[index].text.take(30)}")
        return true
    }
    
    /**
     * 选择下一个候选
     */
    fun selectNext(): Boolean {
        if (candidates.isEmpty()) return false
        val nextIndex = (selectedIndex + 1) % candidates.size
        return selectCandidate(nextIndex)
    }
    
    /**
     * 选择上一个候选
     */
    fun selectPrevious(): Boolean {
        if (candidates.isEmpty()) return false
        val prevIndex = if (selectedIndex > 0) selectedIndex - 1 else candidates.size - 1
        return selectCandidate(prevIndex)
    }
    
    /**
     * 清除所有候选
     */
    fun clear() {
        candidates.clear()
        selectedIndex = 0
        originalText = ""
        Log.d(TAG, "All candidates cleared")
    }
    
    /**
     * 获取用于显示的候选信息
     */
    fun getDisplayInfo(): List<Pair<String, Boolean>> {
        return candidates.map { it.displayLabel to it.isSelected }
    }
}
