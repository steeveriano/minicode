package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable

/**
 * One quarantine operation: a set of files staged under a single batch directory.
 *
 * Serialized to `.quarantine/<batchId>/manifest.json` inside the storage location itself rather
 * than to DataStore. A restore has to remain possible after the app is uninstalled and
 * reinstalled; without the manifest travelling with the files, a staged archive is stranded
 * under names that mean nothing.
 *
 * @property schemaVersion Manifest format version, so a later format change is detectable.
 * @property batchId Directory name under [QUARANTINE_DIR].
 * @property createdAtEpochMs When the batch was created.
 * @property reason Caller-supplied explanation, echoed back on listing and restore.
 * @property entries The files this batch actually holds. Written after the moves complete, so it
 *   never claims a file that did not move.
 */
@Serializable
data class QuarantineBatch(
    val schemaVersion: Int = MANIFEST_SCHEMA_VERSION,
    val batchId: String,
    val createdAtEpochMs: Long,
    val reason: String,
    val entries: List<QuarantineEntry>,
) {
    companion object {
        const val MANIFEST_SCHEMA_VERSION = 1
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val QUARANTINE_DIR = ".quarantine"

        /** Upper bound on files staged by a single quarantine call. */
        const val MAX_BATCH_ENTRIES = 5_000

        /** Upper bound on a manifest read, well above [MAX_BATCH_ENTRIES] worth of JSON. */
        const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
    }
}

/**
 * One quarantined file.
 *
 * @property originalPath Path relative to the location root, before quarantine. Read back from
 *   the manifest on restore, and therefore untrusted input: the manifest lives in storage other
 *   applications can write.
 * @property quarantinedName File name inside the batch directory, disambiguated when two sources
 *   share a name.
 * @property sizeBytes Size at the time of quarantine.
 * @property quarantinedAtEpochMs When this entry was staged.
 */
@Serializable
data class QuarantineEntry(
    val originalPath: String,
    val quarantinedName: String,
    val sizeBytes: Long,
    val quarantinedAtEpochMs: Long,
)
