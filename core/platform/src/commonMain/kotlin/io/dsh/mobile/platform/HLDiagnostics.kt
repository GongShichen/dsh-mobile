package io.dsh.mobile.platform

/** Diagnostic metadata only. Never pass message bodies, QR values, tokens or private keys. */
fun reportDiagnostic(stage: String, detail: String = "") = writeDiagnostic(stage, redactDiagnostic(detail))
fun reportFailure(stage: String, error: Throwable) {
    val kind = error::class.simpleName ?: "Error"
    reportDiagnostic(stage, "$kind: ${error.message.orEmpty()}")
}
private fun redactDiagnostic(text: String): String = text
    .replace(Regex("dsh-mobile://\\S+"), "<pairing-redacted>")
    .replace(Regex("(?i)(secret|token|password|api[_-]?key)\\s*[:=]\\s*[^\\s,}]+"), "$1=<redacted>")
    .replace(Regex("[A-Za-z0-9_+/=-]{48,}"), "<redacted>")
    .take(1200)
internal expect fun writeDiagnostic(stage: String, detail: String)
