package io.dsh.mobile.features.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import io.dsh.mobile.features.HLNavigationIcon
import io.dsh.mobile.design.interactionDialogProperties
import io.dsh.mobile.design.HLKeyboardDismissalHost

/** One bounded, centered surface; long content scrolls while actions remain visible. */
@Composable
fun HLPersonalDialog(title: String, close: () -> Unit, primaryLabel: String = "完成", primary: () -> Unit = close,
    secondaryLabel: String? = null, secondary: (() -> Unit)? = null, destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    HLPersonalOverlay(title, close) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = close, modifier = Modifier.size(44.dp).semantics { contentDescription = "关闭$title" }) { HLNavigationIcon("CLOSE", 18.dp) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (secondaryLabel != null && secondary != null) OutlinedButton(onClick = secondary, modifier = Modifier.weight(1f).heightIn(min = 46.dp), shape = MaterialTheme.shapes.small) { Text(secondaryLabel) }
            Button(onClick = primary, modifier = Modifier.weight(1f).heightIn(min = 46.dp), shape = MaterialTheme.shapes.small,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text(primaryLabel) }
        }
    }
}

@Composable
fun HLPersonalOverlay(title: String, close: () -> Unit, maxWidth: Dp = 440.dp, avoidKeyboard: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    Dialog(onDismissRequest = close, properties = interactionDialogProperties()) {
        HLKeyboardDismissalHost {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().then(if (avoidKeyboard) Modifier.imePadding() else Modifier).padding(horizontal = 24.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = maxWidth).fillMaxWidth().heightIn(max = maxHeight * .88f).semantics { paneTitle = title },
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 12.dp) {
                Column(content = content)
            }
        }
        }
    }
}
