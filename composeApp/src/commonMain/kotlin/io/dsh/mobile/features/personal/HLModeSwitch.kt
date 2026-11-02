package io.dsh.mobile.features.personal

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.dsh.mobile.model.HLConversationMode

/** Stable-sized primary mode control. Switching never starts or cancels Host work. */
@Composable
fun HLModeSwitch(mode: HLConversationMode, attention: Int, onSelect: (HLConversationMode) -> Unit) {
    BoxWithConstraints(Modifier.widthIn(max = 216.dp).fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        val segment = maxWidth / 2
        val offset by animateDpAsState(if (mode == HLConversationMode.NORMAL) 0.dp else segment, tween(200))
        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Surface(Modifier.offset(x = offset).width(segment).fillMaxHeight().padding(3.dp), shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface, shadowElevation = .5.dp) {}
        }
        Row(Modifier.fillMaxSize()) {
            HLConversationMode.entries.forEach { item ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).clickable { onSelect(item) }.semantics {
                    role = Role.Tab; selected = mode == item
                    contentDescription = if (item == HLConversationMode.NORMAL) "会话模式" else "私人助理模式"
                    if (item == HLConversationMode.PERSONAL && attention > 0) stateDescription = "$attention 项需要处理"
                }, contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (item == HLConversationMode.NORMAL) "会话" else "私人助理", style = MaterialTheme.typography.labelLarge,
                            color = if (mode == item) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        if (item == HLConversationMode.PERSONAL && attention > 0) Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}
