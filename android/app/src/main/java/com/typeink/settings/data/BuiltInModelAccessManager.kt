package com.typeink.settings.data

import android.content.Context
import android.content.SharedPreferences

data class BuiltInActivationResult(
    val success: Boolean,
    val message: String,
)

class BuiltInModelAccessManager private constructor(context: Context) {
    companion object {
        private const val PREFS_NAME = "built_in_model_access"
        private const val KEY_USED_COUNT = "used_count"
        private const val KEY_ACTIVATED = "activated"
        private const val KEY_ACTIVATED_BY = "activated_by"
        const val TRIAL_LIMIT = 100

        private val VALID_CODES =
            setOf(
                "shenyang",
                "samyum",
            )

        @Volatile
        private var instance: BuiltInModelAccessManager? = null

        fun getInstance(context: Context): BuiltInModelAccessManager {
            return instance ?: synchronized(this) {
                instance ?: BuiltInModelAccessManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isActivated(): Boolean = prefs.getBoolean(KEY_ACTIVATED, false)

    fun getActivatedBy(): String? = prefs.getString(KEY_ACTIVATED_BY, null)?.takeIf { it.isNotBlank() }

    fun getUsedCount(): Int = prefs.getInt(KEY_USED_COUNT, 0).coerceAtLeast(0)

    fun getRemainingTrialCount(): Int {
        if (isActivated()) return Int.MAX_VALUE
        return (TRIAL_LIMIT - getUsedCount()).coerceAtLeast(0)
    }

    fun canUseBuiltInModel(): Boolean = isActivated() || getUsedCount() < TRIAL_LIMIT

    @Synchronized
    fun tryConsumeBuiltInUse(): Boolean {
        if (isActivated()) return true
        val usedCount = getUsedCount()
        if (usedCount >= TRIAL_LIMIT) return false
        prefs.edit().putInt(KEY_USED_COUNT, usedCount + 1).apply()
        return true
    }

    fun activate(rawCode: String): BuiltInActivationResult {
        val normalized = rawCode.trim().lowercase()
        if (normalized.isBlank()) {
            return BuiltInActivationResult(
                success = false,
                message = "请输入激活码。",
            )
        }
        if (!VALID_CODES.contains(normalized)) {
            return BuiltInActivationResult(
                success = false,
                message = "激活码不正确。",
            )
        }
        prefs.edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_ACTIVATED_BY, normalized)
            .apply()
        return BuiltInActivationResult(
            success = true,
            message = "内置模型已激活，可无限使用。",
        )
    }

    fun getBadgeLabel(): String {
        val activatedBy = getActivatedBy()
        return when {
            isActivated() && activatedBy != null -> "已激活 · $activatedBy"
            isActivated() -> "已激活"
            canUseBuiltInModel() -> "试用剩余 ${getRemainingTrialCount()}/$TRIAL_LIMIT"
            else -> "试用已用尽"
        }
    }

    fun getSummaryText(): String {
        val activatedBy = getActivatedBy()
        return when {
            isActivated() && activatedBy != null ->
                "状态：已激活\n激活码：$activatedBy\n内置模型当前不再受试用次数限制。"
            isActivated() ->
                "状态：已激活\n内置模型当前不再受试用次数限制。"
            else ->
                "状态：试用中\n剩余次数：${getRemainingTrialCount()}/$TRIAL_LIMIT\n输入正确激活码后，可无限使用内置模型。"
        }
    }

    fun getUsageCaption(): String {
        return if (isActivated()) {
            "无限使用已解锁"
        } else {
            "剩余 ${getRemainingTrialCount()} / $TRIAL_LIMIT 次"
        }
    }

    fun getProgressValue(): Int {
        return if (isActivated()) TRIAL_LIMIT else getRemainingTrialCount().coerceIn(0, TRIAL_LIMIT)
    }

    fun buildLimitReachedMessage(): String =
        "内置模型试用 100 次已用完，请到设置里输入激活码，或改用自定义模型。"
}
