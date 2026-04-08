package com.typeink.asr

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 火山引擎 FireRedASR 引擎 - 参考 参考键盘 实现
 *
 * 特点：
 * - 流式识别
 * - 性价比高
 * - 支持中文数字归一化
 */
class FireRedAsrEngine(
    private val config: AsrConfig
) : AsrEngine {
    
    companion object {
        private const val TAG = "FireRedAsrEngine"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/vc/tts/streaming"
        private const val HTTP_URL = "https://openspeech.bytedance.com/api/v1/vc/tts"
    }
    
    override val vendor: AsrVendor = AsrVendor.FIRERED
    
    override val isAvailable: Boolean
        get() = config.apiKey.isNotBlank() && config.appId.isNotBlank()
    
    private var webSocket: WebSocket? = null
    private var listener: AsrEngine.Listener? = null
    private var isRunningFlag = false
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun isRunning(): Boolean = isRunningFlag
    
    override fun start(listener: AsrEngine.Listener) {
        if (!isAvailable) {
            listener.onError(AsrError.authError("FireRedASR 未配置 API Key"))
            return
        }
        
        if (isRunningFlag) {
            Log.w(TAG, "Engine already running")
            return
        }
        
        Log.d(TAG, "Starting FireRedASR engine")
        this.listener = listener
        isRunningFlag = true
        
        connectWebSocket()
    }
    
    private fun connectWebSocket() {
        val request = Request.Builder()
            .url(WS_URL)
            .header("X-Api-Key", config.apiKey)
            .header("X-App-Id", config.appId)
            .header("Content-Type", "application/json")
            .build()
        
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                // 发送配置参数
                sendConfig(webSocket)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                isRunningFlag = false
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                isRunningFlag = false
                listener?.onError(AsrError.networkError(t.message ?: "WebSocket 连接失败"))
            }
        }
        
        webSocket = client.newWebSocket(request, webSocketListener)
    }
    
    private fun sendConfig(webSocket: WebSocket) {
        val configJson = JSONObject().apply {
            put("appid", config.appId)
            put("sample_rate", config.sampleRate)
            put("language", config.language)
            put("enable_punctuation", config.enablePunctuation)
            put("enable_itn", config.enableITN)
        }
        
        webSocket.send(configJson.toString())
    }
    
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val result = json.optJSONObject("result")
            
            when {
                result != null -> {
                    val text = result.optString("text", "")
                    val isFinal = result.optBoolean("is_final", false)
                    
                    if (isFinal) {
                        listener?.onFinalResult(text)
                    } else {
                        listener?.onPartialResult(text)
                    }
                }
                json.has("error") -> {
                    val error = json.getJSONObject("error")
                    listener?.onError(AsrError.serverError(
                        error.optString("message", "未知错误")
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }
    
    override fun sendAudioChunk(chunk: ByteArray) {
        if (!isRunningFlag || webSocket == null) {
            return
        }
        
        // FireRedASR 需要 base64 编码的音频数据
        val base64Audio = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("audio", base64Audio)
        }
        
        webSocket?.send(message.toString())
    }
    
    override fun stop() {
        Log.d(TAG, "Stopping FireRedASR engine")
        isRunningFlag = false
        
        // 发送结束标记
        val endMessage = JSONObject().apply {
            put("audio", "")
            put("is_end", true)
        }
        webSocket?.send(endMessage.toString())
        
        // 关闭 WebSocket
        webSocket?.close(1000, "User stopped")
        webSocket = null
    }
    
    override fun release() {
        stop()
        scope.cancel()
        listener = null
    }
}
