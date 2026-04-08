package com.typeink.inputmethod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.typeink.prototype.DashScopeService
import com.typeink.prototype.PcmRecorder
import com.typeink.prototype.R
import com.typeink.prototype.TypeinkStyleMode
import com.typeink.settings.data.ProviderManager

/**
 * AI 编辑面板 - 对标 参考键盘 的编辑界面
 *
 * 功能：
 * 1. 显示识别结果，支持直接编辑
 * 2. 支持语音指令修改（"删除最后一句"、"把第二句改成..."）
 * 3. 提供光标移动、选择、删除工具
 * 4. 确认后发送回输入框
 */
class EditPanelActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_STYLE_MODE = "style_mode"
        
        fun start(context: Context, text: String, styleMode: TypeinkStyleMode = TypeinkStyleMode.NATURAL) {
            val intent = Intent(context, EditPanelActivity::class.java)
            intent.putExtra(EXTRA_TEXT, text)
            intent.putExtra(EXTRA_STYLE_MODE, styleMode.name)
            context.startActivity(intent)
        }
    }
    
    private lateinit var editText: EditText
    private lateinit var instructionText: TextView
    private lateinit var dashScopeService: DashScopeService
    private var currentStyleMode = TypeinkStyleMode.NATURAL
    private var isRecordingInstruction = false
    private val pcmRecorder = PcmRecorder()
    private val hapticManager by lazy { HapticFeedbackManager(this) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_panel)
        
        supportActionBar?.apply {
            title = "AI 编辑"
            setDisplayHomeAsUpEnabled(true)
        }
        
        // 获取传入的文本
        val initialText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        currentStyleMode = TypeinkStyleMode.valueOf(
            intent.getStringExtra(EXTRA_STYLE_MODE) ?: TypeinkStyleMode.NATURAL.name
        )
        
        // 初始化 DashScopeService
        ProviderManager.getInstance(this)
        dashScopeService = DashScopeService(this)
        dashScopeService.setStyleMode(currentStyleMode)
        
        initViews(initialText)
    }
    
    private fun initViews(initialText: String) {
        editText = findViewById(R.id.editText)
        instructionText = findViewById(R.id.instructionText)
        
        editText.setText(initialText)
        editText.requestFocus()
        
        // 返回按钮
        findViewById<ImageButton>(R.id.backButton)?.setOnClickListener {
            showDiscardConfirmDialog()
        }
        
        // 发送按钮
        findViewById<Button>(R.id.sendButton)?.setOnClickListener {
            sendResult()
        }
        
        // 语音指令按钮 - 长按录音
        val voiceEditButton = findViewById<ImageButton>(R.id.voiceEditButton)
        voiceEditButton?.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startVoiceInstruction()
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    stopVoiceInstruction()
                    view.performClick()
                    true
                }
                else -> false
            }
        }
        
        // 编辑工具栏
        setupEditToolbar()
    }
    
    private fun setupEditToolbar() {
        // 左移光标
        findViewById<Button>(R.id.cursorLeftButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            val selection = editText.selectionStart
            if (selection > 0) {
                editText.setSelection(selection - 1)
            }
        }
        
        // 右移光标
        findViewById<Button>(R.id.cursorRightButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            val selection = editText.selectionEnd
            if (selection < editText.text.length) {
                editText.setSelection(selection + 1)
            }
        }
        
        // 全选
        findViewById<Button>(R.id.selectAllButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            editText.selectAll()
        }
        
        // 复制
        findViewById<Button>(R.id.copyButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            val selectedText = editText.text.substring(
                editText.selectionStart,
                editText.selectionEnd
            )
            if (selectedText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", selectedText))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 粘贴
        findViewById<Button>(R.id.pasteButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                editText.text.insert(editText.selectionStart, text)
            }
        }
        
        // 撤销（简单实现：清空）
        findViewById<Button>(R.id.undoButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            showClearConfirmDialog()
        }
        
        // 删除选中
        findViewById<Button>(R.id.deleteButton)?.setOnClickListener {
            hapticManager.performKeyPress()
            val start = editText.selectionStart
            val end = editText.selectionEnd
            if (start != end) {
                editText.text.delete(start, end)
            } else if (start > 0) {
                // 没有选中，删除前一个字符
                editText.text.delete(start - 1, start)
            }
        }
    }
    
    private fun startVoiceInstruction() {
        if (isRecordingInstruction) return
        
        isRecordingInstruction = true
        hapticManager.performRecordingStart()
        instructionText.text = "请说出修改指令..."
        instructionText.visibility = android.view.View.VISIBLE
        
        // 启动录音
        pcmRecorder.start(object : PcmRecorder.Listener {
            override fun onAudioChunk(bytes: ByteArray) {
                dashScopeService.sendAudioChunk(bytes)
            }
            
            override fun onError(message: String) {
                runOnUiThread {
                    instructionText.text = "录音错误: $message"
                }
            }
            
            override fun onAmplitude(level: Float) {
                // 可以在这里更新 UI 显示音量
            }
        })
        
        // 启动 ASR
        val snapshot = com.typeink.prototype.SessionInputSnapshot(
            originalText = editText.text.toString(),
            selectionStart = editText.selectionStart,
            selectionEnd = editText.selectionEnd,
            rewriteMode = true,
            source = com.typeink.prototype.SessionRewriteSource.SELECTED_TEXT
        )
        dashScopeService.startAsr(object : DashScopeService.Listener {
            override fun onAsrPartial(text: String) {
                runOnUiThread {
                    instructionText.text = "识别中: $text"
                }
            }
            
            override fun onAsrFinal(text: String) {
                runOnUiThread {
                    instructionText.text = "指令: $text"
                    // 执行编辑指令
                    executeEditCommand(text)
                }
            }
            
            override fun onLlmDelta(token: String) {
                // 不需要
            }
            
            override fun onLlmCompleted(finalText: String) {
                // 不需要
            }
            
            override fun onError(message: String) {
                runOnUiThread {
                    instructionText.text = "识别失败: $message"
                }
            }
        }, snapshot)
    }
    
    private fun stopVoiceInstruction() {
        if (!isRecordingInstruction) return
        
        isRecordingInstruction = false
        hapticManager.performRecordingStop()
        
        pcmRecorder.stop()
        dashScopeService.stopAsr()
    }
    
    /**
     * 执行语音编辑指令
     */
    private fun executeEditCommand(command: String) {
        val currentText = editText.text.toString()
        
        // 调用 LLM 执行编辑
        dashScopeService.editByInstruction(
            currentText = currentText,
            instruction = command,
            listener = object : DashScopeService.EditListener {
                override fun onEditResult(newText: String) {
                    runOnUiThread {
                        editText.setText(newText)
                        editText.setSelection(newText.length)
                        instructionText.text = "已执行: $command"
                        Toast.makeText(this@EditPanelActivity, "修改完成", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onError(message: String) {
                    runOnUiThread {
                        instructionText.text = "执行失败: $message"
                        Toast.makeText(this@EditPanelActivity, "修改失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
    
    private fun sendResult() {
        val result = editText.text.toString()
        if (result.isBlank()) {
            Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 返回结果
        val intent = Intent()
        intent.putExtra("result", result)
        setResult(RESULT_OK, intent)
        finish()
    }
    
    private fun showDiscardConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("放弃编辑？")
            .setMessage("确定要放弃当前编辑内容吗？")
            .setPositiveButton("放弃") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("继续编辑", null)
            .show()
    }
    
    private fun showClearConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("清空内容？")
            .setMessage("确定要清空当前编辑内容吗？")
            .setPositiveButton("清空") { _, _ ->
                editText.setText("")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                showDiscardConfirmDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isRecordingInstruction) {
            stopVoiceInstruction()
        }
    }
}
