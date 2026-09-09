package com.danielealbano.androidremotecontrolmcp.services.storage

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageResult

/**
 * File operations for built-in MediaStore storage locations.
 *
 * All methods mirror [FileOperationProvider] but are scoped to built-in locations.
 * Path traversal protection is enforced on all operations.
 */
interface MediaStoreFileOperations : MediaStoreDirectoryOperations {
    suspend fun listFiles(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileListResult

    suspend fun readFile(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileReadResult

    suspend fun readFileBytes(
        locationId: String,
        path: String,
        maxBytes: Long,
    ): FileBytesResult

    suspend fun writeFile(
        locationId: String,
        path: String,
        content: String,
    )

    suspend fun appendFile(
        locationId: String,
        path: String,
        content: String,
    )

    suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult

    suspend fun downloadFromUrl(
        locationId: String,
        path: String,
        url: String,
    ): Long

    suspend fun deleteFile(
        locationId: String,
        path: String,
    )

    suspend fun createFileUri(
        locationId: String,
        path: String,
        mimeType: String,
    ): Uri
}

/**
 * Directory-level MediaStore operations.
 *
 * Mirrors the split in [FileOperationProvider]: reshaping or measuring the tree is a separate
 * responsibility from reading and writing one file's bytes. [MediaStoreFileOperations] extends
 * this, so callers still inject one type.
 */
interface MediaStoreDirectoryOperations {
    suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult

    suspend fun createDirectory(
        locationId: String,
        path: String,
    ): Boolean

    suspend fun statPath(
        locationId: String,
        path: String,
    ): PathKind?

    suspend fun deleteDirectory(
        locationId: String,
        path: String,
    ): Int

    suspend fun diskUsage(
        locationId: String,
        path: String,
        maxDepth: Int,
    ): DiskUsageResult
}
