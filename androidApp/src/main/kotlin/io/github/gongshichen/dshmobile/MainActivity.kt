package io.github.gongshichen.dshmobile

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.dsh.mobile.App
import io.dsh.mobile.AppGraph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var services: AndroidUiServices
    private val mobileApplication get() = application as DshMobileApplication
    private var pendingPush: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = AndroidUiServices(this)
        AppGraph.repository = mobileApplication.repository
        AppGraph.scanner = services.qrScanner
        AppGraph.filePicker = services.filePicker
        AppGraph.notificationPermissions = services.notificationPermissions
        AppGraph.saveFile = services::saveFile
        AppGraph.resetPushConfiguration = mobileApplication.pushService::resetConfiguration
        mobileApplication.pushService.permissionRequester = services.notificationPermissions::request
        pendingPush = intent
        enableEdgeToEdge()
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPush = intent
        handlePendingPush()
    }

    override fun onStart() {
        super.onStart()
        if (!handlePendingPush()) {
            mobileApplication.applicationScope.launch {
                mobileApplication.repository.onForeground()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !services.isPresentingPlatformUi) {
            mobileApplication.applicationScope.launch { mobileApplication.repository.onBackground() }
        }
    }

    override fun onDestroy() {
        mobileApplication.pushService.permissionRequester = null
        AppGraph.scanner = null
        AppGraph.filePicker = null
        AppGraph.notificationPermissions = null
        AppGraph.saveFile = null
        AppGraph.resetPushConfiguration = null
        services.close()
        super.onDestroy()
    }

    private fun handlePendingPush(): Boolean {
        val message = pendingPush ?: return false
        pendingPush = null
        val host = message.getStringExtra(DshFirebaseMessagingService.EXTRA_HOST) ?: message.getStringExtra("hostId") ?: return false
        val event = message.getStringExtra(DshFirebaseMessagingService.EXTRA_EVENT) ?: message.getStringExtra("eventId") ?: return false
        val session = message.getStringExtra(DshFirebaseMessagingService.EXTRA_SESSION) ?: message.getStringExtra("sessionId")
        mobileApplication.applicationScope.launch {
            mobileApplication.repository.receivePush(host, event, session)
            if (session != null && mobileApplication.repository.state.value.selectedSessionId == session) AppGraph.openConversation()
        }
        return true
    }
}
