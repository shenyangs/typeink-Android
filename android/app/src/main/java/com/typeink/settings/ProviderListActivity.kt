package com.typeink.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.typeink.prototype.R
import com.typeink.settings.data.ProviderManager
import com.typeink.settings.model.ProviderConfig
import com.typeink.settings.model.ProviderType

/**
 * 厂商列表页面 - 选择 ASR/LLM 厂商并配置 API Key
 */
class ProviderListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PROVIDER_TYPE = "provider_type"
        
        fun start(context: Context, providerType: ProviderType) {
            val intent = Intent(context, ProviderListActivity::class.java)
            intent.putExtra(EXTRA_PROVIDER_TYPE, providerType.name)
            context.startActivity(intent)
        }
    }
    
    private lateinit var providerManager: ProviderManager
    private lateinit var providerType: ProviderType
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProviderAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_list)
        
        providerType = ProviderType.valueOf(
            intent.getStringExtra(EXTRA_PROVIDER_TYPE) ?: ProviderType.ASR.name
        )
        
        supportActionBar?.apply {
            title = if (providerType == ProviderType.ASR) "语音识别引擎" else "智能改写引擎"
            setDisplayHomeAsUpEnabled(true)
        }
        
        providerManager = ProviderManager.getInstance(this)
        
        recyclerView = findViewById(R.id.providerRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ProviderAdapter { config ->
            showConfigDialog(config)
        }
        recyclerView.adapter = adapter
        
        loadProviders()
    }
    
    private fun loadProviders() {
        val providers = if (providerType == ProviderType.ASR) {
            providerManager.getAllAsrProviders()
        } else {
            providerManager.getAllLlmProviders()
        }
        adapter.submitList(providers)
    }
    
    private fun showConfigDialog(config: ProviderConfig) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_provider_config, null)
        
        val nameText = dialogView.findViewById<TextView>(R.id.providerNameText)
        val descText = dialogView.findViewById<TextView>(R.id.providerDescText)
        val apiKeyInput = dialogView.findViewById<android.widget.EditText>(R.id.apiKeyInput)
        val modelInput = dialogView.findViewById<android.widget.EditText>(R.id.modelInput)
        val urlInput = dialogView.findViewById<android.widget.EditText>(R.id.urlInput)
        
        nameText.text = config.name
        descText.text = config.description
        
        // 填充现有配置
        apiKeyInput.setText(config.userApiKey ?: "")
        modelInput.setText(config.userModel ?: config.defaultModel ?: "")
        urlInput.setText(config.userBaseUrl ?: config.builtInBaseUrl ?: "")
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val updated = config.copy(
                    userApiKey = apiKeyInput.text.toString().takeIf { it.isNotBlank() },
                    userModel = modelInput.text.toString().takeIf { it.isNotBlank() },
                    userBaseUrl = urlInput.text.toString().takeIf { it.isNotBlank() },
                    isEnabled = true,
                    priority = 100  // 用户配置优先级更高
                )
                providerManager.updateUserConfig(updated)
                loadProviders()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("重置") { _, _ ->
                // 清除用户配置，恢复内置
                providerManager.updateUserConfig(
                    config.copy(
                        userApiKey = null,
                        userModel = null,
                        userBaseUrl = null,
                        priority = config.builtInPriority
                    )
                )
                loadProviders()
            }
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.typeink_text))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.typeink_muted))
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(getColor(R.color.typeink_accent_end))
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
    
    /**
     * 厂商列表适配器
     */
    inner class ProviderAdapter(
        private val onConfigClick: (ProviderConfig) -> Unit
    ) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {
        
        private var providers: List<ProviderConfig> = emptyList()
        
        fun submitList(list: List<ProviderConfig>) {
            providers = list
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_provider, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(providers[position])
        }
        
        override fun getItemCount() = providers.size
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.providerName)
            private val statusText: TextView = itemView.findViewById(R.id.providerStatus)
            private val radioButton: RadioButton = itemView.findViewById(R.id.providerRadio)
            private val configButton: Button = itemView.findViewById(R.id.configButton)
            
            fun bind(config: ProviderConfig) {
                nameText.text = config.name
                
                val status = when {
                    config.hasUserConfig() -> "已配置"
                    config.builtInApiKey != null -> "内置"
                    else -> "未配置"
                }
                statusText.text = status
                statusText.setBackgroundResource(
                    when {
                        config.hasUserConfig() || config.builtInApiKey != null -> R.drawable.bg_typeink_success_badge
                        else -> R.drawable.bg_typeink_status_badge
                    },
                )
                
                // 高亮当前选中的
                val isCurrent = if (providerType == ProviderType.ASR) {
                    providerManager.getCurrentAsrProvider().id == config.id
                } else {
                    providerManager.getCurrentLlmProvider().id == config.id
                }
                radioButton.isChecked = isCurrent
                itemView.setBackgroundResource(
                    if (isCurrent) R.drawable.bg_typeink_selected_card else R.drawable.bg_typeink_result_card,
                )
                
                configButton.setOnClickListener {
                    onConfigClick(config)
                }
                
                itemView.setOnClickListener {
                    // 选择这个厂商
                    val updated = config.copy(isEnabled = true, priority = 100)
                    providerManager.updateUserConfig(updated)
                    loadProviders()
                }
            }
        }
    }
}
