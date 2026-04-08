package com.typeink.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.typeink.asr.DraftRecognizerConfig
import com.typeink.inputmethod.EditPanelActivity
import com.typeink.inputmethod.ImmersiveEditorActivity
import com.typeink.inputmethod.TypeinkInputMethodService
import com.typeink.prototype.MainActivity
import com.typeink.prototype.R
import com.typeink.prototype.TypeinkStyleMode
import com.typeink.settings.data.ProviderManager
import com.typeink.settings.model.ProviderType
import com.typeink.syncclipboard.ClipboardHistoryManager
import com.typeink.syncclipboard.ClipboardHistoryTracker
import com.typeink.syncclipboard.SyncClipboardSettingsActivity
import com.typeink.vad.VadConfig
import com.typeink.vad.VadSettingsActivity

/**
 * 设置中心
 *
 * 核心逻辑：
 * 1. 内置 API Key 保底（用户无法删除）
 * 2. 用户配置可选（优先级更高）
 * 3. 第三方配置错误自动回退到内置
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SettingsActivity::class.java)
            context.startActivity(intent)
        }
    }

    private lateinit var providerManager: ProviderManager
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshAllStatuses()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            title = "设置"
            setDisplayHomeAsUpEnabled(true)
        }

        providerManager = ProviderManager.getInstance(this)
        ClipboardHistoryTracker.start(this)

        initViews()
        refreshAllStatuses()
    }

    private fun initViews() {
        findViewById<LinearLayout>(R.id.asrSettingsItem)?.setOnClickListener {
            ProviderListActivity.start(this, ProviderType.ASR)
        }

        findViewById<LinearLayout>(R.id.llmSettingsItem)?.setOnClickListener {
            ProviderListActivity.start(this, ProviderType.LLM)
        }

        findViewById<LinearLayout>(R.id.draftRecognizerItem)?.setOnClickListener {
            showDraftRecognizerSummary()
        }

        findViewById<LinearLayout>(R.id.vadSettingsItem)?.setOnClickListener {
            VadSettingsActivity.start(this)
        }

        findViewById<LinearLayout>(R.id.imeSetupItem)?.setOnClickListener {
            showImeSetupDialog()
        }

        findViewById<LinearLayout>(R.id.syncClipboardSettingsItem)?.setOnClickListener {
            SyncClipboardSettingsActivity.start(this)
        }

        findViewById<LinearLayout>(R.id.homeConsoleItem)?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.immersiveModeItem)?.setOnClickListener {
            ImmersiveEditorActivity.start(this)
        }

        findViewById<LinearLayout>(R.id.editPanelItem)?.setOnClickListener {
            EditPanelActivity.start(
                context = this,
                text = "这是一段示例文本，你可以在这里体验语音修改、复制粘贴和工具栏能力。",
                styleMode = TypeinkStyleMode.NATURAL,
            )
        }

        findViewById<LinearLayout>(R.id.advancedSettingsItem)?.setOnClickListener {
            showStatusSummary()
        }

        findViewById<LinearLayout>(R.id.aboutItem)?.setOnClickListener {
            showAboutDialog()
        }

        findViewById<TextView>(R.id.versionText)?.text = "版本 ${getVersionName()}"
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()
    }

    override fun onDestroy() {
        ClipboardHistoryTracker.stop()
        super.onDestroy()
    }

    private fun refreshAllStatuses() {
        updateProviderStatus()
        updateDraftRecognizerStatus()
        updateVadStatus()
        updateClipboardHistoryStatus()
        updateImeSetupStatus()
        updateFeatureEntryStatus()
        updateAdvancedStatus()
    }

    private fun updateProviderStatus() {
        val asrProvider = providerManager.getCurrentAsrProvider()
        val llmProvider = providerManager.getCurrentLlmProvider()
        val draftStatus = DraftRecognizerConfig.load(this).resolveRuntimeStatus()

        findViewById<TextView>(R.id.currentAsrProvider)?.text =
            "${asrProvider.name} · ${draftStatus.actualBackend.displayName}"

        findViewById<TextView>(R.id.currentLlmProvider)?.text =
            buildString {
                append(llmProvider.name)
                append(if (llmProvider.hasUserConfig()) " · 自定义" else " · 内置")
            }
    }

    private fun updateDraftRecognizerStatus() {
        val draftStatus = DraftRecognizerConfig.load(this).resolveRuntimeStatus()
        findViewById<TextView>(R.id.currentDraftRecognizerStatus)?.text = draftStatus.toBadgeLabel()
    }

    private fun updateVadStatus() {
        val vadConfig = VadConfig.load(this)
        val runtimeStatus = vadConfig.resolveRuntimeStatus()
        val seconds = vadConfig.silenceTimeoutMs / 1000f
        findViewById<TextView>(R.id.currentVadStatus)?.text =
            if (vadConfig.isVadEnabled) {
                "${runtimeStatus.toBadgeLabel()} / ${String.format("%.1f秒", seconds)}"
            } else {
                "关闭"
            }
    }

    private fun updateClipboardHistoryStatus() {
        val historyCount = ClipboardHistoryManager.getInstance(this).getHistoryCount()
        findViewById<TextView>(R.id.currentSyncClipboardStatus)?.text =
            when {
                historyCount > 0 -> "已保存 ${historyCount} 条"
                else -> "暂无历史"
            }
    }

    private fun updateImeSetupStatus() {
        val readySteps =
            listOf(
                isOurImeEnabled(),
                isOurImeCurrent(),
                hasAudioPermission(),
            ).count { it }

        val summary =
            when (readySteps) {
                3 -> "3/3 已就绪"
                2 -> "2/3 待完成"
                1 -> "1/3 待完成"
                else -> "0/3 未准备"
            }
        findViewById<TextView>(R.id.currentImeSetupStatus)?.text = summary
    }

    private fun updateFeatureEntryStatus() {
        findViewById<TextView>(R.id.currentHomeConsoleStatus)?.text = "打开主页"
        findViewById<TextView>(R.id.currentImmersiveStatus)?.text = "可直接打开"
        findViewById<TextView>(R.id.currentEditPanelStatus)?.text = "示例体验"
    }

    private fun updateAdvancedStatus() {
        val healthyCount =
            listOf(
                providerManager.getCurrentAsrProvider().isValid(),
                providerManager.getCurrentLlmProvider().isValid(),
                hasAudioPermission(),
            ).count { it }
        findViewById<TextView>(R.id.currentAdvancedStatus)?.text = "$healthyCount/3 核心正常"
    }

    private fun getVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.4.3"
        } catch (e: Exception) {
            "0.4.3"
        }
    }

    private fun showStatusSummary() {
        val draftStatus = DraftRecognizerConfig.load(this).resolveRuntimeStatus()
        val vadStatus = VadConfig.load(this).resolveRuntimeStatus()
        val summary =
            buildString {
                append(providerManager.getStatusSummary())
                appendLine()
                appendLine()
                appendLine("草稿识别：${draftStatus.toSummaryText()}")
                appendLine()
                appendLine("智能判停：${vadStatus.toSummaryText()}")
                appendLine()
                appendLine("剪贴板历史：${buildClipboardHistorySummary()}")
                appendLine()
                append(buildImeSetupSummary())
            }
        showInfoDialog(
            title = "当前接入状态",
            body = summary,
        )
    }

    private fun showDraftRecognizerSummary() {
        val draftStatus = DraftRecognizerConfig.load(this).resolveRuntimeStatus()
        val summary =
            buildString {
                appendLine("当前请求：${draftStatus.requestedBackend.displayName}")
                appendLine("实际运行：${draftStatus.actualBackend.displayName}")
                append("详情：${draftStatus.detail}")
            }
        showInfoDialog(
            title = "草稿识别底座",
            body = summary,
        )
    }

    private fun showImeSetupDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_typeink_info, null)
        dialogView.findViewById<TextView>(R.id.infoDialogTitle).text = "输入法准备"
        dialogView.findViewById<TextView>(R.id.infoDialogBody).text = buildImeSetupSummary()

        val dialog =
            AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("输入法设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
                .setNeutralButton("切换输入法") { _, _ ->
                    showInputMethodPicker()
                }
                .setNegativeButton("麦克风权限") { _, _ ->
                    requestMicPermission()
                }
                .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showInfoDialog(
        title: String,
        body: String,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_typeink_info, null)
        dialogView.findViewById<TextView>(R.id.infoDialogTitle).text = title
        dialogView.findViewById<TextView>(R.id.infoDialogBody).text = body

        val dialog =
            AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("知道了", null)
                .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about_typeink, null)
        dialogView.findViewById<TextView>(R.id.aboutVersionText).text =
            "Typeink · AI 语音输入法 · 版本 ${getVersionName()}"

        val dialog =
            AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("确定", null)
                .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun buildClipboardHistorySummary(): String {
        val historyCount = ClipboardHistoryManager.getInstance(this).getHistoryCount()
        return if (historyCount > 0) {
            "最近保存 $historyCount 条"
        } else {
            "暂时还没有记录"
        }
    }

    private fun buildImeSetupSummary(): String {
        val enabledText = if (isOurImeEnabled()) "已启用" else "未启用"
        val selectedText = if (isOurImeCurrent()) "已切换为当前输入法" else "还不是当前输入法"
        val micText = if (hasAudioPermission()) "麦克风权限已授权" else "麦克风权限未授权"
        return buildString {
            appendLine("输入法：$enabledText")
            appendLine("当前选择：$selectedText")
            append("权限：$micText")
        }
    }

    private fun requestMicPermission() {
        if (hasAudioPermission()) {
            refreshAllStatuses()
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun isOurImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val targetComponent = ComponentName(this, TypeinkInputMethodService::class.java)
        return imm.enabledInputMethodList.any { it.id == targetComponent.flattenToShortString() }
    }

    private fun isOurImeCurrent(): Boolean {
        val expectedId = "$packageName/${TypeinkInputMethodService::class.java.name}"
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return current == expectedId
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
