package org.harnesslink.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import io.dsh.mobile.platform.initializeAndroidPlatform
import io.dsh.mobile.platform.createSecureStore
import io.dsh.mobile.platform.Clock
import io.dsh.mobile.database.DatabaseDriverFactory
import io.dsh.mobile.sync.MobileRepository
import io.dsh.mobile.transport.AndroidIrohTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DshMobileApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repositoryDelegate = lazy {
        MobileRepository(
            transport = AndroidIrohTransport(this),
            secureStore = createSecureStore(),
            cache = DatabaseDriverFactory(this).createCache(),
            scope = applicationScope,
            clock = object : Clock { override fun nowMillis(): Long = System.currentTimeMillis() },
            platform = "android",
            pushService = pushService,
        )
    }
    val repository: MobileRepository by repositoryDelegate
    fun activeRepository(): MobileRepository? = if (repositoryDelegate.isInitialized()) repositoryDelegate.value else null
    lateinit var pushService: AndroidPushService
        private set

    override fun onCreate() {
        super.onCreate()
        initializeAndroidPlatform(this)
        pushService = AndroidPushService(this)
        pushService.initializeSavedConfiguration()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeRepository()?.let { repository -> applicationScope.launch { repository.resume() } }
            }
        })
    }
}
