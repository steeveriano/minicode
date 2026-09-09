package com.danielealbano.androidremotecontrolmcp.services.storage

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageResult
import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo

/**
 * Result from a file listing operation.
 *
 * @property files The list of file entries in the requested range.
 * @property totalCount The total number of entries in the directory.
 * @property hasMore Whether there are more entries beyond the requested range.
 */
data class FileListResult(
    val files: List<FileInfo>,
    val totalCount: Int,
    val hasMore: Boolean,
)

/**
 * Result from a file read operation.
 *
 * @property content The text content of the requested lines.
 * @property totalLines The total number of lines in the file.
 * @property hasMore Whether there are more lines beyond the requested range.
 * @property startLine The 1-based line number of the first returned line.
 * @property endLine The 1-based line number of the last returned line.
 */
data class FileReadResult(
    val content: String,
    val totalLines: Int,
    val hasMore: Boolean,
    val startLine: Int,
    val endLine: Int,
)

/**
 * Result from a file string replacement operation.
 *
 * @property replacementCount The number of replacements made.
 */
data class FileReplaceResult(
    val replacementCount: Int,
)

/**
 * Result from a raw byte read of an existing file.
 *
 * @property bytes The file's raw bytes.
 * @property mimeType The resolved MIME type (provider-reported or derived from the extension).
 * @property fileName The file's display name.
 * @property sizeBytes The number of bytes read.
 */
data class FileBytesResult(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileBytesResult) return false
        return bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            fileName == other.fileName &&
            sizeBytes == other.sizeBytes
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }
}

/** What lives at a path. */
enum class PathKind {
    FILE,
    DIRECTORY,
}

/**
 * How a move was carried out.
 *
 * A caller that cannot afford a second copy of the file — staging an archive on a device that is
 * short on space — refuses [COPY_DELETE] up front rather than discovering the cost afterwards.
 */
enum class MoveMechanism {
    /** DocumentsContract.moveDocument: metadata only, no second copy, no free space needed. */
    MOVE_DOCUMENT,

    /** DocumentsContract.renameDocument: same parent, name change only. */
    RENAME_DOCUMENT,

    /** moveDocument then renameDocument: a different parent AND a different name. */
    MOVE_THEN_RENAME,

    /** Streamed copy followed by deletion of the source: needs free space equal to the file. */
    COPY_DELETE,
}

/**
 * Result of a file move.
 *
 * @property destinationPath The relative path the file actually occupies afterwards. A storage
 *   provider may assign a different display name than requested (`photo (1).jpg`), so this is
 *   read back from the moved document rather than echoed from the request.
 * @property sizeBytes Size of the moved file.
 * @property mechanism How the move was performed.
 */
data class FileMoveResult(
    val destinationPath: String,
    val sizeBytes: Long,
    val mechanism: MoveMechanism,
)

/**
 * Provides file operations via the Storage Access Framework.
 *
 * All operations work with virtual paths: "{location_id}/{relative_path}".
 * The location_id must reference an authorized storage location.
 *
 * All operations check the configured file size limit before proceeding.
 */
