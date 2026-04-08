package com.typeink.prototype

import android.content.Context
import android.util.Log
import com.typeink.asr.DraftRecognizer

/**
 * 宿主页语音输入会话协调器。
 *
 * 目的：
 * 1. 把 MainActivity 中直接拼装的语音链路收口到一个协调器；
 * 2. 让页面只负责状态更新和文本写回，不再直接管理录音、ASR、本地草稿的编排；
 * 3. 保持当前行为不变，为后续统一 App / IME 状态模型做准备。
 */
class TypeinkHomeInputCoordinator(
    context: Context,
) {
    companion object {
        private const val TAG = "TypeinkHomeInputCoordinator"
    }

    interface Listener {
        fun onLocalDraftText(text: String)

        fun onLocalDraftError(message: String)

        fun onLocalDraftUnavailable()

        fun onAsrPartial(text: String)

        fun onAsrFinal(text: String)

        fun onLlmDelta(token: String)

        fun onLlmCompleted(finalText: String)

        fun onCaptureError(message: String)

        fun onAmplitude(level: Float)

        fun onBackendError(message: String)
    }

    private val dashScopeService = DashScopeService(context)
    private val recorder = PcmRecorder()
    private val localDraftRecognizer: DraftRecognizer = LocalDraftRecognizer(context)

    fun setStyleMode(mode: TypeinkStyleMode) {
        dashScopeService.setStyleMode(mode)
    }

    fun startSession(
        snapshot: SessionInputSnapshot,
        listener: Listener,
    ): Boolean {
        if (!dashScopeService.isConfigured()) {
            listener.onBackendError("当前语音/改写厂商未配置完成，请先到设置里检查。")
            return false
        }

        localDraftRecognizer.start(
            object : DraftRecognizer.Listener {
                override fun onDraftText(text: String) {
                    listener.onLocalDraftText(text)
                }

                override fun onError(message: String) {
                    listener.onLocalDraftError(message)
                }

                override fun onUnavailable() {
                    listener.onLocalDraftUnavailable()
                }
            },
        )

        dashScopeService.startAsr(
            object : DashScopeService.Listener {
                override fun onAsrPartial(text: String) {
                    localDraftRecognizer.stop()
                    listener.onAsrPartial(text)
                }

                override fun onAsrFinal(text: String) {
                    localDraftRecognizer.stop()
                    listener.onAsrFinal(text)
                }

                override fun onLlmDelta(token: String) {
                    listener.onLlmDelta(token)
                }

                override fun onLlmCompleted(finalText: String) {
                    listener.onLlmCompleted(finalText)
                }

                override fun onError(message: String) {
                    listener.onBackendError(message)
                }
            },
            snapshot,
        )

        recorder.start(
            object : PcmRecorder.Listener {
                override fun onAudioChunk(bytes: ByteArray) {
                    dashScopeService.sendAudioChunk(bytes)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Recorder error: $message")
                    listener.onCaptureError(message)
                }

                override fun onAmplitude(level: Float) {
                    listener.onAmplitude(level)
                }
            },
        )

        return true
    }

    fun stopSession() {
        localDraftRecognizer.stop()
        recorder.stop()
        dashScopeService.stopAsr()
    }

    fun clearSession() {
        stopSession()
    }
}
