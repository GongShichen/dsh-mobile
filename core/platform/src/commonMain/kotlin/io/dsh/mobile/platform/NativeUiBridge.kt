package io.dsh.mobile.platform

/** Callback boundary for native iOS UI. Every operation completes at most once. */
interface UiCancellation { fun cancel() }
interface UiStringCallback { fun success(value: String?); fun failure(message: String) }
interface UiFilesCallback { fun success(files: List<PickedFile>); fun failure(message: String) }
interface UiBytesCallback { fun success(bytes: ByteArray); fun failure(message: String) }
interface UiBooleanCallback { fun success(value: Boolean); fun failure(message: String) }
interface UiUnitCallback { fun success(); fun failure(message: String) }

interface NativeUiBridge {
    fun appMetadata(): PushAppMetadata
    fun scan(callback: UiStringCallback): UiCancellation
    fun pickFiles(callback: UiFilesCallback): UiCancellation
    fun pickPhotos(callback: UiFilesCallback): UiCancellation
    fun readFile(handle: String, offset: Long, length: Int, callback: UiBytesCallback): UiCancellation
    fun releaseFile(handle: String)
    fun saveFile(name: String, bytes: ByteArray, callback: UiUnitCallback): UiCancellation
    fun requestNotificationPermission(callback: UiBooleanCallback): UiCancellation
}
