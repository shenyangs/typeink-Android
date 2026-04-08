package com.typeink.inputmethod

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 诊断版输入法 - 极简实现，用于排查是否基础框架有问题
 * 
 * 如果红框能显示，说明基础框架正常，问题在复杂UI代码
 * 如果红框不显示，说明Manifest、权限或系统层面有问题
 */
class DiagnosticIME : InputMethodService() {
    
    companion object {
        private const val TAG = "DiagnosticIME"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "🔴 DiagnosticIME onCreate called!")
    }
    
    override fun onDestroy() {
        Log.e(TAG, "🔴 DiagnosticIME onDestroy called!")
        super.onDestroy()
    }
    
    override fun onCreateInputView(): View {
        Log.e(TAG, "🔴 onCreateInputView called - creating RED BOX")
        
        return FrameLayout(this).apply {
            // 亮红色背景，非常醒目
            setBackgroundColor(Color.RED)
            
            // 固定高度 800px（足够大，不可能看不见）
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                800  // 硬编码高度，测试用
            )
            
            // 添加文字提示
            addView(TextView(context).apply {
                text = "🔴 DIAGNOSTIC IME ACTIVE\nIf you see this, basic framework works!"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = android.view.Gravity.CENTER
            })
        }
    }
    
    override fun onCreateCandidatesView(): View? {
        Log.e(TAG, "🔴 onCreateCandidatesView called")
        return null
    }
    
    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.e(TAG, "🔴 onStartInputView called, restarting=$restarting")
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        Log.e(TAG, "🔴 onFinishInputView called")
        super.onFinishInputView(finishingInput)
    }
    
    override fun onWindowShown() {
        super.onWindowShown()
        Log.e(TAG, "🔴 onWindowShown called - IME window should be visible!")
    }
    
    override fun onWindowHidden() {
        Log.e(TAG, "🔴 onWindowHidden called")
        super.onWindowHidden()
    }
    
    override fun onEvaluateFullscreenMode(): Boolean {
        Log.e(TAG, "🔴 onEvaluateFullscreenMode called, returning false")
        return false  // 强制非全屏，避免 Flyme 拦截
    }
    
    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        outInsets?.let {
            // 强制设置固定高度
            it.contentTopInsets = 800
            it.visibleTopInsets = 800
            it.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
            Log.e(TAG, "🔴 onComputeInsets: forced height=800")
        }
    }
}
