package com.typeink.prototype

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TypeinkSessionClient {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onServerFrame(frame: ServerFrame)
    }

    sealed class ServerFrame {
        data class SessionReady(val context: String?) : ServerFrame()
        data class AsrPartial(val text: String) : ServerFrame()
        data class AsrFinal(val text: String) : ServerFrame()
        data class LlmDelta(val token: String) : ServerFrame()
        data class LlmCompleted(val finalText: String) : ServerFrame()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var listener: Listener? = null
    private var isConnected = false

    fun connect(url: String, listener: Listener) {
        this.listener = listener
        this.currentUrl = url

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("TypeinkSessionClient", "WebSocket 连接已建立")
                isConnected = true
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("TypeinkSessionClient", "收到消息: $text")
                try {
                    val json = JSONObject(text)
                    val frame = parseServerFrame(json)
                    if (frame != null) {
                        listener.onServerFrame(frame)
                    }
                } catch (e: Exception) {
                    Log.e("TypeinkSessionClient", "解析消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("TypeinkSessionClient", "WebSocket 正在关闭: $reason")
                isConnected = false
                listener.onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("TypeinkSessionClient", "WebSocket 已关闭: $reason")
                isConnected = false
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("TypeinkSessionClient", "WebSocket 连接失败: ${t.message}")
                isConnected = false
                listener.onError("连接失败: ${t.message}")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "client_close")
        webSocket = null
        isConnected = false
    }

    fun isConnected(url: String): Boolean {
        return isConnected && currentUrl == url
    }

    fun sendStartSession(context: String, styleMode: TypeinkStyleMode) {
        val settings = JSONObject().apply {
            put("rewriter", JSONObject().apply {
                put("style_mode", if (styleMode == TypeinkStyleMode.FORMAL) "formal" else "natural")
            })
        }

        val frame = JSONObject().apply {
            put("type", "start_session")
            put("context", context)
            put("settings", settings)
        }
        sendJson(frame)
    }

    fun sendContextUpdate(selectedText: String, preText: String?, postText: String?) {
        val frame = JSONObject().apply {
            put("type", "context_update")
            put("selected_text", selectedText)
            put("pre_text", preText)
            put("post_text", postText)
        }
        sendJson(frame)
    }

    fun sendStopRecording() {
        val frame = JSONObject().apply {
            put("type", "stop_recording")
        }
        sendJson(frame)
    }

    fun sendAudioChunk(opcode: Byte, audioData: ByteArray) {
        val frame = ByteArray(audioData.size + 1)
        frame[0] = opcode
        System.arraycopy(audioData, 0, frame, 1, audioData.size)
        webSocket?.send(okio.ByteString.of(*frame))
    }

    private fun sendJson(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    private fun parseServerFrame(json: JSONObject): ServerFrame? {
        return when (json.optString("type")) {
            "session_ready" -> ServerFrame.SessionReady(json.optString("context"))
            "asr_partial" -> {
                val content = json.optJSONObject("content")
                ServerFrame.AsrPartial(content?.optString("text", "") ?: "")
            }
            "asr_final" -> {
                val content = json.optJSONObject("content")
                ServerFrame.AsrFinal(content?.optString("text", "") ?: "")
            }
            "llm_delta" -> {
                val content = json.optJSONObject("content")
                ServerFrame.LlmDelta(content?.optString("token", "") ?: "")
            }
            "llm_completed" -> {
                val content = json.optJSONObject("content")
                ServerFrame.LlmCompleted(content?.optString("final_text", "") ?: "")
            }
            else -> null
        }
    }
}
