package io.dsh.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.dsh.mobile.platform.FilePicker
import io.dsh.mobile.platform.NotificationPermissions
import io.dsh.mobile.platform.QrScanner
import io.dsh.mobile.sync.MobileRepository

/** Installed by the native platform shell; the shared UI contains no transport or storage singleton. */
object AppGraph {
    var repository: MobileRepository? by mutableStateOf(null)
    var scanner: QrScanner? by mutableStateOf(null)
    var filePicker: FilePicker? by mutableStateOf(null)
    var notificationPermissions: NotificationPermissions? by mutableStateOf(null)
    var saveFile: (suspend (name: String, bytes: ByteArray) -> Unit)? by mutableStateOf(null)
    var resetPushConfiguration: (suspend () -> Unit)? by mutableStateOf(null)
    var conversationRequest: Int by mutableStateOf(0)
        private set
    fun openConversation() { conversationRequest += 1 }
}
