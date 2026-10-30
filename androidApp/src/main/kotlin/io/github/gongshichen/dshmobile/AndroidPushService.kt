package io.github.gongshichen.dshmobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import io.dsh.mobile.platform.FirebasePublicOptions
import io.dsh.mobile.platform.PushAvailability
import io.dsh.mobile.platform.PushRegistration
import io.dsh.mobile.platform.PushService
import io.dsh.mobile.platform.PushAppMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Only Firebase's public client configuration is stored here. Server credentials stay on the Mac. */
class AndroidPushService(private val context: Context) : PushService {
    private val preferences = context.getSharedPreferences("firebase-public-options", Context.MODE_PRIVATE)
    private val configureLock = Mutex()
    private val updates = MutableSharedFlow<PushRegistration>(replay = 1, extraBufferCapacity = 1)
    private var restartRequired = false
    override val registrations: Flow<PushRegistration> = updates
    var permissionRequester: (suspend () -> Boolean)? = null
    override fun appMetadata() = PushAppMetadata(platform = "android", applicationId = context.packageName)

    fun initializeSavedConfiguration() {
        val options = savedOptions() ?: return
        initializeFirebase(options)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().token.addOnSuccessListener(::publishToken)
    }

    override suspend fun configure(options: FirebasePublicOptions?): PushAvailability = configureLock.withLock {
        withContext(Dispatchers.IO) {
            check(!restartRequired) { "推送配置已重置，请重新打开应用后配置新项目" }
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                return@withContext PushAvailability.UNSUPPORTED
            }
            val selected = options ?: savedOptions() ?: return@withContext PushAvailability.UNCONFIGURED
            validate(selected)
            val previous = savedOptions()
            require(previous == null || previous.projectId == selected.projectId && previous.appId == selected.appId && previous.senderId == selected.senderId) {
                "此安装已绑定其他 Firebase 项目。请先重置推送配置，不能由另一台电脑覆盖。"
            }
            initializeFirebase(selected)
            preferences.edit()
                .putString("projectId", selected.projectId)
                .putString("appId", selected.appId)
                .putString("apiKey", selected.apiKey)
                .putString("senderId", selected.senderId)
                .putString("packageName", selected.packageName)
                .commit()
                .also { check(it) { "无法保存推送配置" } }
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            publishToken(FirebaseMessaging.getInstance().token.await())
            if (notificationsAllowed()) PushAvailability.AVAILABLE else PushAvailability.PERMISSION_DENIED
        }
    }

    override suspend fun requestPermission(): Boolean = permissionRequester?.invoke() ?: notificationsAllowed()

    fun publishToken(token: String) {
        savedOptions() ?: return
        updates.tryEmit(PushRegistration(provider = "fcm", token = token, appId = context.packageName))
    }

    /** Reset revokes the installation token before clearing its project association. Restart before configuring another project. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun resetConfiguration() = configureLock.withLock {
        withContext(Dispatchers.IO) {
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.let { app ->
                FirebaseMessaging.getInstance().isAutoInitEnabled = false
                FirebaseMessaging.getInstance().deleteToken().await()
                app.delete()
            }
            check(preferences.edit().clear().commit()) { "无法清除推送配置" }
            updates.resetReplayCache()
            restartRequired = true
        }
    }

    private fun initializeFirebase(options: FirebasePublicOptions) {
        validate(options)
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) {
            require(existing.options.projectId == options.projectId && existing.options.applicationId == options.appId) {
                "默认 Firebase 实例属于另一个项目，请重启并重新配置"
            }
            require(existing.options.apiKey == options.apiKey && existing.options.gcmSenderId == options.senderId) {
                "推送公开配置已更改，请重置推送配置并重新打开应用"
            }
            return
        }
        FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(options.projectId)
            .setApplicationId(options.appId)
            .setApiKey(options.apiKey)
            .setGcmSenderId(options.senderId)
            .build())
    }

    private fun validate(options: FirebasePublicOptions) {
        require(options.packageName == null || options.packageName == context.packageName) { "Firebase Android 包名与本应用不符" }
        require(options.projectId.isNotBlank() && options.appId.isNotBlank() && options.apiKey.isNotBlank() && options.senderId.isNotBlank()) {
            "Firebase 公开配置不完整"
        }
    }

    private fun savedOptions(): FirebasePublicOptions? {
        val project = preferences.getString("projectId", null) ?: return null
        return FirebasePublicOptions(project, preferences.getString("appId", "")!!,
            preferences.getString("apiKey", "")!!, preferences.getString("senderId", "")!!,
            preferences.getString("packageName", null))
    }

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
}
