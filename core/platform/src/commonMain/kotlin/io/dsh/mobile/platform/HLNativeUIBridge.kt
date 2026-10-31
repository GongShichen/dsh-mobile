package io.dsh.mobile.platform

/** Callback boundary for native iOS UI. Every operation completes at most once. */
interface HLUICancellation { fun cancel() }
interface HLUIStringCallback { fun success(value: String?); fun failure(message: String) }
interface HLUIFilesCallback { fun success(files: List<HLPickedFile>); fun failure(message: String) }
interface HLUIBytesCallback { fun success(bytes: ByteArray); fun failure(message: String) }
interface HLUIBooleanCallback { fun success(value: Boolean); fun failure(message: String) }
interface HLUIUnitCallback { fun success(); fun failure(message: String) }

interface HLNativeUIBridge {
    fun appMetadata(): HLPushAppMetadata
    fun scan(callback: HLUIStringCallback): HLUICancellation
    fun pickFiles(callback: HLUIFilesCallback): HLUICancellation
    fun pickPhotos(callback: HLUIFilesCallback): HLUICancellation
    fun readFile(handle: String, offset: Long, length: Int, callback: HLUIBytesCallback): HLUICancellation
    fun releaseFile(handle: String)
    fun saveFile(name: String, bytes: ByteArray, callback: HLUIUnitCallback): HLUICancellation
    fun requestNotificationPermission(callback: HLUIBooleanCallback): HLUICancellation
}
