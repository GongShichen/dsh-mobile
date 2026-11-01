package io.dsh.mobile.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.dsh.mobile.features.HLNavigationIcon

internal val LocalHLCompactPanel = staticCompositionLocalOf { false }

/** One floating card for composer details. It stays inside the safe area and keeps its close action visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HLFloatingPanel(title: String, summary: String? = null, icon: String? = null, width: Dp, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val safe = WindowInsets.safeDrawing
    val left = safe.getLeft(density, LayoutDirection.Ltr); val right = safe.getRight(density, LayoutDirection.Ltr)
    val top = safe.getTop(density); val bottom = safe.getBottom(density)
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val maxWidth = with(density) { (window.width - left - right - 2 * margin).coerceAtLeast(160).toDp() }
    val maxHeight = with(density) { (window.height - top - bottom - 2 * margin).coerceAtLeast(120).toDp() }
    val compact = with(density) { window.height.toDp() } < 440.dp
    val preferredWidth = if (window.width > window.height) maxOf(width, 560.dp) else width
    val scroll = rememberScrollState()
    val overflowing = scroll.viewportSize > 0 && scroll.maxValue > 0
    val position = remember(margin, gap, left, right, top, bottom) { HLPanelPosition(margin, gap, left, right, top, bottom) }
    Popup(popupPositionProvider = position, onDismissRequest = close,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true, clippingEnabled = true)) {
        Surface(Modifier.width(minOf(preferredWidth, maxWidth)).heightIn(max = maxHeight).semantics { paneTitle = title },
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            CompositionLocalProvider(LocalHLCompactPanel provides compact) { Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) { HLNavigationIcon(icon, 18.dp); Spacer(Modifier.width(8.dp)) }
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        if (summary != null) Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = close, modifier = Modifier.semantics { contentDescription = "关闭$title" }) { HLNavigationIcon("CLOSE", 18.dp) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f, fill = false)) {
                    Column(Modifier.verticalScroll(scroll).padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp), content = content)
                    if (overflowing) {
                        val track = MaterialTheme.colorScheme.outlineVariant
                        val thumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
                        Canvas(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().padding(vertical = 4.dp)) {
                            val maximum = scroll.maxValue.coerceAtLeast(1)
                            val viewport = scroll.viewportSize.coerceAtLeast(1)
                            val proportion = viewport.toFloat() / (viewport + maximum)
                            val height = (size.height * proportion).coerceAtLeast(20.dp.toPx()).coerceAtMost(size.height)
                            val offset = (size.height - height) * scroll.value / maximum
                            drawRoundRect(track, cornerRadius = CornerRadius(size.width / 2))
                            drawRoundRect(thumb, Offset(0f, offset), Size(size.width, height), CornerRadius(size.width / 2))
                        }
                    }
                }
                if (overflowing && scroll.canScrollForward) Text("向上滑动查看更多", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

private class HLPanelPosition(private val margin: Int, private val gap: Int, private val left: Int, private val right: Int, private val top: Int, private val bottom: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val minX = left + margin; val minY = top + margin
        val maxX = (windowSize.width - right - margin - popupContentSize.width).coerceAtLeast(minX)
        val maxY = (windowSize.height - bottom - margin - popupContentSize.height).coerceAtLeast(minY)
        val centered = left + (windowSize.width - left - right - popupContentSize.width) / 2
        val x = if (popupContentSize.width >= (windowSize.width - left - right) * .8) centered else if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above >= minY) above else anchorBounds.bottom + gap
        return IntOffset(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }
}
