package com.typeink.inputmethod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.typeink.prototype.AudioWaveView
import com.typeink.prototype.DashScopeService
import com.typeink.prototype.LocalDraftRecognizer
import com.typeink.prototype.PcmRecorder
import com.typeink.prototype.R
import com.typeink.prototype.TypeinkStyleMode

/**
 * 沉浸创作模式 - Focus Mode
 * 
 * 设计哲学：
 * 1. 极致专注 - 去除一切干扰，只保留核心创作元素
 * 2. 自然手势 - 双指缩放进入/退出，下滑退出
 * 3. 实时反馈 - 大字体实时显示，波形可视化
 * 4. 优雅过渡 - 平滑的动画，无突兀感
 * 5. 智能感知 - 长段创作自动建议进入
 */
class ImmersiveEditorActivity : Activity() {
    
    companion object {
        private const val TAG = "ImmersiveEditor"
        private const val EXTRA_INITIAL_TEXT = "initial_text"
        private const val EXTRA_STYLE_MODE = "style_mode"
        
        fun start(context: Context, initialText: String = "", styleMode: TypeinkStyleMode = TypeinkStyleMode.NATURAL) {
            val intent = Intent(context, ImmersiveEditorActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_TEXT, initialText)
                putExtra(EXTRA_STYLE_MODE, styleMode.ordinal)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    
    // 核心组件
    private val dashScopeService by lazy { DashScopeService(this) }
    private val recorder = PcmRecorder()
    private var localDraftRecognizer: LocalDraftRecognizer? = null
    
    // UI 组件
    private lateinit var rootContainer: FrameLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var waveView: AudioWaveView
    private lateinit var draftTextView: EditText
    private lateinit var hintTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var micButton: ImageButton
    private lateinit var doneButton: ImageButton
    private lateinit var minimizeButton: ImageButton
    
    // 状态
    private var isRecording = false
    private var currentStyleMode = TypeinkStyleMode.NATURAL
    private var accumulatedText = StringBuilder()
    private var isMinimized = false
    
    // 手势检测
    private lateinit var gestureDetector: GestureDetector
    private var scaleFactor = 1.0f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 沉浸模式设置
        setupImmersiveMode()
        
        setContentView(R.layout.activity_immersive_editor)
        
        // 初始化
        localDraftRecognizer = LocalDraftRecognizer(this)
        currentStyleMode = TypeinkStyleMode.values()[
            intent.getIntExtra(EXTRA_STYLE_MODE, TypeinkStyleMode.NATURAL.ordinal)
        ]
        
        initViews()
        setupGestures()
        setupSystemUi()
        
        // 如果有初始文本，显示它
        val initialText = intent.getStringExtra(EXTRA_INITIAL_TEXT) ?: ""
        if (initialText.isNotBlank()) {
            accumulatedText.append(initialText)
            draftTextView.setText(initialText)
        }
        
        // 自动开始录音
        startRecording()
    }
    
    private fun setupImmersiveMode() {
        // 全屏，状态栏透明
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        // 深色背景
        window.decorView.setBackgroundColor(ContextCompat.getColor(this, R.color.typeink_screen_background))
    }
    
    private fun setupSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    
    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        contentContainer = findViewById(R.id.contentContainer)
        waveView = findViewById(R.id.waveView)
        draftTextView = findViewById(R.id.draftTextView)
        hintTextView = findViewById(R.id.hintTextView)
        statusTextView = findViewById(R.id.statusTextView)
        micButton = findViewById(R.id.micButton)
        doneButton = findViewById(R.id.doneButton)
        minimizeButton = findViewById(R.id.minimizeButton)
        
        // 麦克风按钮 - 开始/停止录音
        micButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        
        // 完成按钮 - 保存并退出
        doneButton.setOnClickListener {
            finishWithResult()
        }
        
        // 最小化按钮 - 回到小窗口模式
        minimizeButton.setOnClickListener {
            minimizeToFloating()
        }
        
        // 初始状态
        updateUiState(UiState.IDLE)
    }
    
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击切换录音状态
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
                return true
            }
            
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val diffY = e2.y - (e1?.y ?: 0f)
                
                // 快速下滑退出
                if (diffY > 200 && velocityY > 500) {
                    finishWithResult()
                    return true
                }
                return false
            }
        })
        
        // 缩放手势检测
        var initialDistance = 0f
        var isScaling = false
        
        rootContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        initialDistance = distanceBetween(event, 0, 1)
                        isScaling = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScaling && event.pointerCount == 2) {
                        val currentDistance = distanceBetween(event, 0, 1)
                        val scale = currentDistance / initialDistance
                        
                        // 双指捏合缩小到一定程度退出
                        if (scale < 0.6) {
                            finishWithResult()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    isScaling = false
                }
            }
            true
        }
    }
    
    private fun distanceBetween(event: MotionEvent, pointer1: Int, pointer2: Int): Float {
        val x = event.getX(pointer1) - event.getX(pointer2)
        val y = event.getY(pointer1) - event.getY(pointer2)
        return kotlin.math.sqrt(x * x + y * y)
    }
    
    private fun startRecording() {
        if (isRecording) return
        
        isRecording = true
        updateUiState(UiState.RECORDING)
        
        // 启动 ASR
        val snapshot = com.typeink.prototype.SessionInputSnapshot(
            originalText = "",
            selectionStart = 0,
            selectionEnd = 0,
            rewriteMode = false,
            source = com.typeink.prototype.SessionRewriteSource.DIRECT_INPUT
        )
        
        dashScopeService.setStyleMode(currentStyleMode)
        dashScopeService.startAsr(object : DashScopeService.Listener {
            override fun onAsrPartial(text: String) {
                runOnUiThread {
                    // 显示临时识别结果
                    val current = accumulatedText.toString()
                    draftTextView.setText("$current$text")
                    // 自动滚动到底部
                    draftTextView.setSelection(draftTextView.text.length)
                }
            }
            
            override fun onAsrFinal(text: String) {
                runOnUiThread {
                    // 确认段落，添加到累计文本
                    accumulatedText.append(text)
                    draftTextView.setText(accumulatedText.toString())
                    draftTextView.setSelection(draftTextView.text.length)
                    
                    // 视觉反馈 - 段落完成动画
                    pulseTextView()
                }
            }
            
            override fun onLlmDelta(token: String) {
                // 沉浸模式暂时不显示润色，保持原始草稿
            }
            
            override fun onLlmCompleted(finalText: String) {
                // 沉浸模式专注于原始输入
            }
            
            override fun onError(message: String) {
                runOnUiThread {
                    hintTextView.text = message
                    hintTextView.alpha = 1f
                    fadeOutHint()
                }
            }
        }, snapshot)
        
        // 启动录音
        recorder.start(object : PcmRecorder.Listener {
            override fun onAudioChunk(bytes: ByteArray) {
                dashScopeService.sendAudioChunk(bytes)
            }
            
            override fun onError(message: String) {
                runOnUiThread {
                    stopRecording()
                    hintTextView.text = "录音错误: $message"
                }
            }
            
            override fun onAmplitude(level: Float) {
                runOnUiThread {
                    waveView.setAmplitude(level)
                }
            }
        })
    }
    
    private fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recorder.stop()
        dashScopeService.stopAsr()
        
        updateUiState(UiState.PAUSED)
    }
    
    private fun updateUiState(state: UiState) {
        when (state) {
            UiState.IDLE -> {
                statusTextView.text = "准备就绪"
                hintTextView.text = "点击麦克风开始说话，或双指缩放退出"
                micButton.setImageResource(R.drawable.ic_mic_start)
                waveView.setVisualState(isActive = false, isProcessing = false, isError = false)
            }
            UiState.RECORDING -> {
                statusTextView.text = "正在聆听..."
                hintTextView.text = "说出你的想法，我会实时记录"
                micButton.setImageResource(R.drawable.ic_mic_stop)
                waveView.setVisualState(isActive = true, isProcessing = false, isError = false)
                fadeOutHint()
            }
            UiState.PAUSED -> {
                statusTextView.text = "已暂停"
                hintTextView.text = "点击麦克风继续，点击完成保存退出"
                micButton.setImageResource(R.drawable.ic_mic_start)
                waveView.setVisualState(isActive = false, isProcessing = false, isError = false)
            }
        }
    }
    
    private fun fadeOutHint() {
        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 2000
            startOffset = 3000
            fillAfter = true
        }
        hintTextView.startAnimation(fadeOut)
    }
    
    private fun pulseTextView() {
        val pulse = AlphaAnimation(0.7f, 1f).apply {
            duration = 300
            repeatMode = Animation.REVERSE
            repeatCount = 1
        }
        draftTextView.startAnimation(pulse)
    }
    
    private fun finishWithResult() {
        stopRecording()
        
        // 保存结果到输入法
        val result = draftTextView.text.toString()
        if (result.isNotBlank()) {
            // 通过广播或共享存储传递结果
            ImmersiveResultHolder.setResult(result)
        }
        
        // 优雅退出动画
        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 300
            fillAfter = true
        }
        fadeOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationRepeat(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                finish()
                overridePendingTransition(0, 0)
            }
        })
        rootContainer.startAnimation(fadeOut)
    }
    
    private fun minimizeToFloating() {
        // 最小化为浮动窗口（可选功能）
        stopRecording()
        isMinimized = true
        // TODO: 实现浮动窗口模式
        finish()
    }
    
    override fun onBackPressed() {
        // 双击返回或确认退出
        if (isRecording) {
            stopRecording()
        } else {
            finishWithResult()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }
    
    private enum class UiState {
        IDLE, RECORDING, PAUSED
    }
}

/**
 * 沉浸模式结果持有者
 */
object ImmersiveResultHolder {
    private var result: String = ""
    private var listener: ((String) -> Unit)? = null
    
    fun setResult(text: String) {
        result = text
        listener?.invoke(text)
    }
    
    fun getResult(): String = result
    
    fun clear() {
        result = ""
    }
    
    fun setListener(l: (String) -> Unit) {
        listener = l
    }
}
