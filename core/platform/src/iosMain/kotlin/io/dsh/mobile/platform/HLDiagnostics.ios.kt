package io.dsh.mobile.platform
import platform.Foundation.NSLog
internal actual fun writeDiagnostic(stage: String, detail: String) { NSLog("HarnessLink $stage $detail".replace("%", "%%")) }
