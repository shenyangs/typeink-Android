package com.typeink.inputmethod

import android.content.Context
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.typeink.core.input.TypeinkInputPhase
import com.typeink.core.input.TypeinkInputState
import com.typeink.core.input.TypeinkInputStateMapper
import com.typeink.core.session.TypeinkEditPhase
import com.typeink.core.session.TypeinkEditSessionReducer
import com.typeink.core.session.TypeinkEditSessionState
import com.typeink.core.session.TypeinkSessionTextReducer
import com.typeink.core.session.TypeinkSessionTextState
import com.typeink.prototype.R
import com.typeink.prototype.SessionInputSnapshot
import com.typeink.prototype.TypeinkStyleMode
import com.typeink.inputmethod.security.PasswordDetector
import com.typeink.syncclipboard.ClipboardHistoryManager

/**
 * Typeink 输入法视图。
 *
 * 当前策略：
 * - 语音主画布由 Compose 接管；
 * - 传统键盘 fallback 继续保留原生 KeyboardView；
 * - 普通语音输入优先订阅 KeyboardActionHandler 的状态更新。
 */
class TypeinkInputView(
    context: Context,
    private val host: TypeinkImeHost,
) : LinearLayout(context), KeyboardActionHandler.UiListener {

    companion object {
        private const val TAG = "TypeinkInputView"
        private const val DEBUG_VOICE_SUCCESS = "success"
        private const val DEBUG_VOICE_ERROR = "error"
        private const val DEBUG_VOICE_CANCEL = "cancel"
        private const val DEBUG_VOICE_TIMEOUT = "timeout"
    }

    enum class InputMode {
        VOICE,
        KEYBOARD,
    }

    private data class EditorSessionKey(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val imeOptions: Int,
        val privateImeOptions: String?,
    )

    private var rootContainer: FrameLayout? = null
    private var voiceComposeView: ComposeView? = null
    private val composeLifecycleOwner = ImeComposeLifecycleOwner()

    private var keyboardContainer: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var qwertyKeyboard: Keyboard? = null
    private var qwertyShiftKeyboard: Keyboard? = null
    private var symbolsKeyboard: Keyboard? = null
    private var btnBackToVoice: TextView? = null
    private var btnHideKeyboard: TextView? = null
    private var isShifted: Boolean = false
    private var isSymbols: Boolean = false

    private var isRecording: Boolean = false
    private var editSessionState: TypeinkEditSessionState = TypeinkEditSessionReducer.inactive()
    private var currentStyleMode: TypeinkStyleMode = TypeinkStyleMode.NATURAL
    private var currentMode: InputMode = InputMode.VOICE
    private var currentAmplitude: Float = 0f
    private var voiceInputEnabled: Boolean = true
    private var blockedReason: String = ""
    private var undoPreviewText: String? = null
    private var currentInputState: TypeinkInputState = TypeinkInputState(
        phase = TypeinkInputPhase.IDLE,
        hint = "点击说话",
    )
    private var sessionTextState: TypeinkSessionTextState = TypeinkSessionTextReducer.idle()
    private var activeSessionSnapshot: SessionInputSnapshot? = null
    private var hostPreviewText: String = ""
    private var currentEditorKey: EditorSessionKey? = null
    private val debugPreviewHandler = Handler(Looper.getMainLooper())
    private var debugPreviewToken: Int = 0
    private val clipboardHistoryManager = ClipboardHistoryManager.getInstance(context)
    private var isClipboardHistoryVisible: Boolean = false
    private var clipboardHistoryEntries: List<ClipboardHistoryEntry> = emptyList()

    private var shellModel by mutableStateOf(
        TypeinkImeShellModel(
            inputState = currentInputState,
            sessionTextState = sessionTextState,
            editSessionState = editSessionState,
            isKeyboardVisible = false,
            amplitude = 0f,
            voiceInputEnabled = true,
            blockedReason = "",
            hasPendingPreview = false,
            canUndoRewrite = false,
            isClipboardHistoryVisible = false,
            clipboardHistory = emptyList(),
        ),
    )

    private var currentText: String
        get() = sessionTextState.stableText
        set(value) {
            sessionTextState = sessionTextState.copy(stableText = value)
        }

    private var currentDraftText: String
        get() = sessionTextState.draftText
        set(value) {
            sessionTextState = sessionTextState.copy(draftText = value)
        }

    private var currentStreamingText: String
        get() = sessionTextState.streamingText
        set(value) {
            sessionTextState = sessionTextState.copy(streamingText = value)
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.typeink_input_view, this, true)

        initViews()
        setupCompose()
        setupKeyboard()
        showIdleState()

        Log.d(TAG, "Compose voice shell with keyboard fallback initialized")
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        voiceComposeView = findViewById(R.id.voiceComposeView)
        keyboardContainer = findViewById(R.id.keyboardContainer)
    }

    private fun setupCompose() {
        composeLifecycleOwner.attachTo(this)
        voiceComposeView?.let { composeLifecycleOwner.attachTo(it) }
        voiceComposeView?.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        installImeInsetsBridge()
        voiceComposeView?.setContent {
            TypeinkImeVoiceShell(
                model = shellModel,
                onMicTap = { handleMicTap() },
                onClear = { clearVoiceSessionAndRestoreHost() },
                onRetry = { retryCurrentFailure() },
                onKeepOriginal = { keepOriginalAfterFailure() },
                onComma = { host.commitText("，", 1) },
                onPeriod = { host.commitText("。", 1) },
                onQuestion = { host.commitText("？", 1) },
                onExclamation = { host.commitText("！", 1) },
                onBackspace = { host.deleteText(1, 0) },
                onSpace = { host.commitText(" ", 1) },
                onEnter = {
                    if (!host.performEnterAction()) {
                        host.commitText("\n", 1)
                    }
                },
                onEdit = {
                    enterEditMode()
                    startEditRecording()
                },
                onSend = {
                    val text = resolveVisibleText()
                    if (text.isNotEmpty()) {
                        val snapshot = activeSessionSnapshot
                        val committed =
                            if (snapshot != null && hostPreviewText.isNotEmpty()) {
                                host.commitPreviewText(snapshot, hostPreviewText)
                            } else {
                                host.commitText(text, 1)
                            }
                        if (!committed) {
                            host.commitText(text, 1)
                        }
                        clearHostPreviewTracking()
                        host.clearManagedVoiceSession()
                    }
                },
                onKeyboard = { toggleInputMode() },
                onHide = { host.requestHideIme() },
                onClipboardToggle = { toggleClipboardHistoryPanel() },
                onClipboardSelect = { applyClipboardHistoryEntry(it) },
                onClipboardDelete = { deleteClipboardHistoryEntry(it) },
                onClipboardClear = { clearClipboardHistory() },
                onUndoRewrite = { undoLastRewrite() },
                onCancelEdit = { exitEditMode() },
                onDoneEdit = { exitEditMode() },
            )
        }
    }

    private fun installImeInsetsBridge() {
        val composeView = voiceComposeView ?: return
        ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
            val bottomInset = ImeInsetsResolver.resolveBottomInset(insets, resources)
            applyBottomInsetPadding(bottomInset)
            WindowInsetsCompat.CONSUMED
        }
        composeView.post {
            ViewCompat.requestApplyInsets(composeView)
        }
    }

    private fun applyBottomInsetPadding(bottomInset: Int) {
        rootContainer?.setPadding(0, 0, 0, bottomInset)
    }

    private fun setupKeyboard() {
        val keyboardLayout =
            LayoutInflater.from(context).inflate(
                R.layout.keyboard_with_toolbar,
                keyboardContainer,
                true,
            )

        btnBackToVoice = keyboardLayout.findViewById(R.id.btnBackToVoice)
        btnHideKeyboard = keyboardLayout.findViewById(R.id.btnHideKeyboard)

        btnBackToVoice?.setOnClickListener { toggleInputMode() }
        btnHideKeyboard?.setOnClickListener { host.requestHideIme() }

        qwertyKeyboard = Keyboard(context, R.xml.qwerty)
        qwertyShiftKeyboard = Keyboard(context, R.xml.qwerty_shift)
        symbolsKeyboard = Keyboard(context, R.xml.symbols)

        keyboardView = keyboardLayout.findViewById<KeyboardView>(R.id.keyboardView).apply {
            keyboard = qwertyKeyboard
            isEnabled = true
            isPreviewEnabled = false

            setOnKeyboardActionListener(object : KeyboardView.OnKeyboardActionListener {
                override fun onPress(primaryCode: Int) = Unit

                override fun onRelease(primaryCode: Int) = Unit

                override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
                    handleKey(primaryCode)
                }

                override fun onText(text: CharSequence?) {
                    text?.let { host.commitText(it, 1) }
                }

                override fun swipeLeft() = Unit

                override fun swipeRight() = Unit

                override fun swipeDown() = Unit

                override fun swipeUp() = Unit
            })
        }
    }

    private fun handleMicTap() {
        if (isClipboardHistoryVisible) {
            isClipboardHistoryVisible = false
            syncShellModel()
        }
        if (!voiceInputEnabled) return
        if (editSessionState.isProcessing) return
        if (!editSessionState.isActive && currentInputState.isProcessing && !isRecording) return

        if (!isRecording) {
            if (editSessionState.isActive) {
                startEditRecording()
            } else {
                startRecording()
            }
        } else {
            stopRecording()
        }
    }

    private fun handleKey(primaryCode: Int) {
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> host.deleteText(1, 0)
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                keyboardView?.keyboard = if (isShifted) qwertyShiftKeyboard else qwertyKeyboard
            }

            Keyboard.KEYCODE_MODE_CHANGE -> {
                isSymbols = !isSymbols
                keyboardView?.keyboard = if (isSymbols) symbolsKeyboard else qwertyKeyboard
            }

            Keyboard.KEYCODE_DONE -> host.performEditorAction(EditorInfo.IME_ACTION_DONE)
            32 -> host.commitText(" ", 1)
            else -> {
                val char = primaryCode.toChar()
                host.commitText(char.toString(), 1)
                if (isShifted && keyboardView?.keyboard == qwertyShiftKeyboard) {
                    isShifted = false
                    keyboardView?.keyboard = qwertyKeyboard
                }
            }
        }
    }

    private fun updateModeUI() {
        val isKeyboardMode = currentMode == InputMode.KEYBOARD
        voiceComposeView?.visibility = if (isKeyboardMode) View.GONE else View.VISIBLE
        keyboardContainer?.visibility = if (isKeyboardMode) View.VISIBLE else View.GONE
        syncShellModel()
    }

    private fun resolveVisibleText(): String {
        return when {
            currentInputState.finalText.isNotBlank() -> currentInputState.finalText
            sessionTextState.displayFinalText.isNotBlank() -> sessionTextState.displayFinalText
            currentInputState.partialText.isNotBlank() -> currentInputState.partialText
            currentDraftText.isNotBlank() -> currentDraftText
            else -> ""
        }
    }

    private fun syncShellModel() {
        shellModel =
            TypeinkImeShellModel(
                inputState = currentInputState,
                sessionTextState = sessionTextState,
                editSessionState = editSessionState,
                isKeyboardVisible = currentMode == InputMode.KEYBOARD,
                amplitude = currentAmplitude,
                voiceInputEnabled = voiceInputEnabled,
                blockedReason = blockedReason,
                hasPendingPreview = hostPreviewText.isNotEmpty(),
                canUndoRewrite = undoPreviewText?.isNotBlank() == true && hostPreviewText.isNotEmpty() && !editSessionState.isActive,
                isClipboardHistoryVisible = isClipboardHistoryVisible,
                clipboardHistory = clipboardHistoryEntries,
            )
    }

    private fun renderVoiceState(state: KeyboardState) {
        renderVoiceState(TypeinkInputStateMapper.fromKeyboardState(state))
    }

    private fun renderVoiceState(state: TypeinkInputState) {
        currentInputState =
            state.copy(
                voiceInputEnabled = voiceInputEnabled,
                blockedReason = blockedReason,
                hasPendingCommit = state.hasPendingCommit || hostPreviewText.isNotEmpty(),
            )
        if (currentInputState.finalText.isNotBlank()) {
            currentText = currentInputState.finalText
        }
        syncShellModel()
    }

    private fun toggleInputMode() {
        isClipboardHistoryVisible = false
        currentMode = if (currentMode == InputMode.VOICE) InputMode.KEYBOARD else InputMode.VOICE
        updateModeUI()
    }

    private fun showIdleState() {
        if (hostPreviewText.isNotEmpty()) {
            restoreOriginalHostText()
        }
        refreshClipboardHistory()
        isClipboardHistoryVisible = false
        currentMode = InputMode.VOICE
        sessionTextState = TypeinkSessionTextReducer.idle()
        editSessionState = TypeinkEditSessionReducer.inactive()
        isRecording = false
        currentAmplitude = 0f
        undoPreviewText = null
        renderVoiceState(
            TypeinkInputState(
                phase = TypeinkInputPhase.IDLE,
                hint = if (voiceInputEnabled) "点击光球，开始捕捉下一句。" else blockedReason,
                voiceInputEnabled = voiceInputEnabled,
                blockedReason = blockedReason,
            ),
        )
        updateModeUI()
    }

    private fun refreshClipboardHistory() {
        clipboardHistoryEntries =
            clipboardHistoryManager
                .getAllHistory()
                .map {
                    ClipboardHistoryEntry(
                        id = it.id,
                        text = it.text,
                        timeDescription = it.timeDescription,
                    )
                }
    }

    private fun toggleClipboardHistoryPanel() {
        refreshClipboardHistory()
        isClipboardHistoryVisible = !isClipboardHistoryVisible
        syncShellModel()
    }

    private fun applyClipboardHistoryEntry(entry: ClipboardHistoryEntry) {
        abandonPendingSession(resetUi = true)
        isClipboardHistoryVisible = false
        host.commitText(entry.text, 1)
        refreshClipboardHistory()
        syncShellModel()
    }

    private fun deleteClipboardHistoryEntry(entry: ClipboardHistoryEntry) {
        clipboardHistoryManager.removeHistory(entry.id)
        refreshClipboardHistory()
        if (clipboardHistoryEntries.isEmpty()) {
            isClipboardHistoryVisible = false
        }
        syncShellModel()
    }

    private fun clearClipboardHistory() {
        clipboardHistoryManager.clearHistory()
        refreshClipboardHistory()
        isClipboardHistoryVisible = false
        syncShellModel()
    }

    private fun showEditRecordingState() {
        editSessionState = TypeinkEditSessionReducer.listening(editSessionState)
        sessionTextState = TypeinkSessionTextReducer.beginEdit(currentText)
        renderVoiceState(
            KeyboardState.Listening(
                hint = "请说修改指令...",
            ),
        )
    }

    private fun showProcessingState() {
        sessionTextState = TypeinkSessionTextReducer.beginRewriting(sessionTextState)
        editSessionState = TypeinkEditSessionReducer.rewriting(editSessionState, editSessionState.lastInstruction)
        currentAmplitude = 0f
        renderVoiceState(
            TypeinkInputState(
                phase = TypeinkInputPhase.REWRITING,
                hint = "正在理解你的修改意图...",
                finalText = sessionTextState.displayFinalText,
            ),
        )
    }

    private fun showResultState(result: String) {
        val originalDraft = currentDraftText
        sessionTextState = TypeinkSessionTextReducer.applyFinalResult(sessionTextState, result)
        editSessionState = TypeinkEditSessionReducer.ready(editSessionState)
        currentAmplitude = 0f
        renderVoiceState(
            KeyboardState.Succeeded(
                finalText = result,
                originalText = originalDraft,
                isRewritten = true,
                hint = "已完成",
            ),
        )
    }

    private fun enterEditMode() {
        if (!voiceInputEnabled) return
        if (currentText.isBlank()) return
        editSessionState = TypeinkEditSessionReducer.primed()
        syncShellModel()
    }

    private fun exitEditMode() {
        editSessionState = TypeinkEditSessionReducer.inactive()
        if (isRecording) {
            stopRecording()
        }
        syncShellModel()
    }

    private fun startRecording() {
        if (isRecording) return
        val snapshot = host.resolveCurrentSnapshot()
        activeSessionSnapshot = snapshot
        hostPreviewText = ""
        undoPreviewText = null
        host.startManagedVoiceSession(currentStyleMode, snapshot)
    }

    private fun startEditRecording() {
        if (isRecording || currentText.isEmpty()) return

        isRecording = true
        showEditRecordingState()

        host.startEditCommandInput(object : TypeinkImeHost.EditCommandCallback {
            override fun onEditCommand(command: String) {
                post {
                    isRecording = false
                    host.stopVoiceInput()
                    editSessionState = TypeinkEditSessionReducer.rewriting(editSessionState, command)
                    applyEditByLLM(command)
                }
            }

            override fun onError(message: String) {
                post {
                    isRecording = false
                    editSessionState = TypeinkEditSessionReducer.failed(editSessionState, message)
                    currentAmplitude = 0f
                    renderVoiceState(
                        TypeinkInputState(
                            phase = TypeinkInputPhase.FAILED,
                            hint = "没听清，再试一次。",
                            finalText = currentText,
                            errorMessage = message,
                        ),
                    )
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (editSessionState.isActive && editSessionState.canRetry && !isRecording) {
                            startEditRecording()
                        }
                    }, 1500)
                }
            }
        })
    }

    private fun stopRecording() {
        if (!isRecording) return
        host.stopVoiceInput()
    }

    private fun clearVoiceSessionAndRestoreHost() {
        restoreOriginalHostText()
        host.clearManagedVoiceSession()
    }

    private fun restoreOriginalHostText() {
        val snapshot = activeSessionSnapshot ?: return
        if (hostPreviewText.isNotEmpty()) {
            host.discardPreviewText(snapshot, hostPreviewText)
        }
        clearHostPreviewTracking()
        undoPreviewText = null
    }

    private fun clearHostPreviewTracking() {
        hostPreviewText = ""
        activeSessionSnapshot = null
    }

    private fun retryCurrentFailure() {
        if (editSessionState.isActive) {
            val lastInstruction = editSessionState.lastInstruction.trim()
            if (lastInstruction.isNotEmpty()) {
                applyEditByLLM(lastInstruction)
            } else {
                startEditRecording()
            }
            return
        }

        if (hostPreviewText.isNotEmpty()) {
            restoreOriginalHostText()
        }
        startRecording()
    }

    private fun keepOriginalAfterFailure() {
        if (!editSessionState.isActive) {
            abandonPendingSession(resetUi = true)
            return
        }

        isRecording = false
        currentAmplitude = 0f
        editSessionState = TypeinkEditSessionReducer.inactive()
        renderVoiceState(
            if (hostPreviewText.isNotBlank()) {
                TypeinkInputState(
                    phase = TypeinkInputPhase.PREVIEW_READY,
                    hint = "已保留修改前文本，可继续发送或再次修改。",
                    finalText = hostPreviewText,
                    partialText = currentText,
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                    hasPendingCommit = true,
                )
            } else {
                TypeinkInputState(
                    phase = TypeinkInputPhase.IDLE,
                    hint = if (voiceInputEnabled) "已放弃本轮修改。" else blockedReason,
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                )
            },
        )
    }

    private fun undoLastRewrite() {
        val snapshot = activeSessionSnapshot ?: return
        val revertText = undoPreviewText?.takeIf { it.isNotBlank() } ?: return
        if (!host.updatePreviewText(snapshot, hostPreviewText, revertText)) return
        hostPreviewText = revertText
        sessionTextState = TypeinkSessionTextReducer.applyFinalResult(sessionTextState, revertText)
        renderVoiceState(
            TypeinkInputState(
                phase = TypeinkInputPhase.PREVIEW_READY,
                hint = "已恢复到改写前，可继续修改或发送。",
                partialText = revertText,
                finalText = revertText,
                voiceInputEnabled = voiceInputEnabled,
                blockedReason = blockedReason,
                hasPendingCommit = true,
            ),
        )
        undoPreviewText = null
    }

    private fun syncHostPreviewFromState(state: KeyboardState) {
        if (editSessionState.isActive) return
        val snapshot = activeSessionSnapshot ?: return
        val nextPreviewText =
            when (state) {
                is KeyboardState.Processing -> state.partialText
                is KeyboardState.Rewriting -> state.finalText.ifBlank { state.partialText }
                is KeyboardState.Succeeded -> state.finalText
                else -> hostPreviewText
            }.trim()

        if (nextPreviewText.isBlank() || nextPreviewText == hostPreviewText) {
            return
        }

        if (host.updatePreviewText(snapshot, hostPreviewText, nextPreviewText)) {
            hostPreviewText = nextPreviewText
            if (state is KeyboardState.Succeeded) {
                undoPreviewText = state.originalText.ifBlank { snapshot.selectedText }
            }
        }
    }

    private fun applyEditByLLM(command: String) {
        val baseText = currentText
        editSessionState = TypeinkEditSessionReducer.rewriting(editSessionState, command)
        showProcessingState()

        host.editByInstruction(
            currentText = currentText,
            instruction = command,
            listener = object : TypeinkImeHost.EditResultCallback {
                override fun onEditResult(newText: String) {
                    post {
                        isRecording = false
                        sessionTextState =
                            TypeinkSessionTextReducer.applyFinalResult(sessionTextState, newText)
                        editSessionState = TypeinkEditSessionReducer.ready(editSessionState)
                        val snapshot = activeSessionSnapshot
                        if (snapshot != null && host.updatePreviewText(snapshot, hostPreviewText, newText)) {
                            hostPreviewText = newText
                        }
                        undoPreviewText = baseText
                        renderVoiceState(
                            TypeinkInputState(
                                phase = TypeinkInputPhase.PREVIEW_READY,
                                hint = "修改结果已进入预编辑区，点发送正式上屏。",
                                finalText = newText,
                                partialText = baseText,
                                voiceInputEnabled = voiceInputEnabled,
                                blockedReason = blockedReason,
                                hasPendingCommit = true,
                            ),
                        )
                    }
                }

                override fun onError(message: String) {
                    post {
                        isRecording = false
                        editSessionState = TypeinkEditSessionReducer.failed(editSessionState, message)
                        renderVoiceState(
                            TypeinkInputState(
                                phase = TypeinkInputPhase.FAILED,
                                hint = "修改失败，再试一次。",
                                finalText = currentText,
                                errorMessage = message,
                                voiceInputEnabled = voiceInputEnabled,
                                blockedReason = blockedReason,
                            ),
                        )
                    }
                }
            },
        )
    }

    fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
        securityLevel: PasswordDetector.SecurityLevel,
    ) {
        val nextEditorKey = buildEditorSessionKey(info)
        val sameEditor = currentEditorKey != null && currentEditorKey == nextEditorKey
        currentEditorKey = nextEditorKey
        val securityChanged = applySecurityLevel(securityLevel)

        if (!voiceInputEnabled) {
            abandonPendingSession(resetUi = true)
            return
        }

        if (securityChanged && !shouldPreserveSessionState()) {
            showIdleState()
            return
        }

        if (sameEditor && shouldPreserveSessionState()) {
            activeSessionSnapshot = host.resolveCurrentSnapshot()
            renderVoiceState(currentInputState)
            updateModeUI()
            return
        }

        if (!sameEditor) {
            abandonPendingSession(resetUi = true)
            return
        }

        showIdleState()
    }

    fun onFinishInputView(finishingInput: Boolean) {
        if (finishingInput) {
            abandonPendingSession(resetUi = true)
            return
        }
        if (isRecording) stopRecording()
    }

    fun onStartInput(attribute: EditorInfo?) {
        val nextEditorKey = buildEditorSessionKey(attribute)
        val securityLevel = PasswordDetector.getSecurityLevel(attribute)
        val securityChanged = applySecurityLevel(securityLevel)
        if (currentEditorKey != null && nextEditorKey != null && currentEditorKey != nextEditorKey) {
            abandonPendingSession(resetUi = true)
        } else if (securityChanged && !shouldPreserveSessionState()) {
            showIdleState()
        }
        currentEditorKey = nextEditorKey
    }

    fun onFinishInput() {
        abandonPendingSession(resetUi = true)
        currentEditorKey = null
    }

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        if (oldSelStart == newSelStart &&
            oldSelEnd == newSelEnd &&
            candidatesStart < 0 &&
            candidatesEnd < 0
        ) {
            return
        }
        if (isRecording) {
            return
        }

        val snapshot = activeSessionSnapshot ?: return
        val refreshedSnapshot = host.resolveCurrentSnapshot()
        val previewStillPresent =
            hostPreviewText.isBlank() || refreshedSnapshot.originalText.contains(hostPreviewText)

        if (!previewStillPresent) {
            clearHostPreviewTracking()
            undoPreviewText = null
            renderVoiceState(
                currentInputState.copy(
                    hint = "宿主输入框已手动调整，当前预编辑已交给应用。",
                    hasPendingCommit = false,
                ),
            )
            return
        }

        if (snapshot.selectionStart != refreshedSnapshot.selectionStart ||
            snapshot.selectionEnd != refreshedSnapshot.selectionEnd ||
            snapshot.originalText != refreshedSnapshot.originalText
        ) {
            activeSessionSnapshot = refreshedSnapshot
            syncShellModel()
        }
    }

    fun dispose() {
        abandonPendingSession(resetUi = false)
        debugPreviewHandler.removeCallbacksAndMessages(null)
        composeLifecycleOwner.destroy()
    }

    fun runDebugPreviewScenario(
        partialText: String,
        finalText: String,
        autoCommit: Boolean,
        stepDelayMs: Long,
    ) {
        post {
            if (!voiceInputEnabled) {
                Log.w(TAG, "Debug preview ignored: current field blocks voice input")
                return@post
            }

            val normalizedPartial = partialText.ifBlank { "这是调试注入的预编辑文本" }
            val normalizedFinal = finalText.ifBlank { "这是调试注入的最终结果文本" }
            val safeDelay = stepDelayMs.coerceIn(150L, 2500L)

            debugPreviewHandler.removeCallbacksAndMessages(null)
            debugPreviewToken += 1
            val token = debugPreviewToken

            abandonPendingSession(resetUi = false)
            currentMode = InputMode.VOICE
            isRecording = false
            currentAmplitude = 0f
            editSessionState = TypeinkEditSessionReducer.inactive()

            val snapshot = host.resolveCurrentSnapshot()
            activeSessionSnapshot = snapshot
            hostPreviewText = ""
            undoPreviewText = snapshot.selectedText.takeIf { it.isNotBlank() }
            sessionTextState = TypeinkSessionTextReducer.beginVoiceInput(snapshot.selectedText)

            renderVoiceState(
                TypeinkInputState(
                    phase = TypeinkInputPhase.PREPARING,
                    hint = "调试注入：准备把预编辑写入宿主输入框。",
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                ),
            )
            updateModeUI()

            if (!applyDebugPreviewText(
                    snapshot = snapshot,
                    nextPreviewText = normalizedPartial,
                    phase = TypeinkInputPhase.LISTENING,
                    hint = "调试注入：预编辑文本已写入宿主。",
                    partialForState = normalizedPartial,
                    finalForState = "",
                )
            ) {
                return@post
            }

            debugPreviewHandler.postDelayed({
                if (token == debugPreviewToken &&
                    applyDebugPreviewText(
                        snapshot = snapshot,
                        nextPreviewText = normalizedFinal,
                        phase = TypeinkInputPhase.PREVIEW_READY,
                        hint = "调试注入：最终结果已进入预编辑区，等待上屏。",
                        partialForState = normalizedPartial,
                        finalForState = normalizedFinal,
                    )
                ) {
                    if (autoCommit) {
                        debugPreviewHandler.postDelayed({
                            if (token == debugPreviewToken) {
                                commitDebugPreview()
                            }
                        }, safeDelay)
                    }
                }
            }, safeDelay)
        }
    }

    fun commitDebugPreview() {
        post {
            debugPreviewToken += 1
            debugPreviewHandler.removeCallbacksAndMessages(null)
            val snapshot = activeSessionSnapshot
            if (snapshot == null || hostPreviewText.isBlank()) {
                Log.w(TAG, "Debug preview commit ignored: no pending preview")
                return@post
            }

            val committedText = hostPreviewText
            if (!host.commitPreviewText(snapshot, committedText)) {
                renderDebugPreviewFailure("调试注入：正式提交失败。")
                return@post
            }

            clearHostPreviewTracking()
            renderVoiceState(
                TypeinkInputState(
                    phase = TypeinkInputPhase.APPLIED,
                    hint = "调试注入：结果已正式上屏。",
                    finalText = committedText,
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                ),
            )
        }
    }

    fun discardDebugPreview() {
        post {
            debugPreviewToken += 1
            debugPreviewHandler.removeCallbacksAndMessages(null)
            val snapshot = activeSessionSnapshot
            if (snapshot == null || hostPreviewText.isBlank()) {
                Log.w(TAG, "Debug preview discard ignored: no pending preview")
                return@post
            }

            if (!host.discardPreviewText(snapshot, hostPreviewText)) {
                renderDebugPreviewFailure("调试注入：撤回预编辑失败。")
                return@post
            }

            clearHostPreviewTracking()
            undoPreviewText = null
            sessionTextState = TypeinkSessionTextReducer.idle()
            renderVoiceState(
                TypeinkInputState(
                    phase = TypeinkInputPhase.CANCELLED,
                    hint = "调试注入：预编辑已撤回，宿主恢复原文。",
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                ),
            )
            updateModeUI()
        }
    }

    fun runDebugVoiceSessionScenario(
        partialText: String,
        finalText: String,
        rewriteText: String,
        outcome: String,
        stepDelayMs: Long,
    ) {
        post {
            if (!voiceInputEnabled) {
                Log.w(TAG, "Debug voice scenario ignored: current field blocks voice input")
                return@post
            }

            val normalizedPartial = partialText.ifBlank { "这是调试注入的语音识别片段" }
            val normalizedFinal = finalText.ifBlank { normalizedPartial }
            val normalizedRewrite = rewriteText.ifBlank { normalizedFinal }
            val normalizedOutcome =
                when (outcome.lowercase()) {
                    DEBUG_VOICE_ERROR -> DEBUG_VOICE_ERROR
                    DEBUG_VOICE_CANCEL -> DEBUG_VOICE_CANCEL
                    DEBUG_VOICE_TIMEOUT -> DEBUG_VOICE_TIMEOUT
                    else -> DEBUG_VOICE_SUCCESS
                }
            val safeDelay = stepDelayMs.coerceIn(150L, 2500L)

            debugPreviewHandler.removeCallbacksAndMessages(null)
            debugPreviewToken += 1
            val token = debugPreviewToken

            abandonPendingSession(resetUi = false)
            currentMode = InputMode.VOICE
            isRecording = false
            currentAmplitude = 0f
            editSessionState = TypeinkEditSessionReducer.inactive()

            val snapshot = host.resolveCurrentSnapshot()
            activeSessionSnapshot = snapshot
            hostPreviewText = ""
            undoPreviewText = null
            sessionTextState = TypeinkSessionTextReducer.beginVoiceInput(snapshot.selectedText)

            renderVoiceState(
                TypeinkInputState(
                    phase = TypeinkInputPhase.PREPARING,
                    hint = "调试注入：准备模拟语音会话。",
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                ),
            )
            updateModeUI()

            var accumulatedDelay = 0L
            fun schedule(stepDelay: Long, block: () -> Unit) {
                debugPreviewHandler.postDelayed({
                    if (token != debugPreviewToken) return@postDelayed
                    block()
                }, stepDelay)
            }

            schedule(accumulatedDelay) {
                isRecording = true
                currentAmplitude = 0.18f
                renderVoiceState(
                    TypeinkInputState(
                        phase = TypeinkInputPhase.LISTENING,
                        hint = "调试注入：模拟收音中。",
                        isRecording = true,
                        voiceInputEnabled = voiceInputEnabled,
                        blockedReason = blockedReason,
                    ),
                )
            }

            accumulatedDelay += safeDelay
            schedule(accumulatedDelay) {
                currentAmplitude = 0.24f
                applyDebugPreviewText(
                    snapshot = snapshot,
                    nextPreviewText = normalizedPartial,
                    phase = TypeinkInputPhase.PROCESSING,
                    hint = "调试注入：模拟 ASR partial 已写入宿主。",
                    partialForState = normalizedPartial,
                    finalForState = "",
                )
            }

            accumulatedDelay += safeDelay
            schedule(accumulatedDelay) {
                isRecording = false
                currentAmplitude = 0f
                if (!applyDebugPreviewText(
                        snapshot = snapshot,
                        nextPreviewText = normalizedFinal,
                        phase = TypeinkInputPhase.REWRITING,
                        hint = "调试注入：模拟 ASR final，开始润色。",
                        partialForState = normalizedFinal,
                        finalForState = "",
                    )
                ) {
                    return@schedule
                }
                undoPreviewText = normalizedFinal
            }

            when (normalizedOutcome) {
                DEBUG_VOICE_CANCEL -> {
                    accumulatedDelay += safeDelay
                    schedule(accumulatedDelay) {
                        if (hostPreviewText.isNotBlank()) {
                            host.discardPreviewText(snapshot, hostPreviewText)
                        }
                        clearHostPreviewTracking()
                        undoPreviewText = null
                        sessionTextState = TypeinkSessionTextReducer.idle()
                        isRecording = false
                        currentAmplitude = 0f
                        renderVoiceState(
                            TypeinkInputState(
                                phase = TypeinkInputPhase.CANCELLED,
                                hint = "调试注入：模拟取消，宿主已恢复原文。",
                                voiceInputEnabled = voiceInputEnabled,
                                blockedReason = blockedReason,
                            ),
                        )
                        updateModeUI()
                    }
                }

                DEBUG_VOICE_ERROR,
                DEBUG_VOICE_TIMEOUT -> {
                    accumulatedDelay += safeDelay
                    schedule(accumulatedDelay) {
                        val message =
                            if (normalizedOutcome == DEBUG_VOICE_TIMEOUT) {
                                "调试注入：模拟处理超时，当前保留识别草稿。"
                            } else {
                                "调试注入：模拟润色失败，当前保留识别草稿。"
                            }
                        renderVoiceState(
                            TypeinkInputState(
                                phase = TypeinkInputPhase.FAILED,
                                hint = message,
                                partialText = normalizedFinal,
                                finalText = hostPreviewText.ifBlank { normalizedFinal },
                                errorMessage = message,
                                voiceInputEnabled = voiceInputEnabled,
                                blockedReason = blockedReason,
                            ),
                        )
                    }
                }

                else -> {
                    val streamFrames = buildDebugRewriteFrames(normalizedRewrite)
                    streamFrames.forEachIndexed { index, frame ->
                        accumulatedDelay += safeDelay
                        schedule(accumulatedDelay) {
                            val isLast = index == streamFrames.lastIndex
                            applyDebugPreviewText(
                                snapshot = snapshot,
                                nextPreviewText = frame,
                                phase = if (isLast) TypeinkInputPhase.PREVIEW_READY else TypeinkInputPhase.REWRITING,
                                hint =
                                    if (isLast) {
                                        "调试注入：模拟 LLM 完成，等待上屏。"
                                    } else {
                                        "调试注入：模拟 LLM 流式改写中。"
                                    },
                                partialForState = normalizedFinal,
                                finalForState = frame,
                            )
                            if (isLast) {
                                undoPreviewText = normalizedFinal
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        composeLifecycleOwner.attachToHierarchy(this)
        rootView?.let { composeLifecycleOwner.attachToHierarchy(it) }
        voiceComposeView?.let { composeLifecycleOwner.attachToHierarchy(it) }
        voiceComposeView?.rootView?.let { composeLifecycleOwner.attachToHierarchy(it) }
        composeLifecycleOwner.onViewAttached()
    }

    override fun onDetachedFromWindow() {
        composeLifecycleOwner.onViewDetached()
        super.onDetachedFromWindow()
    }

    private fun syncSessionTextStateFromKeyboardState(state: KeyboardState) {
        sessionTextState =
            when (state) {
                is KeyboardState.Idle,
                is KeyboardState.Cancelled -> TypeinkSessionTextReducer.idle()

                is KeyboardState.Preparing,
                is KeyboardState.Listening -> TypeinkSessionTextReducer.beginVoiceInput()

                is KeyboardState.Processing ->
                    TypeinkSessionTextReducer.updateDraft(sessionTextState, state.partialText)

                is KeyboardState.Rewriting ->
                    sessionTextState.copy(
                        draftText = state.partialText,
                        streamingText = state.finalText,
                    )

                is KeyboardState.Succeeded ->
                    TypeinkSessionTextReducer.applyFinalResult(sessionTextState, state.finalText)

                is KeyboardState.Failed -> TypeinkSessionTextReducer.keepStableText(sessionTextState)
            }
    }

    override fun onStateChanged(state: KeyboardState) {
        post {
            syncSessionTextStateFromKeyboardState(state)
            isRecording = state.isRecording
            currentAmplitude = if (state is KeyboardState.Listening) state.currentAmplitude else 0f

            if (!editSessionState.isActive) {
                if (state is KeyboardState.Idle || state is KeyboardState.Cancelled) {
                    editSessionState = TypeinkEditSessionReducer.inactive()
                }
            } else if (state is KeyboardState.Failed && editSessionState.phase == TypeinkEditPhase.REWRITING) {
                editSessionState = TypeinkEditSessionReducer.failed(editSessionState, state.error)
            }

            renderVoiceState(state)
            syncHostPreviewFromState(state)
        }
    }

    override fun onStatusMessage(message: String) = Unit

    override fun onVibrate() = Unit

    override fun onAmplitude(amplitude: Float) {
        post {
            currentAmplitude = amplitude
            syncShellModel()
        }
    }

    override fun onShowClipboardPreview(preview: ClipboardPreview) = Unit

    override fun onHideClipboardPreview() = Unit

    private fun buildEditorSessionKey(info: EditorInfo?): EditorSessionKey? {
        if (info == null) return null
        return EditorSessionKey(
            packageName = info.packageName ?: "",
            fieldId = info.fieldId,
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            privateImeOptions = info.privateImeOptions,
        )
    }

    private fun applySecurityLevel(securityLevel: PasswordDetector.SecurityLevel): Boolean {
        val nextVoiceInputEnabled = securityLevel == PasswordDetector.SecurityLevel.NORMAL
        val nextBlockedReason =
            when (securityLevel) {
                PasswordDetector.SecurityLevel.HIGH -> "密码或高敏感输入已禁用语音，请改用键盘。"
                PasswordDetector.SecurityLevel.MEDIUM -> "当前是敏感输入场景，已禁用语音。"
                PasswordDetector.SecurityLevel.NORMAL -> ""
            }
        val changed =
            voiceInputEnabled != nextVoiceInputEnabled ||
                blockedReason != nextBlockedReason
        voiceInputEnabled = nextVoiceInputEnabled
        blockedReason = nextBlockedReason
        return changed
    }

    private fun applyDebugPreviewText(
        snapshot: SessionInputSnapshot,
        nextPreviewText: String,
        phase: TypeinkInputPhase,
        hint: String,
        partialForState: String,
        finalForState: String,
    ): Boolean {
        if (!host.updatePreviewText(snapshot, hostPreviewText, nextPreviewText)) {
            renderDebugPreviewFailure("调试注入：宿主预编辑写回失败。")
            return false
        }

        hostPreviewText = nextPreviewText
        sessionTextState =
            if (phase == TypeinkInputPhase.LISTENING || phase == TypeinkInputPhase.PROCESSING) {
                TypeinkSessionTextReducer.updateDraft(sessionTextState, nextPreviewText)
            } else {
                TypeinkSessionTextReducer.applyFinalResult(sessionTextState, nextPreviewText)
            }

        renderVoiceState(
            TypeinkInputState(
                phase = phase,
                hint = hint,
                partialText = partialForState,
                finalText = finalForState,
                voiceInputEnabled = voiceInputEnabled,
                blockedReason = blockedReason,
                hasPendingCommit = phase == TypeinkInputPhase.PREVIEW_READY,
            ),
        )
        return true
    }

    private fun buildDebugRewriteFrames(text: String): List<String> {
        val normalized = text.ifBlank { "这是调试注入的最终润色文本" }
        if (normalized.length <= 4) return listOf(normalized)
        val chunkSize = ((normalized.length + 2) / 3).coerceAtLeast(2)
        val frames = mutableListOf<String>()
        var index = 0
        while (index < normalized.length) {
            val end = (index + chunkSize).coerceAtMost(normalized.length)
            frames += normalized.substring(0, end)
            index = end
        }
        return frames
    }

    private fun renderDebugPreviewFailure(message: String) {
        renderVoiceState(
            TypeinkInputState(
                phase = TypeinkInputPhase.FAILED,
                hint = message,
                errorMessage = message,
                finalText = hostPreviewText,
                voiceInputEnabled = voiceInputEnabled,
                blockedReason = blockedReason,
            ),
        )
    }

    private fun shouldPreserveSessionState(): Boolean {
        return isRecording ||
            hostPreviewText.isNotBlank() ||
            editSessionState.isActive ||
            currentInputState.phase != TypeinkInputPhase.IDLE
    }

    private fun abandonPendingSession(resetUi: Boolean) {
        val hadPendingPreview = hostPreviewText.isNotEmpty()
        if (hadPendingPreview) {
            restoreOriginalHostText()
        } else {
            clearHostPreviewTracking()
            undoPreviewText = null
        }

        if (isRecording || editSessionState.isActive || currentInputState.isProcessing) {
            host.clearManagedVoiceSession()
        }

        isRecording = false
        currentAmplitude = 0f
        editSessionState = TypeinkEditSessionReducer.inactive()
        currentMode = InputMode.VOICE

        if (resetUi) {
            sessionTextState = TypeinkSessionTextReducer.idle()
            undoPreviewText = null
            renderVoiceState(
                TypeinkInputState(
                    phase = TypeinkInputPhase.IDLE,
                    hint = if (voiceInputEnabled) "点击光球，开始捕捉下一句。" else blockedReason,
                    voiceInputEnabled = voiceInputEnabled,
                    blockedReason = blockedReason,
                ),
            )
            updateModeUI()
        } else {
            syncShellModel()
        }
    }
}
