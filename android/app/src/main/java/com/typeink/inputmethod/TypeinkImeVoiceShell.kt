package com.typeink.inputmethod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typeink.core.input.TypeinkInputPhase
import com.typeink.core.input.TypeinkInputState
import com.typeink.core.session.TypeinkEditPhase
import com.typeink.core.session.TypeinkEditSessionState
import com.typeink.core.session.TypeinkSessionTextState
import com.typeink.prototype.R

@Immutable
data class TypeinkImeShellModel(
    val inputState: TypeinkInputState,
    val sessionTextState: TypeinkSessionTextState,
    val editSessionState: TypeinkEditSessionState,
    val isKeyboardVisible: Boolean,
    val amplitude: Float,
    val voiceInputEnabled: Boolean,
    val blockedReason: String,
    val hasPendingPreview: Boolean,
    val canUndoRewrite: Boolean,
    val isClipboardHistoryVisible: Boolean,
    val clipboardHistory: List<ClipboardHistoryEntry>,
)

@Immutable
data class ClipboardHistoryEntry(
    val id: Long,
    val text: String,
    val timeDescription: String,
)

private data class ShellCopy(
    val badge: String,
    val statusTone: ShellStatusTone,
    val title: String,
    val helper: String,
    val preview: String,
    val previewLabel: String,
    val previewCompact: Boolean,
    val micLabel: String,
    val micHelper: String,
    val rightPrimaryLabel: String,
    val rightPrimaryEnabled: Boolean,
)

private enum class ShellStatusTone {
    ONLINE,
    BUSY,
    OFFLINE,
}

@Composable
fun TypeinkImeVoiceShell(
    model: TypeinkImeShellModel,
    onMicTap: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onKeepOriginal: () -> Unit,
    onComma: () -> Unit,
    onPeriod: () -> Unit,
    onQuestion: () -> Unit,
    onExclamation: () -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onKeyboard: () -> Unit,
    onHide: () -> Unit,
    onClipboardToggle: () -> Unit,
    onClipboardSelect: (ClipboardHistoryEntry) -> Unit,
    onClipboardDelete: (ClipboardHistoryEntry) -> Unit,
    onClipboardClear: () -> Unit,
    onUndoRewrite: () -> Unit,
    onCancelEdit: () -> Unit,
    onDoneEdit: () -> Unit,
) {
    val palette = TypeinkImeShellTokens.palette
    val metrics = TypeinkImeShellTokens.metrics
    val copy = resolveShellCopy(model)
    val hasVisibleText = copy.preview.isNotBlank()
    val recordingGlow by animateFloatAsState(
        targetValue = if (model.inputState.isRecording) 1f else 0.4f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 260f),
        label = "recordingGlow",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(palette.glassTop, palette.glassBottom),
                    ),
                ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        KeyboardAtmosphere(
            modifier = Modifier.matchParentSize(),
            palette = palette,
            recordingStrength = recordingGlow,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = metrics.canvasRadius.dp, topEnd = metrics.canvasRadius.dp),
            color = palette.glassBottom,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF191E25), Color(0xFF11151B)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactStatusBar(
                    badge = copy.badge,
                    statusTone = copy.statusTone,
                    title = copy.title,
                    palette = palette,
                    clipboardEnabled = true,
                    clipboardExpanded = model.isClipboardHistoryVisible,
                    onClipboardToggle = onClipboardToggle,
                    onHide = onHide,
                )
                PreviewBand(
                    label = copy.previewLabel,
                    text = copy.preview,
                    compact = copy.previewCompact,
                    placeholder = "说一句，结果先稳稳落进输入框。",
                    palette = palette,
                    metrics = metrics,
                )
                UtilityGrid(
                    model = model,
                    hasVisibleText = hasVisibleText,
                    palette = palette,
                    metrics = metrics,
                    onComma = onComma,
                    onPeriod = onPeriod,
                    onQuestion = onQuestion,
                    onExclamation = onExclamation,
                    onEnter = onEnter,
                    onClear = onClear,
                    onRetry = onRetry,
                    onKeepOriginal = onKeepOriginal,
                    onSpace = onSpace,
                    onEdit = onEdit,
                    onSend = onSend,
                    onUndoRewrite = onUndoRewrite,
                    onCancelEdit = onCancelEdit,
                    onDoneEdit = onDoneEdit,
                )
                PrimaryKeyRow(
                    model = model,
                    copy = copy,
                    palette = palette,
                    metrics = metrics,
                    recordingGlow = recordingGlow,
                    onMicTap = onMicTap,
                    onKeyboard = onKeyboard,
                    onBackspace = onBackspace,
                )
            }
        }

        ClipboardHistorySheet(
            visible = model.isClipboardHistoryVisible,
            history = model.clipboardHistory,
            palette = palette,
            onClose = onClipboardToggle,
            onSelect = onClipboardSelect,
            onDelete = onClipboardDelete,
            onClear = onClipboardClear,
        )
    }
}

