@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.dsh.mobile.design

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import io.dsh.mobile.features.NavigationIcon
import platform.UIKit.UIView

interface NativeGlassAction { fun invoke() }
interface NativeGlassButtonFactory {
    fun makeButton(symbol: String, label: String, filled: Boolean, action: NativeGlassAction): UIView
    fun updateButton(view: UIView, symbol: String, label: String, enabled: Boolean, filled: Boolean)
}
private var glassFactory: NativeGlassButtonFactory? = null
fun installGlassButtonFactory(factory: NativeGlassButtonFactory) { glassFactory = factory }

@Composable
actual fun PlatformActionButton(icon: String, label: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val factory = glassFactory
    val currentAction by rememberUpdatedState(onClick)
    val action = remember { object : NativeGlassAction { override fun invoke() { currentAction() } } }
    val symbol = when (icon) { "MENU" -> "line.3.horizontal"; "BACK" -> "chevron.left"; "NEW_CHAT" -> "square.and.pencil"; "SEND" -> "arrow.up"; "STOP" -> "stop.fill"; else -> "plus" }
    if (factory == null) {
        val modifier = Modifier.size(48.dp).semantics { contentDescription = label }
        if (filled) FilledIconButton(onClick = onClick, enabled = enabled, modifier = modifier) { NavigationIcon(icon) }
        else IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { NavigationIcon(icon) }
    } else UIKitView(
        factory = { factory.makeButton(symbol, label, filled, action) },
        modifier = Modifier.size(48.dp).clip(CircleShape),
        update = { factory.updateButton(it, symbol, label, enabled, filled) },
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true),
    )
}
