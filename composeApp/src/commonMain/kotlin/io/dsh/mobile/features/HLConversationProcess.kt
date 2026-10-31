package io.dsh.mobile.features

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.sync.HLConversationTimeline
import io.dsh.mobile.sync.HLConversationTurn
import io.dsh.mobile.sync.HLProcessGroup
import io.dsh.mobile.design.HLDisclosureButton

/** Session-local view choices survive lazy-row removal and token updates, but never enter storage. */
internal class HLConversationDisclosure {
    val turns = mutableStateMapOf<String, Boolean>()
    val groups = mutableStateMapOf<String, Boolean>()
    val leaves = mutableStateMapOf<String, Boolean>()

    fun turnOpen(turn: HLConversationTurn) = !turn.foldable || turns[turn.key] == true
    fun groupOpen(group: HLProcessGroup) = groups[group.key] ?: group.failed
    fun toggleTurn(turn: HLConversationTurn, timeline: HLConversationTimeline) {
        val next = !turnOpen(turn)
        turns[turn.key] = next
        if (!next) {
            timeline.groups.values.filter { it.turnKey == turn.key }.forEach { groups[it.key] = false }
            leaves.keys.filter { it.startsWith("${turn.key}|") }.toList().forEach(leaves::remove)
        }
    }
}

@Composable
internal fun HLProcessGroupHeader(group: HLProcessGroup, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(160))
    val color = if (group.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    HLDisclosureButton(onClick = onToggle, contentPadding = PaddingValues(vertical = 8.dp),
        contentColor = color,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            contentDescription = "过程分组，${group.title}"
            stateDescription = if (expanded) "已展开" else "已收起"
        }) {
        Box(Modifier.graphicsLayer { rotationZ = rotation }) { HLNavigationIcon("DOWN", 14.dp) }
        Spacer(Modifier.width(10.dp))
        if (group.running) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = color); Spacer(Modifier.width(8.dp)) }
        Text(group.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
