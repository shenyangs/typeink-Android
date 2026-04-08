package com.typeink.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Typeink 语音识别器
 * 
 * 实现 Android 标准 SpeechRecognizer 接口，支持第三方应用调用
 * 兼容 RecognitionListener 回调
 */
class TypeinkSpeechRecognizer(context: Context) {
    
    companion object {
        private const val TAG = "TypeinkSpeechRecognizer"
        
        // 自定义动作，用于远程调用
        const val ACTION_START_LISTENING = "com.typeink.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.typeink.action.STOP_LISTENING"
        const val ACTION_CANCEL = "com.typeink.action.CANCEL"
        
        // 额外的认识结果键
        const val RESULTS_RECOGNITION = SpeechRecognizer.RESULTS_RECOGNITION
        const val RESULTS_PARTIAL = "results_partial"
    }
    
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 监听器
    private var recognitionListener: RecognitionListener? = null
    
    // 是否正在识别
    private var isListening = false
    
    /**
     * 设置识别监听器
     */
    fun setRecognitionListener(listener: RecognitionListener) {
        this.recognitionListener = listener
    }
    
    /**
     * 开始识别
     */
    fun startListening(recognizerIntent: Intent) {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }
        
        isListening = true
        
        // 通知远程服务开始识别
        val serviceIntent = Intent(context, TypeinkSpeechRecognitionService::class.java).apply {
            action = ACTION_START_LISTENING
            putExtras(recognizerIntent)
        }
        
        context.startService(serviceIntent)
        
        // 触发回调
        mainHandler.post {
            recognitionListener?.onReadyForSpeech(Bundle())
            recognitionListener?.onBeginningOfSpeech()
        }
        
        Log.d(TAG, "Started listening")
    }
    
    /**
     * 停止识别
     */
    fun stopListening() {
        if (!isListening) {
            return
        }
        
        val serviceIntent = Intent(context, TypeinkSpeechRecognitionService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        context.startService(serviceIntent)
        
        Log.d(TAG, "Stopped listening")
    }
    
    /**
     * 取消识别
     */
    fun cancel() {
        if (!isListening) {
            return
        }
        
        isListening = false
        
        val serviceIntent = Intent(context, TypeinkSpeechRecognitionService::class.java).apply {
            action = ACTION_CANCEL
        }
        context.startService(serviceIntent)
        
        mainHandler.post {
            recognitionListener?.onError(SpeechRecognizer.ERROR_CLIENT)
        }
        
        Log.d(TAG, "Cancelled")
    }
    
    /**
     * 销毁
     */
    fun destroy() {
        cancel()
        recognitionListener = null
    }
    
    /**
     * 内部方法：处理部分结果
     */
    internal fun onPartialResult(text: String) {
        mainHandler.post {
            val bundle = Bundle().apply {
                putStringArrayList(RESULTS_PARTIAL, arrayListOf(text))
            }
            recognitionListener?.onPartialResults(bundle)
        }
    }
    
    /**
     * 内部方法：处理最终结果
     */
    internal fun onFinalResult(text: String) {
        isListening = false
        mainHandler.post {
            val bundle = Bundle().apply {
                putStringArrayList(RESULTS_RECOGNITION, arrayListOf(text))
            }
            recognitionListener?.onResults(bundle)
            recognitionListener?.onEndOfSpeech()
        }
    }
    
    /**
     * 内部方法：处理错误
     */
    internal fun onError(errorCode: Int) {
        isListening = false
        mainHandler.post {
            recognitionListener?.onError(errorCode)
        }
    }
}

/**
 * 语音识别服务
 * 处理来自第三方应用的识别请求
 */
class TypeinkSpeechRecognitionService : android.app.Service() {
    
    companion object {
        private const val TAG = "SpeechRecognitionService"
    }
    
    override fun onBind(intent: Intent?) = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TypeinkSpeechRecognizer.ACTION_START_LISTENING -> {
                // 启动识别流程
                Log.d(TAG, "Service: start listening")
            }
            TypeinkSpeechRecognizer.ACTION_STOP_LISTENING -> {
                Log.d(TAG, "Service: stop listening")
            }
            TypeinkSpeechRecognizer.ACTION_CANCEL -> {
                Log.d(TAG, "Service: cancel")
            }
        }
        return START_NOT_STICKY
    }
}
