package com.typeink.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.typeink.prototype.R
import com.typeink.settings.data.ProviderManager
import com.typeink.settings.model.ProviderType
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.apply {
            title = "设置"
            setDisplayHomeAsUpEnabled(true)
        }
        
        providerManager = ProviderManager.getInstance(this)
        
        initViews()
        updateProviderStatus()
    }
    
    private fun initViews() {
        // ASR 设置
        findViewById<LinearLayout>(R.id.asrSettingsItem)?.setOnClickListener {
            ProviderListActivity.start(this, ProviderType.ASR)
        }
        
        // LLM 设置
        findViewById<LinearLayout>(R.id.llmSettingsItem)?.setOnClickListener {
            ProviderListActivity.start(this, ProviderType.LLM)
        }
        
        // VAD 设置
        findViewById<LinearLayout>(R.id.vadSettingsItem)?.setOnClickListener {
            VadSettingsActivity.start(this)
        }
        
        // SyncClipboard 设置
        findViewById<LinearLayout>(R.id.syncClipboardSettingsItem)?.setOnClickListener {
            SyncClipboardSettingsActivity.start(this)
        }
        
        // 高级设置
        findViewById<LinearLayout>(R.id.advancedSettingsItem)?.setOnClickListener {
            showStatusSummary()
        }
        
        updateVadStatus()
        updateSyncClipboardStatus()
        
        // 关于
        findViewById<LinearLayout>(R.id.aboutItem)?.setOnClickListener {
            showAboutDialog()
        }
        
        findViewById<TextView>(R.id.versionText)?.text = "版本 ${getVersionName()}"
    }
    
    /**
     * 更新当前厂商显示状态
     */
    private fun updateProviderStatus() {
        val asrProvider = providerManager.getCurrentAsrProvider()
        val llmProvider = providerManager.getCurrentLlmProvider()
        
        // 显示当前 ASR 厂商
        findViewById<TextView>(R.id.currentAsrProvider)?.text = buildString {
            append(asrProvider.name)
            if (asrProvider.hasUserConfig()) {
                append(" (自定义)")
            } else {
                append(" (内置)")
            }
        }
        
        // 显示当前 LLM 厂商
        findViewById<TextView>(R.id.currentLlmProvider)?.text = buildString {
            append(llmProvider.name)
            if (llmProvider.hasUserConfig()) {
                append(" (自定义)")
            } else {
                append(" (内置)")
            }
        }
    }
    
    private fun updateVadStatus() {
        val vadConfig = VadConfig.load(this)
        val seconds = vadConfig.silenceTimeoutMs / 1000f
        findViewById<TextView>(R.id.currentVadStatus)?.text = if (vadConfig.isVadEnabled) {
            "已开启 (${String.format("%.1f秒", seconds)})"
        } else {
            "已关闭"
        }
    }

    private fun updateSyncClipboardStatus() {
        val prefs = getSharedPreferences(SyncClipboardSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(SyncClipboardSettingsActivity.KEY_ENABLED, false)
        val serverUrl = prefs.getString(SyncClipboardSettingsActivity.KEY_SERVER_URL, "").orEmpty()
        findViewById<TextView>(R.id.currentSyncClipboardStatus)?.text =
            when {
                enabled && serverUrl.isNotBlank() -> "已启用"
                serverUrl.isNotBlank() -> "已配置"
                else -> "未启用"
            }
    }
    
    override fun onResume() {
        super.onResume()
        updateProviderStatus()
        updateVadStatus()
        updateSyncClipboardStatus()
    }
    
    private fun getVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.4.3"
        } catch (e: Exception) {
            "0.4.3"
        }
    }
    
    private fun showStatusSummary() {
        val summary = providerManager.getStatusSummary()
        val dialogView = layoutInflater.inflate(R.layout.dialog_typeink_info, null)
        dialogView.findViewById<TextView>(R.id.infoDialogTitle).text = "当前接入状态"
        dialogView.findViewById<TextView>(R.id.infoDialogBody).text = summary

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
