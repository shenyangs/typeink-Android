package com.typeink.prototype

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TypeinkViewModel : ViewModel() {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 状态
    private val _styleMode = MutableLiveData(TypeinkStyleMode.NATURAL)
    val styleMode: LiveData<TypeinkStyleMode> = _styleMode

    private val _uiPhase = MutableLiveData(TypeinkUiPhase.IDLE)
    val uiPhase: LiveData<TypeinkUiPhase> = _uiPhase

    private val _statusHint = MutableLiveData("")
    val statusHint: LiveData<String> = _statusHint

    private val _asrText = MutableLiveData("")
    val asrText: LiveData<String> = _asrText

    private val _llmText = MutableLiveData("")
    val llmText: LiveData<String> = _llmText

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _backendConnected = MutableLiveData(false)
    val backendConnected: LiveData<Boolean> = _backendConnected

    private val _backendUrl = MutableLiveData("")
    val backendUrl: LiveData<String> = _backendUrl

    // 首字时延和错误信息
    private val _firstWordLatency = MutableLiveData<Long?>(null)
    val firstWordLatency: LiveData<Long?> = _firstWordLatency

    private val _errorMessage = MutableLiveData("")
    val errorMessage: LiveData<String> = _errorMessage

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _recentCorrectionArmed = MutableLiveData(false)
    val recentCorrectionArmed: LiveData<Boolean> = _recentCorrectionArmed

    // 会话相关
    private var currentSnapshot = SessionInputSnapshot("", 0, 0, false)
    private var backendAsrStarted = false
    private var recentCommitSnapshot: RecentCommitSnapshot? = null
    private val llmBuffer = StringBuilder()

    // 状态更新方法
    fun setStyleMode(mode: TypeinkStyleMode) {
        _styleMode.value = mode
    }

    fun setPhase(phase: TypeinkUiPhase, hint: String) {
        _uiPhase.value = phase
        _statusHint.value = hint
    }

    fun setAsrText(text: String) {
        _asrText.value = text
    }

    fun setLlmText(text: String) {
        _llmText.value = text
    }

    fun setRecording(isRecording: Boolean) {
        _isRecording.value = isRecording
    }

    fun setBackendConnected(connected: Boolean) {
        _backendConnected.value = connected
    }

    fun setBackendUrl(url: String) {
        _backendUrl.value = url
    }

    fun resetLlmBuffer() {
        llmBuffer.clear()
    }

    fun appendLlmBuffer(text: String) {
        llmBuffer.append(text)
    }

    fun getLlmBuffer(): String {
        return llmBuffer.toString()
    }

    fun setCurrentSnapshot(snapshot: SessionInputSnapshot) {
        currentSnapshot = snapshot
    }

    fun getCurrentSnapshot(): SessionInputSnapshot {
        return currentSnapshot
    }

    fun setBackendAsrStarted(started: Boolean) {
        backendAsrStarted = started
    }

    fun isBackendAsrStarted(): Boolean {
        return backendAsrStarted
    }

    fun setRecentCorrectionArmed(armed: Boolean) {
        _recentCorrectionArmed.value = armed
    }

    fun isRecentCorrectionArmed(): Boolean {
        return _recentCorrectionArmed.value == true
    }

    fun rememberRecentCommit(snapshot: RecentCommitSnapshot) {
        recentCommitSnapshot = snapshot
    }

    fun getRecentCommit(): RecentCommitSnapshot? {
        return recentCommitSnapshot
    }

    fun clearRecentCommit() {
        recentCommitSnapshot = null
        _recentCorrectionArmed.value = false
    }

    fun clearOutputs() {
        _asrText.value = ""
        _llmText.value = ""
        llmBuffer.clear()
        backendAsrStarted = false
        _firstWordLatency.value = null
        _errorMessage.value = ""
        _isLoading.value = false
        _recentCorrectionArmed.value = false
    }

    fun setFirstWordLatency(latency: Long) {
        _firstWordLatency.value = latency
    }

    fun setErrorMessage(message: String) {
        _errorMessage.value = message
    }

    fun setLoading(isLoading: Boolean) {
        _isLoading.value = isLoading
    }

    override fun onCleared() {
        super.onCleared()
        // 协程会在 ViewModel 清除时自动取消
    }
}
