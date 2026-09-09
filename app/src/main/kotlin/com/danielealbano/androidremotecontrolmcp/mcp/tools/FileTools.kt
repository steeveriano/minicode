@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageNode
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// list_storage_locations
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_storage_locations`.
 *
 * Lists all available storage locations (built-in and user-added) with their metadata.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "[{\"id\":\"...\", ...}]" }] }`
 * **Errors**:
 *   - ActionFailed if listing storage locations fails
 */
class ListStorageLocationsHandler
    @Inject
    constructor(
        private val storageLocationProvider: StorageLocationProvider,
    ) {
        @Suppress("UnusedParameter", "TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locations = storageLocationProvider.getAllLocations()
                val jsonArray =
                    buildJsonArray {
                        for (location in locations) {
                            add(
                                buildJsonObject {
                                    put("id", location.id)
                                    put("name", location.name)
                                    put("path", location.path)
                                    put("description", location.description)
                                    if (location.availableBytes != null) {
                                        put("available_bytes", location.availableBytes)
                                    } else {
                                        put("available_bytes", JsonNull)
                                    }
                                    // All storage locations are always readable by design — the StorageLocation
                                    // model has no allowRead field because read access is granted implicitly
                                    // when the user adds a location via the SAF picker.
                                    put("allow_read", true)
                                    put("allow_write", location.allowWrite)
                                    put("allow_delete", location.allowDelete)
                                    location.accessLevel?.let { level ->
                                        put("access_level", level.jsonValue)
                                    }
                                },
                            )
                        }
                    }
                McpToolUtils.untrustedTextResult(Json.encodeToString(jsonArray))
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to list storage locations: ${e.message ?: "Unknown error"}",
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
                    "Lists available storage locations. Includes built-in locations " +
                        "(always available, no setup required) and user-added locations. " +
                        "Use the location ID from this list for all file operations. " +
                        "Built-in locations report access_level: full, partial (user granted a " +
                        "limited photo/video selection or a per-type subset), or owned_only.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_storage_locations"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// list_files
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_files`.
 *
 * Lists files and directories in an authorized storage location with pagination.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "offset": 0, "limit": 200 }`
 * **Output**: `{ "content": [{ "type": "text", "text": "{\"files\":[...], ...}" }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if listing files fails
 */
class ListFilesHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.optionalString(arguments, "path", "")
                val offset = McpToolUtils.optionalInt(arguments, "offset", 0)
                val limit = McpToolUtils.optionalInt(arguments, "limit", FileOperationProvider.MAX_LIST_ENTRIES)

                val result = fileOperationProvider.listFiles(locationId, path, offset, limit)

                val resultJson =
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for (file in result.files) {
                                    add(
                                        buildJsonObject {
                                            put("name", file.name)
                                            put("path", file.path)
                                            put("is_directory", file.isDirectory)
                                            put("size", file.size)
                                            if (file.lastModified != null) {
                                                put("last_modified", file.lastModified)
                                            } else {
                                                put("last_modified", JsonNull)
                                            }
                                            if (file.mimeType != null) {
                                                put("mime_type", file.mimeType)
                                            } else {
                                                put("mime_type", JsonNull)
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put("total_count", result.totalCount)
                        put("has_more", result.hasMore)
                    }

                McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to list files: ${e.message ?: "Unknown error"}",
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
                    "Lists files and directories in a storage location. The location must be authorized. " +
                        "Returns name, path, type, size, last_modified, and mime_type for each entry.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path within the location (empty for root)")
                                    put("default", "")
                                }
                                putJsonObject("offset") {
                                    put("type", "integer")
                                    put("description", "Number of entries to skip (0-based)")
                                    put("default", 0)
                                }
                                putJsonObject("limit") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Maximum number of entries to return " +
                                            "(max ${FileOperationProvider.MAX_LIST_ENTRIES})",
                                    )
                                    put("default", FileOperationProvider.MAX_LIST_ENTRIES)
                                }
                            },
                        required = listOf("location_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_files"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// read_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `read_file`.
 *
 * Reads a text file from an authorized storage location with line-based pagination.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "offset": 1, "limit": 200 }`
 * **Output**: `{ "content": [{ "type": "text", "text": "1| line content\n2| ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if reading the file fails
 */
class ReadFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val offset = McpToolUtils.optionalInt(arguments, "offset", 1)
                val limit = McpToolUtils.optionalInt(arguments, "limit", FileOperationProvider.MAX_READ_LINES)

                val result = fileOperationProvider.readFile(locationId, path, offset, limit)

                val lines = result.content.split("\n")
                val numberedLines =
                    lines.mapIndexed { index, line ->
                        "${result.startLine + index}| $line"
                    }

                val text =
                    buildString {
                        append(numberedLines.joinToString("\n"))
                        if (result.hasMore) {
                            append(
                                "\n--- More lines available. Use offset=${result.endLine + 1} " +
                                    "to continue reading. Total lines: ${result.totalLines} ---",
                            )
                        }
                    }

                McpToolUtils.untrustedTextResult(text)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to read file: ${e.message ?: "Unknown error"}",
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
                    "Reads a text file from an authorized storage location with line-based pagination. " +
                        "Returns content with line numbers. Maximum ${FileOperationProvider.MAX_READ_LINES} " +
                        "lines per call.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("offset") {
                                    put("type", "integer")
                                    put("description", "1-based line number to start reading from")
                                    put("default", 1)
                                }
                                putJsonObject("limit") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Maximum number of lines to return " +
                                            "(max ${FileOperationProvider.MAX_READ_LINES})",
                                    )
                                    put("default", FileOperationProvider.MAX_READ_LINES)
                                }
                            },
                        required = listOf("location_id", "path"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "read_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// write_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `write_file`.
 *
 * Writes text content to a file in an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "content": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "File written successfully: ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if writing the file fails
 */
class WriteFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val content = McpToolUtils.requireString(arguments, "content")

                fileOperationProvider.writeFile(locationId, path, content)

                McpToolUtils.textResult("File written successfully: $locationId/$path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to write file: ${e.message ?: "Unknown error"}",
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
                    "Writes text content to a file in an authorized storage location. Creates the file if it " +
                        "doesn't exist. Creates parent directories automatically. Overwrites existing content. " +
                        "Pass empty content to create an empty file.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "The text content to write")
                                }
                            },
                        required = listOf("location_id", "path", "content"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "write_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// append_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `append_file`.
 *
 * Appends text content to an existing file in an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "content": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Content appended successfully to: ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if appending fails or provider does not support append mode
 */
class AppendFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val content = McpToolUtils.requireString(arguments, "content")

                fileOperationProvider.appendFile(locationId, path, content)

                McpToolUtils.textResult("Content appended successfully to: $locationId/$path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to append to file: ${e.message ?: "Unknown error"}",
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
                    "Appends text content to an existing file in an authorized storage location. If the " +
                        "storage provider does not support append mode, an error is returned with a hint " +
                        "to use ${toolNamePrefix}write_file instead.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "The text content to append")
                                }
                            },
                        required = listOf("location_id", "path", "content"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "append_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// file_replace
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `file_replace`.
 *
 * Performs literal string replacement in a text file in an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "old_string": "...",
 *   "new_string": "...", "replace_all": false }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Replaced N occurrence(s) ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if replacement fails
 */
class FileReplaceHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val oldString = McpToolUtils.requireString(arguments, "old_string")
                val newString = McpToolUtils.requireString(arguments, "new_string")
                val replaceAll = McpToolUtils.optionalBoolean(arguments, "replace_all", false)

                val result = fileOperationProvider.replaceInFile(locationId, path, oldString, newString, replaceAll)

                McpToolUtils.textResult("Replaced ${result.replacementCount} occurrence(s) of the specified string.")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to replace in file: ${e.message ?: "Unknown error"}",
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
                    "Performs literal string replacement in a text file in an authorized storage location. " +
                        "Reads the file, performs replacement, and writes back. Uses advisory file locking " +
                        "when supported by the provider.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("old_string") {
                                    put("type", "string")
                                    put("description", "The literal string to find")
                                }
                                putJsonObject("new_string") {
                                    put("type", "string")
                                    put("description", "The replacement string")
                                }
                                putJsonObject("replace_all") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "If true, replace all occurrences; otherwise replace only the first",
                                    )
                                    put("default", false)
                                }
                            },
                        required = listOf("location_id", "path", "old_string", "new_string"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "file_replace"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// download_from_url
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `download_from_url`.
 *
 * Downloads a file from a URL and saves it to an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "url": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "File downloaded successfully. ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if download or save fails
 */
class DownloadFromUrlHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val url = McpToolUtils.requireString(arguments, "url")

                if (!url.lowercase().startsWith("http://") && !url.lowercase().startsWith("https://")) {
                    throw McpToolException.InvalidParams("URL must use http:// or https:// scheme")
                }

                val downloadedBytes = fileOperationProvider.downloadFromUrl(locationId, path, url)

                McpToolUtils.textResult(
                    "File downloaded successfully. Size: $downloadedBytes bytes. Saved to: $locationId/$path",
                )
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to download file: ${e.message ?: "Unknown error"}",
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
                    "Downloads a file from a URL and saves it to an authorized storage location. Creates " +
                        "the file and parent directories if they don't exist. Overwrites existing content. " +
                        "Subject to the configured file size limit and download timeout. HTTP downloads and " +
                        "unverified HTTPS certificates must be explicitly allowed in the app settings.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path for the destination file")
                                }
                                putJsonObject("url") {
                                    put("type", "string")
                                    put("description", "The URL to download from")
                                }
                            },
                        required = listOf("location_id", "path", "url"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "download_from_url"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// delete_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `delete_file`.
 *
 * Deletes a single file from an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "File deleted successfully: ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if deletion fails
 */
class DeleteFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")

                fileOperationProvider.deleteFile(locationId, path)

                McpToolUtils.textResult("File deleted successfully: $locationId/$path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to delete file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Deletes a single file from an authorized storage location. Cannot delete directories.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                            },
                        required = listOf("location_id", "path"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "delete_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// move_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `move_file`.
 *
 * Relocates a file within one storage location.
 *
 * **Input**: `{ "location_id": "...", "source_path": "...", "destination_path": "...",
 *   "overwrite": false, "allow_copy_fallback": false }`
 * **Output**: the path reached and the mechanism used.
 */
class MoveFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val sourcePath = McpToolUtils.requireString(arguments, "source_path")
                val destinationPath = McpToolUtils.requireString(arguments, "destination_path")
                val overwrite = McpToolUtils.optionalBoolean(arguments, "overwrite", false)
                val allowCopyFallback =
                    McpToolUtils.optionalBoolean(arguments, "allow_copy_fallback", false)

                val result =
                    fileOperationProvider.moveFile(
                        locationId,
                        sourcePath,
                        destinationPath,
                        overwrite,
                        allowCopyFallback,
                    )

                // The destination is echoed from the caller's own request, so nothing here
                // originates from the device.
                McpToolUtils.textResult(
                    "Moved to ${result.destinationPath} (${result.sizeBytes} bytes, " +
                        "${result.mechanism.name.lowercase()})",
                )
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to move file: ${e.message ?: "Unknown error"}",
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
                    "Move a file to a different path within the same storage location. Requires " +
                        "the location to allow both write and delete, because the file leaves " +
                        "the path it occupied. Fails if the destination is a directory. When the " +
                        "storage provider supports neither move nor rename the call fails unless " +
                        "allow_copy_fallback is set, since copying needs free space equal to the " +
                        "file. Not supported for built-in locations.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("source_path") {
                                    put("type", "string")
                                    put("description", "Relative path of the file to move")
                                }
                                putJsonObject("destination_path") {
                                    put("type", "string")
                                    put("description", "Relative destination path; parent directories are created")
                                }
                                putJsonObject("overwrite") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "Replace an existing destination file (default false). " +
                                            "A destination directory is never replaced.",
                                    )
                                }
                                putJsonObject("allow_copy_fallback") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "Permit copy-then-delete when the provider cannot move " +
                                            "or rename (default false). Needs free space equal " +
                                            "to the file.",
                                    )
                                }
                            },
                        required = listOf("location_id", "source_path", "destination_path"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "move_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// disk_usage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `disk_usage`.
 *
 * Aggregates the size of every file below a path in one call. `list_files` is flat and capped at
 * [FileOperationProvider.MAX_LIST_ENTRIES] entries, so summarising an archive through it costs
 * one call per directory page.
 */
class DiskUsageHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.optionalString(arguments, "path", "")
                val maxDepth =
                    McpToolUtils
                        .optionalInt(arguments, "max_depth", DEFAULT_DEPTH)
                        .coerceIn(0, FileOperationProvider.MAX_USAGE_DEPTH)

                val result = fileOperationProvider.diskUsage(locationId, path, maxDepth)

                val summary =
                    buildString {
                        // A partial total read as a whole one would send a caller deleting the
                        // wrong things, so say so before the numbers.
                        if (result.truncated) {
                            appendLine(
                                "WARNING: traversal stopped at the node budget; these totals " +
                                    "under-report the real usage.",
                            )
                        }
                        if (!result.complete) {
                            appendLine(
                                "WARNING: this location only exposes files this app owns, so " +
                                    "these totals cover part of its contents.",
                            )
                        }
                        appendUsageNode(result.root, 0)
                    }
                // Directory names originate from the device.
                McpToolUtils.untrustedTextResult(summary)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to compute disk usage: ${e.message ?: "Unknown error"}",
                )
            }

        private fun StringBuilder.appendUsageNode(
            node: DiskUsageNode,
            depth: Int,
        ) {
            val indent = "  ".repeat(depth)
            val label = node.path.ifEmpty { "/" }
            appendLine("$indent$label — ${node.totalBytes} bytes, ${node.fileCount} file(s)")
            node.children
                .sortedByDescending { it.totalBytes }
                .forEach { appendUsageNode(it, depth + 1) }
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Aggregate the total size and file count below a path, broken down per " +
                        "directory. Totals always cover the whole subtree; max_depth limits only " +
                        "how deep the breakdown goes. A large archive can take minutes on the " +
                        "first call.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to aggregate; empty for the location root")
                                }
                                putJsonObject("max_depth") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Levels of sub-directory to itemize (default " +
                                            "$DEFAULT_DEPTH, max ${FileOperationProvider.MAX_USAGE_DEPTH})",
                                    )
                                }
                            },
                        required = listOf("location_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "disk_usage"
            private const val DEFAULT_DEPTH = 2
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all file operation tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerFileTools(
    registrar: LoggedToolRegistrar,
    storageLocationProvider: StorageLocationProvider,
    fileOperationProvider: FileOperationProvider,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(ListStorageLocationsHandler.TOOL_NAME)) {
        ListStorageLocationsHandler(storageLocationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListFilesHandler.TOOL_NAME)) {
        ListFilesHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ReadFileHandler.TOOL_NAME)) {
        ReadFileHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(WriteFileHandler.TOOL_NAME)) {
        WriteFileHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(AppendFileHandler.TOOL_NAME)) {
        AppendFileHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(FileReplaceHandler.TOOL_NAME)) {
        FileReplaceHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DownloadFromUrlHandler.TOOL_NAME)) {
        DownloadFromUrlHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(MoveFileHandler.TOOL_NAME)) {
        MoveFileHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DiskUsageHandler.TOOL_NAME)) {
        DiskUsageHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DeleteFileHandler.TOOL_NAME)) {
        DeleteFileHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
}
