package com.typeink.inputmethod

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 振动反馈管理器 - 对标 参考键盘 的触感反馈
 */
class HapticFeedbackManager(context: Context) {
    
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    /**
     * 按键按压反馈 - 轻触感
     */
    fun performKeyPress() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }
    
    /**
     * 录音开始反馈 - 三短振动（参考键盘 风格）
     */
    fun performRecordingStart() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 三短振动：0ms延迟, 30ms振动, 20ms间隔, 30ms振动, 20ms间隔, 30ms振动
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 20, 30, 20, 30), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 30, 20, 30, 20, 30), -1)
        }
    }
    
    /**
     * 录音停止反馈 - 单次短振动
     */
    fun performRecordingStop() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }
    }
    
    /**
     * 成功反馈 - 轻微双振动
     */
    fun performSuccess() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 30, 50, 30), -1)
        }
    }
    
    /**
     * 错误反馈 - 单次长振动
     */
    fun performError() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}
