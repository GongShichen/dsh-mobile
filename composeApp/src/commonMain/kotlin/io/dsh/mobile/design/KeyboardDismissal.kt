package io.dsh.mobile.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

private val LocalInputAreas = staticCompositionLocalOf<MutableMap<Any, Rect>?> { null }

/** Observe taps without consuming them; editable regions keep their own focus and cursor gestures. */
@Composable
fun KeyboardDismissalHost(content: @Composable () -> Unit) {
    val areas = remember { mutableMapOf<Any, Rect>() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    CompositionLocalProvider(LocalInputAreas provides areas) {
        Box(Modifier.fillMaxSize().pointerInput(focus, keyboard) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop && areas.values.none { it.contains(down.position) }) {
                    focus.clearFocus()
                    keyboard?.hide()
                }
            }
        }) { content() }
    }
}

fun Modifier.keyboardInputArea(): Modifier = composed {
    val areas = LocalInputAreas.current
    val key = remember { Any() }
    DisposableEffect(areas, key) { onDispose { areas?.remove(key) } }
    onGloballyPositioned { areas?.set(key, it.boundsInRoot()) }
}
