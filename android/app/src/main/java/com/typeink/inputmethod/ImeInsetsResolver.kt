package com.typeink.inputmethod

import android.content.res.Resources
import androidx.core.view.WindowInsetsCompat

/**
 * IME Insets 解析器 - 参考 参考键盘 实现
 *
 * 统一解析 IME 底部系统占位，避免不同 ROM（魅族 AIOS、小米 MIUI 等）
 * 在导航手势/工具条场景下高度漏算。
 */
object ImeInsetsResolver {
    
    /**
     * 默认导航栏高度（dp）
     */
    private const val FALLBACK_NAV_BAR_FRAME_HEIGHT_DP = 48
    
    /**
     * 解析底部 insets 高度
     *
     * @param insets WindowInsetsCompat
     * @param resources Resources
     * @return 底部需要预留的高度（像素）
     */
    fun resolveBottomInset(insets: WindowInsetsCompat, resources: Resources): Int {
        // 获取各种类型的底部 insets
        val navBarsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val mandatoryBottom = insets.getInsets(
            WindowInsetsCompat.Type.mandatorySystemGestures()
        ).bottom
        val tappableBottom = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
        val systemGesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        
        // 取最大值
        var resolvedBottom = maxOf(navBarsBottom, mandatoryBottom, tappableBottom)
        
        // 如果都为 0，尝试从系统资源获取导航栏高度
        if (resolvedBottom <= 0) {
            resolvedBottom = maxOf(systemGesturesBottom, getNavigationBarFrameHeight(resources))
        }
        
        return resolvedBottom.coerceAtLeast(0)
    }
    
    /**
     * 从系统资源获取导航栏高度
     */
    private fun getNavigationBarFrameHeight(resources: Resources): Int {
        // 尝试获取系统定义的导航栏高度
        val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        if (resId > 0) {
            try {
                return resources.getDimensionPixelSize(resId)
            } catch (_: Resources.NotFoundException) {
                // 忽略错误，使用默认值
            }
        }
        
        // 使用默认值计算
        val density = resources.displayMetrics.density
        return (FALLBACK_NAV_BAR_FRAME_HEIGHT_DP * density + 0.5f).toInt()
    }
    
    /**
     * 检查当前系统是否使用手势导航
     */
    fun isGestureNavigation(insets: WindowInsetsCompat): Boolean {
        val navBarsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        
        // 如果手势区域大于导航栏区域，可能是手势导航
        return gesturesBottom > navBarsBottom
    }
}
