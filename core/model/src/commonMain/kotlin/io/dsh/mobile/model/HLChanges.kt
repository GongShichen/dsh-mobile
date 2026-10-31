package io.dsh.mobile.model

import kotlinx.serialization.Serializable

/** Public, read-only DSH turn snapshots. These models are never written to the mobile database. */
@Serializable
data class HLChangedFile(val path: String, val display: String, val added: Long, val deleted: Long, val binary: Boolean = false, val oversized: Boolean = false)

@Serializable
data class HLChangeSummary(val turn: Long, val files: List<HLChangedFile>, val total: Long, val added: Long, val deleted: Long)

@Serializable
data class HLDiffHunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<String>)

@Serializable
data class HLFileDiff(
    val kind: String, val path: String, val display: String,
    val before: Boolean? = null, val after: Boolean? = null,
    val hunks: List<HLDiffHunk> = emptyList(), val coarse: Boolean = false,
)

data class HLChangeAnnouncement(val sessionId: String, val seq: Long, val turn: Long)

/** File indices always refer to the original summary, even after search or sorting. */
data class HLChangeFileRef(val announcement: HLChangeAnnouncement, val index: Int, val file: HLChangedFile)
