package io.github.gongshichen.dshmobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Notifications carry routing identifiers; session content is fetched over authenticated P2P after opening. */
class DshFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as DshMobileApplication).pushService.publishToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "DSH 任务通知", NotificationManager.IMPORTANCE_DEFAULT))
        val data = message.data
        val session = data["sessionId"]
        val host = data["hostId"] ?: data["desktopId"] ?: return
        val event = data["eventId"] ?: return
        val app = application as DshMobileApplication
        val repository = app.activeRepository()
        if (repository != null && repository.state.value.connected && repository.state.value.activeHost?.endpointId == host) {
            app.applicationScope.launch {
                // Receipt reporting does not navigate away from the conversation being viewed.
                runCatching { repository.call("push.received", buildJsonObject { put("eventId", event) }) }
            }
        }
        val notificationId = event.hashCode()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_SESSION, session)
            .putExtra(EXTRA_EVENT, event)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (data["kind"] == "approval") "DSH 需要你确认" else "DSH 任务有更新"
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText("点击连接电脑并查看会话")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    companion object {
        const val EXTRA_HOST = "dsh.hostId"
        const val EXTRA_SESSION = "dsh.sessionId"
        const val EXTRA_EVENT = "dsh.eventId"
        private const val CHANNEL = "dsh-task-updates"
    }
}
