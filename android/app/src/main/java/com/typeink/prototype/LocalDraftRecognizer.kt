package com.typeink.prototype

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.typeink.asr.DraftRecognizer

/**
 * 基于系统 `SpeechRecognizer` 的草稿识别实现。
 *
 * 当前仍是阶段 A 的默认实现，后续会在不改调用方的前提下增加其他实现。
 */
class LocalDraftRecognizer(private val context: Context) : DraftRecognizer {
    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: DraftRecognizer.Listener? = null

    @Volatile
    private var running = false

    private var committedText = ""
    private var restartAttempts = 0
    private val maxRestartAttempts = 3

    override fun start(listener: DraftRecognizer.Listener) {
        if (!isSpeechRecognizerAvailable()) {
            listener.onUnavailable()
            return
        }

        stop()
        this.listener = listener
        committedText = ""
        running = true
        restartAttempts = 0

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit

                    override fun onBeginningOfSpeech() {
                        restartAttempts = 0
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        if (running) restart()
                    }

                    override fun onError(error: Int) {
                        if (!running) return
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_CLIENT,
                            -> {
                                if (restartAttempts < maxRestartAttempts) {
                                    restartAttempts++
                                    restart()
                                } else {
                                    listener.onError("本地草稿识别暂时不可用，请等待云端识别结果")
                                }
                            }

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                listener.onError("麦克风权限不足，无法使用本地草稿")
                            }

                            SpeechRecognizer.ERROR_NETWORK -> {
                                listener.onError("网络连接问题，本地草稿暂时不可用")
                            }

                            else -> {
                                listener.onError("本地草稿识别遇到问题：$error")
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (!running) return
                        val draftText = extractText(results)
                        if (draftText.isNotBlank()) {
                            committedText = draftText
                            listener.onDraftText(committedText)
                        }
                        restartAttempts = 0
                        restart()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!running) return
                        val partialText = extractText(partialResults)
                        val merged = listOf(committedText, partialText).filter { it.isNotBlank() }.joinToString("")
                        if (merged.isNotBlank()) {
                            listener.onDraftText(merged)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )
            recognizer.startListening(buildIntent())
        } catch (e: Exception) {
            listener.onError("初始化本地草稿识别失败：${e.message}")
            stop()
        }
    }

    override fun stop() {
        running = false
        committedText = ""
        restartAttempts = 0
        speechRecognizer?.apply {
            try {
                stopListening()
            } catch (_: Exception) {
            }
            try {
                cancel()
            } catch (_: Exception) {
            }
            try {
                destroy()
            } catch (_: Exception) {
            }
        }
        speechRecognizer = null
        listener = null
    }

    private fun restart() {
        val recognizer = speechRecognizer ?: return
        if (!running) return

        try {
            recognizer.cancel()
            recognizer.startListening(buildIntent())
        } catch (e: Exception) {
            listener?.onError("重启本地草稿识别失败：${e.message}")
            if (restartAttempts < maxRestartAttempts) {
                restartAttempts++
                // 延迟重试
                Thread { 
                    Thread.sleep(500)
                    if (running) {
                        try {
                            recognizer.startListening(buildIntent())
                        } catch (_: Exception) {
                        }
                    }
                }.start()
            }
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // 优先使用离线识别，提高稳定性
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500) // 缩短最小语音长度
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000) // 调整静音检测
        }
    }

    private fun extractText(bundle: Bundle?): String {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return results?.firstOrNull()?.trim().orEmpty()
    }

    override fun isAvailable(): Boolean = isSpeechRecognizerAvailable()

    private fun isSpeechRecognizerAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
    }
}
