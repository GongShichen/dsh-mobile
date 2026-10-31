package org.harnesslink.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import io.dsh.mobile.platform.initializeAndroidPlatform
import io.dsh.mobile.platform.createSecureStore
import io.dsh.mobile.platform.HLClock
import io.dsh.mobile.database.HLDatabaseDriverFactory
import io.dsh.mobile.sync.HLRepository
import io.dsh.mobile.transport.HLAndroidIrohTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HLApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repositoryDelegate = lazy {
        HLRepository(
            transport = HLAndroidIrohTransport(this),
            secureStore = createSecureStore(),
            cache = HLDatabaseDriverFactory(this).createCache(),
            scope = applicationScope,
            clock = object : HLClock { override fun nowMillis(): Long = System.currentTimeMillis() },
            platform = "android",
            pushService = pushService,
        )
    }
    val repository: HLRepository by repositoryDelegate
    fun activeRepository(): HLRepository? = if (repositoryDelegate.isInitialized()) repositoryDelegate.value else null
    lateinit var pushService: HLAndroidPushService
        private set

    override fun onCreate() {
        super.onCreate()
        initializeAndroidPlatform(this)
        pushService = HLAndroidPushService(this)
        pushService.initializeSavedConfiguration()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeRepository()?.let { repository -> applicationScope.launch { repository.resume() } }
            }
        })
    }
}
