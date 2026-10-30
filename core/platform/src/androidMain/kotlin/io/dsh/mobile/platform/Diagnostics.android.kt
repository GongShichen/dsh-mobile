package io.dsh.mobile.platform
import android.util.Log
internal actual fun writeDiagnostic(stage: String, detail: String) { runCatching { Log.i("HarnessLink", "$stage $detail") } }
