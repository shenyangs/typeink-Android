package com.typeink.asr

import android.content.Context
import com.typeink.prototype.LocalDraftRecognizer
import java.io.File

/**
 * sherpa 草稿识别入口预留。
 *
 * 当前这层先做两件事：
 * 1. 明确未来本地模型会放在哪；
 * 2. 在模型未接入/引擎未落地时，给上层一个明确、可展示的回退原因。
 */
object SherpaDraftRecognizerFactory {
    private const val MODEL_ROOT_DIR = "local_asr/paraformer"

    data class ParaformerModelFiles(
        val modelDir: File,
        val tokensPath: String,
        val encoderPath: String,
        val decoderPath: String,
    )

    data class Resolution(
        val recognizer: DraftRecognizer,
        val actualBackend: DraftRecognizerBackend,
        val isAvailable: Boolean,
        val detail: String,
    )

    fun preloadIfPossible(context: Context) {
        val modelFiles = resolveModelFiles(context) ?: return
        SherpaParaformerRecognizerManager.preload(modelFiles)
    }

    fun resolveFallbackReason(context: Context): String {
        if (!isSherpaRuntimeAvailable()) {
            return "当前 APK 未包含 sherpa 草稿运行时，已回退系统"
        }

        val modelFiles = resolveModelFiles(context)
        if (modelFiles == null) {
            return "未发现 sherpa 草稿模型目录，已回退系统"
        }

        return "Sherpa Paraformer 模型已就绪：${modelFiles.modelDir.name}"
    }

    fun resolveDraftRecognizer(context: Context): Resolution {
        if (!isSherpaRuntimeAvailable()) {
            return Resolution(
                recognizer = LocalDraftRecognizer(context.applicationContext),
                actualBackend = DraftRecognizerBackend.SYSTEM,
                isAvailable = true,
                detail = "当前 APK 未包含 sherpa 草稿运行时，已回退系统",
            )
        }

        val modelFiles = resolveModelFiles(context)
        if (modelFiles == null) {
            return Resolution(
                recognizer = LocalDraftRecognizer(context.applicationContext),
                actualBackend = DraftRecognizerBackend.SYSTEM,
                isAvailable = true,
                detail = "未发现 sherpa 草稿模型目录，已回退系统",
            )
        }

        return Resolution(
            recognizer = SherpaParaformerDraftRecognizer(context.applicationContext, modelFiles),
            actualBackend = DraftRecognizerBackend.SHERPA,
            isAvailable = true,
            detail = "Sherpa Paraformer 已就绪：${modelFiles.modelDir.name}",
        )
    }

    private fun isSherpaRuntimeAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun resolveModelFiles(context: Context): ParaformerModelFiles? {
        val modelDir = findParaformerModelDir(context) ?: return null
        val tokens = File(modelDir, "tokens.txt").takeIf(File::exists) ?: return null
        val encoder =
            firstExistingFile(
                modelDir,
                "encoder.int8.onnx",
                "encoder.onnx",
            ) ?: return null
        val decoder =
            firstExistingFile(
                modelDir,
                "decoder.int8.onnx",
                "decoder.onnx",
            ) ?: return null
        return ParaformerModelFiles(
            modelDir = modelDir,
            tokensPath = tokens.absolutePath,
            encoderPath = encoder.absolutePath,
            decoderPath = decoder.absolutePath,
        )
    }

    private fun findParaformerModelDir(context: Context): File? {
        val roots =
            buildList {
                add(File(context.filesDir, MODEL_ROOT_DIR))
                context.getExternalFilesDir(null)?.let { add(File(it, MODEL_ROOT_DIR)) }
            }

        return roots.firstNotNullOfOrNull { root -> findModelDirRecursively(root) }
    }

    private fun findModelDirRecursively(root: File?): File? {
        if (root == null || !root.exists() || !root.isDirectory) return null
        if (isParaformerModelDir(root)) return root
        return root.listFiles()?.firstNotNullOfOrNull { child ->
            if (child.isDirectory) findModelDirRecursively(child) else null
        }
    }

    private fun isParaformerModelDir(dir: File): Boolean {
        val hasTokens = File(dir, "tokens.txt").exists()
        val hasEncoder = File(dir, "encoder.int8.onnx").exists() || File(dir, "encoder.onnx").exists()
        val hasDecoder = File(dir, "decoder.int8.onnx").exists() || File(dir, "decoder.onnx").exists()
        return hasTokens && hasEncoder && hasDecoder
    }

    private fun firstExistingFile(
        dir: File,
        vararg names: String,
    ): File? {
        for (name in names) {
            val candidate = File(dir, name)
            if (candidate.exists()) return candidate
        }
        return null
    }
}
