package com.typeink.inputmethod

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * IME 布局控制器 - 参考 参考键盘 实现
 *
 * 职责：
 * - 管理键盘布局的缩放和适配
 * - 处理系统 insets 变化
 * - 键盘高度调节
 */
class ImeLayoutController(
    private val rootView: View
) {
    
    companion object {
        private const val TAG = "ImeLayoutController"
    }
    
    // 系统导航栏高度
    private var systemNavBarHeight: Int = 0
    
    // 键盘高度缩放比例（1.0 = 默认，1.15 = 大，1.30 = 超大）
    private var heightScale: Float = 1.0f
    
    // Insets 监听器回调
    private var onInsetsChangedListener: ((bottomInset: Int) -> Unit)? = null
    
    /**
     * 安装键盘 insets 监听器
     */
    fun installKeyboardInsetsListener(onInsetsChanged: (bottomInset: Int) -> Unit) {
        this.onInsetsChangedListener = onInsetsChanged
        
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bottomInset = ImeInsetsResolver.resolveBottomInset(
                insets,
                view.resources
            )
            
            if (systemNavBarHeight != bottomInset) {
                systemNavBarHeight = bottomInset
                onInsetsChangedListener?.invoke(bottomInset)
            }
            
            insets
        }
        
        // 请求立即应用 insets
        rootView.requestApplyInsets()
    }
    
    /**
     * 设置键盘高度缩放
     *
     * @param scale 缩放比例：1.0 = 默认，1.15 = 大，1.30 = 超大
     */
    fun setKeyboardHeightScale(scale: Float) {
        heightScale = scale.coerceIn(0.8f, 1.5f)
        applyKeyboardHeightScale()
    }
    
    /**
     * 应用键盘高度缩放
     *
     * @return 是否发生了布局变化
     */
    fun applyKeyboardHeightScale(): Boolean {
        // 这里可以根据缩放比例调整布局参数
        // 例如：调整按键高度、间距等
        
        // TODO: 实现具体的缩放逻辑
        
        return false
    }
    
    /**
     * 获取系统导航栏高度
     */
    fun getSystemNavBarHeight(): Int = systemNavBarHeight
    
    /**
     * 获取键盘高度缩放比例
     */
    fun getHeightScale(): Float = heightScale
    
    /**
     * 修复 IME insets（用于冷启动时系统返回错误 insets 的情况）
     *
     * @param outInsets 输出的 insets
     */
    fun fixImeInsetsIfNeeded(outInsets: android.inputmethodservice.InputMethodService.Insets?) {
        outInsets?.let {
            val viewHeight = rootView.height
            if (viewHeight > 0) {
                val totalHeight = (viewHeight * heightScale).toInt() + systemNavBarHeight
                it.contentTopInsets = totalHeight
                it.visibleTopInsets = totalHeight
            }
        }
    }
}
