package com.typeink.inputmethod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.typeink.prototype.BuildConfig

class TypeinkDebugPreviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        TypeinkInputMethodService.dispatchDebugPreviewIntent(intent)
    }
}
