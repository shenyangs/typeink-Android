package com.typeink.settings.model

import com.typeink.prototype.BuildConfig

/**
 * 厂商配置数据类
 * 
 * 逻辑：
 * 1. 内置 API Key 保底（永不删除）
 * 2. 用户配置叠加（优先级更高）
 * 3. 配置失败自动回退到内置
 */
data class ProviderConfig(
    val id: String,                    // 厂商唯一ID，如 "dashscope"
    val name: String,                  // 显示名称
    val type: ProviderType,            // ASR / LLM
    val description: String? = null,   // 厂商描述
    val defaultModel: String? = null,  // 默认模型
    val builtInPriority: Int = 0,      // 内置优先级（用于重置时恢复）
    
    // 内置配置（保底）
    val builtInApiKey: String? = null,         // 内置 API Key
    val builtInApiSecret: String? = null,      // 内置 API Secret
    val builtInBaseUrl: String? = null,        // 内置 Base URL
    
    // 用户配置（覆盖）
    val userApiKey: String? = null,            // 用户 API Key（可选）
    val userApiSecret: String? = null,         // 用户 API Secret（可选）
    val userBaseUrl: String? = null,           // 用户 Base URL（可选）
    val userModel: String? = null,             // 用户指定模型
    
    // 状态
    val isEnabled: Boolean = true,             // 是否启用
    val priority: Int = 0,                     // 优先级（用于 fallback）
    val lastError: String? = null,             // 上次错误
    val failCount: Int = 0                     // 连续失败次数
) {
    /**
     * 获取实际使用的 API Key（用户配置优先）
     */
    fun getEffectiveApiKey(): String? {
        return userApiKey?.takeIf { it.isNotBlank() } 
            ?: builtInApiKey?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 获取实际使用的 API Secret
     */
    fun getEffectiveApiSecret(): String? {
        return userApiSecret?.takeIf { it.isNotBlank() }
            ?: builtInApiSecret?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 获取实际使用的 Base URL
     */
    fun getEffectiveBaseUrl(): String? {
        return userBaseUrl?.takeIf { it.isNotBlank() }
            ?: builtInBaseUrl?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 获取实际使用的模型
     */
    fun getEffectiveModel(): String? {
        return userModel?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 是否有用户自定义配置
     */
    fun hasUserConfig(): Boolean {
        return !userApiKey.isNullOrBlank() 
            || !userApiSecret.isNullOrBlank()
            || !userBaseUrl.isNullOrBlank()
            || !userModel.isNullOrBlank()
    }
    
    /**
     * 配置是否有效（有 API Key 可用）
     */
    fun isValid(): Boolean {
        return !getEffectiveApiKey().isNullOrBlank()
    }
    
    /**
     * 记录失败
     */
    fun recordFailure(error: String): ProviderConfig {
        return copy(
            failCount = failCount + 1,
            lastError = error
        )
    }
    
    /**
     * 重置失败计数
     */
    fun resetFailure(): ProviderConfig {
        return copy(failCount = 0, lastError = null)
    }
}

enum class ProviderType {
    ASR,    // 语音识别
    LLM     // 文本改写
}

/**
 * 预置厂商配置（内置保底配置）
 */
object BuiltInProviders {
    
    // 内置 DashScope ASR
    fun dashScopeAsr(): ProviderConfig {
        return ProviderConfig(
            id = "dashscope_asr",
            name = "阿里云 DashScope",
            type = ProviderType.ASR,
            description = "阿里云实时语音识别，中文效果优秀",
            defaultModel = "paraformer-realtime-v2",
            builtInApiKey = BuildConfig.DASHSCOPE_API_KEY,
            builtInBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            builtInPriority = 100,
            priority = 100
        )
    }
    
    // 内置 DashScope LLM
    fun dashScopeLlm(): ProviderConfig {
        return ProviderConfig(
            id = "dashscope_llm",
            name = "阿里云 DashScope",
            type = ProviderType.LLM,
            description = "阿里云 Qwen 大模型，中文润色效果优秀",
            defaultModel = "qwen-turbo",
            builtInApiKey = BuildConfig.DASHSCOPE_API_KEY,
            builtInBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            builtInPriority = 100,
            priority = 100
        )
    }
    
    // 其他厂商（仅占位，无内置 Key）
    fun baiduAsr(): ProviderConfig {
        return ProviderConfig(
            id = "baidu_asr",
            name = "百度语音",
            type = ProviderType.ASR,
            description = "百度语音识别引擎",
            defaultModel = "",
            builtInPriority = 90,
            priority = 90
        )
    }
    
    fun iflytekAsr(): ProviderConfig {
        return ProviderConfig(
            id = "iflytek_asr",
            name = "讯飞听见",
            type = ProviderType.ASR,
            description = "科大讯飞语音识别，国内老牌厂商",
            defaultModel = "",
            builtInPriority = 90,
            priority = 90
        )
    }
    
    fun openAiLlm(): ProviderConfig {
        return ProviderConfig(
            id = "openai_llm",
            name = "OpenAI",
            type = ProviderType.LLM,
            description = "OpenAI GPT 系列模型",
            defaultModel = "gpt-3.5-turbo",
            builtInPriority = 90,
            priority = 90
        )
    }
    
    fun volcengineAsr(): ProviderConfig {
        return ProviderConfig(
            id = "volcengine_asr",
            name = "火山引擎",
            type = ProviderType.ASR,
            description = "字节跳动火山引擎语音识别",
            defaultModel = "",
            builtInPriority = 90,
            priority = 90
        )
    }
    
    fun qwenLlm(): ProviderConfig {
        return ProviderConfig(
            id = "qwen_llm",
            name = "通义千问",
            type = ProviderType.LLM,
            description = "阿里云通义千问大模型",
            defaultModel = "qwen-turbo",
            builtInPriority = 90,
            priority = 90
        )
    }
    
    /**
     * 获取所有预置 ASR 厂商
     */
    fun getAllAsrProviders(): List<ProviderConfig> {
        return listOf(
            dashScopeAsr(),
            baiduAsr(),
            iflytekAsr(),
            volcengineAsr()
        )
    }
    
    /**
     * 获取所有预置 LLM 厂商
     */
    fun getAllLlmProviders(): List<ProviderConfig> {
        return listOf(
            dashScopeLlm(),
            openAiLlm(),
            qwenLlm()
        )
    }
}