@Composable
private fun CompactStatusBar(
    badge: String,
    statusTone: ShellStatusTone,
    title: String,
    palette: TypeinkImeShellPalette,
    clipboardEnabled: Boolean,
    clipboardExpanded: Boolean,
    onClipboardToggle: () -> Unit,
    onHide: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusBeacon(
            label = badge,
            tone = statusTone,
            palette = palette,
        )
        if (title.isNotBlank()) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconKeyButton(
                palette = palette,
                size = 34.dp,
                onClick = onClipboardToggle,
                iconRes = R.drawable.ic_clipboard,
                active = clipboardExpanded,
                enabled = clipboardEnabled,
            )
            IconKeyButton(
                palette = palette,
                size = 34.dp,
                onClick = onHide,
                iconRes = R.drawable.ic_keyboard_hide_thin,
            )
        }
    }
}

@Composable
private fun ClipboardHistorySheet(
    visible: Boolean,
    history: List<ClipboardHistoryEntry>,
    palette: TypeinkImeShellPalette,
    onClose: () -> Unit,
    onSelect: (ClipboardHistoryEntry) -> Unit,
    onDelete: (ClipboardHistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0x660A0E13))
                    .clickable(onClick = onClose),
        ) {
            ClipboardHistoryPanel(
                history = history,
                palette = palette,
                onClose = onClose,
                onSelect = onSelect,
                onDelete = onDelete,
                onClear = onClear,
            )
        }
    }
}

