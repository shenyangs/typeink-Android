package com.typeink.inputmethod

import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * 撤销管理器 - 参考 参考键盘 实现
 * 
 * 功能：
 * 1. 保存多个撤销快照（默认最多3个）
 * 2. 智能判断是否需要保存新快照
 * 3. 支持撤销和恢复
 */
internal class UndoManager(
    private val inputHelper: InputConnectionHelper,
    private val logTag: String = "UndoManager",
    private val maxSnapshots: Int = 3
) {
    
    data class UndoSnapshot(
        val beforeCursor: CharSequence,
        val afterCursor: CharSequence
    )
    
    private val snapshots = ArrayDeque<UndoSnapshot>(maxSnapshots)
    
    /**
     * 保存当前状态的快照
     * 
     * @param ic InputConnection
     * @param force 是否强制保存（忽略内容是否相同）
     */
    fun saveSnapshot(ic: InputConnection?, force: Boolean = false) {
        if (ic == null) {
            Log.w(logTag, "saveSnapshot: InputConnection is null")
            return
        }
        
        val newSnapshot = captureSnapshot(ic) ?: return
        
        if (force) {
            snapshots.addLast(newSnapshot)
            trim()
            Log.d(logTag, "Snapshot saved (forced), count=${snapshots.size}")
            return
        }
        
        // 智能判断：如果当前内容与栈顶不同，则保存新快照
        val topSnapshot = snapshots.lastOrNull()
        if (topSnapshot == null) {
            snapshots.addLast(newSnapshot)
            trim()
            Log.d(logTag, "First snapshot saved")
            return
        }
        
        try {
            val beforeChanged = newSnapshot.beforeCursor.toString() != topSnapshot.beforeCursor.toString()
            val afterChanged = newSnapshot.afterCursor.toString() != topSnapshot.afterCursor.toString()
            
            if (beforeChanged || afterChanged) {
                snapshots.addLast(newSnapshot)
                trim()
                Log.d(logTag, "Snapshot saved (content changed), count=${snapshots.size}")
            } else {
                Log.d(logTag, "Snapshot skipped (content unchanged)")
            }
        } catch (e: Throwable) {
            Log.w(logTag, "Failed to compare snapshot, saving anyway", e)
            snapshots.addLast(newSnapshot)
            trim()
        }
    }
    
    /**
     * 弹出并恢复上一个快照
     * 
     * @param ic InputConnection
     * @return 剩余快照数量，null 表示没有可恢复的快照
     */
    fun popAndRestoreSnapshot(ic: InputConnection?): Int? {
        if (ic == null) {
            Log.w(logTag, "popAndRestoreSnapshot: InputConnection is null")
            return null
        }
        
        val snapshot = snapshots.removeLastOrNull() ?: run {
            Log.d(logTag, "No snapshot to restore")
            return null
        }
        
        val ok = restoreSnapshot(ic, snapshot)
        if (!ok) {
            Log.e(logTag, "Failed to restore snapshot")
            return null
        }
        
        Log.d(logTag, "Snapshot restored, remaining=${snapshots.size}")
        return snapshots.size
    }
    
    /**
     * 查看上一个快照但不恢复
     */
    fun peekSnapshot(): UndoSnapshot? = snapshots.lastOrNull()
    
    /**
     * 获取当前快照数量
     */
    fun getSnapshotCount(): Int = snapshots.size
    
    /**
     * 是否有可撤销的快照
     */
    fun canUndo(): Boolean = snapshots.isNotEmpty()
    
    /**
     * 清空所有快照
     */
    fun clear() {
        snapshots.clear()
        Log.d(logTag, "All snapshots cleared")
    }
    
    /**
     * 捕获当前状态的快照
     */
    private fun captureSnapshot(ic: InputConnection?): UndoSnapshot? {
        if (ic == null) return null
        
        return try {
            val before = inputHelper.getTextBeforeCursor(ic, 10000) ?: ""
            val after = inputHelper.getTextAfterCursor(ic, 10000) ?: ""
            UndoSnapshot(before, after)
        } catch (e: Throwable) {
            Log.e(logTag, "Failed to capture snapshot", e)
            null
        }
    }
    
    /**
     * 恢复快照到输入框
     */
    private fun restoreSnapshot(ic: InputConnection, snapshot: UndoSnapshot): Boolean {
        return try {
            val before = snapshot.beforeCursor.toString()
            val after = snapshot.afterCursor.toString()
            
            inputHelper.beginBatchEdit(ic)
            
            // 获取当前内容长度
            val currBefore = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
            val currAfter = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
            
            // 删除当前所有内容
            if (currBefore.isNotEmpty()) {
                inputHelper.deleteSurroundingText(ic, currBefore.length, 0)
            }
            if (currAfter.isNotEmpty()) {
                inputHelper.deleteSurroundingText(ic, 0, currAfter.length)
            }
            
            // 恢复快照内容
            val fullText = before + after
            if (fullText.isNotEmpty()) {
                inputHelper.commitText(ic, fullText, 1)
                // 设置光标位置
                val cursorPos = before.length
                inputHelper.setSelection(ic, cursorPos, cursorPos)
            }
            
            inputHelper.finishComposingText(ic)
            inputHelper.endBatchEdit(ic)
            
            true
        } catch (e: Throwable) {
            Log.e(logTag, "restoreSnapshot failed", e)
            try {
                inputHelper.endBatchEdit(ic)
            } catch (_: Throwable) {}
            false
        }
    }
    
    private fun trim() {
        while (snapshots.size > maxSnapshots) {
            snapshots.removeFirst()
        }
    }
}
