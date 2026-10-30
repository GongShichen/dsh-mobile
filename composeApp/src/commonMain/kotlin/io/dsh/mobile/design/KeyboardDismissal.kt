package io.dsh.mobile.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.dsh.mobile.platform.reportDiagnostic
import kotlinx.coroutines.launch

private class KeyboardTapTracker {
    var gesture = 0L
    var inputGesture = -1L
    var focusRevision = 0L
    val focusedInputs = mutableSetOf<Any>()
}
private val LocalKeyboardTaps = staticCompositionLocalOf<KeyboardTapTracker?> { null }

/** Observe touches without consuming them. Inputs identify their own hit path, independent of layout/IME movement. */
@Composable
fun KeyboardDismissalHost(content: @Composable () -> Unit) {
    val tracker = remember { KeyboardTapTracker() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalKeyboardTaps provides tracker) {
        Box(Modifier.fillMaxSize().pointerInput(focus, keyboard, tracker) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val gesture = ++tracker.gesture
                val focusRevision = tracker.focusRevision
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                    // Child input handlers receive the same event after the parent's Initial pass.
                    awaitPointerEvent(PointerEventPass.Final)
                    if (tracker.inputGesture != gesture) scope.launch {
                        // Let a field's focus request finish before considering an outside dismissal.
                        withFrameNanos { }
                        if (tracker.gesture == gesture && tracker.focusRevision == focusRevision && tracker.focusedInputs.isNotEmpty()) {
                            reportDiagnostic("keyboard.dismiss", "source=outside-tap")
                            focus.clearFocus()
                            keyboard?.hide()
                        }
                    }
                }
            }
        }) { content() }
    }
}

fun Modifier.keyboardInputArea(label: String = "input"): Modifier = composed {
    val tracker = LocalKeyboardTaps.current ?: return@composed this
    val key = remember { Any() }
    DisposableEffect(tracker, key) { onDispose { tracker.focusedInputs.remove(key) } }
    onFocusChanged { state ->
        val changed = if (state.isFocused) tracker.focusedInputs.add(key) else tracker.focusedInputs.remove(key)
        if (changed) {
            if (state.isFocused) tracker.focusRevision++
            reportDiagnostic("input.focus", "field=$label focused=${state.isFocused}")
        }
    }.pointerInput(tracker) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            tracker.inputGesture = tracker.gesture
            waitForUpOrCancellation(pass = PointerEventPass.Initial)
        }
    }
}
