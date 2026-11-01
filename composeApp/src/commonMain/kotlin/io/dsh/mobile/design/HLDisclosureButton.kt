package io.dsh.mobile.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Flat process-row control with touch feedback and a small keyboard focus outline, without capsule fill. */
@Composable
internal fun HLDisclosureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    accessibilityState: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val performClick = onClick
    val accessible = if (accessibilityLabel == null) Modifier else Modifier.clearAndSetSemantics {
        contentDescription = accessibilityLabel
        role = Role.Button
        if (accessibilityState != null) stateDescription = accessibilityState
        if (!enabled) disabled()
        onClick { if (enabled) { performClick(); true } else false }
    }
    CompositionLocalProvider(LocalContentColor provides if (enabled) contentColor else contentColor.copy(alpha = .38f)) {
        Row(modifier.heightIn(min = 48.dp)
            .background(if (pressed) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent, RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .5f) else Color.Transparent), RoundedCornerShape(4.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onClick)
            .then(accessible)
            .padding(contentPadding), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
