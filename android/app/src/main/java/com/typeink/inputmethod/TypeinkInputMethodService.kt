package com.typeink.inputmethod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import androidx.core.view.ViewCompat
import com.typeink.asr.DraftRecognizerConfig
import com.typeink.prototype.BuildConfig
import com.typeink.prototype.DashScopeService
import com.typeink.prototype.PcmRecorder
import com.typeink.prototype.R
import com.typeink.prototype.SessionInputSnapshot
import com.typeink.prototype.SessionRewriteSource
import com.typeink.inputmethod.security.PasswordDetector
import com.typeink.settings.SettingsActivity
import com.typeink.syncclipboard.ClipboardHistoryTracker
import com.typeink.vad.VadConfig
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Typeink 输入法主服务
 * 
 * 参考 参考键盘 实现，确保兼容性和稳定性
 */
class TypeinkInputMethodService : InputMethodService(), TypeinkImeHost {
    
    companion object {
        private const val TAG = "TypeinkInputService"
        private const val VOICE_IME_HEIGHT_DP = 336
        private const val ACTION_DEBUG_PREVIEW_SCENARIO = "com.typeink.prototype.action.DEBUG_PREVIEW_SCENARIO"
        private const val EXTRA_DEBUG_MODE = "mode"
        private const val EXTRA_PARTIAL_TEXT = "partial_text"
        private const val EXTRA_FINAL_TEXT = "final_text"
        private const val EXTRA_REWRITE_TEXT = "rewrite_text"
        private const val EXTRA_AUTO_COMMIT = "auto_commit"
        private const val EXTRA_STEP_DELAY_MS = "step_delay_ms"
        private const val DEBUG_MODE_PREVIEW = "preview"
        private const val DEBUG_MODE_COMMIT = "commit"
        private const val DEBUG_MODE_DISCARD = "discard"
        private const val DEBUG_MODE_VOICE = "voice"
        private const val EXTRA_VOICE_OUTCOME = "voice_outcome"
        private const val DEBUG_VOICE_SUCCESS = "success"
        private const val DEBUG_VOICE_ERROR = "error"
        private const val DEBUG_VOICE_CANCEL = "cancel"
        private const val DEBUG_VOICE_TIMEOUT = "timeout"

        @Volatile
        private var activeInstance: TypeinkInputMethodService? = null

        fun dispatchDebugPreviewIntent(intent: Intent) {
            if (!BuildConfig.DEBUG) return
            val service = activeInstance
            if (service == null) {
                Log.w(TAG, "Debug preview ignored: active IME service is null")
                return
            }
            service.handleDebugPreviewIntent(intent)
        }
    }
    
    // 核心组件
    val dashScopeService by lazy { DashScopeService(this) }
    val recorder = PcmRecorder()
    val inputHelper = InputConnectionHelper()
    private lateinit var keyboardActionHandler: KeyboardActionHandler
    private lateinit var asrSessionManager: AsrSessionManager
    
    // 布局控制器
    private var layoutController: ImeLayoutController? = null
    
