package com.typeink.syncclipboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * SyncClipboard 客户端
 * 
 * 基于 SyncClipboard 项目协议实现
 * https://github.com/Jeric-X/SyncClipboard
 * 
 * 功能：
 * 1. 同步剪贴板内容到 SyncClipboard 服务器
 * 2. 从服务器获取剪贴板内容
 * 3. 支持文本、图片、文件类型
 * 4. 实时同步（轮询）
 */
class SyncClipboardClient(
    context: Context,
    private val config: SyncClipboardConfig
) {
    companion object {
        private const val TAG = "SyncClipboardClient"
        private const val SYNC_PATH = "/SyncClipboard.json"
        private const val FILE_PATH = "/file/"
        
        // 轮询间隔（毫秒）
        private const val POLL_INTERVAL_MS = 2000L
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncJob: Job? = null
    
    // 最后同步的 hash，用于避免重复同步
    private var lastSyncedHash: String = ""
    
    // 监听回调
    var listener: SyncClipboardListener? = null
    
    /**
     * 配置数据类
     */
    data class SyncClipboardConfig(
        val serverUrl: String,
        val username: String = "",
        val password: String = "",
        val enabled: Boolean = false
    ) {
        val baseUrl: String
            get() = serverUrl.trimEnd('/')
    }
    
    /**
     * 剪贴板数据类
     */
    data class ClipboardData(
        val type: ClipboardType,
        val text: String,
        val fileName: String = "",
        val hash: String = "",
        val size: Long = 0
    ) {
        enum class ClipboardType {
            TEXT, IMAGE, FILE, UNKNOWN
        }
        
        fun isText(): Boolean = type == ClipboardType.TEXT
        fun isImage(): Boolean = type == ClipboardType.IMAGE
        fun isFile(): Boolean = type == ClipboardType.FILE
    }
    
    /**
     * 同步监听接口
     */
    interface SyncClipboardListener {
        fun onClipboardReceived(data: ClipboardData)
        fun onClipboardUploaded()
        fun onError(message: String)
    }
    
    /**
     * 开始自动同步
     */
    fun startAutoSync() {
        if (!config.enabled) {
            Log.w(TAG, "SyncClipboard is disabled")
            return
        }
        
        stopAutoSync()
        
        syncJob = mainScope.launch {
            while (isActive) {
                try {
                    fetchClipboard()
                } catch (e: Exception) {
                    Log.e(TAG, "Auto sync error", e)
                    listener?.onError("同步失败: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        
        Log.d(TAG, "Auto sync started")
    }
    
    /**
     * 停止自动同步
     */
    fun stopAutoSync() {
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "Auto sync stopped")
    }
    
    /**
     * 从服务器获取剪贴板内容
     */
    suspend fun fetchClipboard(): ClipboardData? = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl}$SYNC_PATH"
        
        val request = Request.Builder()
            .url(url)
            .apply {
                if (config.username.isNotBlank() && config.password.isNotBlank()) {
                    addHeader("Authorization", Credentials.basic(config.username, config.password))
                }
            }
            .get()
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                
                val jsonString = response.body?.string() ?: return@withContext null
                parseClipboardData(jsonString)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Fetch clipboard failed", e)
            withContext(Dispatchers.Main) {
                listener?.onError("获取剪贴板失败: ${e.message}")
            }
            null
        }
    }
    
    /**
     * 上传剪贴板内容到服务器
     */
    suspend fun uploadClipboard(text: String): Boolean = withContext(Dispatchers.IO) {
        // 计算 hash
        val hash = calculateHash(text)
        
        // 如果与上次同步的相同，跳过
        if (hash == lastSyncedHash) {
            Log.d(TAG, "Clipboard unchanged, skipping upload")
            return@withContext true
        }
        
        val url = "${config.baseUrl}$SYNC_PATH"
        
        val jsonObject = JSONObject().apply {
            put("Type", "Text")
            put("Text", text)
            put("Clipboard", text)
            put("File", "")
            put("hash", hash)
            put("size", text.length)
        }
        
        val body = jsonObject.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .apply {
                if (config.username.isNotBlank() && config.password.isNotBlank()) {
                    addHeader("Authorization", Credentials.basic(config.username, config.password))
                }
            }
            .put(body)
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    lastSyncedHash = hash
                    withContext(Dispatchers.Main) {
                        listener?.onClipboardUploaded()
                    }
                    Log.d(TAG, "Clipboard uploaded successfully")
                    true
                } else {
                    throw IOException("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Upload clipboard failed", e)
            withContext(Dispatchers.Main) {
                listener?.onError("上传剪贴板失败: ${e.message}")
            }
            false
        }
    }
    
    /**
     * 解析剪贴板数据
     */
    private fun parseClipboardData(jsonString: String): ClipboardData? {
        return try {
            val json = JSONObject(jsonString)
            
            val typeStr = json.optString("Type", "Text")
            val type = when (typeStr) {
                "Text" -> ClipboardData.ClipboardType.TEXT
                "Image" -> ClipboardData.ClipboardType.IMAGE
                "File" -> ClipboardData.ClipboardType.FILE
                else -> ClipboardData.ClipboardType.UNKNOWN
            }
            
            val text = json.optString("Text", json.optString("Clipboard", ""))
            val fileName = json.optString("File", "")
            val hash = json.optString("hash", "")
            val size = json.optLong("size", 0)
            
            ClipboardData(type, text, fileName, hash, size).also {
                // 检查 hash 是否变化
                if (hash.isNotBlank() && hash != lastSyncedHash) {
                    lastSyncedHash = hash
                    listener?.onClipboardReceived(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse clipboard data failed", e)
            null
        }
    }
    
    /**
     * 计算文本的 MD5 hash
     */
    private fun calculateHash(text: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(text.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Calculate hash failed", e)
            ""
        }
    }
    
    /**
     * 释放资源
     */
    fun dispose() {
        stopAutoSync()
        mainScope.cancel()
    }
}
