package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import kotlinx.serialization.json.*
import kotlin.math.floor

private const val HL_MAX_WIRE_INTEGER = 9_007_199_254_740_991L
private fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..HL_MAX_WIRE_INTEGER }
private fun JsonObject.measure(key: String): Double? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 && it <= HL_MAX_WIRE_INTEGER }

/** Missing projections and incomplete buckets stay unknown; no window-scoped local reconstruction. */
fun composerTelemetry(projections: JsonObject?): HLComposerTelemetry {
    val usage = projections?.get("tokenUsage") as? JsonObject
    val tokens = usage?.let {
        val input = it.count("uncachedInputTokens"); val read = it.count("cacheReadTokens"); val write = it.count("cacheWriteTokens"); val output = it.count("outputTokens")
        if (input == null || read == null || write == null || output == null) null else HLTokenUsage(input, read, write, output).takeIf { value -> value.total > 0 }
    }
    val pressure = projections?.get("contextPressure") as? JsonObject
    val context = pressure?.let {
        val used = it.measure("projectedTokens") ?: it.measure("pressureTokens")
        val window = it.count("contextWindow")?.takeIf { size -> size > 0 }
        val breakdown = (projections?.get("contextBreakdown") as? JsonObject)?.let { value ->
            val system = value.measure("systemTokens"); val tools = value.measure("toolsTokens"); val messages = value.measure("messageTokens")
            if (system == null || tools == null || messages == null) null else HLContextBreakdown(system, tools, messages)
        }
        if (used == null || window == null) null else HLContextUsage(used, window, floor(used / window * 100 + .5).toInt().coerceIn(0, 100), breakdown)
    }
    val statistics = (projections?.get("sessionStats") as? JsonObject)?.let {
        val turns = it.count("turns"); val steps = it.count("steps"); val model = it.measure("llmMs"); val tool = it.measure("toolMs")
        val ttft = it.measure("ttftMs"); val timed = it.count("ttftSteps"); val decode = it.measure("decodeMs"); val output = it.count("decodeTokens")
        if (turns == null || steps == null || model == null || tool == null || ttft == null || timed == null || decode == null || output == null || steps == 0L) null
        else HLSessionStatistics(turns, steps, model, tool, ttft, timed, decode, output)
    }
    return HLComposerTelemetry(tokens, context, statistics)
}

/** Same positive-half-up formatting and bucket semantics as pinned DSH Web; see THIRD_PARTY_NOTICES. */
private fun displayNumber(value: Double): String = if (value == floor(value)) value.toLong().toString() else value.toString()
private fun roundOne(value: Double): Double = floor(value * 10 + .5) / 10
fun compactTokens(value: Long): String = compactTokens(value.toDouble())
fun compactTokens(value: Double): String {
    fun scaled(number: Double) = displayNumber(if (number >= 100) floor(number + .5) else roundOne(number))
    return when { value < 1_000 -> displayNumber(value); value < 1_000_000 -> scaled(value / 1_000) + "K"; else -> scaled(value / 1_000_000) + "M" }
}
fun exactTokens(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
fun statisticDuration(ms: Double): String {
    val seconds = ms / 1_000
    if (seconds < 60) return displayNumber(roundOne(seconds)) + "秒"
    val whole = floor(seconds + .5).toLong()
    return "${whole / 60}分${whole % 60}秒"
}
fun tokensPerSecond(tokens: Long, ms: Double): String? {
    if (ms <= 0) return null
    val speed = tokens / (ms / 1_000)
    return displayNumber(if (speed >= 10) floor(speed + .5) else roundOne(speed)) + " tok/s"
}

/** A partial cache hit must not round to 100%; cache writes belong in the prompt denominator. */
fun cacheHitPercent(cacheRead: Long, billedInput: Long, decimals: Int = 0): String? {
    require(decimals in 0..1)
    if (billedInput == 0L) return null
    require(cacheRead in 0..billedInput)
    val missed = billedInput - cacheRead
    if (missed == 0L) return "100"
    val scale = if (decimals == 0) 100L else 1_000L
    val doubledScale = scale * 2
    val quotient = billedInput / doubledScale; val remainder = billedInput % doubledScale
    var lower = 0L; var upper = scale
    while (lower < upper) {
        val candidate = (lower + upper + 1) / 2
        val factor = candidate * 2 - 1
        val threshold = factor * quotient + (factor * remainder + doubledScale - 1) / doubledScale
        if (cacheRead >= threshold) lower = candidate else upper = candidate - 1
    }
    if (lower < scale) return if (decimals == 0) lower.toString() else if (lower % 10 == 0L) (lower / 10).toString() else "${lower / 10}.${lower % 10}"
    var places = 1; var scaledGap = missed * 200
    val tens = billedInput / 10
    while (scaledGap <= tens) { scaledGap *= 10; places++ }
    val ones = billedInput % 10
    var roundedLoss = 5
    for (loss in 1..4) {
        val factor = loss * 2L + 1
        val threshold = factor * tens + factor * ones / 10
        if (scaledGap <= threshold) { roundedLoss = loss; break }
    }
    return "99.${"9".repeat(places - 1)}${10 - roundedLoss}"
}

fun permissionLabel(value: String, name: String = value): String {
    if (value == "auto") return "自动审查"
    val builtin = when (value) { "read-only" -> "Read Only" to "仅可查看"; "workspace-write" -> "Workspace Write" to "工作区内修改"; "danger-full-access" -> "Full access" to "完全权限"; else -> null }
    if (builtin != null && (name == value || name == builtin.first)) return builtin.second
    if (value == "custom" && name == value) return "自定义"
    return if (Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(name)) name.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) } else name
}