    // 输入视图
    private var inputView: TypeinkInputView? = null
    private var lastReportedContentTopInsets: Int = 0
    private val lastTouchableBounds = Rect()
    private var debugReceiverRegistered: Boolean = false
    private val debugPreviewReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!BuildConfig.DEBUG || intent?.action != ACTION_DEBUG_PREVIEW_SCENARIO) return
                handleDebugPreviewIntent(intent)
            }
        }
    
    override fun onCreate() {
        super.onCreate()
        keyboardActionHandler = KeyboardActionHandler(
            context = this,
            dashScopeService = dashScopeService,
            inputHelper = inputHelper,
        )
        asrSessionManager = AsrSessionManager(
            context = this,
            dashScopeService = dashScopeService,
            recorder = recorder,
            draftRecognizerProvider = { DraftRecognizerConfig.load(this).createDraftRecognizer() },
            actionHandler = keyboardActionHandler,
        )
        thread(name = "typeink-local-runtime-preload", isDaemon = true) {
            try {
                VadConfig.load(this).preloadPreferredBackendIfPossible()
                DraftRecognizerConfig.load(this).preloadPreferredBackendIfPossible()
            } catch (t: Throwable) {
                Log.w(TAG, "Local runtime preload skipped", t)
            }
        }
        ClipboardHistoryTracker.start(this)
        activeInstance = this
        registerDebugReceiverIfNeeded()
        Log.d(TAG, "onCreate")
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        keyboardActionHandler.setUiListener(null)
        asrSessionManager.cleanup()
        keyboardActionHandler.cleanup()
        inputView?.dispose()
        inputView = null
        if (activeInstance === this) {
            activeInstance = null
        }
        ClipboardHistoryTracker.stop()
        unregisterDebugReceiverIfNeeded()
        super.onDestroy()
    }
    
    /**
     * 创建输入法视图 - 必须返回非空视图
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")
        
        // 创建主视图
        val view = TypeinkInputView(
            context = this,
            host = this
        )
        
        // 设置最小高度
        val minHeight = (VOICE_IME_HEIGHT_DP * resources.displayMetrics.density).toInt()
        view.minimumHeight = minHeight
        
        // 设置布局参数
        view.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top == oldTop && bottom == oldBottom) return@addOnLayoutChangeListener
            val nextContentTopInsets = resolveKeyboardContentTop(view)
            if (nextContentTopInsets != lastReportedContentTopInsets) {
                lastReportedContentTopInsets = nextContentTopInsets
                ViewCompat.requestApplyInsets(view)
            }
        }
        
        inputView = view
        keyboardActionHandler.setUiListener(view)
        
        // 初始化布局控制器
        layoutController = ImeLayoutController(view).apply {
            installKeyboardInsetsListener { bottomInset ->
                Log.d(TAG, "Navigation bar height changed: $bottomInset")
            }
        }
        
        Log.d(TAG, "Input view created successfully")
        return view
    }
    
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val securityLevel = PasswordDetector.getSecurityLevel(info)
        Log.d(
            TAG,
            "onStartInputView, package=${info?.packageName}, inputType=${info?.inputType}, " +
                "privateImeOptions=${info?.privateImeOptions}, securityLevel=$securityLevel, restarting=$restarting",
        )
        
        val isPasswordField = PasswordDetector.isPasswordField(info)
        
        if (isPasswordField) {
            Log.w(TAG, "Password field detected, voice input disabled")
        }
        
        inputView?.onStartInputView(info, restarting, securityLevel)
        
        // 请求重新应用 insets
        inputView?.let { view ->
            ViewCompat.requestApplyInsets(view)
        }
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "onFinishInputView")
        inputView?.onFinishInputView(finishingInput)
        super.onFinishInputView(finishingInput)
    }
    
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(
            TAG,
            "onStartInput, package=${attribute?.packageName}, inputType=${attribute?.inputType}, " +
                "privateImeOptions=${attribute?.privateImeOptions}, securityLevel=${PasswordDetector.getSecurityLevel(attribute)}",
        )
        inputView?.onStartInput(attribute)
    }
    
    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        inputView?.onFinishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        inputView?.onUpdateSelection(
            oldSelStart = oldSelStart,
            oldSelEnd = oldSelEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
        )
    }
    
    /**
     * 禁用全屏模式
     */
    override fun onEvaluateFullscreenMode(): Boolean = false
    
    /**
     * 计算键盘 insets - 关键方法，影响键盘显示
     */
    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        
        outInsets?.let { insets ->
            val rootView = inputView
            val contentTopInsets = rootView?.let(::resolveKeyboardContentTop) ?: 0
            val visibleTopInsets = contentTopInsets

            insets.contentTopInsets = contentTopInsets
            insets.visibleTopInsets = visibleTopInsets
            insets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT

            if (rootView != null) {
                val width = rootView.width.coerceAtLeast(0)
                val height = rootView.height.coerceAtLeast(0)
                lastTouchableBounds.set(0, contentTopInsets, width, height)
                insets.touchableRegion.set(lastTouchableBounds)
            } else {
                lastTouchableBounds.setEmpty()
                insets.touchableRegion.setEmpty()
            }

            lastReportedContentTopInsets = contentTopInsets
            Log.d(
                TAG,
                "onComputeInsets: contentTop=$contentTopInsets visibleTop=$visibleTopInsets " +
                    "touchable=[${lastTouchableBounds.left},${lastTouchableBounds.top}][${lastTouchableBounds.right},${lastTouchableBounds.bottom}]",
            )
        }
    }
    
    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        Log.d(TAG, "onCurrentInputMethodSubtypeChanged")
    }
    
    /**
     * 创建候选词视图 - 返回 null 表示不使用系统候选词栏
     */
    override fun onCreateCandidatesView(): View? {
        Log.d(TAG, "onCreateCandidatesView")
        // 我们在自定义视图中处理候选词，不使用系统候选词栏
        return null
    }
    
    /**
     * 更新候选词显示状态
     */
    override fun onUpdateExtractingVisibility(ev: EditorInfo?) {
        setExtractViewShown(false)
        Log.d(TAG, "onUpdateExtractingVisibility: extract view forced hidden")
    }

    private fun resolveKeyboardContentTop(view: View): Int {
        if (view.height <= 0) return 0
        val viewLocation = IntArray(2)
        view.getLocationInWindow(viewLocation)
        var contentTop = (viewLocation[1] + view.paddingTop).coerceAtLeast(0)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index) ?: continue
                if (child.visibility != View.VISIBLE || child.height <= 0) continue
                val childLocation = IntArray(2)
                child.getLocationInWindow(childLocation)
                contentTop = minOf(contentTop, childLocation[1].coerceAtLeast(0))
            }
        }
        return contentTop.coerceAtLeast(0)
    }

    private fun registerDebugReceiverIfNeeded() {
        if (!BuildConfig.DEBUG || debugReceiverRegistered) return
        val filter = IntentFilter(ACTION_DEBUG_PREVIEW_SCENARIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugPreviewReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(debugPreviewReceiver, filter)
        }
        debugReceiverRegistered = true
        Log.d(
            TAG,
            "Debug preview receiver ready. action=$ACTION_DEBUG_PREVIEW_SCENARIO mode=preview|commit|discard|voice",
        )
    }

    private fun unregisterDebugReceiverIfNeeded() {
        if (!debugReceiverRegistered) return
        unregisterReceiver(debugPreviewReceiver)
        debugReceiverRegistered = false
    }

    private fun handleDebugPreviewIntent(intent: Intent) {
        val view = inputView
        if (view == null) {
            Log.w(TAG, "Debug preview ignored: inputView is null")
            return
        }

        when (intent.getStringExtra(EXTRA_DEBUG_MODE)?.ifBlank { DEBUG_MODE_PREVIEW } ?: DEBUG_MODE_PREVIEW) {
            DEBUG_MODE_COMMIT -> {
                Log.d(TAG, "Debug preview commit requested")
                view.commitDebugPreview()
            }

            DEBUG_MODE_DISCARD -> {
                Log.d(TAG, "Debug preview discard requested")
                view.discardDebugPreview()
            }

            DEBUG_MODE_VOICE -> {
                val partialText = intent.getStringExtra(EXTRA_PARTIAL_TEXT).orEmpty()
                val finalText = intent.getStringExtra(EXTRA_FINAL_TEXT).orEmpty()
                val rewriteText = intent.getStringExtra(EXTRA_REWRITE_TEXT).orEmpty()
                val voiceOutcome =
                    intent.getStringExtra(EXTRA_VOICE_OUTCOME)?.ifBlank { DEBUG_VOICE_SUCCESS }
                        ?: DEBUG_VOICE_SUCCESS
                val stepDelayMs = intent.getLongExtra(EXTRA_STEP_DELAY_MS, 900L)
                Log.d(
                    TAG,
                    "Debug voice scenario requested: outcome=$voiceOutcome stepDelayMs=$stepDelayMs",
                )
                view.runDebugVoiceSessionScenario(
                    partialText = partialText,
                    finalText = finalText,
                    rewriteText = rewriteText,
                    outcome = voiceOutcome,
                    stepDelayMs = stepDelayMs,
                )
            }

            else -> {
                val partialText = intent.getStringExtra(EXTRA_PARTIAL_TEXT).orEmpty()
                val finalText = intent.getStringExtra(EXTRA_FINAL_TEXT).orEmpty()
                val autoCommit = intent.getBooleanExtra(EXTRA_AUTO_COMMIT, false)
                val stepDelayMs = intent.getLongExtra(EXTRA_STEP_DELAY_MS, 900L)
                Log.d(
                    TAG,
                    "Debug preview scenario requested: autoCommit=$autoCommit stepDelayMs=$stepDelayMs",
                )
                view.runDebugPreviewScenario(
                    partialText = partialText,
                    finalText = finalText,
                    autoCommit = autoCommit,
                    stepDelayMs = stepDelayMs,
                )
            }
        }
    }
    
    // ==================== 提供给 View 的接口 ====================
    
    fun getInputConnection() = currentInputConnection
    fun getCurrentEditorInfo() = currentInputEditorInfo
    fun getImeLayoutController() = layoutController

    override fun requestHideIme() {
        requestHideSelf(0)
    }

    override fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    override fun resolveCurrentSnapshot(): SessionInputSnapshot {
        val ic = currentInputConnection
        val selectedText = inputHelper.getSelectedText(ic)?.toString().orEmpty()
        val beforeText = inputHelper.getTextBeforeCursor(ic, 10000)?.toString().orEmpty()
        val afterText = inputHelper.getTextAfterCursor(ic, 10000)?.toString().orEmpty()
        val selectionStart = beforeText.length
        val selectionEnd = selectionStart + selectedText.length
        val originalText = beforeText + selectedText + afterText
        val rewriteMode = selectedText.isNotBlank()
        return SessionInputSnapshot(
            originalText = originalText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            rewriteMode = rewriteMode,
            source = if (rewriteMode) SessionRewriteSource.SELECTED_TEXT else SessionRewriteSource.DIRECT_INPUT,
        )
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        return inputHelper.commitText(currentInputConnection, text, newCursorPosition)
    }

    override fun deleteText(beforeLength: Int, afterLength: Int): Boolean {
        return inputHelper.deleteSurroundingText(currentInputConnection, beforeLength, afterLength)
    }

    override fun performEditorAction(actionId: Int): Boolean {
        val inputConnection = currentInputConnection ?: return false
        return try {
            inputConnection.performEditorAction(actionId)
        } catch (e: Throwable) {
            Log.e(TAG, "performEditorAction failed: actionId=$actionId", e)
            false
        }
    }

    override fun performEnterAction(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val actionId = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (actionId != EditorInfo.IME_ACTION_NONE &&
            actionId != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            return performEditorAction(actionId)
        }
        return commitText("\n", 1)
    }

    override fun updatePreviewText(
        snapshot: SessionInputSnapshot,
        previousPreviewText: String,
        newPreviewText: String,
    ): Boolean {
        if (newPreviewText == previousPreviewText) return true
        val inputConnection = currentInputConnection ?: return false
        return replaceRangeWithText(
            ic = inputConnection,
            snapshot = snapshot,
            previousPreviewText = previousPreviewText,
            replacementText = newPreviewText,
            commit = false,
        )
    }

    override fun commitPreviewText(
        snapshot: SessionInputSnapshot,
        previewText: String,
    ): Boolean {
        if (previewText.isEmpty()) return true
        val inputConnection = currentInputConnection ?: return false
        return replaceRangeWithText(
            ic = inputConnection,
            snapshot = snapshot,
            previousPreviewText = previewText,
            replacementText = previewText,
            commit = true,
        )
    }

    override fun discardPreviewText(
        snapshot: SessionInputSnapshot,
        previewText: String,
    ): Boolean {
        val inputConnection = currentInputConnection ?: return false
        return replaceRangeWithText(
            ic = inputConnection,
            snapshot = snapshot,
            previousPreviewText = previewText,
            replacementText = snapshot.selectedText,
            commit = true,
        )
    }

    override fun startManagedVoiceSession(
        styleMode: com.typeink.prototype.TypeinkStyleMode,
        snapshot: com.typeink.prototype.SessionInputSnapshot,
    ) {
        asrSessionManager.setListener(null)
        asrSessionManager.startSession(
            styleMode = styleMode,
            snapshot = snapshot,
        )
    }

    override fun clearManagedVoiceSession() {
        if (asrSessionManager.isRunning()) {
            asrSessionManager.forceStop()
        } else {
            dashScopeService.cancelActiveSession()
            recorder.stop()
            keyboardActionHandler.clearAll()
        }
    }

    override fun startVoiceInput(
        styleMode: com.typeink.prototype.TypeinkStyleMode,
        snapshot: com.typeink.prototype.SessionInputSnapshot,
        listener: DashScopeService.Listener,
    ) {
        dashScopeService.setStyleMode(styleMode)
        dashScopeService.startAsr(listener, snapshot)
        recorder.start(
            object : PcmRecorder.Listener {
                override fun onAudioChunk(bytes: ByteArray) {
                    dashScopeService.sendAudioChunk(bytes)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Recorder error while voice input: $message")
                }

                override fun onAmplitude(level: Float) = Unit
            },
        )
    }

    override fun startEditCommandInput(listener: TypeinkImeHost.EditCommandCallback) {
        val resolved = AtomicBoolean(false)

        fun resolveEditCommand(block: () -> Unit) {
            if (!resolved.compareAndSet(false, true)) return
            recorder.stop()
            block()
        }

        dashScopeService.startAsrForEdit(
            object : DashScopeService.EditCommandListener {
                override fun onEditCommand(command: String) {
                    resolveEditCommand {
                        listener.onEditCommand(command)
                    }
                }

                override fun onError(message: String) {
                    resolveEditCommand {
                        listener.onError(message)
                    }
                }
            },
        )
        recorder.start(
            object : PcmRecorder.Listener {
                override fun onAudioChunk(bytes: ByteArray) {
                    dashScopeService.sendAudioChunk(bytes)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Recorder error while edit command input: $message")
                    resolveEditCommand {
                        dashScopeService.cancelActiveSession()
                        listener.onError("录音异常：$message")
                    }
                }

                override fun onAmplitude(level: Float) = Unit
            },
        )
    }

    override fun stopVoiceInput() {
        if (asrSessionManager.isRunning()) {
            asrSessionManager.stopSession()
        } else {
            dashScopeService.cancelActiveSession()
            recorder.stop()
        }
    }

    override fun editByInstruction(
        currentText: String,
        instruction: String,
        listener: TypeinkImeHost.EditResultCallback,
    ) {
        dashScopeService.editByInstruction(
            currentText = currentText,
            instruction = instruction,
            listener =
                object : DashScopeService.EditListener {
                    override fun onEditResult(newText: String) {
                        listener.onEditResult(newText)
                    }

                    override fun onError(message: String) {
                        listener.onError(message)
                    }
                },
        )
    }

    private fun replaceRangeWithText(
        ic: InputConnection,
        snapshot: SessionInputSnapshot,
        previousPreviewText: String,
        replacementText: String,
        commit: Boolean,
    ): Boolean {
        val replaceStart = snapshot.preText.length
        val replaceEnd =
            if (previousPreviewText.isNotEmpty()) {
                replaceStart + previousPreviewText.length
            } else {
                snapshot.selectionEnd
            }
        val safeEnd = replaceEnd.coerceAtLeast(replaceStart)

        inputHelper.beginBatchEdit(ic)
        return try {
            inputHelper.setSelection(ic, replaceStart, safeEnd)
            val ok =
                if (commit) {
                    inputHelper.commitText(ic, replacementText, 1)
                } else {
                    inputHelper.setComposingText(ic, replacementText, 1)
                }
            if (commit) {
                inputHelper.finishComposingText(ic)
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "replaceRangeWithText failed", t)
            false
        } finally {
            inputHelper.endBatchEdit(ic)
        }
    }
}
