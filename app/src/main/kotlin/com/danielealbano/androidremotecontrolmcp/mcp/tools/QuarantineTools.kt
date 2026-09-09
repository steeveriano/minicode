package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.QuarantineBatch
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.storage.QuarantineProvider
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// quarantine_files
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `quarantine_files`.
 *
 * Stages files for removal without destroying them: each is moved into a batch directory in the
 * same storage location and recorded in a manifest that maps it back to where it came from.
 *
 * **Input**: `{ "location_id": "...", "paths": ["..."], "reason": "..." }`
 * **Output**: the batch id, what was staged, and anything that did not move.
 */
class QuarantineFilesHandler
    @Inject
    constructor(
        private val quarantineProvider: QuarantineProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val paths =
                    McpToolUtils.requireStringArray(
                        arguments,
                        "paths",
                        QuarantineBatch.MAX_BATCH_ENTRIES,
                    )
                val reason = McpToolUtils.requireString(arguments, "reason")

                val batch = quarantineProvider.quarantine(locationId, paths, reason)
                val staged = batch.entries.size
                val notMoved = paths.size - staged
                val totalBytes = batch.entries.sumOf { it.sizeBytes }

                // Reporting what did NOT move matters as much as what did: a caller that reads a
                // partial batch as complete would believe files are staged that are still in place.
                val summary =
                    buildString {
                        appendLine("Quarantine batch ${batch.batchId} created in $locationId")
                        appendLine("Staged $staged of ${paths.size} files ($totalBytes bytes)")
                        if (notMoved > 0) {
                            appendLine("$notMoved file(s) could not be moved and remain in place")
                        }
                        appendLine("Restore with restore_quarantine_batch, remove with purge_quarantine_batch")
                        batch.entries.forEach { appendLine("  ${it.originalPath} -> ${it.quarantinedName}") }
                    }
                // File names originate from the device, so the response is untrusted content.
                McpToolUtils.untrustedTextResult(summary)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to quarantine files: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Stage files for removal without deleting them. Each file is moved into a " +
                        "quarantine batch inside the same storage location and recorded in a " +
                        "manifest, so the batch can be restored later. Requires the location to " +
                        "allow both write and delete. Nothing is destroyed until " +
                        "purge_quarantine_batch is called.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("paths") {
                                    put("type", "array")
                                    put(
                                        "description",
                                        "Relative paths of the files to stage, at most " +
                                            "${QuarantineBatch.MAX_BATCH_ENTRIES}",
                                    )
                                    putJsonObject("items") { put("type", "string") }
                                }
                                putJsonObject("reason") {
                                    put("type", "string")
                                    put("description", "Why these files are being staged; shown on listing and restore")
                                }
                            },
                        required = listOf("location_id", "paths", "reason"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "quarantine_files"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// list_quarantine_batches
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_quarantine_batches`.
 *
 * Lists the staged batches of a storage location. A location that was never quarantined returns
 * an empty list rather than an error.
 */
class ListQuarantineBatchesHandler
    @Inject
    constructor(
        private val quarantineProvider: QuarantineProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val batches = quarantineProvider.listBatches(locationId)

                val summary =
                    if (batches.isEmpty()) {
                        "No quarantine batches in $locationId"
                    } else {
                        buildString {
                            appendLine("${batches.size} quarantine batch(es) in $locationId")
                            batches.sortedByDescending { it.createdAtEpochMs }.forEach { batch ->
                                val bytes = batch.entries.sumOf { it.sizeBytes }
                                appendLine(
                                    "${batch.batchId}: ${batch.entries.size} file(s), $bytes bytes — ${batch.reason}",
                                )
                            }
                        }
                    }
                // Batch reasons and file names come from the device and from a manifest other
                // applications can write, so this is untrusted content.
                McpToolUtils.untrustedTextResult(summary)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to list quarantine batches: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "List the quarantine batches staged in a storage location, with the file " +
                        "count, total size and reason of each. Returns an empty list when " +
                        "nothing was ever quarantined.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                            },
                        required = listOf("location_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_quarantine_batches"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// restore_quarantine_batch
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `restore_quarantine_batch`.
 *
 * Returns a batch's files to their original paths. Entries that cannot be restored are reported
 * rather than aborting the batch, and stay in quarantine so the restore can be resumed.
 */
class RestoreQuarantineBatchHandler
    @Inject
    constructor(
        private val quarantineProvider: QuarantineProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val batchId = McpToolUtils.requireString(arguments, "batch_id")

                val outcome = quarantineProvider.restore(locationId, batchId)
                val summary =
                    buildString {
                        appendLine("Restored ${outcome.restored.size} file(s) from batch $batchId")
                        outcome.restored.forEach { appendLine("  ${it.originalPath}") }
                        if (outcome.skipped.isNotEmpty()) {
                            appendLine("${outcome.skipped.size} file(s) stayed in quarantine:")
                            outcome.skipped.forEach { appendLine("  ${it.entry.originalPath}: ${it.reason}") }
                        }
                    }
                // Paths come from the manifest, which is device-derived and writable by other
                // applications.
                McpToolUtils.untrustedTextResult(summary)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to restore quarantine batch: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Return a quarantine batch's files to the paths they came from. Files whose " +
                        "original path is occupied stay in quarantine and are reported, so the " +
                        "restore can be resumed. Requires the location to allow write and delete.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("batch_id") {
                                    put("type", "string")
                                    put("description", "Batch identifier from list_quarantine_batches")
                                }
                            },
                        required = listOf("location_id", "batch_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "restore_quarantine_batch"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// purge_quarantine_batch
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `purge_quarantine_batch`.
 *
 * The only irreversible step in the quarantine flow.
 */
class PurgeQuarantineBatchHandler
    @Inject
    constructor(
        private val quarantineProvider: QuarantineProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val batchId = McpToolUtils.requireString(arguments, "batch_id")

                val deleted = quarantineProvider.purge(locationId, batchId)
                // A count is server-generated, so this response carries no device content.
                McpToolUtils.textResult("Purged batch $batchId: $deleted file(s) permanently deleted")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to purge quarantine batch: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Permanently delete a quarantine batch and its manifest. This is " +
                        "irreversible — the files cannot be restored afterwards. Requires the " +
                        "location to allow delete.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("batch_id") {
                                    put("type", "string")
                                    put("description", "Batch identifier from list_quarantine_batches")
                                }
                            },
                        required = listOf("location_id", "batch_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "purge_quarantine_batch"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/** Registers the quarantine tools. Called from `McpServerService.registerAllTools`. */
fun registerQuarantineTools(
    registrar: LoggedToolRegistrar,
    quarantineProvider: QuarantineProvider,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(QuarantineFilesHandler.TOOL_NAME)) {
        QuarantineFilesHandler(quarantineProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListQuarantineBatchesHandler.TOOL_NAME)) {
        ListQuarantineBatchesHandler(quarantineProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(RestoreQuarantineBatchHandler.TOOL_NAME)) {
        RestoreQuarantineBatchHandler(quarantineProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(PurgeQuarantineBatchHandler.TOOL_NAME)) {
        PurgeQuarantineBatchHandler(quarantineProvider).register(registrar, toolNamePrefix)
    }
}
