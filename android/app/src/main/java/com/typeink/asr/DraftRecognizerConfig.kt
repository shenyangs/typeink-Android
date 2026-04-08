package com.typeink.asr

import android.content.Context
import android.content.SharedPreferences
import android.speech.SpeechRecognizer
import com.typeink.prototype.LocalDraftRecognizer

enum class DraftRecognizerBackend(
    val storageValue: String,
    val displayName: String,
) {
    SYSTEM("system", "系统"),
    SHERPA("sherpa", "Sherpa"),
    ;

    companion object {
        @JvmStatic
        fun fromStorage(value: String?): DraftRecognizerBackend {
            val normalized = value?.trim()?.lowercase()
            return values().firstOrNull { it.storageValue == normalized } ?: SYSTEM
        }
    }
}

data class DraftRecognizerRuntimeStatus(
    val requestedBackend: DraftRecognizerBackend,
    val actualBackend: DraftRecognizerBackend,
    val isAvailable: Boolean,
    val detail: String,
) {
    fun toBadgeLabel(): String {
        return if (requestedBackend == actualBackend) {
            actualBackend.displayName
        } else {
            "${requestedBackend.displayName}->${actualBackend.displayName}"
        }
    }

    fun toSummaryText(): String {
        return "${toBadgeLabel()}；$detail"
    }
}

data class DraftRecognizerResolution(
    val recognizer: DraftRecognizer,
    val runtimeStatus: DraftRecognizerRuntimeStatus,
)

/**
 * 草稿识别后端配置与运行时回退收口。
 *
 * 当前策略：
 * 1. 默认仍走系统 `SpeechRecognizer`，保持现有体验不变；
 * 2. 已把 sherpa 入口和状态边界收出来，但在真正接入模型前，仍会自动回退系统实现；
 * 3. 这样做的目的是先把“可配置、可观测、可回退”打稳，再接真正的本地 ASR 解码器。
 */
class DraftRecognizerConfig(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "draft_recognizer_config"
        private const val KEY_DRAFT_BACKEND = "draft_backend"
        val DEFAULT_BACKEND = DraftRecognizerBackend.SHERPA

        @JvmStatic
        fun load(context: Context): DraftRecognizerConfig = DraftRecognizerConfig(context)
    }

    var backend: DraftRecognizerBackend
        get() =
            DraftRecognizerBackend.fromStorage(
                prefs.getString(KEY_DRAFT_BACKEND, DEFAULT_BACKEND.storageValue),
            )
        set(value) = prefs.edit().putString(KEY_DRAFT_BACKEND, value.storageValue).apply()

    fun createDraftRecognizer(): DraftRecognizer = createResolution().recognizer

    fun createResolution(): DraftRecognizerResolution {
        return when (backend) {
            DraftRecognizerBackend.SYSTEM ->
                DraftRecognizerResolution(
                    recognizer = LocalDraftRecognizer(appContext),
                    runtimeStatus = buildSystemStatus(DraftRecognizerBackend.SYSTEM, null),
                )

            DraftRecognizerBackend.SHERPA -> {
                val resolution = SherpaDraftRecognizerFactory.resolveDraftRecognizer(appContext)
                DraftRecognizerResolution(
                    recognizer = resolution.recognizer,
                    runtimeStatus =
                        DraftRecognizerRuntimeStatus(
                            requestedBackend = DraftRecognizerBackend.SHERPA,
                            actualBackend = resolution.actualBackend,
                            isAvailable = resolution.isAvailable,
                            detail = resolution.detail,
                        ),
                )
            }
        }
    }

    fun resolveRuntimeStatus(): DraftRecognizerRuntimeStatus = createResolution().runtimeStatus

    fun preloadPreferredBackendIfPossible() {
        if (backend == DraftRecognizerBackend.SHERPA) {
            SherpaDraftRecognizerFactory.preloadIfPossible(appContext)
        }
    }

    fun getConfigDescription(): String {
        return "草稿识别：${resolveRuntimeStatus().toSummaryText()}"
    }

    private fun buildSystemStatus(
        requestedBackend: DraftRecognizerBackend,
        fallbackPrefix: String?,
    ): DraftRecognizerRuntimeStatus {
        val systemAvailable = isSystemRecognizerAvailable()
        val detail =
            buildList {
                if (!fallbackPrefix.isNullOrBlank()) {
                    add(fallbackPrefix)
                }
                add(
                    if (systemAvailable) {
                        "系统草稿识别可用"
                    } else {
                        "系统语音识别当前不可用"
                    },
                )
            }.joinToString("；")
        return DraftRecognizerRuntimeStatus(
            requestedBackend = requestedBackend,
            actualBackend = DraftRecognizerBackend.SYSTEM,
            isAvailable = systemAvailable,
            detail = detail,
        )
    }

    private fun isSystemRecognizerAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        } catch (_: Throwable) {
            false
        }
    }
}
