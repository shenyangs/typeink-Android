package com.typeink.inputmethod

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class TypeinkImeShellPalette(
    val textPrimary: Color = Color(0xFFF8F4EE),
    val textSecondary: Color = Color(0xCCF4EEE7),
    val textHint: Color = Color(0x80F4EEE7),
    val accentWarm: Color = Color(0xFFF26B4C),
    val accentCool: Color = Color(0xFF96E8CF),
    val glassTop: Color = Color(0xFF101318),
    val glassBottom: Color = Color(0xFF171B21),
    val chipSurface: Color = Color(0x1AFFFFFF),
    val chipStrong: Color = Color(0x24FFFFFF),
    val keySurface: Color = Color(0xFF20252D),
    val keySurfaceStrong: Color = Color(0xFF2A303A),
    val errorSurface: Color = Color(0x2EF26B4C),
    val statusOnline: Color = Color(0xFF7DE8B7),
    val statusOnlineGlow: Color = Color(0x447DE8B7),
    val statusBusy: Color = Color(0xFFEFB56B),
    val statusBusyGlow: Color = Color(0x44EFB56B),
    val statusOffline: Color = Color(0xFFFF6A6A),
    val statusOfflineGlow: Color = Color(0x44FF6A6A),
)

@Immutable
data class TypeinkImeShellMetrics(
    val canvasRadius: Int = 28,
    val horizontalPadding: Int = 0,
    val verticalPadding: Int = 0,
    val previewCorner: Int = 18,
    val previewMinHeight: Int = 78,
    val utilityKeyHeight: Int = 38,
    val primaryKeyHeight: Int = 60,
    val secondaryKeyWidth: Int = 76,
    val actionGap: Int = 6,
)

object TypeinkImeShellTokens {
    val palette = TypeinkImeShellPalette()
    val metrics = TypeinkImeShellMetrics()

    val canvasCorner = 24.dp
}