@Composable
private fun ClipboardHistoryPanel(
    history: List<ClipboardHistoryEntry>,
    palette: TypeinkImeShellPalette,
    onClose: () -> Unit,
    onSelect: (ClipboardHistoryEntry) -> Unit,
    onDelete: (ClipboardHistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ClipboardHistoryFilter.ALL) }
    val filteredItems = remember(history, query, filter) {
        filterClipboardHistory(history, query, filter)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Color(0xFF2F3137),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF44444A), Color(0xFF2E2E33), Color(0xFF25262B)),
                            ),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(width = 40.dp, height = 40.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = palette.keySurfaceStrong,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_clipboard),
                                contentDescription = "剪贴板历史",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "剪贴板",
                            color = palette.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "最近 ${history.size}/${com.typeink.syncclipboard.ClipboardHistoryManager.MAX_HISTORY_SIZE} 条，点一下直接粘贴",
                            color = palette.textHint,
                            fontSize = 11.sp,
                        )
                    }
                    IconKeyButton(
                        palette = palette,
                        size = 34.dp,
                        onClick = onClear,
                        iconRes = R.drawable.ic_clear,
                        enabled = history.isNotEmpty(),
                    )
                    IconKeyButton(
                        palette = palette,
                        size = 34.dp,
                        onClick = onClose,
                        iconRes = R.drawable.ic_keyboard_hide_thin,
                    )
                }

                SearchField(
                    value = query,
                    palette = palette,
                    onValueChange = { query = it },
                )

                FilterRow(
                    selected = filter,
                    palette = palette,
                    onSelected = { filter = it },
                )

                if (filteredItems.isEmpty()) {
                    EmptyClipboardState(
                        query = query,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            ClipboardHistoryCard(
                                entry = item,
                                palette = palette,
                                onSelect = { onSelect(item) },
                                onDelete = { onDelete(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    palette: TypeinkImeShellPalette,
    onValueChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF232830),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle =
                androidx.compose.ui.text.TextStyle(
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "搜索",
                        color = palette.textHint,
                        fontSize = 13.sp,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isBlank()) {
                            Text(
                                text = "搜索历史内容",
                                color = palette.textHint,
                                fontSize = 15.sp,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}

private enum class ClipboardHistoryFilter(val label: String) {
    ALL("全部"),
    RECENT("最近"),
    TEXT("文本"),
    NUMBER("数字"),
    LINK("链接"),
}

@Composable
private fun FilterRow(
    selected: ClipboardHistoryFilter,
    palette: TypeinkImeShellPalette,
    onSelected: (ClipboardHistoryFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClipboardHistoryFilter.entries.forEach { item ->
            val active = item == selected
            Surface(
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (active) Color(0x26F26B4C) else Color.Transparent,
                onClick = { onSelected(item) },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        color = if (active) palette.accentWarm else palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyClipboardState(
    query: String,
    palette: TypeinkImeShellPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (query.isBlank()) "还没有可用的剪贴板历史" else "没有搜到匹配内容",
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (query.isBlank()) "先去复制一点文字，这里会自动收集最近 100 条。" else "换个关键词试试，或者切回“全部”。",
                color = palette.textHint,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ClipboardHistoryCard(
    entry: ClipboardHistoryEntry,
    palette: TypeinkImeShellPalette,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF4A4A4F),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.text,
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = entry.timeDescription,
                    color = palette.textHint,
                    fontSize = 11.sp,
                )
            }
            IconKeyButton(
                palette = palette,
                size = 30.dp,
                onClick = onDelete,
                iconRes = R.drawable.ic_clear,
            )
        }
    }
}

@Composable
private fun PreviewBand(
    label: String,
    text: String,
    compact: Boolean,
    placeholder: String,
    palette: TypeinkImeShellPalette,
    metrics: TypeinkImeShellMetrics,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(metrics.previewCorner.dp))
                .background(palette.keySurface)
                .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp)
                .height(if (compact) (metrics.previewMinHeight - 6).dp else metrics.previewMinHeight.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = palette.textHint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text.ifBlank { placeholder },
            modifier = Modifier.fillMaxWidth(),
            color = if (text.isBlank()) palette.textHint else palette.textPrimary,
            fontSize =
                when {
                    compact -> 14.sp
                    text.length > 32 -> 14.sp
                    else -> 15.sp
                },
            lineHeight = if (compact) 20.sp else 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UtilityGrid(
    model: TypeinkImeShellModel,
    hasVisibleText: Boolean,
    palette: TypeinkImeShellPalette,
    metrics: TypeinkImeShellMetrics,
    onComma: () -> Unit,
    onPeriod: () -> Unit,
    onQuestion: () -> Unit,
    onExclamation: () -> Unit,
    onEnter: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onKeepOriginal: () -> Unit,
    onSpace: () -> Unit,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onUndoRewrite: () -> Unit,
    onCancelEdit: () -> Unit,
    onDoneEdit: () -> Unit,
) {
    val topRow =
        listOf(
            UtilityKeySpec("，", true, onComma),
            UtilityKeySpec("。", true, onPeriod),
            UtilityKeySpec("？", true, onQuestion),
            UtilityKeySpec("！", true, onExclamation),
            UtilityKeySpec("回车", true, onEnter),
        )
    val bottomRow =
        when {
            !model.voiceInputEnabled -> {
                listOf(
                    UtilityKeySpec("空格", true, onSpace),
                    UtilityKeySpec("修改", false, onEdit),
                    UtilityKeySpec("发送", false, onSend),
                    UtilityKeySpec("清空", hasVisibleText, onClear),
                )
            }
            model.inputState.isError -> {
                listOf(
                    UtilityKeySpec("空格", true, onSpace),
                    UtilityKeySpec("重试", true, onRetry),
                    UtilityKeySpec("保留", true, onKeepOriginal),
                    UtilityKeySpec("清空", hasVisibleText, onClear),
                )
            }
            model.editSessionState.isActive -> {
                listOf(
                    UtilityKeySpec("空格", true, onSpace),
                    UtilityKeySpec("撤改", model.canUndoRewrite, onUndoRewrite),
                    UtilityKeySpec("完成", model.editSessionState.phase == TypeinkEditPhase.READY, onDoneEdit),
                    UtilityKeySpec("放弃", true, onCancelEdit),
                )
            }
            else -> {
                listOf(
                    UtilityKeySpec("空格", true, onSpace),
                    UtilityKeySpec("修改", hasVisibleText, onEdit),
                    UtilityKeySpec("发送", model.hasPendingPreview, onSend),
                    UtilityKeySpec("清空", hasVisibleText, onClear),
                )
            }
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(metrics.actionGap.dp),
    ) {
        UtilityRow(
            specs = topRow,
            palette = palette,
            metrics = metrics,
        )
        UtilityRow(
            specs = bottomRow,
            palette = palette,
            metrics = metrics,
        )
    }
}

@Composable
private fun PrimaryKeyRow(
    model: TypeinkImeShellModel,
    copy: ShellCopy,
    palette: TypeinkImeShellPalette,
    metrics: TypeinkImeShellMetrics,
    recordingGlow: Float,
    onMicTap: () -> Unit,
    onKeyboard: () -> Unit,
    onBackspace: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(metrics.actionGap.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(metrics.actionGap.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.actionGap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryKey(
                    text = "ABC",
                    palette = palette,
                    height = metrics.primaryKeyHeight.dp,
                    width = metrics.secondaryKeyWidth.dp,
                    onClick = onKeyboard,
                )
                MicBarKey(
                    label = copy.micLabel,
                    palette = palette,
                    height = metrics.primaryKeyHeight.dp,
                    enabled = model.voiceInputEnabled,
                    glowStrength = recordingGlow,
                    onClick = if (model.voiceInputEnabled) onMicTap else onKeyboard,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(metrics.actionGap.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconKeyButton(
                palette = palette,
                size = metrics.primaryKeyHeight.dp,
                onClick = onBackspace,
                iconRes = R.drawable.ic_backspace_thin,
            )
        }
    }
}

@Composable
private fun UtilityRow(
    specs: List<UtilityKeySpec>,
    palette: TypeinkImeShellPalette,
    metrics: TypeinkImeShellMetrics,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(metrics.actionGap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        specs.forEach { spec ->
            UtilityKey(
                label = spec.label,
                enabled = spec.enabled,
                palette = palette,
                metrics = metrics,
                onClick = spec.onClick,
            )
        }
    }
}

@Composable
private fun RowScope.UtilityKey(
    label: String,
    enabled: Boolean,
    palette: TypeinkImeShellPalette,
    metrics: TypeinkImeShellMetrics,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .weight(1f)
                .height(metrics.utilityKeyHeight.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) palette.keySurfaceStrong else palette.keySurface,
        contentColor = palette.textPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (enabled) palette.textPrimary else palette.textHint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SecondaryKey(
    text: String,
    palette: TypeinkImeShellPalette,
    height: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .requiredWidth(width)
                .height(height),
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) palette.keySurfaceStrong else palette.keySurface,
        contentColor = palette.textPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (enabled) palette.textPrimary else palette.textHint,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RowScope.MicBarKey(
    label: String,
    palette: TypeinkImeShellPalette,
    height: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    glowStrength: Float,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = true,
        modifier =
            Modifier
                .weight(1f)
                .height(height),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        brush =
                            Brush.horizontalGradient(
                                colors =
                                    listOf(
                                        palette.keySurfaceStrong,
                                        palette.accentCool.copy(alpha = 0.24f + glowStrength * 0.10f),
                                        palette.accentWarm.copy(alpha = 0.18f + glowStrength * 0.08f),
                                    ),
                            ),
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (enabled) palette.textPrimary.copy(alpha = 0.92f) else palette.keySurface,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_typeink_mic),
                        contentDescription = label,
                        tint = palette.glassBottom,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = label,
                    color = if (enabled) palette.textPrimary else palette.textHint,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun IconKeyButton(
    palette: TypeinkImeShellPalette,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    text: String? = null,
    iconRes: Int? = null,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(18.dp),
        color = if (active) palette.errorSurface else palette.keySurfaceStrong,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = text,
                    tint = if (enabled) palette.textPrimary else palette.textHint,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = text.orEmpty(),
                    color = if (enabled) palette.textPrimary else palette.textHint,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun filterClipboardHistory(
    history: List<ClipboardHistoryEntry>,
    query: String,
    filter: ClipboardHistoryFilter,
): List<ClipboardHistoryEntry> {
    val normalizedQuery = query.trim()
    return history.filterIndexed { index, item ->
        val matchesFilter =
            when (filter) {
                ClipboardHistoryFilter.ALL -> true
                ClipboardHistoryFilter.RECENT -> index < 8
                ClipboardHistoryFilter.TEXT -> !isLikelyNumber(item.text) && !isLikelyLink(item.text)
                ClipboardHistoryFilter.NUMBER -> isLikelyNumber(item.text)
                ClipboardHistoryFilter.LINK -> isLikelyLink(item.text)
            }
        val matchesQuery =
            normalizedQuery.isBlank() || item.text.contains(normalizedQuery, ignoreCase = true)
        matchesFilter && matchesQuery
    }
}

private fun isLikelyNumber(text: String): Boolean {
    val normalized = text.trim()
    if (normalized.isBlank()) return false
    return normalized.all { it.isDigit() || it == ' ' || it == '+' || it == '-' }
}

private fun isLikelyLink(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return normalized.startsWith("http://") ||
        normalized.startsWith("https://") ||
        normalized.startsWith("www.") ||
        normalized.contains(".com") ||
        normalized.contains(".cn") ||
        normalized.contains(".io")
}

@Composable
private fun StatusBeacon(
    label: String,
    tone: ShellStatusTone,
    palette: TypeinkImeShellPalette,
) {
    val dotColor =
        when (tone) {
            ShellStatusTone.ONLINE -> palette.statusOnline
            ShellStatusTone.BUSY -> palette.statusBusy
            ShellStatusTone.OFFLINE -> palette.statusOffline
        }
    val glowColor =
        when (tone) {
            ShellStatusTone.ONLINE -> palette.statusOnlineGlow
            ShellStatusTone.BUSY -> palette.statusBusyGlow
            ShellStatusTone.OFFLINE -> palette.statusOfflineGlow
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(glowColor),
            )
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
            )
        }
        Text(
            text = label,
            color = palette.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private data class UtilityKeySpec(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun KeyboardAtmosphere(
    modifier: Modifier,
    palette: TypeinkImeShellPalette,
    recordingStrength: Float,
) {
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .padding(start = 24.dp, top = 16.dp)
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(palette.accentCool.copy(alpha = 0.05f + recordingStrength * 0.02f)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 44.dp)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(palette.accentWarm.copy(alpha = 0.04f + recordingStrength * 0.02f)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 86.dp, bottom = 38.dp)
                    .size(width = 66.dp, height = 18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x14C4D08E))
                    .alpha(0.7f),
        )
    }
}

private fun resolveShellCopy(model: TypeinkImeShellModel): ShellCopy {
    val state = model.inputState
    val displayText =
        when {
            state.finalText.isNotBlank() -> state.finalText
            model.sessionTextState.displayFinalText.isNotBlank() -> model.sessionTextState.displayFinalText
            state.partialText.isNotBlank() -> state.partialText
            model.sessionTextState.draftText.isNotBlank() -> model.sessionTextState.draftText
            else -> ""
        }

    if (!model.voiceInputEnabled) {
        return ShellCopy(
            badge = "受限",
            statusTone = ShellStatusTone.OFFLINE,
            title = "当前输入框禁用语音",
            helper = model.blockedReason,
            preview = displayText,
            previewLabel = "当前文本",
            previewCompact = true,
            micLabel = "键盘输入",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
    }

    if (model.editSessionState.isActive) {
        return when (model.editSessionState.phase) {
            TypeinkEditPhase.LISTENING -> ShellCopy(
                badge = "处理中",
                statusTone = ShellStatusTone.BUSY,
                title = "说你的修改指令",
                helper = "例如：把最后一句改得更礼貌。",
                preview = displayText,
                previewLabel = "正在修改的文本",
                previewCompact = false,
                micLabel = "停止录音",
                micHelper = "",
                rightPrimaryLabel = "",
                rightPrimaryEnabled = true,
            )
            TypeinkEditPhase.REWRITING -> ShellCopy(
                badge = "处理中",
                statusTone = ShellStatusTone.BUSY,
                title = "正在理解修改指令",
                helper = "",
                preview = displayText,
                previewLabel = "修改中的文本",
                previewCompact = true,
                micLabel = "处理中",
                micHelper = "",
                rightPrimaryLabel = "",
                rightPrimaryEnabled = true,
            )
            TypeinkEditPhase.READY -> ShellCopy(
                badge = "可用",
                statusTone = ShellStatusTone.ONLINE,
                title = "修改结果已写入输入框",
                helper = "",
                preview = displayText,
                previewLabel = "修改结果",
                previewCompact = true,
                micLabel = "继续修改",
                micHelper = "",
                rightPrimaryLabel = "",
                rightPrimaryEnabled = true,
            )
            TypeinkEditPhase.FAILED -> ShellCopy(
                badge = "异常",
                statusTone = ShellStatusTone.OFFLINE,
                title = "修改失败",
                helper = state.errorMessage.ifBlank { "可以重试，也可以保留原文。" },
                preview = displayText,
                previewLabel = "当前文本",
                previewCompact = true,
                micLabel = "重试修改",
                micHelper = "",
                rightPrimaryLabel = "",
                rightPrimaryEnabled = true,
            )
            else -> ShellCopy(
                badge = "在线",
                statusTone = ShellStatusTone.ONLINE,
                title = "准备修改",
                helper = "",
                preview = displayText,
                previewLabel = "待修改文本",
                previewCompact = false,
                micLabel = "开始修改",
                micHelper = "",
                rightPrimaryLabel = "",
                rightPrimaryEnabled = true,
            )
        }
    }

    return when (state.phase) {
        TypeinkInputPhase.PREPARING -> ShellCopy(
            badge = "处理中",
            statusTone = ShellStatusTone.BUSY,
            title = "准备收音",
            helper = "",
            preview = displayText,
            previewLabel = "目标文本",
            previewCompact = false,
            micLabel = "准备中",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.LISTENING -> ShellCopy(
            badge = "处理中",
            statusTone = ShellStatusTone.BUSY,
            title = "正在收音",
            helper = "",
            preview = displayText,
            previewLabel = "识别草稿",
            previewCompact = true,
            micLabel = "停止录音",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.PROCESSING -> ShellCopy(
            badge = "处理中",
            statusTone = ShellStatusTone.BUSY,
            title = "正在识别",
            helper = "",
            preview = displayText,
            previewLabel = "识别草稿",
            previewCompact = true,
            micLabel = "处理中",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.REWRITING -> ShellCopy(
            badge = "处理中",
            statusTone = ShellStatusTone.BUSY,
            title = "正在整理",
            helper = "",
            preview = displayText,
            previewLabel = "实时结果",
            previewCompact = true,
            micLabel = "处理中",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.PREVIEW_READY -> ShellCopy(
            badge = "可用",
            statusTone = ShellStatusTone.ONLINE,
            title = "结果已写入输入框",
            helper = "",
            preview = displayText,
            previewLabel = "当前结果",
            previewCompact = true,
            micLabel = "继续输入",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.APPLIED -> ShellCopy(
            badge = "可用",
            statusTone = ShellStatusTone.ONLINE,
            title = "已完成整理",
            helper = "",
            preview = displayText,
            previewLabel = "已上屏文本",
            previewCompact = true,
            micLabel = "继续输入",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.FAILED -> ShellCopy(
            badge = "异常",
            statusTone = ShellStatusTone.OFFLINE,
            title = "处理失败",
            helper = "",
            preview = displayText,
            previewLabel = "当前草稿",
            previewCompact = true,
            micLabel = "重试",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        TypeinkInputPhase.CANCELLED -> ShellCopy(
            badge = "可用",
            statusTone = ShellStatusTone.ONLINE,
            title = "已取消",
            helper = "",
            preview = displayText,
            previewLabel = "当前文本",
            previewCompact = true,
            micLabel = "重新开始",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
        else -> ShellCopy(
            badge = "可用",
            statusTone = ShellStatusTone.ONLINE,
            title = "",
            helper = "",
            preview = displayText,
            previewLabel = "目标文本",
            previewCompact = displayText.isNotBlank(),
            micLabel = if (displayText.isBlank()) "开始输入" else "继续输入",
            micHelper = "",
            rightPrimaryLabel = "",
            rightPrimaryEnabled = true,
        )
    }
}