interface FileOperationProvider {
    /**
     * Lists files in a directory.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path within the location (empty string for root).
     * @param offset Number of entries to skip (0-based).
     * @param limit Maximum number of entries to return (capped at [MAX_LIST_ENTRIES]).
     * @return [FileListResult] with file entries and pagination info.
     */
    suspend fun listFiles(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileListResult

    /**
     * Reads a text file with line-based pagination.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param offset 1-based line number to start reading from.
     * @param limit Maximum number of lines to return (capped at [MAX_READ_LINES]).
     * @return [FileReadResult] with content and pagination info.
     */
    suspend fun readFile(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileReadResult

    /**
     * Reads an EXISTING file's raw bytes from an authorized location. Does NOT create the file.
     * Fails if the file is absent or its size exceeds [maxBytes].
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param maxBytes Maximum allowed file size in bytes.
     * @return [FileBytesResult] with bytes, MIME type, display name, and size.
     */
    suspend fun readFileBytes(
        locationId: String,
        path: String,
        maxBytes: Long,
    ): FileBytesResult

    /**
     * Writes text content to a file. Creates the file and parent directories
     * if they don't exist. Overwrites existing content.
     * Empty content creates an empty file.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param content The text content to write.
     */
    suspend fun writeFile(
        locationId: String,
        path: String,
        content: String,
    )

    /**
     * Appends text content to a file. Tries native append mode ("wa") first.
     * If the provider does not support append mode, throws an ActionFailed
     * exception with a hint to use write_file instead.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param content The text content to append.
     */
    suspend fun appendFile(
        locationId: String,
        path: String,
        content: String,
    )

    /**
     * Performs literal string replacement in a text file.
     * Uses read-modify-write pattern. Tries advisory file lock on local providers.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param oldString The literal string to find.
     * @param newString The replacement string.
     * @param replaceAll If true, replace all occurrences; otherwise replace only the first.
     * @return [FileReplaceResult] with the number of replacements made.
     */
    suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult

    /**
     * Downloads a file from a URL and saves it to an authorized storage location.
     * Creates the file and parent directories if they don't exist. Overwrites
     * existing content. Subject to the configured file size limit.
     *
     * Respects user-configured settings:
     * - If the URL is HTTP (not HTTPS) and `allowHttpDownloads` is false, throws ActionFailed.
     * - If `allowUnverifiedHttpsCerts` is true, skips HTTPS certificate verification.
     *
     * Uses the user-configured download timeout. Follows HTTP redirects.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path for the destination file.
     * @param url The URL to download from.
     * @return The size of the downloaded file in bytes.
     */
    suspend fun downloadFromUrl(
        locationId: String,
        path: String,
        url: String,
    ): Long

    /**
     * Deletes a single file. Does not delete directories.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     */
    suspend fun deleteFile(
        locationId: String,
        path: String,
    )

    /**
     * Creates a new file in the specified storage location and returns its URI.
     * Creates parent directories if they don't exist. If a file at the given
     * path already exists, it is returned as-is (no overwrite of content).
     *
     * This is intended for use cases where an external component (e.g., CameraX)
     * needs a URI to write to directly, rather than writing content through
     * this provider.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param mimeType The MIME type for the new file (e.g., "image/jpeg", "video/mp4").
     *        Used directly for document creation via [android.provider.DocumentsContract].
     * @return The [Uri] for the created file.
     */
    suspend fun createFileUri(
        locationId: String,
        path: String,
        mimeType: String,
    ): Uri

    /**
     * Moves a file to a different path within the same storage location.
     *
     * Requires write AND delete permission: the file leaves the path it occupied, which is a
     * deletion from that path's point of view.
     *
     * @param locationId The authorized storage location identifier.
     * @param sourcePath Relative path of the existing file.
     * @param destinationPath Relative destination path; parent directories are created.
     * @param overwrite When false, an existing destination file fails the operation. An
     *   existing destination *directory* fails regardless of this flag, because removing one
     *   would take its whole subtree with it.
     * @param allowCopyFallback When false and the storage provider supports neither move nor
     *   rename, the operation fails instead of duplicating the file's bytes.
     * @return [FileMoveResult] describing the destination reached and the mechanism used.
     */
    suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult

    /**
     * Creates [path] as a directory, including any missing parents.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path of the directory.
     * @return true when the directory was newly created, false when it already existed. A
     *   caller claiming a uniquely named directory uses the false result to detect a collision.
     */
    suspend fun createDirectory(
        locationId: String,
        path: String,
    ): Boolean

    /**
     * Reports what lives at [path], or null when nothing does.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to inspect.
     */
    suspend fun statPath(
        locationId: String,
        path: String,
    ): PathKind?

    /**
     * Deletes a directory and everything below it.
     *
     * [deleteFile] refuses directories by design, so a caller that needs to remove a whole
     * subtree uses this instead. Throws rather than deleting partially when the subtree exceeds
     * [MAX_USAGE_NODES] directories: a partial delete reporting success would leave files
     * stranded with no record of them.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path of the directory.
     * @return the number of files deleted.
     */
    suspend fun deleteDirectory(
        locationId: String,
        path: String,
    ): Int

    /**
     * Aggregates the size of every file below [path].
     *
     * Totals always cover the whole subtree; [maxDepth] limits only how deep the returned tree
     * is broken down, so a shallow request still reports a complete total.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to aggregate; empty for the location root.
     * @param maxDepth How many levels of sub-directory to itemize.
     */
    suspend fun diskUsage(
        locationId: String,
        path: String,
        maxDepth: Int,
    ): DiskUsageResult

    companion object {
        /** Maximum entries returned by list_files. */
        const val MAX_LIST_ENTRIES = 200

        /** Maximum lines returned by read_file. */
        const val MAX_READ_LINES = 200

        /** Upper bound on directories visited by a single recursive traversal. */
        const val MAX_USAGE_NODES = 20_000

        /** Upper bound on the depth disk_usage will itemize. */
        const val MAX_USAGE_DEPTH = 10

        // File size limit constants are defined in ServerConfig
        // (DEFAULT_FILE_SIZE_LIMIT_MB, MIN_FILE_SIZE_LIMIT_MB, MAX_FILE_SIZE_LIMIT_MB).
        // Use ServerConfig constants — do NOT duplicate them here.
    }
}
