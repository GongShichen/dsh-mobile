package io.dsh.mobile.design

import androidx.compose.ui.window.DialogProperties

actual fun interactionDialogProperties() = DialogProperties(
    usePlatformDefaultWidth = false,
    dismissOnClickOutside = false,
)
