package com.danielealbano.androidremotecontrolmcp.services.storage

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageNode
import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageResult
import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException

/**
 * In-memory [FileOperationProvider] for tests that exercise callers of the provider rather than
 * the provider itself.
 *
 * A file is a key in [files]; a directory exists when it is a prefix of some file's path or was
 * created explicitly. Deliberate fidelity choices, each of which would otherwise hide a real bug:
 *
 * - [FileInfo.path] is `"$locationId/$relativePath"`, matching both real implementations. A
 *   location-relative path here would let a caller that consumes it look correct in tests and
 *   double-prefix on a device.
 * - [listFiles] honours [FileOperationProvider.MAX_LIST_ENTRIES], so a caller that fails to page
 *   fails here too.
 * - [moveFile] honours `overwrite` and refuses a destination that is a directory.
 * - Permissions are enforced, because the callers under test delegate their checks here.
 *
 * [supportsMove] and [failMoveFor] make the failure paths reachable.
 */
@Suppress("TooManyFunctions")
class FakeFileOperationProvider(
    val files: MutableMap<String, ByteArray> = mutableMapOf(),
    private val explicitDirectories: MutableSet<String> = mutableSetOf(),
    var supportsMove: Boolean = true,
    var failMoveFor: Set<String> = emptySet(),
    var isAuthorized: Boolean = true,
    var allowWrite: Boolean = true,
    var allowDelete: Boolean = true,
) : FileOperationProvider {
    /** Every move the fake performed, in order, for assertions about how a caller staged work. */
    val moves: MutableList<Pair<String, String>> = mutableListOf()

    fun putFile(
        path: String,
        content: String,
    ) {
        files[path] = content.toByteArray()
    }

    fun exists(path: String): Boolean = files.containsKey(path)

    private fun requireAuthorized() {
        if (!isAuthorized) throw McpToolException.PermissionDenied("Storage location not found")
    }

    private fun requireWrite() {
        requireAuthorized()
        if (!allowWrite) throw McpToolException.PermissionDenied("Write not allowed")
    }

    private fun requireDelete() {
        requireAuthorized()
        if (!allowDelete) throw McpToolException.PermissionDenied("Delete not allowed")
    }

    private fun isDirectory(path: String): Boolean = path in explicitDirectories || files.keys.any { it.startsWith("$path/") }

    override suspend fun listFiles(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileListResult {
        requireAuthorized()
        BuiltinStorageLocation.validatePath(path)
        if (path.isNotEmpty() && !isDirectory(path)) {
            throw McpToolException.ActionFailed("Directory not found: $path")
        }
        val prefix = if (path.isEmpty()) "" else "$path/"
        val names =
            (files.keys + explicitDirectories)
                .filter { it.startsWith(prefix) && it != path }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        val capped = limit.coerceAtMost(FileOperationProvider.MAX_LIST_ENTRIES)
        val page = names.drop(offset).take(capped)
        return FileListResult(
            files =
                page.map { name ->
                    val full = "$prefix$name"
                    val directory = isDirectory(full)
                    FileInfo(
                        name = name,
                        // Matches FileOperationProviderImpl and MediaStoreFileOperationsImpl.
                        path = "$locationId/$full",
                        isDirectory = directory,
                        size = if (directory) 0L else files[full]?.size?.toLong() ?: 0L,
                        lastModified = null,
                        mimeType = null,
                    )
                },
            totalCount = names.size,
            hasMore = offset + page.size < names.size,
        )
    }

    override suspend fun readFile(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileReadResult {
        val text = readBytesOrThrow(path).decodeToString()
        val allLines = text.lines()
        val capped = limit.coerceAtMost(FileOperationProvider.MAX_READ_LINES)
        val slice = allLines.drop(offset - 1).take(capped)
        return FileReadResult(
            content = slice.joinToString("\n"),
            totalLines = allLines.size,
            hasMore = offset - 1 + slice.size < allLines.size,
            startLine = offset,
            endLine = offset + slice.size - 1,
        )
    }

    override suspend fun readFileBytes(
        locationId: String,
        path: String,
        maxBytes: Long,
    ): FileBytesResult {
        val bytes = readBytesOrThrow(path)
        if (bytes.size > maxBytes) {
            throw McpToolException.ActionFailed("File exceeds $maxBytes bytes")
        }
        return FileBytesResult(
            bytes = bytes,
            mimeType = "application/octet-stream",
            fileName = path.substringAfterLast('/'),
            sizeBytes = bytes.size.toLong(),
        )
    }

    private fun readBytesOrThrow(path: String): ByteArray {
        requireAuthorized()
        BuiltinStorageLocation.validatePath(path)
        return files[path] ?: throw McpToolException.ActionFailed("File not found: $path")
    }

    override suspend fun writeFile(
        locationId: String,
        path: String,
        content: String,
    ) {
        requireWrite()
        BuiltinStorageLocation.validatePath(path)
        files[path] = content.toByteArray()
    }

    override suspend fun appendFile(
        locationId: String,
        path: String,
        content: String,
    ) {
        requireWrite()
        BuiltinStorageLocation.validatePath(path)
        files[path] = (files[path]?.decodeToString().orEmpty() + content).toByteArray()
    }

    override suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult {
        requireWrite()
        val text = readBytesOrThrow(path).decodeToString()
        val count =
            if (replaceAll) {
                text.split(oldString).size - 1
            } else if (text.contains(oldString)) {
                1
            } else {
                0
            }
        files[path] =
            (if (replaceAll) text.replace(oldString, newString) else text.replaceFirst(oldString, newString))
                .toByteArray()
        return FileReplaceResult(replacementCount = count)
    }

    override suspend fun downloadFromUrl(
        locationId: String,
        path: String,
        url: String,
    ): Long = throw McpToolException.ActionFailed("Downloads are not simulated")

    override suspend fun deleteFile(
        locationId: String,
        path: String,
    ) {
        requireDelete()
        BuiltinStorageLocation.validatePath(path)
        if (isDirectory(path)) {
            throw McpToolException.InvalidParams("Cannot delete a directory")
        }
        files.remove(path) ?: throw McpToolException.ActionFailed("File not found: $path")
    }

    override suspend fun createFileUri(
        locationId: String,
        path: String,
        mimeType: String,
    ): Uri = throw McpToolException.ActionFailed("URIs are not simulated")

    override suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult {
        requireWrite()
        requireDelete()
        BuiltinStorageLocation.validatePath(sourcePath)
        BuiltinStorageLocation.validatePath(destinationPath)
        if (sourcePath in failMoveFor) {
            throw McpToolException.ActionFailed("Move failed for $sourcePath")
        }
        if (!supportsMove && !allowCopyFallback) {
            throw McpToolException.InvalidParams(
                "The storage provider supports neither move nor rename for this file.",
            )
        }
        if (isDirectory(destinationPath)) {
            throw McpToolException.InvalidParams("Destination is a directory: $destinationPath")
        }
        if (files.containsKey(destinationPath) && !overwrite) {
            throw McpToolException.InvalidParams("Destination already exists: $destinationPath")
        }
        val bytes =
            files.remove(sourcePath)
                ?: throw McpToolException.ActionFailed("File not found: $sourcePath")
        files[destinationPath] = bytes
        moves += sourcePath to destinationPath
        return FileMoveResult(
            destinationPath = destinationPath,
            sizeBytes = bytes.size.toLong(),
            mechanism = if (supportsMove) MoveMechanism.MOVE_DOCUMENT else MoveMechanism.COPY_DELETE,
        )
    }

    override suspend fun createDirectory(
        locationId: String,
        path: String,
    ): Boolean {
        requireWrite()
        BuiltinStorageLocation.validatePath(path)
        if (isDirectory(path)) return false
        explicitDirectories += path
        return true
    }

    override suspend fun statPath(
        locationId: String,
        path: String,
    ): PathKind? {
        requireAuthorized()
        BuiltinStorageLocation.validatePath(path)
        return when {
            files.containsKey(path) -> PathKind.FILE
            isDirectory(path) -> PathKind.DIRECTORY
            else -> null
        }
    }

    override suspend fun deleteDirectory(
        locationId: String,
        path: String,
    ): Int {
        requireDelete()
        BuiltinStorageLocation.validatePath(path)
        if (!isDirectory(path)) {
            throw McpToolException.ActionFailed("Directory not found: $path")
        }
        val prefix = "$path/"
        val doomed = files.keys.filter { it.startsWith(prefix) }
        doomed.forEach { files.remove(it) }
        explicitDirectories.removeAll { it == path || it.startsWith(prefix) }
        return doomed.size
    }

    override suspend fun diskUsage(
        locationId: String,
        path: String,
        maxDepth: Int,
    ): DiskUsageResult {
        requireAuthorized()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val matching = files.filterKeys { it.startsWith(prefix) }
        return DiskUsageResult(
            root =
                DiskUsageNode(
                    path = path,
                    totalBytes = matching.values.sumOf { it.size.toLong() },
                    fileCount = matching.size,
                    children = emptyList(),
                ),
            truncated = false,
            complete = true,
        )
    }
}
