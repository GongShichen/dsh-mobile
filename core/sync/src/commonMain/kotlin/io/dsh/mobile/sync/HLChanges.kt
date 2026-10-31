package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import kotlinx.serialization.json.*

/** A turn can publish a replacement snapshot. Retain only its latest durable event coordinate. */
fun changeAnnouncements(messages: List<HLMessageRecord>, sessionId: String): List<HLChangeAnnouncement> = messages.mapNotNull { message ->
    if (message.sessionId != sessionId || (message.raw["type"] as? JsonPrimitive)?.contentOrNull != "workspace/changes") return@mapNotNull null
    val seq = message.sequence?.takeIf { it >= 0 } ?: return@mapNotNull null
    val turn = ((message.raw["data"] as? JsonObject)?.get("turn") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
    HLChangeAnnouncement(sessionId, seq, turn)
}.groupBy { it.turn }.map { (_, events) -> events.maxBy { it.seq } }.sortedByDescending { it.turn }

data class HLDiffLine(val prefix: Char, val text: String, val oldLine: Int?, val newLine: Int?)

/** Compact line-number indexes; only visible diff lines allocate presentation objects. */
class HLIndexedDiffHunk(val hunk: HLDiffHunk) {
    private val old = IntArray(hunk.lines.size)
    private val new = IntArray(hunk.lines.size)
    init {
        var oldLine = hunk.oldStart
        var newLine = hunk.newStart
        hunk.lines.forEachIndexed { index, line ->
            when (line.firstOrNull()) {
                '+' -> new[index] = newLine++
                '-' -> old[index] = oldLine++
                ' ' -> { old[index] = oldLine++; new[index] = newLine++ }
            }
        }
    }
    fun line(index: Int): HLDiffLine {
        val raw = hunk.lines[index]
        return HLDiffLine(raw.firstOrNull() ?: ' ', raw.drop(1), old[index].takeIf { it > 0 }, new[index].takeIf { it > 0 })
    }
}
