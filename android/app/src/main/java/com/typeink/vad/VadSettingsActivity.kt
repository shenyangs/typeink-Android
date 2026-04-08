package com.typeink.vad

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.typeink.prototype.R

/**
 * VAD 设置页面
 * 
 * 功能：
 * 1. 开启/关闭 VAD 智能判停
 * 2. 调节停顿时间（0.3秒 - 3秒）
 * 3. 实时预览当前配置
 */
class VadSettingsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, VadSettingsActivity::class.java)
            context.startActivity(intent)
        }
    }

    private lateinit var vadConfig: VadConfig
    
    private lateinit var vadSwitch: Switch
    private lateinit var timeoutSeekBar: SeekBar
    private lateinit var timeoutValueText: TextView
    private lateinit var timeoutDescription: TextView
    private lateinit var resetButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vad_settings)
        
        supportActionBar?.apply {
            title = "VAD 智能判停设置"
            setDisplayHomeAsUpEnabled(true)
        }
        
        vadConfig = VadConfig.load(this)
        initViews()
        loadSettings()
    }
    
    private fun initViews() {
        vadSwitch = findViewById(R.id.vadSwitch)
        timeoutSeekBar = findViewById(R.id.timeoutSeekBar)
        timeoutValueText = findViewById(R.id.timeoutValueText)
        timeoutDescription = findViewById(R.id.timeoutDescription)
        resetButton = findViewById(R.id.resetButton)
        
        // VAD 开关
        vadSwitch.setOnCheckedChangeListener { _, isChecked ->
            vadConfig.isVadEnabled = isChecked
            updateTimeoutUI(isChecked)
        }
        
        // 超时时间调节
        timeoutSeekBar.max = ((VadConfig.MAX_SILENCE_TIMEOUT_MS - VadConfig.MIN_SILENCE_TIMEOUT_MS) / 100).toInt()
        timeoutSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val timeoutMs = VadConfig.MIN_SILENCE_TIMEOUT_MS + progress * 100
                    vadConfig.silenceTimeoutMs = timeoutMs
                    updateTimeoutText(timeoutMs)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 重置按钮
        resetButton.setOnClickListener {
            vadConfig.resetToDefaults()
            loadSettings()
            showToast("已恢复默认设置")
        }
    }
    
    private fun loadSettings() {
        vadSwitch.isChecked = vadConfig.isVadEnabled
        
        val progress = ((vadConfig.silenceTimeoutMs - VadConfig.MIN_SILENCE_TIMEOUT_MS) / 100).toInt()
        timeoutSeekBar.progress = progress.coerceIn(0, timeoutSeekBar.max)
        
        updateTimeoutText(vadConfig.silenceTimeoutMs)
        updateTimeoutUI(vadConfig.isVadEnabled)
    }
    
    private fun updateTimeoutUI(enabled: Boolean) {
        timeoutSeekBar.isEnabled = enabled
        timeoutValueText.isEnabled = enabled
        timeoutDescription.isEnabled = enabled
        timeoutDescription.text = if (enabled) {
            "停止说话后等待此时间自动结束录音"
        } else {
            "VAD 已关闭，需要手动松开麦克风按钮停止录音"
        }
    }
    
    private fun updateTimeoutText(timeoutMs: Long) {
        val seconds = timeoutMs / 1000f
        timeoutValueText.text = String.format("%.1f秒", seconds)
        
        // 根据时长给出描述
        timeoutDescription.text = when {
            seconds <= 0.5f -> "反应灵敏，适合快速输入"
            seconds <= 1.5f -> "平衡模式，推荐设置"
            seconds <= 2.0f -> "容错较高，适合长句"
            else -> "容错最高，适合段落输入"
        }
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
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
