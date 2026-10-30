package io.dsh.mobile.design

import androidx.compose.runtime.Composable

/** Native glass controls on supported iOS; matching Material controls on Android. */
@Composable
expect fun PlatformActionButton(icon: String, label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit)
