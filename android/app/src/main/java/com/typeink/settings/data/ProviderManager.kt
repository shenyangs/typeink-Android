package com.typeink.settings.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.typeink.settings.model.BuiltInProviders
import com.typeink.settings.model.ProviderConfig
import com.typeink.settings.model.ProviderType
import org.json.JSONObject

/**
 * 厂商管理器
 * 
 * 核心逻辑：
 * 1. 内置 API Key 保底（用户无法删除）
 * 2. 用户配置叠加（持久化存储）
 * 3. 失败自动回退（用户配置失败 → 内置配置）
 * 4. 多厂商轮询（当前失败 → 尝试下一个）
 */
class ProviderManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "ProviderManager"
        private const val PREFS_NAME = "provider_configs"
        private const val KEY_ASR_PROVIDERS = "asr_providers"
        private const val KEY_LLM_PROVIDERS = "llm_providers"
        
        @Volatile
        private var instance: ProviderManager? = null
        
        fun getInstance(context: Context): ProviderManager {
            return instance ?: synchronized(this) {
                instance ?: ProviderManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 内存中的配置缓存
    private var asrProviders: MutableList<ProviderConfig> = mutableListOf()
    private var llmProviders: MutableList<ProviderConfig> = mutableListOf()
    
    // 当前使用的厂商索引
    private var currentAsrIndex: Int = 0
    private var currentLlmIndex: Int = 0
    
    init {
        loadProviders()
    }
    
    /**
     * 加载厂商配置
     * 逻辑：预置配置 + 用户覆盖
     */
    private fun loadProviders() {
        Log.d(TAG, "Loading providers...")
        
        // 加载 ASR 配置
        val asrJson = prefs.getString(KEY_ASR_PROVIDERS, null)
        val userAsrConfigs = parseUserConfigs(asrJson)
        asrProviders = mergeConfigs(
            BuiltInProviders.getAllAsrProviders(),
            userAsrConfigs
        ).toMutableList()
        
        // 加载 LLM 配置
        val llmJson = prefs.getString(KEY_LLM_PROVIDERS, null)
        val userLlmConfigs = parseUserConfigs(llmJson)
        llmProviders = mergeConfigs(
            BuiltInProviders.getAllLlmProviders(),
            userLlmConfigs
        ).toMutableList()
        
        // 按优先级排序
        asrProviders.sortByDescending { it.priority }
        llmProviders.sortByDescending { it.priority }
        
        Log.d(TAG, "Loaded ${asrProviders.size} ASR providers, ${llmProviders.size} LLM providers")
        
        // 确保至少有一个可用的
        if (!getCurrentAsrProvider().isValid()) {
            Log.w(TAG, "No valid ASR provider found!")
        }
        if (!getCurrentLlmProvider().isValid()) {
            Log.w(TAG, "No valid LLM provider found!")
        }
    }
    
    /**
     * 解析用户配置
     */
    private fun parseUserConfigs(json: String?): Map<String, ProviderConfig> {
        if (json.isNullOrBlank()) return emptyMap()
        
        return try {
            val result = mutableMapOf<String, ProviderConfig>()
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.getJSONObject(key)
                result[key] = ProviderConfig(
                    id = key,
                    name = obj.optString("name", key),
                    type = if (obj.optString("type") == "LLM") ProviderType.LLM else ProviderType.ASR,
                    userApiKey = obj.optString("userApiKey").takeIf { it.isNotBlank() },
                    userApiSecret = obj.optString("userApiSecret").takeIf { it.isNotBlank() },
                    userBaseUrl = obj.optString("userBaseUrl").takeIf { it.isNotBlank() },
                    userModel = obj.optString("userModel").takeIf { it.isNotBlank() },
                    isEnabled = obj.optBoolean("isEnabled", true),
                    priority = obj.optInt("priority", 0)
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse user configs", e)
            emptyMap()
        }
    }
    
    /**
     * 合并预置配置和用户配置
     */
    private fun mergeConfigs(
        builtIn: List<ProviderConfig>,
        userConfigs: Map<String, ProviderConfig>
    ): List<ProviderConfig> {
        return builtIn.map { builtInConfig ->
            val userConfig = userConfigs[builtInConfig.id]
            if (userConfig != null) {
                // 用户有配置，合并（用户配置覆盖内置）
                builtInConfig.copy(
                    userApiKey = userConfig.userApiKey,
                    userApiSecret = userConfig.userApiSecret,
                    userBaseUrl = userConfig.userBaseUrl,
                    userModel = userConfig.userModel,
                    isEnabled = userConfig.isEnabled,
                    priority = userConfig.priority
                )
            } else {
                // 用户无配置，使用内置
                builtInConfig
            }
        }
    }
    
    /**
     * 获取当前 ASR 厂商
     */
    fun getCurrentAsrProvider(): ProviderConfig {
        if (asrProviders.isEmpty()) {
            return BuiltInProviders.dashScopeAsr()  // 保底
        }
        // 确保索引有效
        if (currentAsrIndex >= asrProviders.size) {
            currentAsrIndex = 0
        }
        return asrProviders[currentAsrIndex]
    }
    
    /**
     * 获取当前 LLM 厂商
     */
    fun getCurrentLlmProvider(): ProviderConfig {
        if (llmProviders.isEmpty()) {
            return BuiltInProviders.dashScopeLlm()  // 保底
        }
        if (currentLlmIndex >= llmProviders.size) {
            currentLlmIndex = 0
        }
        return llmProviders[currentLlmIndex]
    }
    
    /**
     * 获取所有 ASR 厂商（用于设置页面显示）
     */
    fun getAllAsrProviders(): List<ProviderConfig> = asrProviders.toList()
    
    /**
     * 获取所有 LLM 厂商（用于设置页面显示）
     */
    fun getAllLlmProviders(): List<ProviderConfig> = llmProviders.toList()
    
    /**
     * 更新用户配置
     */
    fun updateUserConfig(config: ProviderConfig) {
        Log.d(TAG, "Updating user config: ${config.id}")
        
        // 更新内存中的配置
        val list = if (config.type == ProviderType.ASR) asrProviders else llmProviders
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            list[index] = list[index].copy(
                userApiKey = config.userApiKey,
                userApiSecret = config.userApiSecret,
                userBaseUrl = config.userBaseUrl,
                userModel = config.userModel,
                isEnabled = config.isEnabled,
                priority = config.priority
            )
        }
        
        // 持久化
        saveProviders()
    }
    
    /**
     * 保存配置到 SharedPreferences
     */
    private fun saveProviders() {
        try {
            // 只保存用户配置部分
            val asrJson = JSONObject()
            asrProviders.filter { it.hasUserConfig() }.forEach { config ->
                asrJson.put(config.id, configToJson(config))
            }
            prefs.edit().putString(KEY_ASR_PROVIDERS, asrJson.toString()).apply()
            
            val llmJson = JSONObject()
            llmProviders.filter { it.hasUserConfig() }.forEach { config ->
                llmJson.put(config.id, configToJson(config))
            }
            prefs.edit().putString(KEY_LLM_PROVIDERS, llmJson.toString()).apply()
            
            Log.d(TAG, "Providers saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save providers", e)
        }
    }
    
    private fun configToJson(config: ProviderConfig): JSONObject {
        return JSONObject().apply {
            put("name", config.name)
            put("type", config.type.name)
            put("userApiKey", config.userApiKey ?: "")
            put("userApiSecret", config.userApiSecret ?: "")
            put("userBaseUrl", config.userBaseUrl ?: "")
            put("userModel", config.userModel ?: "")
            put("isEnabled", config.isEnabled)
            put("priority", config.priority)
        }
    }
    
    /**
     * 报告厂商失败，自动切换到下一个
     * @return 是否成功切换
     */
    fun reportAsrFailure(error: String): Boolean {
        val current = getCurrentAsrProvider()
        Log.w(TAG, "ASR provider ${current.id} failed: $error")
        
        // 记录失败
        val index = asrProviders.indexOfFirst { it.id == current.id }
        if (index >= 0) {
            asrProviders[index] = current.recordFailure(error)
        }
        
        // 尝试切换到下一个
        return switchToNextAsrProvider()
    }
    
    fun reportLlmFailure(error: String): Boolean {
        val current = getCurrentLlmProvider()
        Log.w(TAG, "LLM provider ${current.id} failed: $error")
        
        val index = llmProviders.indexOfFirst { it.id == current.id }
        if (index >= 0) {
            llmProviders[index] = current.recordFailure(error)
        }
        
        return switchToNextLlmProvider()
    }
    
    /**
     * 切换到下一个 ASR 厂商
     */
    private fun switchToNextAsrProvider(): Boolean {
        val startIndex = currentAsrIndex
        do {
            currentAsrIndex = (currentAsrIndex + 1) % asrProviders.size
            val next = getCurrentAsrProvider()
            if (next.isValid() && next.failCount < 3) {
                Log.i(TAG, "Switched to ASR provider: ${next.id}")
                return true
            }
        } while (currentAsrIndex != startIndex)
        
        Log.e(TAG, "No available ASR provider")
        return false
    }
    
    private fun switchToNextLlmProvider(): Boolean {
        val startIndex = currentLlmIndex
        do {
            currentLlmIndex = (currentLlmIndex + 1) % llmProviders.size
            val next = getCurrentLlmProvider()
            if (next.isValid() && next.failCount < 3) {
                Log.i(TAG, "Switched to LLM provider: ${next.id}")
                return true
            }
        } while (currentLlmIndex != startIndex)
        
        Log.e(TAG, "No available LLM provider")
        return false
    }
    
    /**
     * 重置厂商失败计数（用户手动切换时调用）
     */
    fun resetProviderStatus(providerId: String) {
        asrProviders.find { it.id == providerId }?.let { config ->
            val index = asrProviders.indexOf(config)
            asrProviders[index] = config.resetFailure()
        }
        llmProviders.find { it.id == providerId }?.let { config ->
            val index = llmProviders.indexOf(config)
            llmProviders[index] = config.resetFailure()
        }
    }
    
    /**
     * 获取配置状态摘要（用于调试）
     */
    fun getStatusSummary(): String {
        val asr = getCurrentAsrProvider()
        val llm = getCurrentLlmProvider()
        return """
            当前云端配置：
            ASR：${asr.name}（${if (asr.hasUserConfig()) "用户配置" else "内置"}），可用=${asr.isValid()}
            LLM：${llm.name}（${if (llm.hasUserConfig()) "用户配置" else "内置"}），可用=${llm.isValid()}
        """.trimIndent()
    }
}
