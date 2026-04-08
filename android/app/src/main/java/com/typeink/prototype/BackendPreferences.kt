package com.typeink.prototype

import android.content.Context

class BackendPreferences(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBackendUrl(): String {
        return sharedPreferences.getString(PREF_BACKEND_URL, "").orEmpty().trim()
    }

    fun setBackendUrl(value: String) {
        sharedPreferences.edit().putString(PREF_BACKEND_URL, value.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "typeink_android"
        private const val PREF_BACKEND_URL = "backend_url"
    }
}

