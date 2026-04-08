package com.typeink.prototype

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.typeink.core.input.TypeinkInputPhase
import com.typeink.core.input.TypeinkInputStateMapper
import com.typeink.syncclipboard.ClipboardHistoryTracker

class MainActivity : AppCompatActivity() {
    private lateinit var homeInputCoordinator: TypeinkHomeInputCoordinator
    private lateinit var viewModel: TypeinkViewModel

    private lateinit var backendStatusView: TextView
    private lateinit var statusSummaryView: TextView
    private lateinit var statusHintView: TextView
    private lateinit var statusWaveView: AudioWaveView
    private lateinit var editorInput: EditText
    private lateinit var asrTextView: TextView
    private lateinit var llmTextView: TextView
    private lateinit var styleNaturalView: TextView
    private lateinit var styleFormalView: TextView
    private lateinit var micButton: ImageButton
    private lateinit var imeSetupStatusView: TextView
    private lateinit var secondaryActionButton: ImageButton

    private var pendingStartAfterPermission = false
    private var recordingStartTime: Long = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingStartAfterPermission) {
                    pendingStartAfterPermission = false
                    startVoiceInput()
                }
            } else {
                pendingStartAfterPermission = false
                // 权限被拒绝，显示引导对话框
                showPermissionDeniedDialog()
            }
        }
    
    /**
     * 显示权限被拒绝后的引导对话框
     */
    private fun showPermissionDeniedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要麦克风权限")
            .setMessage("语音输入需要麦克风权限才能工作。请在设置中开启权限。")
            .setPositiveButton("去设置") { _, _ ->
                // 跳转到应用设置页
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消") { _, _ ->
                viewModel.setPhase(TypeinkUiPhase.FAILED, "麦克风权限被拒绝，语音输入不可用")
            }
            .setCancelable(false)
            .show()
    }

    private val homeInputListener =
        object : TypeinkHomeInputCoordinator.Listener {
            override fun onLocalDraftText(text: String) {
                runOnUiThread {
                    val snapshot = viewModel.getCurrentSnapshot()
                    if (viewModel.isRecording.value != true || viewModel.isBackendAsrStarted()) return@runOnUiThread
                    viewModel.setAsrText(text)
                    if (!snapshot.rewriteMode) {
                        applyAsrDraftToEditor(text)
                    }
                }
            }

            override fun onLocalDraftError(message: String) {
                runOnUiThread {
                    if (!viewModel.isBackendAsrStarted() && viewModel.isRecording.value == true) {
                        viewModel.setPhase(TypeinkUiPhase.LISTENING, TypeinkUiText.friendlyLocalDraftIssue(message))
                    }
                }
            }

            override fun onLocalDraftUnavailable() {
                runOnUiThread {
                    if (!viewModel.isBackendAsrStarted() && viewModel.isRecording.value == true) {
                        viewModel.setPhase(TypeinkUiPhase.LISTENING, "这台设备暂时不支持本地草稿，继续等待云端识别。")
                    }
                }
            }

            override fun onAsrPartial(text: String) {
                runOnUiThread {
                    viewModel.setBackendAsrStarted(true)
                    val snapshot = viewModel.getCurrentSnapshot()
                    viewModel.setPhase(TypeinkUiPhase.LISTENING, listeningHint(snapshot))
                    viewModel.setAsrText(text)
                    if (!snapshot.rewriteMode) {
                        applyAsrDraftToEditor(text)
                    }
                }
            }

            override fun onAsrFinal(text: String) {
                runOnUiThread {
                    viewModel.setBackendAsrStarted(true)
                    val snapshot = viewModel.getCurrentSnapshot()
                    viewModel.setPhase(TypeinkUiPhase.REWRITING, rewritingHint(snapshot))
                    viewModel.setAsrText(text)
                    if (!snapshot.rewriteMode) {
                        applyAsrDraftToEditor(text)
                    }
                }
            }

            override fun onLlmDelta(token: String) {
                runOnUiThread {
                    val snapshot = viewModel.getCurrentSnapshot()
                    viewModel.setPhase(TypeinkUiPhase.REWRITING, rewritingHint(snapshot))
                    viewModel.appendLlmBuffer(token)
                    viewModel.setLlmText(viewModel.getLlmBuffer())
                }
            }

            override fun onLlmCompleted(finalText: String) {
                runOnUiThread {
                    viewModel.setLlmText(finalText)
                    applyFinalTextToEditor(finalText)
                    stopRecordingUiOnly()
                    viewModel.setErrorMessage("")
                    viewModel.setPhase(TypeinkUiPhase.APPLIED, "结果已经写回，你可以继续说或直接发送。")
                }
            }

            override fun onCaptureError(message: String) {
                runOnUiThread {
                    stopRecordingUiOnly()
                    viewModel.setErrorMessage("麦克风采集失败，请再试一次。")
                    viewModel.setPhase(TypeinkUiPhase.FAILED, "麦克风采集失败，请再试一次。")
                }
            }

            override fun onAmplitude(level: Float) {
                runOnUiThread {
                    statusWaveView.setAmplitude(level)
                }
            }

            override fun onBackendError(message: String) {
                runOnUiThread {
                    val friendly = TypeinkUiText.friendlyBackendError(message)
                    viewModel.setErrorMessage(friendly)
                    viewModel.setPhase(TypeinkUiPhase.FAILED, friendly)
                    stopRecordingUiOnly()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[TypeinkViewModel::class.java]
        homeInputCoordinator = TypeinkHomeInputCoordinator(this)
        ClipboardHistoryTracker.start(this)

        backendStatusView = findViewById(R.id.backendStatus)
        statusSummaryView = findViewById(R.id.statusSummary)
        statusHintView = findViewById(R.id.statusHint)
        statusWaveView = findViewById(R.id.statusWave)
        editorInput = findViewById(R.id.editorInput)
        asrTextView = findViewById(R.id.asrText)
        llmTextView = findViewById(R.id.llmText)
        styleNaturalView = findViewById(R.id.styleNatural)
        styleFormalView = findViewById(R.id.styleFormal)
        micButton = findViewById(R.id.micButton)
        imeSetupStatusView = findViewById(R.id.imeSetupStatus)
        secondaryActionButton = findViewById(R.id.secondaryActionButton)
        editorInput.clearFocus()

        setupObservers()

        styleNaturalView.setOnClickListener { selectStyle(TypeinkStyleMode.NATURAL) }
        styleFormalView.setOnClickListener { selectStyle(TypeinkStyleMode.FORMAL) }
        findViewById<Button>(R.id.enableImeButton).setOnClickListener { openInputMethodSettings() }
        findViewById<Button>(R.id.switchImeButton).setOnClickListener { showInputMethodPicker() }
        findViewById<Button>(R.id.requestMicButton).setOnClickListener { requestMicPermissionIfNeeded() }
        findViewById<ImageButton>(R.id.clearButton).setOnClickListener { clearOutputs() }
        findViewById<ImageButton>(R.id.settingsButton)?.setOnClickListener {
            com.typeink.settings.SettingsActivity.start(this)
        }
        micButton.setOnClickListener {
            if (viewModel.isRecording.value == true) {
                stopVoiceInput()
            } else if (viewModel.uiPhase.value != TypeinkUiPhase.CONNECTING && viewModel.uiPhase.value != TypeinkUiPhase.REWRITING) {
                startVoiceInput()
            }
        }
        secondaryActionButton.setOnClickListener { toggleRecentCorrection() }

        updateStyleSelection(viewModel.styleMode.value ?: TypeinkStyleMode.NATURAL)
        updateBackendStatus()
        updateImeSetupStatus()
        viewModel.setPhase(TypeinkUiPhase.IDLE, getString(R.string.wave_hint_idle))
        renderActionButtons()
    }

    override fun onResume() {
        super.onResume()
        updateImeSetupStatus()
        renderStatusUi()
        renderActionButtons()
    }

    override fun onDestroy() {
        homeInputCoordinator.stopSession()
        ClipboardHistoryTracker.stop()
        super.onDestroy()
    }

    private fun setupObservers() {
        viewModel.styleMode.observe(this) { mode ->
            updateStyleSelection(mode)
        }

        viewModel.uiPhase.observe(this) {
            renderStatusUi()
            updateBackendStatus()
            renderActionButtons()
        }

        viewModel.statusHint.observe(this) {
            renderStatusUi()
        }

        viewModel.asrText.observe(this) { text ->
            asrTextView.text = text
            if (text.isNotBlank() && viewModel.firstWordLatency.value == null && recordingStartTime > 0) {
                val latency = System.currentTimeMillis() - recordingStartTime
                viewModel.setFirstWordLatency(latency)
            }
        }

        viewModel.llmText.observe(this) { text ->
            llmTextView.text = text
        }

        viewModel.isRecording.observe(this) {
            renderStatusUi()
            renderActionButtons()
        }

        viewModel.errorMessage.observe(this) {
            renderStatusUi()
        }

        viewModel.recentCorrectionArmed.observe(this) {
            renderActionButtons()
        }
    }

    private fun startVoiceInput() {
        if (!hasAudioPermission()) {
            pendingStartAfterPermission = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val snapshot = resolveSessionSnapshot()
        if (viewModel.isRecentCorrectionArmed() && !snapshot.rewriteMode) {
            viewModel.setRecentCorrectionArmed(false)
            viewModel.setPhase(TypeinkUiPhase.FAILED, "最近一句没有成功选中，请重新点一次“修最近一句”。")
            return
        }

        viewModel.setCurrentSnapshot(snapshot)
        viewModel.setBackendAsrStarted(false)
        viewModel.resetLlmBuffer()
        viewModel.setAsrText("")
        viewModel.setLlmText("")
        viewModel.setErrorMessage("")
        viewModel.setPhase(TypeinkUiPhase.CONNECTING, "正在打开麦克风和识别通道。")

        beginRecordingSession(snapshot)
    }

    private fun beginRecordingSession(snapshot: SessionInputSnapshot) {
        recordingStartTime = System.currentTimeMillis()
        if (!homeInputCoordinator.startSession(snapshot, homeInputListener)) {
            viewModel.setPhase(TypeinkUiPhase.FAILED, "DashScope API Key 未配置，请在 build.gradle 中配置")
            showToast("请配置 DashScope API Key")
            return
        }

        viewModel.setRecording(true)
        viewModel.setPhase(TypeinkUiPhase.LISTENING, listeningHint(snapshot))
        updateBackendStatus()
    }

    private fun stopVoiceInput() {
        homeInputCoordinator.stopSession()
        stopRecordingUiOnly()
        statusWaveView.setAmplitude(0f)
        if (viewModel.uiPhase.value != TypeinkUiPhase.FAILED) {
            viewModel.setPhase(TypeinkUiPhase.REWRITING, rewritingHint(viewModel.getCurrentSnapshot()))
        }
    }

    private fun stopRecordingUiOnly() {
        viewModel.setRecording(false)
        statusWaveView.setAmplitude(0f)
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clearOutputs() {
        homeInputCoordinator.clearSession()
        viewModel.clearOutputs()
        viewModel.clearRecentCommit()
        editorInput.text = null
        statusWaveView.setAmplitude(0f)
        viewModel.setPhase(TypeinkUiPhase.IDLE, getString(R.string.wave_hint_idle))
    }

    private fun resolveSessionSnapshot(): SessionInputSnapshot {
        val original = editorInput.text?.toString().orEmpty()
        val start = editorInput.selectionStart.coerceAtLeast(0)
        val end = editorInput.selectionEnd.coerceAtLeast(0)
        val selectionStart = minOf(start, end)
        val selectionEnd = maxOf(start, end)
        val rewriteMode =
            selectionEnd > selectionStart &&
                original.substring(selectionStart, selectionEnd).isNotBlank()

        if (viewModel.isRecentCorrectionArmed()) {
            return SessionInputSnapshot(
                originalText = original,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                rewriteMode = rewriteMode,
                source = SessionRewriteSource.RECENT_COMMIT,
            )
        }

        return SessionInputSnapshot(
            originalText = original,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            rewriteMode = rewriteMode,
            source =
                if (rewriteMode) {
                    SessionRewriteSource.SELECTED_TEXT
                } else {
                    SessionRewriteSource.DIRECT_INPUT
                },
        )
    }

    private fun applyAsrDraftToEditor(draftText: String) {
        val snapshot = viewModel.getCurrentSnapshot()
        val before = snapshot.preText
        val after = snapshot.postText
        val merged = before + draftText + after
        editorInput.setText(merged)
        editorInput.setSelection((before + draftText).length.coerceAtMost(merged.length))
    }

    private fun applyFinalTextToEditor(finalText: String) {
        val snapshot = viewModel.getCurrentSnapshot()
        val normalizedFinal = normalizeAppliedText(finalText, snapshot)
        val before = snapshot.preText
        val after = snapshot.postText
        val merged = before + normalizedFinal + after
        editorInput.setText(merged)
        val cursor = (before + normalizedFinal).length.coerceAtMost(merged.length)
        editorInput.setSelection(cursor)
        viewModel.rememberRecentCommit(
            RecentCommitSnapshot(
                fullText = merged,
                selectionStart = before.length,
                selectionEnd = before.length + normalizedFinal.length,
                appliedText = normalizedFinal,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        viewModel.setRecentCorrectionArmed(false)
    }

    private fun renderStatusUi() {
        val sharedState =
            TypeinkInputStateMapper.fromAppState(
                phase = viewModel.uiPhase.value ?: TypeinkUiPhase.IDLE,
                hint = viewModel.statusHint.value.orEmpty(),
                isRecording = viewModel.isRecording.value == true,
                partialText = viewModel.asrText.value.orEmpty(),
                finalText = viewModel.llmText.value.orEmpty(),
                errorMessage = viewModel.errorMessage.value.orEmpty(),
            )
        val presentation = TypeinkUiText.present(sharedState)

        statusSummaryView.text = presentation.title
        statusHintView.text = presentation.hint

        val titleColor =
            when {
                presentation.isError -> R.color.typeink_error
                sharedState.phase == TypeinkInputPhase.APPLIED -> R.color.typeink_success
                else -> R.color.typeink_text
            }
        val hintColor =
            when {
                presentation.isError -> R.color.typeink_warning
                else -> R.color.typeink_text_secondary
            }

        statusSummaryView.setTextColor(ContextCompat.getColor(this, titleColor))
        statusHintView.setTextColor(ContextCompat.getColor(this, hintColor))
        statusWaveView.setVisualState(
            isActive = sharedState.phase == TypeinkInputPhase.LISTENING && sharedState.isRecording,
            isProcessing = sharedState.isProcessing,
            isError = presentation.isError,
        )
    }

    private fun renderActionButtons() {
        val isRecording = viewModel.isRecording.value == true
        val phase = viewModel.uiPhase.value ?: TypeinkUiPhase.IDLE
        val busy = phase == TypeinkUiPhase.CONNECTING || phase == TypeinkUiPhase.REWRITING
        val recentArmed = viewModel.isRecentCorrectionArmed()
        val hasRecentCommit = viewModel.getRecentCommit() != null

        // 麦克风按钮：录音状态显示停止图标，否则显示麦克风图标
        micButton.isEnabled = isRecording || !busy
        micButton.setImageResource(
            if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic_start,
        )
        micButton.setBackgroundResource(
            if (isRecording) {
                R.drawable.bg_typeink_mic_button_recording
            } else {
                R.drawable.bg_typeink_mic_button
            },
        )

        // 修正按钮：根据状态切换图标和透明度
        secondaryActionButton.isSelected = recentArmed
        secondaryActionButton.setImageResource(
            if (recentArmed) R.drawable.ic_clear else R.drawable.ic_edit,
        )
        secondaryActionButton.alpha = if (hasRecentCommit || recentArmed) 1f else 0.45f
        secondaryActionButton.setBackgroundResource(
            if (recentArmed) R.drawable.bg_typeink_secondary_pill else R.drawable.bg_typeink_toggle_key,
        )
    }

    private fun selectStyle(mode: TypeinkStyleMode) {
        viewModel.setStyleMode(mode)
        homeInputCoordinator.setStyleMode(mode)
        updateStyleSelection(mode)
    }

    private fun updateStyleSelection(mode: TypeinkStyleMode) {
        applySegmentState(styleNaturalView, mode == TypeinkStyleMode.NATURAL)
        applySegmentState(styleFormalView, mode == TypeinkStyleMode.FORMAL)
    }

    private fun applySegmentState(
        view: TextView,
        selected: Boolean,
    ) {
        view.isSelected = selected
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.typeink_text else R.color.typeink_muted,
            ),
        )
    }

    private fun updateBackendStatus() {
        val failed = viewModel.uiPhase.value == TypeinkUiPhase.FAILED
        backendStatusView.text =
            if (failed) {
                getString(R.string.status_badge_retry)
            } else {
                getString(R.string.status_badge_ready)
            }
        backendStatusView.setBackgroundResource(
            if (failed) {
                R.drawable.bg_typeink_error_badge
            } else {
                R.drawable.bg_typeink_status_badge
            },
        )
        backendStatusView.setTextColor(
            ContextCompat.getColor(
                this,
                if (failed) R.color.typeink_warning else R.color.typeink_text_secondary,
            ),
        )
    }

    private fun updateImeSetupStatus() {
        val enabledText =
            if (isOurImeEnabled()) {
                getString(R.string.ime_enabled_yes)
            } else {
                getString(R.string.ime_enabled_no)
            }
        val selectedText =
            if (isOurImeCurrent()) {
                getString(R.string.ime_selected_yes)
            } else {
                getString(R.string.ime_selected_no)
            }
        val micText =
            if (hasAudioPermission()) {
                getString(R.string.ime_mic_yes)
            } else {
                getString(R.string.ime_mic_no)
            }
        imeSetupStatusView.text = "输入法：$enabledText\n当前选择：$selectedText\n权限：$micText"
    }

    private fun openInputMethodSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun requestMicPermissionIfNeeded() {
        if (hasAudioPermission()) {
            updateImeSetupStatus()
            return
        }
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun isOurImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val targetComponent = ComponentName(this, com.typeink.inputmethod.TypeinkInputMethodService::class.java)
        return imm.enabledInputMethodList.any { it.id == targetComponent.flattenToShortString() }
    }

    private fun isOurImeCurrent(): Boolean {
        val expectedId = "${packageName}/${com.typeink.inputmethod.TypeinkInputMethodService::class.java.name}"
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return current == expectedId
    }

    private fun toggleRecentCorrection() {
        if (viewModel.isRecentCorrectionArmed()) {
            viewModel.setRecentCorrectionArmed(false)
            viewModel.setPhase(TypeinkUiPhase.IDLE, getString(R.string.wave_hint_idle))
            return
        }

        val recent = viewModel.getRecentCommit()
        if (recent == null || recent.selectionEnd <= recent.selectionStart) {
            viewModel.setPhase(TypeinkUiPhase.FAILED, "还没有可修正的最近一句，请先完成一次语音输入。")
            return
        }

        if (recent.selectionEnd > editorInput.text.length) {
            viewModel.setPhase(TypeinkUiPhase.FAILED, "最近一句的范围已经变了，请重新完成一次语音输入。")
            return
        }

        editorInput.requestFocus()
        editorInput.setSelection(recent.selectionStart, recent.selectionEnd)
        viewModel.setRecentCorrectionArmed(true)
        viewModel.setPhase(TypeinkUiPhase.IDLE, "已选中最近一句，点麦克风后直接说修改指令。")
    }

    private fun normalizeAppliedText(
        finalText: String,
        snapshot: SessionInputSnapshot,
    ): String {
        if (finalText == " " && snapshot.preText.isBlank() && snapshot.postText.isBlank()) {
            return ""
        }
        return finalText
    }

    private fun listeningHint(snapshot: SessionInputSnapshot): String {
        return when (snapshot.source) {
            SessionRewriteSource.RECENT_COMMIT -> "已锁定最近一句，灰色草稿会先出现，最终结果会覆盖这一句。"
            SessionRewriteSource.SELECTED_TEXT -> "已进入语音修改模式，系统会按你的意图改写选中文本。"
            SessionRewriteSource.DIRECT_INPUT -> "灰色草稿会先出现，整理后的结果随后跟上。"
        }
    }

    private fun rewritingHint(snapshot: SessionInputSnapshot): String {
        return when (snapshot.source) {
            SessionRewriteSource.RECENT_COMMIT -> "正在根据你的指令重写最近一句。"
            SessionRewriteSource.SELECTED_TEXT -> "正在根据你的指令整理选中的内容。"
            SessionRewriteSource.DIRECT_INPUT -> "正在把口语整理成更清晰的书面表达。"
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
}
