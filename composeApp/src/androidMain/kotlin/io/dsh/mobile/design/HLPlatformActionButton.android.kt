package io.dsh.mobile.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.dsh.mobile.features.HLNavigationIcon

@Composable
actual fun HLPlatformActionButton(icon: String, label: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        if (filled) Surface(Modifier.size(36.dp), shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)) {
            Box(contentAlignment = Alignment.Center) { HLNavigationIcon(icon, 18.dp) }
        } else HLNavigationIcon(icon, if (icon == "PLUS") 18.dp else 23.dp)
    }
}
