package com.typeink.asr

/**
 * 草稿识别器抽象。
 *
 * 目的：
 * 1. 让 IME/宿主页只依赖“草稿识别能力”，不直接绑死在系统 `SpeechRecognizer`；
 * 2. 为后续接入 sherpa-onnx 等本地 ASR 实现保留统一边界；
 * 3. 保持当前接口足够小，避免阶段 A 就把未来能力过度设计进去。
 */
interface DraftRecognizer {

    interface Listener {
        fun onDraftText(text: String)

        fun onError(message: String)

        fun onUnavailable()
    }

    fun start(listener: Listener)

    /**
     * 喂入实时 PCM 音频块。
     *
     * 系统 `SpeechRecognizer` 当前不需要这一步，因此默认留空；
     * 后续 sherpa-onnx 这类本地模型会通过这里接收录音数据。
     */
    fun acceptAudioChunk(bytes: ByteArray) = Unit

    fun stop()

    fun isAvailable(): Boolean
}
