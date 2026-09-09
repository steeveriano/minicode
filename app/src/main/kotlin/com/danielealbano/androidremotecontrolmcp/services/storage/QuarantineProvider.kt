package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.QuarantineBatch
import com.danielealbano.androidremotecontrolmcp.data.model.QuarantineEntry

/**
 * Reversible staging for files a caller intends to remove.
 *
 * Reclaiming space on an archive that has no second copy has to be undoable. Files are moved
 * into a batch directory in the same storage location — a metadata-only move, so it is instant
 * and needs no free space — and recorded in a manifest that maps each one back to where it came
 * from. Nothing is destroyed until [purge], which is a separate, explicit step.
 *
 * Every operation is scoped to one storage location and serialised against the others on that
 * same location.
 */
interface QuarantineProvider {
    /**
     * Stages [paths] under a new batch.
     *
     * @return the batch as written, listing only the files that actually moved. A caller
     *   comparing its size against [paths] sees what did not.
     */
    suspend fun quarantine(
        locationId: String,
        paths: List<String>,
        reason: String,
    ): QuarantineBatch

    /** Reads every batch manifest in [locationId]; empty when nothing was ever quarantined. */
    suspend fun listBatches(locationId: String): List<QuarantineBatch>

    /**
     * Returns the files of [batchId] to their original paths.
     *
     * Entries that cannot be restored are reported rather than aborting the batch, and the
     * manifest is rewritten with what remains so the restore can be resumed.
     */
    suspend fun restore(
        locationId: String,
        batchId: String,
    ): RestoreOutcome

    /** Permanently deletes [batchId] and its manifest. @return the number of files removed. */
    suspend fun purge(
        locationId: String,
        batchId: String,
    ): Int
}

/**
 * @property restored Entries returned to their original paths.
 * @property skipped Entries left in quarantine, each with the reason it stayed.
 */
data class RestoreOutcome(
    val restored: List<QuarantineEntry>,
    val skipped: List<SkippedEntry>,
)

/**
 * @property entry The entry that stayed in quarantine.
 * @property reason Why it could not be restored.
 */
data class SkippedEntry(
    val entry: QuarantineEntry,
    val reason: String,
)
