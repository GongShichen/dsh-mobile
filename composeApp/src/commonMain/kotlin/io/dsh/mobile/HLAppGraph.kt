package io.dsh.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.dsh.mobile.platform.HLFilePicker
import io.dsh.mobile.platform.HLNotificationPermissions
import io.dsh.mobile.platform.HLQRScanner
import io.dsh.mobile.sync.HLRepository

/** Installed by the native platform shell; the shared UI contains no transport or storage singleton. */
object HLAppGraph {
    var repository: HLRepository? by mutableStateOf(null)
    var scanner: HLQRScanner? by mutableStateOf(null)
    var filePicker: HLFilePicker? by mutableStateOf(null)
    var notificationPermissions: HLNotificationPermissions? by mutableStateOf(null)
    var saveFile: (suspend (name: String, bytes: ByteArray) -> Unit)? by mutableStateOf(null)
    var resetPushConfiguration: (suspend () -> Unit)? by mutableStateOf(null)
    var conversationRequest: Int by mutableStateOf(0)
        private set
    fun openConversation() { conversationRequest += 1 }
}
