package com.typeink.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * 输入法联动接口
 * 
 * 支持与小企鹅输入法、同文输入法等第三方输入法联动
 * 让第三方输入法可以调用 Typeink 的语音识别能力
 */
class ImeIntegration(private val context: Context) {
    
    companion object {
        private const val TAG = "ImeIntegration"
        
        // 支持的第三方输入法包名
        private val SUPPORTED_IMES = listOf(
            "com.tencent.qqpinyin",      // 小企鹅输入法
            "com.osfans.trime",           // 同文输入法
            "com.google.android.inputmethod.pinyin" // 谷歌拼音
        )
        
        // 联动动作
        const val ACTION_VOICE_INPUT = "com.typeink.action.VOICE_INPUT"
        const val ACTION_VOICE_INPUT_RESULT = "com.typeink.action.VOICE_INPUT_RESULT"
        
        // 额外键
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_IME_PACKAGE = "ime_package"
    }
    
    /**
     * 检查是否支持指定输入法
     */
    fun isImeSupported(packageName: String): Boolean {
        return SUPPORTED_IMES.contains(packageName)
    }
    
    /**
     * 检查已安装的可联动输入法
     */
    fun getInstalledCompatibleImes(): List<ImeInfo> {
        val pm = context.packageManager
        val installed = mutableListOf<ImeInfo>()
        
        SUPPORTED_IMES.forEach { packageName ->
            try {
                val info = pm.getPackageInfo(packageName, 0)
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                installed.add(ImeInfo(packageName, label, true))
            } catch (e: Exception) {
                Log.d(TAG, "$packageName not installed")
            }
        }
        
        return installed
    }
    
    /**
     * 请求语音输入
     * 第三方输入法可以调用此方法
     */
    fun requestVoiceInput(imePackage: String, callback: VoiceInputCallback) {
        if (!isImeSupported(imePackage)) {
            Log.w(TAG, "IME not supported: $imePackage")
            callback.onError("不支持的输入法: $imePackage")
            return
        }
        
        Log.d(TAG, "Requesting voice input for: $imePackage")
        
        // 启动语音输入流程
        val intent = Intent(context, VoiceInputActivity::class.java).apply {
            putExtra(EXTRA_IME_PACKAGE, imePackage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    /**
     * 返回语音输入结果给调用者
     */
    fun returnVoiceResult(imePackage: String, text: String) {
        val intent = Intent(ACTION_VOICE_INPUT_RESULT).apply {
            setPackage(imePackage)
            putExtra(EXTRA_RESULT_TEXT, text)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Returned result to $imePackage: $text")
    }
    
    /**
     * 输入法信息
     */
    data class ImeInfo(
        val packageName: String,
        val label: String,
        val isInstalled: Boolean
    )
    
    /**
     * 语音输入回调
     */
    interface VoiceInputCallback {
        fun onResult(text: String)
        fun onError(error: String)
    }
}

/**
 * 语音输入 Activity
 * 用于第三方输入法调用时显示语音输入界面
 */
class VoiceInputActivity : android.app.Activity() {
    
    companion object {
        private const val TAG = "VoiceInputActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val imePackage = intent.getStringExtra(ImeIntegration.EXTRA_IME_PACKAGE)
        Log.d(TAG, "Voice input for IME: $imePackage")
        
        // 显示简化的语音输入界面
        // 这里可以跳转到沉浸式编辑器或专用语音输入界面
        
        // 演示：直接返回测试结果
        val integration = ImeIntegration(this)
        integration.returnVoiceResult(imePackage ?: "", "这是来自 Typeink 的语音输入结果")
        finish()
    }
}
