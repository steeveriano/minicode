package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Aggregated usage for one directory in a storage location.
 *
 * @property path Path relative to the location root; empty for the root itself.
 * @property totalBytes Sum of every file below this directory, at any depth.
 * @property fileCount Number of files below this directory, at any depth.
 * @property children Sub-directories, present only while within the requested depth. A node with
 *   no children may still carry a large [totalBytes]: the walk descended, the breakdown did not.
 */
data class DiskUsageNode(
    val path: String,
    val totalBytes: Long,
    val fileCount: Int,
    val children: List<DiskUsageNode>,
)

/**
 * Outcome of a usage walk.
 *
 * @property root Aggregate for the requested path.
 * @property truncated Traversal stopped at the node budget, so the totals under-report.
 * @property complete False when the backend cannot see every file in the location. A MediaStore
 *   collection the app does not fully own reports only the entries it may read, and a caller
 *   must not present those totals as the whole picture.
 */
data class DiskUsageResult(
    val root: DiskUsageNode,
    val truncated: Boolean,
    val complete: Boolean,
)
