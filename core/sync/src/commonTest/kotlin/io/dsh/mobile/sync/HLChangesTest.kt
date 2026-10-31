package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import io.dsh.mobile.protocol.HLMethods
import io.dsh.mobile.protocol.WireJson
import kotlinx.serialization.json.*
import kotlin.test.*

class HLChangesTest {
    private fun announcement(seq: Long?, turn: Long, session: String = "s", type: String = "workspace/changes") = HLMessageRecord(
        "event:$seq", session, "event", "", seq, raw = buildJsonObject { put("type", type); put("data", buildJsonObject { put("turn", turn) }) },
    )

    @Test fun replacementEventsAndPagedHistoryKeepOnlyTheLatestSnapshotOfEachTurn() {
        val current = listOf(announcement(20, 2), announcement(30, 3), announcement(22, 2), announcement(20, 2))
        assertEquals(listOf(30L, 22L), changeAnnouncements(current, "s").map { it.seq })
        val paged = current + listOf(announcement(10, 1), announcement(19, 2))
        assertEquals(listOf(3L, 2L, 1L), changeAnnouncements(paged, "s").map { it.turn })
        assertEquals(22L, changeAnnouncements(paged.reversed(), "s")[1].seq)
    }

    @Test fun unrelatedSessionsTransientRecordsAndInvalidTurnsNeverAddressSnapshots() {
        val rows = listOf(announcement(1, 1, "other"), announcement(null, 1), announcement(-1, 1), announcement(2, 0), announcement(3, -1), announcement(4, 1, type = "tool/result"))
        assertTrue(changeAnnouncements(rows, "s").isEmpty())
    }

    @Test fun unifiedDiffNumbersRespectHunkBoundariesInsertionsDeletionsAndEmptyLines() {
        val indexed = HLIndexedDiffHunk(HLDiffHunk(5, 3, 8, 4, listOf(" old", "-removed", "+replacement", "+", " tail")))
        assertEquals(HLDiffLine(' ', "old", 5, 8), indexed.line(0))
        assertEquals(HLDiffLine('-', "removed", 6, null), indexed.line(1))
        assertEquals(HLDiffLine('+', "replacement", null, 9), indexed.line(2))
        assertEquals(HLDiffLine('+', "", null, 10), indexed.line(3))
        assertEquals(HLDiffLine(' ', "tail", 7, 11), indexed.line(4))
        val added = HLIndexedDiffHunk(HLDiffHunk(1, 0, 1, 1, listOf("+new")))
        assertEquals(HLDiffLine('+', "new", null, 1), added.line(0))
        val deleted = HLIndexedDiffHunk(HLDiffHunk(1, 1, 1, 0, listOf("-gone")))
        assertEquals(HLDiffLine('-', "gone", 1, null), deleted.line(0))
    }

    @Test fun fileSearchRetainsOriginalSummaryIndicesAndWireVariantsDoNotInventText() {
        val summary = WireJson.decodeFromString(HLChangeSummary.serializer(), """{"turn":1,"files":[{"path":"a","display":"a","added":1,"deleted":0},{"path":"b","display":"b","added":0,"deleted":0,"binary":true}],"total":5,"added":3,"deleted":2}""")
        val filtered = summary.files.withIndex().filter { it.value.display == "b" }
        assertEquals(1, filtered.single().index)
        assertTrue(filtered.single().value.binary)
        assertEquals(5L, summary.total)
        val binary = WireJson.decodeFromString(HLFileDiff.serializer(), """{"kind":"binary","path":"b","display":"b"}""")
        assertNull(binary.before); assertNull(binary.after); assertTrue(binary.hunks.isEmpty())
        val oversized = WireJson.decodeFromString(HLFileDiff.serializer(), """{"kind":"oversized","path":"big","display":"big"}""")
        assertEquals("oversized", oversized.kind)
        assertTrue(setOf("changes.summary", "changes.diff").all { it in HLMethods.reads && it !in HLMethods.mutations })
    }
}
