package com.typeink.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.typeink.prototype.R

/**
 * 本地剪贴板历史页面。
 *
 * 兼容沿用原来的类名与入口，避免影响设置中心和 Manifest。
 */
class SyncClipboardSettingsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SyncClipboardSettingsActivity::class.java)
            context.startActivity(intent)
        }
    }

    private lateinit var historyManager: ClipboardHistoryManager
    private lateinit var summaryText: TextView
    private lateinit var searchEdit: EditText
    private lateinit var historyListView: ListView
    private lateinit var clearButton: ImageButton
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_syncclipboard_settings)

        supportActionBar?.apply {
            title = "本地剪贴板"
            setDisplayHomeAsUpEnabled(true)
        }

        ClipboardHistoryTracker.start(this)
        historyManager = ClipboardHistoryManager.getInstance(this)

        initViews()
        bindEvents()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroy() {
        ClipboardHistoryTracker.stop()
        super.onDestroy()
    }

    private fun initViews() {
        summaryText = findViewById(R.id.statusText)
        searchEdit = findViewById(R.id.searchEditText)
        historyListView = findViewById(R.id.historyListView)
        clearButton = findViewById(R.id.clearButton)

        adapter =
            HistoryAdapter(emptyList()) { item, action ->
                when (action) {
                    Action.CLICK -> restoreToSystemClipboard(item)
                    Action.DELETE -> {
                        historyManager.removeHistory(item.id)
                        refreshList()
                    }
                }
            }

        historyListView.adapter = adapter
    }

    private fun bindEvents() {
        searchEdit.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    refreshList()
                }
            },
        )

        clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("确定要清空最近 100 条本地剪贴板历史吗？")
                .setPositiveButton("清空") { _, _ ->
                    historyManager.clearHistory()
                    refreshList()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshList() {
        val query = searchEdit.text?.toString().orEmpty().trim()
        val items =
            if (query.isBlank()) {
                historyManager.getAllHistory()
            } else {
                historyManager.searchHistory(query)
            }
        adapter.updateList(items)

        val total = historyManager.getHistoryCount()
        summaryText.text =
            if (total > 0) {
                "已保存 ${total}/${ClipboardHistoryManager.MAX_HISTORY_SIZE} 条本地剪贴板历史。点一条即可重新放回系统剪贴板。"
            } else {
                "还没有历史记录。复制过的文本会自动保存在本机，不再走服务器同步。"
            }
    }

    private fun restoreToSystemClipboard(item: ClipboardHistoryManager.HistoryItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, "系统剪贴板当前不可用", Toast.LENGTH_SHORT).show()
            return
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("TypeinkClipboardHistory", item.text))
        Toast.makeText(this, "已放回系统剪贴板", Toast.LENGTH_SHORT).show()
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

    private enum class Action {
        CLICK,
        DELETE,
    }

    private class HistoryAdapter(
        private var items: List<ClipboardHistoryManager.HistoryItem>,
        private val onItemAction: (ClipboardHistoryManager.HistoryItem, Action) -> Unit,
    ) : BaseAdapter() {

        fun updateList(newItems: List<ClipboardHistoryManager.HistoryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?,
        ): View {
            val view =
                convertView
                    ?: LayoutInflater.from(parent?.context)
                        .inflate(R.layout.item_clipboard_history, parent, false)

            val item = items[position]
            view.findViewById<TextView>(R.id.typeIcon).text =
                when (item.type) {
                    ClipboardHistoryManager.HistoryItem.ItemType.TEXT -> "📝"
                    ClipboardHistoryManager.HistoryItem.ItemType.IMAGE -> "🖼️"
                    ClipboardHistoryManager.HistoryItem.ItemType.FILE -> "📎"
                }
            view.findViewById<TextView>(R.id.contentText).text = item.displayText
            view.findViewById<TextView>(R.id.timeText).text = item.timeDescription
            view.setOnClickListener { onItemAction(item, Action.CLICK) }
            view.findViewById<ImageButton>(R.id.deleteButton).setOnClickListener {
                onItemAction(item, Action.DELETE)
            }
            return view
        }
    }
}
