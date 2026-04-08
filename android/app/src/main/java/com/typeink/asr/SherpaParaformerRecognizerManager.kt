package com.typeink.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream

/**
 * 单模型 Paraformer 在线识别器管理器。
 *
 * 第一版目标很克制：
 * 1. 只支持一个外置 Paraformer 模型目录；
 * 2. 只服务“本地草稿识别”场景；
 * 3. 先把预热、缓存、流创建与释放打稳，不同时引入下载中心或多模型管理。
 */
object SherpaParaformerRecognizerManager {
    private const val TAG = "SherpaPfManager"
    private const val SAMPLE_RATE = 16000
    private const val FEATURE_DIM = 80
    private const val NUM_THREADS = 2

    private val lock = Any()

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedRecognizer: OnlineRecognizer? = null

    fun preload(modelFiles: SherpaDraftRecognizerFactory.ParaformerModelFiles): Boolean {
        return prepare(modelFiles)
    }

    fun prepare(modelFiles: SherpaDraftRecognizerFactory.ParaformerModelFiles): Boolean {
        val signature = modelFiles.signature()
        synchronized(lock) {
            val recognizer = cachedRecognizer
            if (recognizer != null && cachedSignature == signature) {
                return true
            }

            releaseLocked()

            return try {
                cachedRecognizer = OnlineRecognizer(null, buildConfig(modelFiles))
                cachedSignature = signature
                Log.d(TAG, "Sherpa Paraformer prepared: $signature")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to prepare Sherpa Paraformer", t)
                releaseLocked()
                false
            }
        }
    }

    fun createStreamOrNull(modelFiles: SherpaDraftRecognizerFactory.ParaformerModelFiles): OnlineStream? {
        synchronized(lock) {
            val prepared = prepare(modelFiles)
            if (!prepared) return null
            return try {
                cachedRecognizer?.createStream("")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create Sherpa stream", t)
                null
            }
        }
    }

    fun acceptWaveform(
        stream: OnlineStream,
        samples: FloatArray,
    ) {
        synchronized(lock) {
            stream.acceptWaveform(samples, SAMPLE_RATE)
        }
    }

    fun isReady(stream: OnlineStream): Boolean {
        synchronized(lock) {
            return try {
                cachedRecognizer?.isReady(stream) == true
            } catch (t: Throwable) {
                Log.e(TAG, "isReady failed", t)
                false
            }
        }
    }

    fun decode(stream: OnlineStream) {
        synchronized(lock) {
            try {
                cachedRecognizer?.decode(stream)
            } catch (t: Throwable) {
                Log.e(TAG, "decode failed", t)
            }
        }
    }

    fun isEndpoint(stream: OnlineStream): Boolean {
        synchronized(lock) {
            return try {
                cachedRecognizer?.isEndpoint(stream) == true
            } catch (t: Throwable) {
                Log.e(TAG, "isEndpoint failed", t)
                false
            }
        }
    }

    fun getResultText(stream: OnlineStream): String {
        synchronized(lock) {
            return try {
                cachedRecognizer?.getResult(stream)?.text.orEmpty()
            } catch (t: Throwable) {
                Log.e(TAG, "getResultText failed", t)
                ""
            }
        }
    }

    fun reset(stream: OnlineStream) {
        synchronized(lock) {
            try {
                cachedRecognizer?.reset(stream)
            } catch (t: Throwable) {
                Log.e(TAG, "reset failed", t)
            }
        }
    }

    fun inputFinished(stream: OnlineStream) {
        synchronized(lock) {
            try {
                stream.inputFinished()
            } catch (t: Throwable) {
                Log.e(TAG, "inputFinished failed", t)
            }
        }
    }

    fun releaseStream(stream: OnlineStream?) {
        if (stream == null) return
        try {
            stream.release()
        } catch (t: Throwable) {
            Log.e(TAG, "releaseStream failed", t)
        }
    }

    fun unload() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        try {
            cachedRecognizer?.release()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to release Sherpa recognizer", t)
        } finally {
            cachedRecognizer = null
            cachedSignature = null
        }
    }

    private fun buildConfig(
        modelFiles: SherpaDraftRecognizerFactory.ParaformerModelFiles,
    ): OnlineRecognizerConfig {
        val modelConfig =
            OnlineModelConfig().apply {
                paraformer = OnlineParaformerModelConfig(modelFiles.encoderPath, modelFiles.decoderPath)
                tokens = modelFiles.tokensPath
                numThreads = NUM_THREADS
                provider = "cpu"
                debug = false
            }

        return OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig(SAMPLE_RATE, FEATURE_DIM, 0f)
            this.modelConfig = modelConfig
            endpointConfig =
                EndpointConfig(
                    EndpointRule(false, 2.4f, 0.0f),
                    EndpointRule(true, 1.2f, 0.0f),
                    EndpointRule(false, 0.0f, 20.0f),
                )
            enableEndpoint = true
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }
    }

    private fun SherpaDraftRecognizerFactory.ParaformerModelFiles.signature(): String {
        return listOf(tokensPath, encoderPath, decoderPath, NUM_THREADS).joinToString("|")
    }
}
