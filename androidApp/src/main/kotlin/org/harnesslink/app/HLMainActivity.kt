package org.harnesslink.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.dsh.mobile.HLApp
import io.dsh.mobile.HLAppGraph
import kotlinx.coroutines.launch

class HLMainActivity : ComponentActivity() {
    private lateinit var services: HLAndroidUIServices
    private val mobileApplication get() = application as HLApplication
    private var pendingPush: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = HLAndroidUIServices(this)
        HLAppGraph.repository = mobileApplication.repository
        HLAppGraph.scanner = services.qrScanner
        HLAppGraph.filePicker = services.filePicker
        HLAppGraph.notificationPermissions = services.notificationPermissions
        HLAppGraph.saveFile = services::saveFile
        HLAppGraph.resetPushConfiguration = mobileApplication.pushService::resetConfiguration
        mobileApplication.pushService.permissionRequester = services.notificationPermissions::request
        pendingPush = intent
        enableEdgeToEdge()
        setContent { HLApp() }
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
        HLAppGraph.scanner = null
        HLAppGraph.filePicker = null
        HLAppGraph.notificationPermissions = null
        HLAppGraph.saveFile = null
        HLAppGraph.resetPushConfiguration = null
        services.close()
        super.onDestroy()
    }

    private fun handlePendingPush(): Boolean {
        val message = pendingPush ?: return false
        pendingPush = null
        val host = message.getStringExtra(HLFirebaseMessagingService.EXTRA_HOST) ?: message.getStringExtra("hostId") ?: return false
        val event = message.getStringExtra(HLFirebaseMessagingService.EXTRA_EVENT) ?: message.getStringExtra("eventId") ?: return false
        val session = message.getStringExtra(HLFirebaseMessagingService.EXTRA_SESSION) ?: message.getStringExtra("sessionId")
        mobileApplication.applicationScope.launch {
            mobileApplication.repository.receivePush(host, event, session)
            if (session != null && mobileApplication.repository.state.value.selectedSessionId == session) HLAppGraph.openConversation()
        }
        return true
    }
}
