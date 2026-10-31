package io.dsh.mobile.design

import androidx.compose.ui.window.DialogProperties

/** Let the content own keyboard and safe-area insets instead of native window panning. */
expect fun interactionDialogProperties(): DialogProperties
