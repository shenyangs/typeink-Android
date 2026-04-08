package com.typeink.syncclipboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.typeink.prototype.R
import kotlinx.coroutines.*

/**
 * SyncClipboard 设置页面
 */
class SyncClipboardSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "syncclipboard_config"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        
        fun start(context: Context) {
            val intent = Intent(context, SyncClipboardSettingsActivity::class.java)
            context.startActivity(intent)
        }
    }
    
    private lateinit var enableSwitch: Switch
    private lateinit var serverUrlEdit: EditText
    private lateinit var usernameEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var testButton: Button
    private lateinit var saveButton: Button
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_syncclipboard_settings)
        
        supportActionBar?.apply {
            title = "SyncClipboard 设置"
            setDisplayHomeAsUpEnabled(true)
        }
        
        initViews()
        loadSettings()
    }
    
    private fun initViews() {
        enableSwitch = findViewById(R.id.enableSwitch)
        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        usernameEdit = findViewById(R.id.usernameEdit)
        passwordEdit = findViewById(R.id.passwordEdit)
        statusText = findViewById(R.id.statusText)
        testButton = findViewById(R.id.testButton)
        saveButton = findViewById(R.id.saveButton)
        
        testButton.setOnClickListener {
            testConnection()
        }
        
        saveButton.setOnClickListener {
            saveSettings()
        }
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enableSwitch.isChecked = prefs.getBoolean(KEY_ENABLED, false)
        serverUrlEdit.setText(prefs.getString(KEY_SERVER_URL, ""))
        usernameEdit.setText(prefs.getString(KEY_USERNAME, ""))
        passwordEdit.setText(prefs.getString(KEY_PASSWORD, ""))
        
        updateStatus()
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, enableSwitch.isChecked)
            putString(KEY_SERVER_URL, serverUrlEdit.text.toString().trim())
            putString(KEY_USERNAME, usernameEdit.text.toString().trim())
            putString(KEY_PASSWORD, passwordEdit.text.toString())
            apply()
        }
        
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun testConnection() {
        val serverUrl = serverUrlEdit.text.toString().trim()
        if (serverUrl.isBlank()) {
            statusText.text = "请输入服务器地址"
            return
        }
        
        statusText.text = "测试中..."
        
        val config = SyncClipboardClient.SyncClipboardConfig(
            serverUrl = serverUrl,
            username = usernameEdit.text.toString().trim(),
            password = passwordEdit.text.toString(),
            enabled = true
        )
        
        val client = SyncClipboardClient(this, config)
        
        scope.launch {
            try {
                val data = withTimeout(10000) {
                    client.fetchClipboard()
                }
                
                if (data != null) {
                    statusText.text = "连接成功！\n类型: ${data.type}\n内容: ${data.text.take(50)}"
                } else {
                    statusText.text = "连接成功，但服务器返回空内容"
                }
            } catch (e: Exception) {
                statusText.text = "连接失败: ${e.message}"
            } finally {
                client.dispose()
            }
        }
    }
    
    private fun updateStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        
        statusText.text = if (enabled) {
            "状态: 已启用"
        } else {
            "状态: 已禁用"
        }
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
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
