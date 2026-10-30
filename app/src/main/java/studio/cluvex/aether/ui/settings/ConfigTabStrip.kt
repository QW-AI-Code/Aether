package studio.cluvex.aether.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.custom.ConfigCatalog

@Composable
internal fun ConfigTabStrip(tabs: List<ConfigCatalog.Tab>, selectedKey: String,
    enabled: Boolean, editable: Boolean, onSelect: (String) -> Unit,
    onRename: (ConfigCatalog.Tab) -> Unit, onNew: () -> Unit = {}) {
    val localeDirection = LocalLayoutDirection.current
    // The add button stays at the physical left, matching the Persian QWVPN reference.
    val tabScroll = rememberScrollState()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(onClick = onNew, enabled = editable, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.Add, stringResource(R.string.cc_group_new))
            }
            CompositionLocalProvider(LocalLayoutDirection provides localeDirection) {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(tabScroll),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        tabs.forEach { tab -> key(tab.key) {
                            val selected = selectedKey == tab.key
                            Column(Modifier.width(IntrinsicSize.Max)) {
                                Row(Modifier.widthIn(max = 260.dp).heightIn(min = 48.dp)
                                    .selectable(selected, enabled = enabled, role = Role.Tab,
                                        onClick = { onSelect(tab.key) }).padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 140.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Surface(shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (selected) .18f else .06f),
                                        modifier = Modifier.padding(horizontal = 6.dp)) {
                                        Text("${tab.count}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(enabled = editable, modifier = Modifier.size(32.dp), onClick = { onRename(tab) }) {
                                        Icon(Icons.Rounded.Edit, stringResource(R.string.cc_group_edit), Modifier.size(16.dp))
                                    }
                                }
                                HorizontalDivider(thickness = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            }
                        } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().tabStripScrollbar(tabScroll))
                }
            }
        }
    }
}

@Composable
private fun Modifier.tabStripScrollbar(
    state: ScrollState,
    height: Dp = 4.dp,
    minThumb: Dp = 28.dp,
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (state.maxValue > 0) {
            if (state.isScrollInProgress) 1f else 0.82f
        } else {
            0f
        },
        animationSpec = tween(durationMillis = if (state.isScrollInProgress) 120 else 400),
        label = "tabScrollbarAlpha",
    )
    return drawWithContent {
        drawContent()
        val max = state.maxValue
        if (alpha <= 0f || max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val viewport = size.width
        val h = height.toPx()
        if (viewport <= 0f || h <= 0f) return@drawWithContent
        val trackY = (size.height - h) / 2f
        val trackWidth = viewport
        val thumbWidth = (trackWidth * viewport / (viewport + max))
            .coerceIn(minThumb.toPx().coerceAtMost(trackWidth), trackWidth)
        val thumbX = (trackWidth - thumbWidth) * (state.value.toFloat() / max)
        val radius = CornerRadius(h / 2f)
        drawRoundRect(
            color = Color(0xFF0F2A1C).copy(alpha = 0.96f),
            topLeft = Offset(0f, trackY),
            size = Size(trackWidth, h),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = Color(0xFF22C55E).copy(alpha = alpha),
            topLeft = Offset(thumbX, trackY),
            size = Size(thumbWidth, h),
            cornerRadius = radius,
        )
    }
}
