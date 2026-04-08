package com.typeink.prototype

import android.content.Context
import android.util.Log
import com.typeink.settings.data.ProviderManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DashScopeService(
    context: Context? = null,
) {
    
    companion object {
        // WebSocket 重连配置
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val BASE_RECONNECT_DELAY_MS = 1000L
        const val MAX_RECONNECT_DELAY_MS = 5000L
        private const val LLM_WATCHDOG_TIMEOUT_MS = 15000L
    }
    
    interface Listener {
        fun onAsrPartial(text: String)

        fun onAsrFinal(text: String)

        fun onLlmDelta(token: String)

        fun onLlmCompleted(finalText: String)

        fun onError(message: String)
    }
    
    interface EditListener {
        fun onEditResult(newText: String)
        fun onError(message: String)
    }
    
    // 编辑命令识别监听器 - 只听写，不润色
    interface EditCommandListener {
        fun onEditCommand(command: String)
        fun onError(message: String)
    }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private data class AsrRuntimeConfig(
        val apiKey: String,
        val model: String,
        val url: String,
    )

    private data class LlmRuntimeConfig(
        val apiKey: String,
        val model: String,
        val url: String,
    )

    private val appContext = context?.applicationContext
    private val providerManager: ProviderManager? by lazy {
        appContext?.let { ProviderManager.getInstance(it) }
    }

    private var asrWebSocket: WebSocket? = null
    private var listener: Listener? = null
    private var currentStyleMode = TypeinkStyleMode.NATURAL
    private var currentTaskId: String? = null
    private var taskStarted = false
    private var currentSnapshot = SessionInputSnapshot("", 0, 0, false)
    private var activeAsrConfig: AsrRuntimeConfig? = null

    @Volatile
    private var finishRequested = false

    @Volatile
    private var rewriteStarted = false

    @Volatile
    private var latestPartialText = ""

    @Volatile
    private var latestFinalText = ""
    @Volatile
    private var sessionGeneration = 0L
    private var activeRewriteCall: Call? = null
    private var activeEditCall: Call? = null
    
    // 预连接相关
    private var preconnectedSocket: WebSocket? = null
    private var isPreconnecting = false
    
    // 重连相关
    private var reconnectAttempts = 0
    private var isReconnecting = false
    private var pendingListener: Listener? = null
    private var pendingSnapshot: SessionInputSnapshot? = null

    fun setStyleMode(mode: TypeinkStyleMode) {
        currentStyleMode = mode
    }

    fun isConfigured(): Boolean {
        return resolveAsrRuntimeConfig().apiKey.isNotBlank() && resolveLlmRuntimeConfig().apiKey.isNotBlank()
    }

    @Synchronized
    private fun nextGeneration(): Long {
        sessionGeneration += 1
        return sessionGeneration
    }

    private fun isGenerationActive(generation: Long): Boolean = generation == sessionGeneration

    private fun cancelPendingNetworkCalls() {
        activeRewriteCall?.cancel()
        activeRewriteCall = null
        activeEditCall?.cancel()
        activeEditCall = null
    }
    
    /**
     * 预连接 WebSocket - 在录音开始前建立连接，减少延迟
     */
    fun preconnect() {
        val asrConfig = resolveAsrRuntimeConfig()
        if (asrConfig.apiKey.isBlank() || preconnectedSocket != null || isPreconnecting) {
            return
        }
        
        Log.d("DashScopeService", "Preconnecting WebSocket...")
        isPreconnecting = true
        
        val request = Request.Builder()
            .url(asrConfig.url)
            .addHeader("Authorization", "Bearer ${asrConfig.apiKey}")
            .build()
        
        preconnectedSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("DashScopeService", "Preconnection successful")
                isPreconnecting = false
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("DashScopeService", "Preconnection failed: ${t.message}")
                preconnectedSocket = null
                isPreconnecting = false
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                preconnectedSocket = null
                isPreconnecting = false
            }
        })
    }
    
    /**
     * 取消预连接
     */
    fun cancelPreconnect() {
        preconnectedSocket?.close(1000, "Cancel preconnect")
        preconnectedSocket = null
        isPreconnecting = false
    }

    fun startAsr(
        listener: Listener,
        snapshot: SessionInputSnapshot,
    ) {
        val asrConfig = resolveAsrRuntimeConfig()
        if (asrConfig.apiKey.isBlank()) {
            listener.onError("DashScope Key 还没配置，暂时无法启动语音识别。")
            return
        }

        // 保存用于重连的参数
        pendingListener = listener
        pendingSnapshot = snapshot
        
        // 重置重连计数
        reconnectAttempts = 0
        isReconnecting = false

        doStartAsr(listener, snapshot, nextGeneration(), asrConfig)
    }
    
    private fun doStartAsr(
        listener: Listener,
        snapshot: SessionInputSnapshot,
        generation: Long,
        asrConfig: AsrRuntimeConfig,
    ) {
        this.listener = listener
        currentSnapshot = snapshot
        activeAsrConfig = asrConfig
        taskStarted = false
        finishRequested = false
        rewriteStarted = false
        latestPartialText = ""
        latestFinalText = ""
        currentTaskId = UUID.randomUUID().toString().replace("-", "").take(32)

        // 取消任何现有的预连接（因为我们要创建新的）
        cancelPreconnect()
        
        val request = Request.Builder()
            .url(asrConfig.url)
            .addHeader("Authorization", "Bearer ${asrConfig.apiKey}")
            .build()

        cancelPendingNetworkCalls()
        asrWebSocket = client.newWebSocket(request, createWebSocketListener(listener, generation))
    }

    private fun createWebSocketListener(listener: Listener, generation: Long): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isGenerationActive(generation)) {
                    webSocket.close(1000, "stale_generation")
                    return
                }
                Log.d("DashScopeService", "WebSocket onOpen")
                // 连接成功，重置重连计数
                reconnectAttempts = 0
                isReconnecting = false
                sendRunTaskCommand(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isGenerationActive(generation)) return
                Log.d("DashScopeService", "WebSocket onMessage: $text")
                handleAsrMessage(text, generation)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isGenerationActive(generation)) return
                Log.e("DashScopeService", "ASR 连接失败: ${t.message}", t)
                closeAsrSocket()
                
                // 尝试重连（如果还在录音中且未达到最大重试次数）
                if (shouldReconnect()) {
                    attemptReconnect()
                } else {
                    val emitted = emitFinalAndRewriteIfNeeded(generation = generation)
                    if (!emitted) {
                        listener.onError("ASR 连接失败：${t.message ?: "未知错误"}")
                    }
                }
            }
        }
    }
    
    private fun shouldReconnect(): Boolean {
        // 只有在未完成任务且未达到最大重试次数时才重连
        return !finishRequested && 
               !rewriteStarted && 
               reconnectAttempts < MAX_RECONNECT_ATTEMPTS &&
               pendingListener != null &&
               pendingSnapshot != null
    }
    
    private fun attemptReconnect() {
        if (isReconnecting) return
        
        isReconnecting = true
        reconnectAttempts++
        
        // 计算退避延迟（指数退避）
        val delayMs = (BASE_RECONNECT_DELAY_MS * (1 shl (reconnectAttempts - 1)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        
        Log.w("DashScopeService", "WebSocket 将在 ${delayMs}ms 后重连 (尝试 $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
        
        Thread {
            try {
                    Thread.sleep(delayMs)
                    if (!finishRequested && pendingListener != null && pendingSnapshot != null) {
                        Log.d("DashScopeService", "开始重连...")
                        isReconnecting = false
                        doStartAsr(
                            pendingListener!!,
                            pendingSnapshot!!,
                            nextGeneration(),
                            resolveAsrRuntimeConfig(),
                        )
                    }
                } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                isReconnecting = false
            }
        }.start()
    }

    fun sendAudioChunk(audioData: ByteArray) {
        if (!taskStarted || finishRequested) {
            return
        }
        asrWebSocket?.send(audioData.toByteString())
    }

    fun stopAsr() {
        val generation = sessionGeneration
        finishRequested = true
        taskStarted = false
        // 标记不再需要重连
        pendingListener = null
        pendingSnapshot = null

        val webSocket = asrWebSocket
        if (webSocket == null) {
            Log.w("DashScopeService", "stopAsr called without active WebSocket, forcing local fallback")
            val forcedText = latestFinalText.ifBlank { latestPartialText }.trim()
            if (forcedText.isNotBlank()) {
                rewriteStarted = true
                currentSnapshot?.let { snapshot ->
                    if (isGenerationActive(generation)) {
                        listener?.onAsrFinal(forcedText)
                        rewriteText(forcedText, snapshot, generation)
                    }
                } ?: run {
                    if (isGenerationActive(generation)) {
                        listener?.onError("语音上下文已丢失，请重试")
                    }
                }
            } else if (isGenerationActive(generation)) {
                listener?.onError("语音连接未建立，请重试")
            }
            return
        }

        Log.d("DashScopeService", "stopAsr called, latestPartialText=$latestPartialText, latestFinalText=$latestFinalText")

        currentTaskId?.let { taskId ->
            val finishTaskCmd =
                JSONObject().apply {
                    put(
                        "header",
                        JSONObject().apply {
                            put("action", "finish-task")
                            put("task_id", taskId)
                            put("streaming", "duplex")
                        },
                    )
                    put(
                        "payload",
                        JSONObject().apply {
                            put("input", JSONObject())
                        },
                    )
                }
            webSocket.send(finishTaskCmd.toString())
        }

        // 强制超时兜底 - 确保即使没收到 task-finished 也能触发改写
        Thread {
            Thread.sleep(2000)
            if (finishRequested && !rewriteStarted) {
                Log.w("DashScopeService", "超时兜底：强制触发改写")
                // 使用 partial 或 final 文本强制触发
                val forcedText = latestFinalText.ifBlank { latestPartialText }.trim()
                if (forcedText.isNotBlank()) {
                    rewriteStarted = true
                    if (isGenerationActive(generation)) {
                        listener?.onAsrFinal(forcedText)
                        rewriteText(forcedText, currentSnapshot, generation)
                    }
                } else {
                    if (isGenerationActive(generation)) {
                        listener?.onError("未能识别到语音，请重试")
                    }
                }
                closeAsrSocket()
            }
        }.start()
    }

    fun cancelActiveSession() {
        finishRequested = false
        taskStarted = false
        rewriteStarted = false
        latestPartialText = ""
        latestFinalText = ""
        pendingListener = null
        pendingSnapshot = null
        nextGeneration()
        cancelPendingNetworkCalls()
        closeAsrSocket()
    }

    private fun handleAsrMessage(text: String, generation: Long) {
        if (!isGenerationActive(generation)) return
        try {
            val response = JSONObject(text)
            val header = response.optJSONObject("header")
            val event = header?.optString("event", "").orEmpty()

            when (event) {
                "task-started" -> {
                    Log.d("DashScopeService", "Task started")
                    taskStarted = true
                }

                "result-generated" -> {
                    val payload = response.optJSONObject("payload")
                    val output = payload?.optJSONObject("output")
                    val sentence = output?.optJSONObject("sentence")
                    val resultText = sentence?.optString("text", "").orEmpty().trim()
                    val sentenceEnd = sentence?.optBoolean("sentence_end", false) ?: false

                    if (resultText.isBlank()) {
                        return
                    }

                    Log.d("DashScopeService", "result-generated: text=$resultText, sentenceEnd=$sentenceEnd")
                    
                    latestPartialText = resultText
                    // 无论是否 sentence_end，都更新 final 文本（作为备选）
                    latestFinalText = resultText
                    
                    if (sentenceEnd) {
                        emitFinalAndRewriteIfNeeded(resultText, generation)
                    } else {
                        listener?.onAsrPartial(resultText)
                    }
                }

                "task-finished" -> {
                    emitFinalAndRewriteIfNeeded(generation = generation)
                    closeAsrSocket()
                }

                "task-failed" -> {
                    val errorMessage = header?.optString("error_message", "任务失败").orEmpty()
                    val emitted = emitFinalAndRewriteIfNeeded(generation = generation)
                    closeAsrSocket()
                    if (!emitted) {
                        listener?.onError("ASR 任务失败：$errorMessage")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DashScopeService", "解析 ASR 响应失败: $text", e)
            listener?.onError("语音结果解析失败，请再试一次。")
        }
    }

    private fun sendRunTaskCommand(webSocket: WebSocket) {
        val taskId = currentTaskId ?: return

        val runTaskCmd =
            JSONObject().apply {
                put(
                    "header",
                    JSONObject().apply {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    },
                )
                put(
                    "payload",
                    JSONObject().apply {
                        put("task_group", "audio")
                        put("task", "asr")
                        put("function", "recognition")
                        put("model", activeAsrConfig?.model ?: resolveAsrRuntimeConfig().model)
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("format", "pcm")
                                put("sample_rate", 16000)
                                put("disfluency_removal_enabled", false)
                            },
                        )
                        put("input", JSONObject())
                    },
                )
            }

        webSocket.send(runTaskCmd.toString())
    }

    private fun emitFinalAndRewriteIfNeeded(candidate: String? = null, generation: Long): Boolean {
        if (!isGenerationActive(generation)) {
            return false
        }
        if (rewriteStarted) {
            return true
        }

        val sourceText =
            (candidate ?: latestFinalText.ifBlank { latestPartialText }).trim()
        if (sourceText.isBlank()) {
            return false
        }

        rewriteStarted = true
        latestFinalText = sourceText
        listener?.onAsrFinal(sourceText)
        rewriteText(sourceText, currentSnapshot, generation)
        return true
    }

    private fun rewriteText(
        text: String,
        snapshot: SessionInputSnapshot,
        generation: Long,
    ) {
        if (!isGenerationActive(generation)) return
        Log.d("DashScopeService", "rewriteText: text=$text, styleMode=$currentStyleMode")
        
        val directResult = TypeinkRewriteSupport.tryApplyDirectEdit(text, snapshot)
        if (directResult != null) {
            Log.d("DashScopeService", "Direct edit applied: $directResult")
            if (isGenerationActive(generation)) {
                listener?.onLlmDelta(directResult)
                listener?.onLlmCompleted(directResult)
            }
            return
        }

        val fallbackText = TypeinkRewriteSupport.buildFallbackText(text, snapshot)
        val messages = TypeinkRewriteSupport.buildMessages(text, snapshot, currentStyleMode)
        val llmConfig = resolveLlmRuntimeConfig()
        Log.d("DashScopeService", "Calling LLM API with ${messages.size} messages")
        
        val payload =
            JSONObject().apply {
                put("model", llmConfig.model)
                put(
                    "messages",
                    org.json.JSONArray().apply {
                        messages.forEach { message ->
                            put(JSONObject().put("role", message.role).put("content", message.content))
                        }
                    },
                )
                put("stream", true)
                put("stream_options", JSONObject().put("include_usage", true))
                put("temperature", 0.1)
                put("max_tokens", 192)
            }

        val request =
            Request.Builder()
                .url(llmConfig.url)
                .addHeader("Authorization", "Bearer ${llmConfig.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

        val streamBuffer = StringBuilder()
        val rewriteCompleted = AtomicBoolean(false)

        fun completeRewrite(reason: String, candidate: String) {
            if (!rewriteCompleted.compareAndSet(false, true)) {
                return
            }
            activeRewriteCall = null
            val finalText = candidate.trim().ifBlank { fallbackText }
            Log.d("DashScopeService", "LLM completed($reason): $finalText")
            if (isGenerationActive(generation)) {
                listener?.onLlmCompleted(finalText)
            }
        }

        Thread {
            try {
                Thread.sleep(LLM_WATCHDOG_TIMEOUT_MS)
                if (rewriteCompleted.compareAndSet(false, true)) {
                    Log.w("DashScopeService", "LLM watchdog timeout, fallback to current best result")
                    activeRewriteCall?.cancel()
                    activeRewriteCall = null
                    val bestEffortText = streamBuffer.toString().trim().ifBlank { fallbackText }
                    if (isGenerationActive(generation)) {
                        listener?.onLlmCompleted(bestEffortText)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()

        activeRewriteCall = client.newCall(request).also { call ->
            call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (!isGenerationActive(generation)) return
                    Log.e("DashScopeService", "重写请求失败: ${e.message}", e)
                    completeRewrite("failure", streamBuffer.toString().ifBlank { fallbackText })
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (!isGenerationActive(generation)) {
                        response.close()
                        return
                    }
                    Log.d("DashScopeService", "重写请求响应: code=${response.code}")
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Log.e("DashScopeService", "重写请求失败: code=${response.code}, body=$errorBody")
                        completeRewrite("http_${response.code}", streamBuffer.toString().ifBlank { fallbackText })
                        response.close()
                        return
                    }

                    val source = response.body?.source()
                    var lastReceivedTime = System.currentTimeMillis()
                    val timeoutMs = 30000L  // 30秒超时

                    try {
                        while (source != null) {
                            // 检查超时
                            if (System.currentTimeMillis() - lastReceivedTime > timeoutMs) {
                                Log.w("DashScopeService", "LLM 响应超时")
                                break
                            }
                            
                            // 检查是否被中断
                            if (Thread.currentThread().isInterrupted) {
                                Log.w("DashScopeService", "LLM 读取被中断")
                                break
                            }

                            val line = source.readUtf8Line() 
                            if (line == null) {
                                // 短暂休眠避免CPU占用
                                Thread.sleep(10)
                                continue
                            }
                            
                            lastReceivedTime = System.currentTimeMillis()
                            
                            if (!line.startsWith("data:")) {
                                continue
                            }
                            val data = line.substringAfter("data:").trim()
                            if (data == "[DONE]") {
                                Log.d("DashScopeService", "LLM stream completed")
                                break
                            }

                            val token = TypeinkRewriteSupport.extractDeltaText(data)
                            if (token.isBlank()) {
                                continue
                            }
                            if (!isGenerationActive(generation)) {
                                break
                            }
                            Log.d("DashScopeService", "LLM token: $token")
                            listener?.onLlmDelta(token)
                            streamBuffer.append(token)
                        }
                    } catch (e: Exception) {
                        Log.e("DashScopeService", "流式解析失败: ${e.message}", e)
                    } finally {
                        response.close()
                    }

                    completeRewrite("stream_end", streamBuffer.toString())
                }
            },
        )
        }
    }
    
    // 语音指令编辑功能
    fun editByInstruction(
        currentText: String,
        instruction: String,
        listener: EditListener,
    ) {
        val generation = nextGeneration()
        Log.d("DashScopeService", "editByInstruction called: text='$currentText', instruction='$instruction'")
        
        val llmConfig = resolveLlmRuntimeConfig()
        if (llmConfig.apiKey.isBlank()) {
            Log.e("DashScopeService", "API key is blank")
            listener.onError("DashScope Key 还没配置")
            return
        }
        
        val editPrompt = """
            你是一个智能文本编辑助手。请根据用户的语音指令修改文本。
            
            当前文本：
            $currentText
            
            用户指令：$instruction
            
            规则：
            1. 准确理解指令意图，直接执行修改
            2. 只输出修改后的完整文本，不要解释
            3. 不要重复原文+修改说明，只返回修改后的文本
            4. 如果指令不明确，原样返回当前文本
            
            请直接输出修改后的文本：
        """.trimIndent()
        
        Log.d("DashScopeService", "Edit prompt: $editPrompt")
        
        val payload =
            JSONObject().apply {
                put("model", llmConfig.model)
                put(
                    "messages",
                    org.json.JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", "你是一个智能文本编辑助手，根据用户指令修改文本，只输出结果不解释。"))
                        put(JSONObject().put("role", "user").put("content", editPrompt))
                    },
                )
                put("stream", false)
                put("temperature", 0.1)
                put("max_tokens", 512)
            }
        
        val request =
            Request.Builder()
                .url(llmConfig.url)
                .addHeader("Authorization", "Bearer ${llmConfig.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        
        cancelPendingNetworkCalls()
        activeEditCall = client.newCall(request).also { call ->
            call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!isGenerationActive(generation)) return
                    Log.e("DashScopeService", "编辑请求失败: ${e.message}", e)
                    listener.onError("网络错误: ${e.message}")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    if (!isGenerationActive(generation)) {
                        response.close()
                        return
                    }
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            Log.e("DashScopeService", "编辑请求失败: ${response.code}, $errorBody")
                            listener.onError("请求失败: ${response.code}")
                            return
                        }
                        
                        val responseBody = response.body?.string() ?: ""
                        val jsonResponse = JSONObject(responseBody)
                        val choices = jsonResponse.optJSONArray("choices")
                        
                        if (choices != null && choices.length() > 0) {
                            val message = choices.getJSONObject(0).optJSONObject("message")
                            val newText = message?.optString("content", currentText) ?: currentText
                            Log.d("DashScopeService", "编辑完成: original='$currentText' -> new='$newText'")
                            if (isGenerationActive(generation)) {
                                listener.onEditResult(newText.trim())
                            }
                        } else {
                            Log.e("DashScopeService", "Empty choices in response")
                            listener.onError("返回结果为空")
                        }
                    } catch (e: Exception) {
                        Log.e("DashScopeService", "解析编辑结果失败: ${e.message}", e)
                        listener.onError("解析失败: ${e.message}")
                    } finally {
                        response.close()
                    }
                }
            },
        )
        }
    }

    private fun closeAsrSocket() {
        finishRequested = false
        taskStarted = false
        currentTaskId = null
        asrWebSocket?.close(1000, "typeink_close")
        asrWebSocket = null
    }
    
    /**
     * 清理所有状态（在录音完全结束时调用）
     */
    private fun clearAllState() {
        closeAsrSocket()
        pendingListener = null
        pendingSnapshot = null
        reconnectAttempts = 0
        isReconnecting = false
        cancelPendingNetworkCalls()
    }
    
    // ==================== 编辑命令识别 ====================
    
    private var editCommandListener: EditCommandListener? = null
    private var latestEditCommand: String = ""
    
    /**
     * 启动 ASR 仅用于识别编辑命令（不听写，不润色，纯 ASR）
     */
    fun startAsrForEdit(listener: EditCommandListener) {
        val asrConfig = resolveAsrRuntimeConfig()
        if (asrConfig.apiKey.isBlank()) {
            listener.onError("DashScope Key 还没配置")
            return
        }
        
        this.editCommandListener = listener
        this.latestEditCommand = ""
        this.activeAsrConfig = asrConfig
        val generation = nextGeneration()
        
        val taskId = UUID.randomUUID().toString().replace("-", "").take(32)
        currentTaskId = taskId
        taskStarted = false
        finishRequested = false
        
        val request = Request.Builder()
            .url(asrConfig.url)
            .addHeader("Authorization", "Bearer ${asrConfig.apiKey}")
            .build()
        
        cancelPendingNetworkCalls()
        asrWebSocket = client.newWebSocket(request, createEditWebSocketListener(listener, generation))
    }
    
    private fun createEditWebSocketListener(listener: EditCommandListener, generation: Long): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isGenerationActive(generation)) {
                    webSocket.close(1000, "stale_generation")
                    return
                }
                Log.d("DashScopeService", "Edit WebSocket onOpen")
                sendEditRunTaskCommand(webSocket)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isGenerationActive(generation)) return
                Log.d("DashScopeService", "Edit WebSocket onMessage: $text")
                handleEditAsrMessage(text, listener, generation)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isGenerationActive(generation)) return
                Log.e("DashScopeService", "Edit ASR 连接失败: ${t.message}", t)
                listener.onError("ASR 连接失败")
                closeAsrSocket()
            }
        }
    }
    
    private fun sendEditRunTaskCommand(webSocket: WebSocket) {
        val taskId = currentTaskId ?: return
        
        val runTaskCmd = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", activeAsrConfig?.model ?: resolveAsrRuntimeConfig().model)
                put("parameters", JSONObject().apply {
                    put("format", "pcm")
                    put("sample_rate", 16000)
                })
                put("input", JSONObject())
            })
        }
        
        webSocket.send(runTaskCmd.toString())
    }
    
    private fun handleEditAsrMessage(text: String, listener: EditCommandListener, generation: Long) {
        if (!isGenerationActive(generation)) return
        try {
            val response = JSONObject(text)
            val header = response.optJSONObject("header")
            val event = header?.optString("event", "").orEmpty()
            
            when (event) {
                "task-started" -> {
                    taskStarted = true
                    Log.d("DashScopeService", "Edit task started")
                }
                
                "result-generated" -> {
                    val payload = response.optJSONObject("payload")
                    val output = payload?.optJSONObject("output")
                    val sentence = output?.optJSONObject("sentence")
                    val resultText = sentence?.optString("text", "").orEmpty().trim()
                    val sentenceEnd = sentence?.optBoolean("sentence_end", false) ?: false
                    
                    if (resultText.isNotBlank()) {
                        latestEditCommand = resultText
                        Log.d("DashScopeService", "Edit ASR partial: $resultText, end=$sentenceEnd")
                        
                        if (sentenceEnd) {
                            // 句子结束，返回命令
                            if (isGenerationActive(generation)) {
                                listener.onEditCommand(resultText)
                            }
                            closeAsrSocket()
                        }
                    }
                }
                
                "task-finished" -> {
                    // 任务完成，如果有积累的命令也返回
                    if (latestEditCommand.isNotBlank()) {
                        if (isGenerationActive(generation)) {
                            listener.onEditCommand(latestEditCommand)
                        }
                    }
                    closeAsrSocket()
                }
                
                "task-failed" -> {
                    val errorMessage = header?.optString("error_message", "任务失败").orEmpty()
                    listener.onError("ASR 失败: $errorMessage")
                    closeAsrSocket()
                }
            }
        } catch (e: Exception) {
            Log.e("DashScopeService", "解析编辑 ASR 响应失败: $text", e)
            listener.onError("解析失败")
        }
    }

    private fun resolveAsrRuntimeConfig(): AsrRuntimeConfig {
        val provider = providerManager?.getCurrentAsrProvider()
        val apiKey = provider?.getEffectiveApiKey().orEmpty().ifBlank { BuildConfig.DASHSCOPE_API_KEY }
        val model = provider?.getEffectiveModel().orEmpty().ifBlank {
            provider?.defaultModel ?: "paraformer-realtime-v2"
        }
        val url = normalizeAsrUrl(provider?.getEffectiveBaseUrl())
        return AsrRuntimeConfig(
            apiKey = apiKey,
            model = model,
            url = url,
        )
    }

    private fun resolveLlmRuntimeConfig(): LlmRuntimeConfig {
        val provider = providerManager?.getCurrentLlmProvider()
        val apiKey = provider?.getEffectiveApiKey().orEmpty().ifBlank { BuildConfig.DASHSCOPE_API_KEY }
        val model = provider?.getEffectiveModel().orEmpty().ifBlank {
            provider?.defaultModel ?: "qwen-flash"
        }
        val url = normalizeLlmUrl(provider?.getEffectiveBaseUrl())
        return LlmRuntimeConfig(
            apiKey = apiKey,
            model = model,
            url = url,
        )
    }

    private fun normalizeAsrUrl(baseUrl: String?): String {
        val raw = baseUrl.orEmpty().trim()
        if (raw.isBlank()) {
            return "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
        }
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    private fun normalizeLlmUrl(baseUrl: String?): String {
        val raw = baseUrl.orEmpty().trim()
        if (raw.isBlank()) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        }
        return if (raw.endsWith("/chat/completions")) {
            raw
        } else {
            "${raw.removeSuffix("/")}/chat/completions"
        }
    }
}
