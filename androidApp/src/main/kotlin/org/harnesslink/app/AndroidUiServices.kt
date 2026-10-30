package org.harnesslink.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import io.dsh.mobile.platform.FilePicker
import io.dsh.mobile.platform.NotificationPermissions
import io.dsh.mobile.platform.PickedFile
import io.dsh.mobile.platform.QrScanner
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Activity-result launchers are registered before the Activity becomes started. */
class AndroidUiServices(private val activity: ComponentActivity) {
    private var scanResult: CompletableDeferred<String?>? = null
    private var filesResult: CompletableDeferred<List<android.net.Uri>>? = null
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var saveResult: CompletableDeferred<android.net.Uri?>? = null
    private val scanLock = Mutex()
    private val filesLock = Mutex()
    private val permissionLock = Mutex()
    private val saveLock = Mutex()
    private val files = mutableMapOf<String, File>()
    val isPresentingPlatformUi: Boolean
        get() = scanResult != null || filesResult != null || permissionResult != null || saveResult != null

    private val scanLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val value = result.data?.getStringExtra(DshScanActivity.RESULT_QR)
        val error = result.data?.getStringExtra(DshScanActivity.RESULT_ERROR)
        Log.i("HarnessLink", "scanner.result code=${result.resultCode} received=${value != null} length=${value?.length ?: 0}")
        if (error != null) scanResult?.completeExceptionally(IllegalStateException(error))
        else scanResult?.complete(if (result.resultCode == Activity.RESULT_OK) value else null)
    }
    private val filesLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filesResult?.complete(uris)
    }
    private val photosLauncher = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        filesResult?.complete(uris)
    }
    private val notificationLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionResult?.complete(granted)
    }
    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        saveResult?.complete(uri)
    }

    suspend fun saveFile(name: String, bytes: ByteArray) = saveLock.withLock {
        require(bytes.size <= 20 * 1024 * 1024) { "保存内容不能超过 20 MiB" }
        val uri = withContext(Dispatchers.Main.immediate) {
            val pending = CompletableDeferred<android.net.Uri?>()
            saveResult = pending
            try {
                saveLauncher.launch(name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "download" })
                pending.await()
            } finally { saveResult = null }
        } ?: throw CancellationException("已取消保存")
        withContext(Dispatchers.IO) {
            val output = activity.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
            output.use { it.write(bytes) }
        }
    }

    val qrScanner: QrScanner = object : QrScanner {
        override suspend fun scan(): String? = scanLock.withLock {
            withContext(Dispatchers.Main.immediate) {
                val pending = CompletableDeferred<String?>()
                scanResult = pending
                try {
                    Log.i("HarnessLink", "scanner.open")
                    scanLauncher.launch(Intent(activity, DshScanActivity::class.java))
                    pending.await()
                } finally {
                    scanResult = null
                }
            }
        }
    }

    val filePicker: FilePicker = object : FilePicker {
        override suspend fun pick(photos: Boolean): List<PickedFile> = filesLock.withLock {
            val uris = withContext(Dispatchers.Main.immediate) {
                val pending = CompletableDeferred<List<android.net.Uri>>()
                filesResult = pending
                try {
                    if (photos) photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else filesLauncher.launch(arrayOf("*/*"))
                    pending.await()
                } finally {
                    filesResult = null
                }
            }
            withContext(Dispatchers.IO) {
                val created = mutableListOf<String>()
                try {
                    uris.map { uri ->
                        val resolver = activity.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "attachment"
                        val handle = UUID.randomUUID().toString()
                        val directory = File(activity.cacheDir, "mobile-uploads").apply { mkdirs() }
                        val file = File(directory, handle)
                        created += handle
                        synchronized(files) { files[handle] = file }
                        resolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取选择的文件" }
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_ATTACHMENT_BYTES) { "单个附件不能超过 128 MiB" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        PickedFile(name, resolver.getType(uri), file.length(), handle)
                    }
                } catch (error: Exception) {
                    created.forEach { handle -> synchronized(files) { files.remove(handle) }?.delete() }
                    throw error
                }
            }
        }

        override suspend fun read(handle: String, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
            require(offset >= 0 && length in 0..MAX_READ_BYTES) { "无效的文件读取范围" }
            val file = synchronized(files) { files[handle] } ?: error("文件已释放，请重新选择")
            RandomAccessFile(file, "r").use { input ->
                require(offset <= input.length()) { "读取位置超出文件长度" }
                input.seek(offset)
                val bytes = ByteArray(minOf(length.toLong(), input.length() - offset).toInt())
                input.readFully(bytes)
                bytes
            }
        }

        override suspend fun release(handle: String) {
            withContext(Dispatchers.IO) { synchronized(files) { files.remove(handle) }?.delete() }
        }
    }

    val notificationPermissions: NotificationPermissions = object : NotificationPermissions {
        override suspend fun request(): Boolean = permissionLock.withLock {
            if (Build.VERSION.SDK_INT < 33) return@withLock true
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                return@withLock true
            }
            withContext(Dispatchers.Main.immediate) {
                val pending = CompletableDeferred<Boolean>()
                permissionResult = pending
                try {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    pending.await()
                } finally {
                    permissionResult = null
                }
            }
        }
    }

    fun close() {
        scanResult?.cancel()
        filesResult?.cancel()
        permissionResult?.cancel()
        saveResult?.cancel()
        synchronized(files) {
            files.values.forEach(File::delete)
            files.clear()
        }
    }

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 128L * 1024 * 1024
        const val MAX_READ_BYTES = 1024 * 1024
    }
}
