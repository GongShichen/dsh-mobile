package io.dsh.mobile.model

import kotlinx.serialization.Serializable

@Serializable
data class HLPermissionOption(val value: String, val name: String, val description: String? = null)

@Serializable
data class HLPermissionCatalog(val options: List<HLPermissionOption>, val defaultOptions: List<HLPermissionOption> = emptyList(), val defaultPreset: String? = null)

/** Read-only, in-memory views of complete desktop projections. */
data class HLTokenUsage(val uncachedInput: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long) {
    val billedInput: Long get() = uncachedInput + cacheRead + cacheWrite
    val total: Long get() = billedInput + output
}

data class HLContextBreakdown(val system: Double, val tools: Double, val messages: Double)
data class HLContextUsage(val used: Double, val capacity: Long, val percent: Int, val breakdown: HLContextBreakdown?)

data class HLSessionStatistics(val turns: Long, val steps: Long, val modelMs: Double, val toolMs: Double, val ttftMs: Double, val ttftSteps: Long, val decodeMs: Double, val decodeTokens: Long)
data class HLComposerTelemetry(val usage: HLTokenUsage?, val context: HLContextUsage?, val statistics: HLSessionStatistics?)
