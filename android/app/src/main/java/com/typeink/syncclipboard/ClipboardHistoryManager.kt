package com.typeink.syncclipboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 剪贴板历史管理器
 * 
 * 功能：
 * 1. 保存剪贴板历史记录
 * 2. 管理历史列表（最大100条）
 * 3. 支持从历史中恢复内容
 */
class ClipboardHistoryManager(context: Context) {
    
    companion object {
        private const val TAG = "ClipboardHistory"
        private const val PREFS_NAME = "clipboard_history"
        private const val KEY_HISTORY = "history"
        const val MAX_HISTORY_SIZE = 100

        @Volatile
        private var instance: ClipboardHistoryManager? = null

        fun getInstance(context: Context): ClipboardHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: ClipboardHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 历史列表（线程安全）
    private val historyList = CopyOnWriteArrayList<HistoryItem>()

    init {
        loadHistory()
    }
    
    /**
     * 历史项数据类
     */
    data class HistoryItem(
        val id: Long = System.currentTimeMillis(),
        val type: ItemType,
        val text: String,
        val fileName: String = "",
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class ItemType {
            TEXT, IMAGE, FILE
        }
        
        val displayText: String
            get() = when (type) {
                ItemType.TEXT -> text.take(100)
                ItemType.IMAGE -> "[图片] ${fileName.ifBlank { "未命名" }}"
                ItemType.FILE -> "[文件] ${fileName.ifBlank { "未命名" }}"
            }
        
        val timeDescription: String
            get() {
                val elapsedMs = System.currentTimeMillis() - timestamp
                return when {
                    elapsedMs < 60_000 -> "刚刚"
                    elapsedMs < 60 * 60_000 -> "${elapsedMs / 60_000}分钟前"
                    elapsedMs < 24 * 60 * 60_000 -> "${elapsedMs / (60 * 60_000)}小时前"
                    else -> "${elapsedMs / (24 * 60 * 60_000)}天前"
                }
            }
    }
    
    /**
     * 加载历史记录
     */
    fun loadHistory() {
        try {
            historyList.clear()
            
            val jsonString = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
            val jsonArray = JSONArray(jsonString)
            
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val item = HistoryItem(
                    id = json.optLong("id", System.currentTimeMillis()),
                    type = HistoryItem.ItemType.valueOf(json.optString("type", "TEXT")),
                    text = json.optString("text", ""),
                    fileName = json.optString("fileName", ""),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                historyList.add(item)
            }
            
            Log.d(TAG, "Loaded ${historyList.size} history items")
        } catch (e: Exception) {
            Log.e(TAG, "Load history failed", e)
        }
    }
    
    /**
     * 保存历史记录
     */
    private fun saveHistory() {
        try {
            val jsonArray = JSONArray()
            
            historyList.forEach { item ->
                val json = JSONObject().apply {
                    put("id", item.id)
                    put("type", item.type.name)
                    put("text", item.text)
                    put("fileName", item.fileName)
                    put("timestamp", item.timestamp)
                }
                jsonArray.put(json)
            }
            
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
            Log.d(TAG, "Saved ${historyList.size} history items")
        } catch (e: Exception) {
            Log.e(TAG, "Save history failed", e)
        }
    }
    
    /**
     * 添加历史项
     */
    fun addHistory(type: HistoryItem.ItemType, text: String, fileName: String = "") {
        if (text.isBlank()) return

        val normalizedText = text.trim()

        // 避免重复添加相同内容
        if (historyList.isNotEmpty()) {
            val last = historyList.first()
            if (last.type == type && last.text == normalizedText) {
                return
            }
        }
        
        val item = HistoryItem(
            type = type,
            text = normalizedText,
            fileName = fileName
        )
        
        historyList.add(0, item)
        
        // 限制历史数量
        while (historyList.size > MAX_HISTORY_SIZE) {
            historyList.removeAt(historyList.size - 1)
        }
        
        saveHistory()
        Log.d(TAG, "Added history item: ${item.displayText.take(30)}")
    }
    
    /**
     * 获取所有历史
     */
    fun getAllHistory(): List<HistoryItem> = historyList.toList()
    
    /**
     * 获取历史数量
     */
    fun getHistoryCount(): Int = historyList.size
    
    /**
     * 根据 ID 获取历史项
     */
    fun getHistoryById(id: Long): HistoryItem? {
        return historyList.find { it.id == id }
    }
    
    /**
     * 删除历史项
     */
    fun removeHistory(id: Long) {
        historyList.removeAll { it.id == id }
        saveHistory()
    }
    
    /**
     * 清空历史
     */
    fun clearHistory() {
        historyList.clear()
        saveHistory()
        Log.d(TAG, "History cleared")
    }
    
    /**
     * 搜索历史
     */
    fun searchHistory(query: String): List<HistoryItem> {
        return historyList.filter { 
            it.text.contains(query, ignoreCase = true) ||
            it.fileName.contains(query, ignoreCase = true)
        }
    }
}
