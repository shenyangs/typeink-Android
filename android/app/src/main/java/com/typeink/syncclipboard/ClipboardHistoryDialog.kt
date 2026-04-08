package com.typeink.syncclipboard

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import com.typeink.prototype.R

/**
 * 剪贴板历史对话框
 */
class ClipboardHistoryDialog(
    private val context: Context,
    private val historyManager: ClipboardHistoryManager,
    private val onItemClick: (String) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var adapter: HistoryAdapter? = null
    
    /**
     * 显示对话框
     */
    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_clipboard_history, null)
        
        val listView = view.findViewById<ListView>(R.id.historyListView)
        val searchEdit = view.findViewById<EditText>(R.id.searchEditText)
        val clearButton = view.findViewById<ImageButton>(R.id.clearButton)
        
        adapter = HistoryAdapter(historyManager.getAllHistory()) { item, action ->
            when (action) {
                Action.CLICK -> {
                    onItemClick(item.text)
                    dialog?.dismiss()
                }
                Action.DELETE -> {
                    historyManager.removeHistory(item.id)
                    refreshList()
                }
            }
        }
        
        listView.adapter = adapter
        
        // 搜索功能
        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString() ?: ""
                val filtered = if (query.isBlank()) {
                    historyManager.getAllHistory()
                } else {
                    historyManager.searchHistory(query)
                }
                adapter?.updateList(filtered)
            }
        })
        
        // 清空按钮
        clearButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("清空历史")
                .setMessage("确定要清空所有剪贴板历史吗？")
                .setPositiveButton("清空") { _, _ ->
                    historyManager.clearHistory()
                    adapter?.updateList(emptyList())
                }
                .setNegativeButton("取消", null)
                .show()
        }
        
        dialog = AlertDialog.Builder(context)
            .setTitle("剪贴板历史")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        
        dialog?.show()
    }
    
    /**
     * 刷新列表
     */
    private fun refreshList() {
        adapter?.updateList(historyManager.getAllHistory())
    }
    
    /**
     * 关闭对话框
     */
    fun dismiss() {
        dialog?.dismiss()
    }
    
    enum class Action {
        CLICK, DELETE
    }
    
    /**
     * 历史列表适配器
     */
    private class HistoryAdapter(
        private var items: List<ClipboardHistoryManager.HistoryItem>,
        private val onItemAction: (ClipboardHistoryManager.HistoryItem, Action) -> Unit
    ) : BaseAdapter() {
        
        fun updateList(newItems: List<ClipboardHistoryManager.HistoryItem>) {
            items = newItems
            notifyDataSetChanged()
        }
        
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(parent?.context)
                .inflate(R.layout.item_clipboard_history, parent, false)
            
            val item = items[position]
            
            val typeIcon = view.findViewById<TextView>(R.id.typeIcon)
            val contentText = view.findViewById<TextView>(R.id.contentText)
            val timeText = view.findViewById<TextView>(R.id.timeText)
            val deleteButton = view.findViewById<ImageButton>(R.id.deleteButton)
            
            // 设置类型图标
            typeIcon.text = when (item.type) {
                ClipboardHistoryManager.HistoryItem.ItemType.TEXT -> "📝"
                ClipboardHistoryManager.HistoryItem.ItemType.IMAGE -> "🖼️"
                ClipboardHistoryManager.HistoryItem.ItemType.FILE -> "📎"
            }
            
            contentText.text = item.displayText
            timeText.text = item.timeDescription
            
            view.setOnClickListener {
                onItemAction(item, Action.CLICK)
            }
            
            deleteButton.setOnClickListener {
                onItemAction(item, Action.DELETE)
            }
            
            return view
        }
    }
}
