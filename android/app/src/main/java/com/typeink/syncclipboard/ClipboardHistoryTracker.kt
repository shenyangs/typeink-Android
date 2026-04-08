package com.typeink.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * 系统剪贴板监听器。
 *
 * 目标：
 * 1. 监听系统主剪贴板变化；
 * 2. 将文本内容写入本地最近 100 条历史；
 * 3. 多处调用 start/stop 时避免重复注册监听。
 */
object ClipboardHistoryTracker {
    private const val TAG = "ClipboardHistoryTracker"

    private val lock = Any()

    @Volatile
    private var startedCount: Int = 0

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var clipboardManager: ClipboardManager? = null

    private val listener =
        ClipboardManager.OnPrimaryClipChangedListener {
            persistCurrentPrimaryClip()
        }

    fun start(context: Context) {
        synchronized(lock) {
            val applicationContext = context.applicationContext
            if (startedCount == 0) {
                appContext = applicationContext
                clipboardManager =
                    applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboardManager?.addPrimaryClipChangedListener(listener)
                persistCurrentPrimaryClip()
                Log.d(TAG, "Clipboard tracker started")
            }
            startedCount++
        }
    }

    fun stop() {
        synchronized(lock) {
            if (startedCount <= 0) return
            startedCount--
            if (startedCount == 0) {
                clipboardManager?.removePrimaryClipChangedListener(listener)
                clipboardManager = null
                appContext = null
                Log.d(TAG, "Clipboard tracker stopped")
            }
        }
    }

    private fun persistCurrentPrimaryClip() {
        val context = appContext ?: return
        val clipboard = clipboardManager ?: return
        val clip = try {
            clipboard.primaryClip
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read primary clip", t)
            null
        } ?: return

        val text = extractText(clip).orEmpty().trim()
        if (text.isBlank()) return

        ClipboardHistoryManager.getInstance(context).addHistory(
            type = ClipboardHistoryManager.HistoryItem.ItemType.TEXT,
            text = text,
        )
    }

    private fun extractText(clip: ClipData): String? {
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        return item.text?.toString()
            ?: item.coerceToText(appContext).toString()
    }
}
