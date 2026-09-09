package com.danielealbano.androidremotecontrolmcp.services.storage

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.QuarantineBatch
import com.danielealbano.androidremotecontrolmcp.data.model.QuarantineEntry
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Default [QuarantineProvider], built on [FileOperationProvider].
 *
 * Permission enforcement is deliberately NOT repeated here. Every mutating step goes through
 * [FileOperationProvider.moveFile] or [FileOperationProvider.deleteDirectory], which check
 * authorization, write and delete for the location; duplicating those checks would create two
 * places for the rules to drift apart.
 */
@Singleton
class QuarantineProviderImpl
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) : QuarantineProvider {
        private val json = Json { ignoreUnknownKeys = true }

        // One lock per location. quarantine, restore and purge all read-modify-write the same
        // manifest and enumerate the same directory, and concurrent MCP requests are in scope by
        // design, so a lost update here would mean lost files.
        private val locationLocks = ConcurrentHashMap<String, Mutex>()

        private suspend fun <T> withLocationLock(
            locationId: String,
            block: suspend () -> T,
        ): T = locationLocks.computeIfAbsent(locationId) { Mutex() }.withLock { block() }

        override suspend fun quarantine(
            locationId: String,
            paths: List<String>,
            reason: String,
        ): QuarantineBatch =
            withLocationLock(locationId) {
                if (paths.isEmpty()) {
                    throw McpToolException.InvalidParams("paths must not be empty")
                }
                if (paths.size > QuarantineBatch.MAX_BATCH_ENTRIES) {
                    throw McpToolException.InvalidParams(
                        "A batch accepts at most ${QuarantineBatch.MAX_BATCH_ENTRIES} paths, " +
                            "got ${paths.size}",
                    )
                }
                paths.forEach { BuiltinStorageLocation.validatePath(it) }

                val batchId = claimBatchDirectory(locationId)
                val batchPath = batchDirectory(batchId)
                val usedNames = mutableSetOf<String>()
                val entries = mutableListOf<QuarantineEntry>()

                for (path in paths) {
                    val requestedName = disambiguate(path.substringAfterLast('/'), usedNames)
                    val moved =
                        runCatching {
                            fileOperationProvider.moveFile(
                                locationId = locationId,
                                sourcePath = path,
                                destinationPath = "$batchPath/$requestedName",
                                overwrite = false,
                                // Copying would need free space equal to the archive, which is
                                // the opposite of what staging is for on a device short on it.
                                allowCopyFallback = false,
                            )
                        }.getOrElse { error ->
                            Log.w(TAG, "Could not quarantine an entry: ${error.message}")
                            usedNames -= requestedName
                            null
                        } ?: continue

                    // Record where the file actually landed: the provider may have assigned a
                    // different display name than the one requested.
                    val actualName = moved.destinationPath.substringAfterLast('/')
                    usedNames += actualName
                    entries +=
                        QuarantineEntry(
                            originalPath = path,
                            quarantinedName = actualName,
                            sizeBytes = moved.sizeBytes,
                            quarantinedAtEpochMs = System.currentTimeMillis(),
                        )
                }

                // Written after the moves, listing only what moved: a manifest must never claim
                // a file it does not hold.
                val batch =
                    QuarantineBatch(
                        batchId = batchId,
                        createdAtEpochMs = System.currentTimeMillis(),
                        reason = reason,
                        entries = entries,
                    )
                writeManifest(locationId, batch)
                batch
            }

        override suspend fun listBatches(locationId: String): List<QuarantineBatch> =
            withLocationLock(locationId) {
                // A location that was never quarantined has no .quarantine directory, and
                // listFiles throws for a missing path. That is the most common first call.
                if (fileOperationProvider.statPath(locationId, QuarantineBatch.QUARANTINE_DIR) == null) {
                    return@withLocationLock emptyList()
                }
                listAll(locationId, QuarantineBatch.QUARANTINE_DIR)
                    .filter { it.isDirectory }
                    .mapNotNull { entry ->
                        // A manifest that will not parse must not fail the whole listing: the
                        // other batches are still restorable and the caller needs to see them.
                        runCatching { readManifest(locationId, entry.name) }
                            .onFailure { Log.w(TAG, "Skipping unreadable manifest in ${entry.name}") }
                            .getOrNull()
                    }
            }

        override suspend fun restore(
            locationId: String,
            batchId: String,
        ): RestoreOutcome =
            withLocationLock(locationId) {
                BuiltinStorageLocation.validatePath(batchId)
                val batch = readManifest(locationId, batchId)
                val restored = mutableListOf<QuarantineEntry>()
                val skipped = mutableListOf<SkippedEntry>()

                for (entry in batch.entries) {
                    // The manifest lives in storage other applications can write, so its
                    // contents are untrusted input. moveFile validates too, but failing here
                    // keeps the batch intact and names the entry that is wrong.
                    val invalid =
                        runCatching {
                            BuiltinStorageLocation.validatePath(entry.originalPath)
                            BuiltinStorageLocation.validatePath(entry.quarantinedName)
                        }.exceptionOrNull()
                    if (invalid != null) {
                        skipped += SkippedEntry(entry, "invalid path in manifest")
                        continue
                    }
                    if (fileOperationProvider.statPath(locationId, entry.originalPath) != null) {
                        skipped += SkippedEntry(entry, "original path is occupied")
                        continue
                    }
                    val outcome =
                        runCatching {
                            fileOperationProvider.moveFile(
                                locationId = locationId,
                                sourcePath = "${batchDirectory(batchId)}/${entry.quarantinedName}",
                                destinationPath = entry.originalPath,
                                overwrite = false,
                                allowCopyFallback = false,
                            )
                        }
                    if (outcome.isSuccess) {
                        restored += entry
                    } else {
                        skipped += SkippedEntry(entry, outcome.exceptionOrNull()?.message.orEmpty())
                    }
                }

                if (skipped.isEmpty()) {
                    fileOperationProvider.deleteDirectory(locationId, batchDirectory(batchId))
                } else {
                    // Rewriting with what remains keeps a partial restore resumable.
                    writeManifest(locationId, batch.copy(entries = skipped.map { it.entry }))
                }
                RestoreOutcome(restored, skipped)
            }

        override suspend fun purge(
            locationId: String,
            batchId: String,
        ): Int =
            withLocationLock(locationId) {
                BuiltinStorageLocation.validatePath(batchId)
                fileOperationProvider.deleteDirectory(locationId, batchDirectory(batchId))
            }

        /**
         * Creates a batch directory under a name nothing else holds.
         *
         * A timestamp alone collides for two calls in the same second, and the second call would
         * then move its files into the first batch and overwrite its manifest, leaving the first
         * batch's files unrestorable under names that mean nothing.
         */
        private suspend fun claimBatchDirectory(locationId: String): String {
            repeat(BATCH_ID_ATTEMPTS) {
                val candidate =
                    "${LocalDateTime.now().format(BATCH_ID_FORMAT)}-${randomSuffix()}"
                if (fileOperationProvider.createDirectory(locationId, batchDirectory(candidate))) {
                    return candidate
                }
            }
            throw McpToolException.ActionFailed(
                "Could not claim a unique quarantine batch id after $BATCH_ID_ATTEMPTS attempts",
            )
        }

        /** Appends an index before the extension until the name is free within the batch. */
        private fun disambiguate(
            name: String,
            used: MutableSet<String>,
        ): String {
            if (used.add(name)) return name
            val stem = name.substringBeforeLast('.')
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var index = 1
            while (true) {
                val candidate = "$stem-$index$extension"
                if (used.add(candidate)) return candidate
                index++
            }
        }

        /**
         * Enumerates [path] completely.
         *
         * [FileOperationProvider.listFiles] caps at [FileOperationProvider.MAX_LIST_ENTRIES], so
         * a single call would see part of a large batch — and a purge built on a partial listing
         * would report success having left files behind.
         */
        private suspend fun listAll(
            locationId: String,
            path: String,
        ): List<com.danielealbano.androidremotecontrolmcp.data.model.FileInfo> {
            val all = mutableListOf<com.danielealbano.androidremotecontrolmcp.data.model.FileInfo>()
            var offset = 0
            while (true) {
                val page =
                    fileOperationProvider.listFiles(
                        locationId,
                        path,
                        offset,
                        FileOperationProvider.MAX_LIST_ENTRIES,
                    )
                all += page.files
                if (!page.hasMore || page.files.isEmpty()) return all
                offset += page.files.size
            }
        }

        /**
         * Reads a manifest as bytes.
         *
         * [FileOperationProvider.readFile] truncates at [FileOperationProvider.MAX_READ_LINES],
         * which for a large batch would yield invalid JSON and make the batch look corrupt.
         */
        private suspend fun readManifest(
            locationId: String,
            batchId: String,
        ): QuarantineBatch {
            val result =
                fileOperationProvider.readFileBytes(
                    locationId,
                    manifestPath(batchId),
                    QuarantineBatch.MAX_MANIFEST_BYTES,
                )
            return json.decodeFromString(result.bytes.decodeToString())
        }

        private suspend fun writeManifest(
            locationId: String,
            batch: QuarantineBatch,
        ) {
            // Serialized on a single line so its size never interacts with the read-line cap.
            fileOperationProvider.writeFile(
                locationId,
                manifestPath(batch.batchId),
                json.encodeToString(batch),
            )
        }

        private fun batchDirectory(batchId: String) = "${QuarantineBatch.QUARANTINE_DIR}/$batchId"

        private fun manifestPath(batchId: String) = "${batchDirectory(batchId)}/${QuarantineBatch.MANIFEST_FILE_NAME}"

        private fun randomSuffix(): String =
            (1..SUFFIX_LENGTH)
                .map { SUFFIX_ALPHABET[Random.nextInt(SUFFIX_ALPHABET.length)] }
                .joinToString("")

        private companion object {
            const val TAG = "MCP:Quarantine"
            const val BATCH_ID_ATTEMPTS = 8
            const val SUFFIX_LENGTH = 6
            const val SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
            val BATCH_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        }
    }
