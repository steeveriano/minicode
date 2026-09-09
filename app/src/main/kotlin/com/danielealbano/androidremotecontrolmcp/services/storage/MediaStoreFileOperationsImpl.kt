@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageNode
import com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageResult
import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo
import com.danielealbano.androidremotecontrolmcp.data.model.MediaCollection
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

@Suppress("SwallowedException")
class MediaStoreFileOperationsImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storageLocationProvider: StorageLocationProvider,
        private val settingsRepository: SettingsRepository,
        private val permissionChecker: PermissionChecker,
    ) : MediaStoreFileOperations {
        private val downloader = MediaStoreDownloader(context)

        // ─── listFiles ──────────────────────────────────────────────────────

        override suspend fun listFiles(
            locationId: String,
            path: String,
            offset: Int,
            limit: Int,
        ): FileListResult =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                val targetRelativePath = buildRelativePathForListing(builtin, path)
                val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_LIST_ENTRIES)

                // Query files in the target directory and children (for directory synthesis)
                val projection =
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.MIME_TYPE,
                    )

                val entries = mutableListOf<FileInfo>()
                val seenDirs = mutableSetOf<String>()

                for (collection in builtin.collections) {
                    val includeNonOwned = hasNonOwnedReadAccess(collection)
                    val selection = buildListSelection(includeNonOwned)
                    val selectionArgs = buildListSelectionArgs(targetRelativePath, includeNonOwned)
                    context.contentResolver
                        .query(
                            collection.uri,
                            projection,
                            selection,
                            selectionArgs,
                            null,
                        )?.use { cursor ->
                            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val relPathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                            while (cursor.moveToNext()) {
                                processCursorRow(
                                    cursor,
                                    nameIdx,
                                    relPathIdx,
                                    sizeIdx,
                                    dateIdx,
                                    mimeIdx,
                                    targetRelativePath,
                                    locationId,
                                    path,
                                    entries,
                                    seenDirs,
                                )
                            }
                        }
                }

                // Sort: directories first, then by name
                val sorted =
                    entries.sortedWith(
                        compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name },
                    )
                val totalCount = sorted.size
                val paginated = sorted.drop(offset).take(cappedLimit)
                val hasMore = offset + cappedLimit < totalCount

                FileListResult(files = paginated, totalCount = totalCount, hasMore = hasMore)
            }

        @Suppress("LongParameterList")
        private fun processCursorRow(
            cursor: android.database.Cursor,
            nameIdx: Int,
            relPathIdx: Int,
            sizeIdx: Int,
            dateIdx: Int,
            mimeIdx: Int,
            targetRelativePath: String,
            locationId: String,
            path: String,
            entries: MutableList<FileInfo>,
            seenDirs: MutableSet<String>,
        ) {
            val relPath = cursor.getString(relPathIdx) ?: return
            val displayName = cursor.getString(nameIdx) ?: return

            if (relPath == targetRelativePath) {
                // Direct child file
                val childRelPath = if (path.isEmpty()) displayName else "$path/$displayName"
                entries.add(
                    FileInfo(
                        name = displayName,
                        path = "$locationId/$childRelPath",
                        isDirectory = false,
                        size = cursor.getLong(sizeIdx),
                        lastModified =
                            cursor
                                .getLong(dateIdx)
                                .takeIf { it > 0L }
                                ?.let { it * MILLIS_PER_SECOND },
                        mimeType = cursor.getString(mimeIdx),
                    ),
                )
            } else if (relPath.length > targetRelativePath.length &&
                relPath.startsWith(targetRelativePath)
            ) {
                // Deeper child -> synthesize directory
                val remainder = relPath.removePrefix(targetRelativePath)
                val dirName = remainder.split("/").firstOrNull { it.isNotEmpty() }
                if (dirName != null && seenDirs.add(dirName)) {
                    val dirRelPath = if (path.isEmpty()) dirName else "$path/$dirName"
                    entries.add(
                        FileInfo(
                            name = dirName,
                            path = "$locationId/$dirRelPath",
                            isDirectory = true,
                            size = 0L,
                            lastModified = null,
                            mimeType = null,
                        ),
                    )
                }
            }
        }

        private fun buildListSelection(includeNonOwned: Boolean): String {
            val pathFilter = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            return if (includeNonOwned) {
                pathFilter
            } else {
                "$pathFilter AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            }
        }

        private fun buildListSelectionArgs(
            targetRelativePath: String,
            includeNonOwned: Boolean,
        ): Array<String> {
            // LIKE pattern: match exact dir and all children; escape the literal prefix only
            val pattern = "${escapeLikePattern(targetRelativePath)}%"
            return if (includeNonOwned) arrayOf(pattern) else arrayOf(pattern, context.packageName)
        }

        // ─── readFile ───────────────────────────────────────────────────────

        @Suppress("NestedBlockDepth")
        override suspend fun readFile(
            locationId: String,
            path: String,
            offset: Int,
            limit: Int,
        ): FileReadResult =
            withContext(Dispatchers.IO) {
                if (offset < 1) {
                    throw McpToolException.InvalidParams("offset must be >= 1, got $offset")
                }
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)

                val uri = findFileOrThrow(builtin, path)
                checkFileSizeByUri(uri)

                val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_READ_LINES)
                val bufferedLines = mutableListOf<String>()
                var totalLines = 0

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var lineNumber = 1
                        var line: String? = reader.readLine()
                        while (line != null) {
                            totalLines = lineNumber
                            if (lineNumber >= offset && bufferedLines.size < cappedLimit) {
                                bufferedLines.add(line)
                            }
                            lineNumber++
                            line = reader.readLine()
                        }
                    }
                } ?: throw McpToolException.ActionFailed("Failed to open file for reading: $path")

                val endLine = if (bufferedLines.isEmpty()) offset else offset + bufferedLines.size - 1

                FileReadResult(
                    content = bufferedLines.joinToString("\n"),
                    totalLines = totalLines,
                    hasMore = endLine < totalLines,
                    startLine = offset,
                    endLine = endLine,
                )
            }

        // ─── readFileBytes ──────────────────────────────────────────────────

        override suspend fun readFileBytes(
            locationId: String,
            path: String,
            maxBytes: Long,
        ): FileBytesResult =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                val uri = findFileOrThrow(builtin, path)
                readFileBytesFromUri(
                    context.contentResolver,
                    uri,
                    extractDisplayName(path),
                    queryFileSize(uri),
                    maxBytes,
                )
            }

        // ─── writeFile ──────────────────────────────────────────────────────

        override suspend fun writeFile(
            locationId: String,
            path: String,
            content: String,
        ) = withContext(Dispatchers.IO) {
            val builtin = resolveBuiltin(locationId)
            BuiltinStorageLocation.validatePath(path)
            checkWritePermission(locationId)

            val config = settingsRepository.getServerConfig()
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            if (contentBytes.size.toLong() > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Content size exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.",
                )
            }

            val relativePath = buildRelativePathForDir(builtin, path)
            val displayName = extractDisplayName(path)
            val mimeType = MimeTypeUtils.guessMimeType(displayName)
            val collection = selectCollectionForMimeType(builtin, mimeType)
            val existingUri = findFileInCollection(collection, relativePath, displayName, ownedOnly = true)

            if (existingUri != null) {
                context.contentResolver.openOutputStream(existingUri, "wt")?.use { it.write(contentBytes) }
                    ?: throw McpToolException.ActionFailed("Failed to open file for writing: $path")
            } else {
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    }
                val insertUri =
                    context.contentResolver.insert(collection.uri, values)
                        ?: throw McpToolException.ActionFailed("Failed to create file: $path")
                context.contentResolver.openOutputStream(insertUri, "wt")?.use { it.write(contentBytes) }
                    ?: throw McpToolException.ActionFailed("Failed to write to new file: $path")
            }

            Log.d(TAG, "Wrote ${contentBytes.size} bytes to $locationId/$path")
            Unit
        }

        // ─── appendFile ─────────────────────────────────────────────────────

        override suspend fun appendFile(
            locationId: String,
            path: String,
            content: String,
        ) = withContext(Dispatchers.IO) {
            val builtin = resolveBuiltin(locationId)
            BuiltinStorageLocation.validatePath(path)
            checkWritePermission(locationId)

            val config = settingsRepository.getServerConfig()
            val uri = findOwnedFileOrThrow(builtin, path)

            val existingSize = queryFileSize(uri)
            val newContentBytes = content.toByteArray(Charsets.UTF_8)
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            if (existingSize + newContentBytes.size.toLong() > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Appending would exceed the configured file size limit of ${config.fileSizeLimitMb} MB.",
                )
            }

            try {
                context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(newContentBytes) }
                    ?: throw McpToolException.ActionFailed("Failed to open file for appending: $path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                throw McpToolException.ActionFailed(
                    "This storage provider does not support append mode. Use write_file instead.",
                )
            } catch (e: IllegalArgumentException) {
                throw McpToolException.ActionFailed(
                    "This storage provider does not support append mode. Use write_file instead.",
                )
            }

            Log.d(TAG, "Appended ${newContentBytes.size} bytes to $locationId/$path")
            Unit
        }

        // ─── replaceInFile ──────────────────────────────────────────────────

        override suspend fun replaceInFile(
            locationId: String,
            path: String,
            oldString: String,
            newString: String,
            replaceAll: Boolean,
        ): FileReplaceResult =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                checkWritePermission(locationId)

                val uri = findOwnedFileOrThrow(builtin, path)
                checkFileSizeByUri(uri)

                val originalContent =
                    context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw McpToolException.ActionFailed("Failed to read file: $path")

                val occurrences = countOccurrences(originalContent, oldString)
                if (occurrences == 0) return@withContext FileReplaceResult(replacementCount = 0)

                val modifiedContent =
                    if (replaceAll) {
                        originalContent.replace(oldString, newString)
                    } else {
                        originalContent.replaceFirst(oldString, newString)
                    }
                val replacementCount = if (replaceAll) occurrences else 1

                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(modifiedContent.toByteArray(Charsets.UTF_8))
                } ?: throw McpToolException.ActionFailed("Failed to write back file: $path")

                Log.d(TAG, "Replaced $replacementCount occurrence(s) in $locationId/$path")
                FileReplaceResult(replacementCount = replacementCount)
            }

        // ─── downloadFromUrl ────────────────────────────────────────────────

        override suspend fun downloadFromUrl(
            locationId: String,
            path: String,
            url: String,
        ): Long =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                checkWritePermission(locationId)

                val config = settingsRepository.getServerConfig()
                val parsedUrl = parseAndValidateDownloadUrl(url, config)
                val relativePath = buildRelativePathForDir(builtin, path)
                val displayName = extractDisplayName(path)
                val mimeType = MimeTypeUtils.guessMimeType(displayName)
                val collection = selectCollectionForMimeType(builtin, mimeType)

                // Create MediaStore entry with IS_PENDING = 1
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val insertUri =
                    context.contentResolver.insert(collection.uri, values)
                        ?: throw McpToolException.ActionFailed("Failed to create download destination: $path")

                downloader.downloadToPendingUri(insertUri, parsedUrl, url, config, "$locationId/$path")
            }

        // ─── deleteFile ─────────────────────────────────────────────────────

        override suspend fun deleteFile(
            locationId: String,
            path: String,
        ) = withContext(Dispatchers.IO) {
            val builtin = resolveBuiltin(locationId)
            BuiltinStorageLocation.validatePath(path)
            checkDeletePermission(locationId)

            val uri = findOwnedFileOrThrow(builtin, path)
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted == 0) {
                throw McpToolException.ActionFailed(
                    "Failed to delete file: $path in location '$locationId'",
                )
            }

            Log.d(TAG, "Deleted file: $locationId/$path")
            Unit
        }

        // ─── createFileUri ──────────────────────────────────────────────────

        override suspend fun createFileUri(
            locationId: String,
            path: String,
            mimeType: String,
        ): Uri =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                checkWritePermission(locationId)

                val relativePath = buildRelativePathForDir(builtin, path)
                val displayName = extractDisplayName(path)
                val collection = selectCollectionForMimeType(builtin, mimeType)

                // Return existing if found
                findFileInCollection(collection, relativePath, displayName, ownedOnly = true)
                    ?.let { return@withContext it }

                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    }
                context.contentResolver.insert(collection.uri, values)
                    ?: throw McpToolException.ActionFailed("Failed to create file: $path")
            }

        // ─── Private helpers ────────────────────────────────────────────────

        // Throws PermissionDenied (not ActionFailed) because this is only called after
        // BuiltinStorageLocation.isBuiltinId() routing — reaching here with an invalid ID
        // means the location is not authorized, matching the SAF checkAuthorization() pattern.
        private fun resolveBuiltin(locationId: String): BuiltinStorageLocation =
            BuiltinStorageLocation.fromLocationId(locationId)
                ?: throw McpToolException.PermissionDenied(
                    "Storage location '$locationId' not found.",
                )

        /**
         * Whether rows in [collection] can be read beyond the ones this app wrote.
         *
         * All-files access is checked first and covers every collection, including those with no
         * `readMediaPermission` of their own — Downloads is the one that matters: MediaStore treats
         * a PDF as neither image, video nor audio, so no READ_MEDIA_* grant ever reveals one.
         *
         * Otherwise MediaStore scopes the read itself: either the full read permission is granted,
         * or the user granted a visual-media selection (READ_MEDIA_VISUAL_USER_SELECTED). In every
         * case that returns true, the app-side owner filter must be dropped so provider-visible
         * non-owned rows are returned.
         */
        private fun hasNonOwnedReadAccess(collection: MediaCollection): Boolean {
            if (permissionChecker.hasAllFilesAccess()) return true
            return collection.readMediaPermission?.let { permission ->
                permissionChecker.hasPermission(permission) ||
                    (
                        collection.isVisual &&
                            permissionChecker.hasPermission(
                                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                            )
                    )
            } == true
        }

        private fun selectCollectionForMimeType(
            builtin: BuiltinStorageLocation,
            mimeType: String,
        ): MediaCollection =
            builtin.collections.firstOrNull { collection ->
                collection.mimeTypePrefix == null || mimeType.startsWith(collection.mimeTypePrefix)
            } ?: throw McpToolException.InvalidParams(
                "File type '$mimeType' is not supported by location '${builtin.locationId}'. " +
                    "Accepted types: ${builtin.collections.joinToString(", ") { it.typeLabel }}.",
            )

        private fun findFileInCollection(
            collection: MediaCollection,
            relativePath: String,
            displayName: String,
            ownedOnly: Boolean,
        ): Uri? {
            val selection =
                buildString {
                    append("${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ")
                    append("${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
                    if (ownedOnly) append(" AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
                }
            val args =
                if (ownedOnly) {
                    arrayOf(relativePath, displayName, context.packageName)
                } else {
                    arrayOf(relativePath, displayName)
                }
            return queryForUri(collection.uri, selection, args)
        }

        /**
         * Builds the MediaStore RELATIVE_PATH for the directory containing the file.
         * E.g., builtin=DOWNLOADS, path="subdir/file.txt" → "Download/subdir/"
         * E.g., builtin=DOWNLOADS, path="file.txt" → "Download/"
         * E.g., builtin=DOWNLOADS, path="" → "Download/"
         */
        private fun buildRelativePathForDir(
            builtin: BuiltinStorageLocation,
            path: String,
        ): String {
            if (path.isEmpty()) return builtin.baseRelativePath
            val segments = path.split("/").filter { it.isNotEmpty() }
            val parentSegments = segments.dropLast(1)
            return if (parentSegments.isEmpty()) {
                builtin.baseRelativePath
            } else {
                "${builtin.baseRelativePath}${parentSegments.joinToString("/")}/"
            }
        }

        /**
         * Builds the MediaStore RELATIVE_PATH for a directory itself (all segments kept).
         * E.g., builtin=PICTURES, path="DCIM/Camera" → "Pictures/DCIM/Camera/"
         * E.g., builtin=PICTURES, path="" → "Pictures/"
         */
        private fun buildRelativePathForListing(
            builtin: BuiltinStorageLocation,
            path: String,
        ): String {
            if (path.isEmpty()) return builtin.baseRelativePath
            val segments = path.split("/").filter { it.isNotEmpty() }
            return "${builtin.baseRelativePath}${segments.joinToString("/")}/"
        }

        /** Escapes LIKE wildcards so the target path matches literally ('\' MUST be replaced first). */
        private fun escapeLikePattern(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        /**
         * Extracts the file name (last segment) from a relative path.
         * Throws if path is empty.
         */
        private fun extractDisplayName(path: String): String {
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                throw McpToolException.InvalidParams("File path cannot be empty")
            }
            return segments.last()
        }

        private fun findOwnedFile(
            builtin: BuiltinStorageLocation,
            relativePath: String,
            displayName: String,
        ): Uri? =
            builtin.collections.firstNotNullOfOrNull { collection ->
                findFileInCollection(collection, relativePath, displayName, ownedOnly = true)
            }

        private fun findFile(
            builtin: BuiltinStorageLocation,
            relativePath: String,
            displayName: String,
        ): Uri? =
            builtin.collections.firstNotNullOfOrNull { collection ->
                findFileInCollection(
                    collection,
                    relativePath,
                    displayName,
                    ownedOnly = !hasNonOwnedReadAccess(collection),
                )
            }

        private fun findFileOrThrow(
            builtin: BuiltinStorageLocation,
            path: String,
        ): Uri {
            val relativePath = buildRelativePathForDir(builtin, path)
            val displayName = extractDisplayName(path)
            return findFile(builtin, relativePath, displayName)
                ?: throw McpToolException.ActionFailed(
                    "File not found: $path in location '${builtin.locationId}'",
                )
        }

        private fun findOwnedFileOrThrow(
            builtin: BuiltinStorageLocation,
            path: String,
        ): Uri {
            val relativePath = buildRelativePathForDir(builtin, path)
            val displayName = extractDisplayName(path)
            return findOwnedFile(builtin, relativePath, displayName)
                ?: throw McpToolException.ActionFailed(
                    "File not found: $path in location '${builtin.locationId}'",
                )
        }

        private fun queryForUri(
            collectionUri: Uri,
            selection: String,
            selectionArgs: Array<String>,
        ): Uri? {
            context.contentResolver
                .query(
                    collectionUri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID),
                            )
                        return Uri.withAppendedPath(collectionUri, id.toString())
                    }
                }
            return null
        }

        private fun queryFileSize(uri: Uri): Long {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE),
                        )
                    }
                }
            return 0L
        }

        private suspend fun checkFileSizeByUri(uri: Uri) {
            val config = settingsRepository.getServerConfig()
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            val fileSize = queryFileSize(uri)
            if (fileSize > limitBytes) {
                throw McpToolException.ActionFailed(
                    "File size ($fileSize bytes) exceeds the configured limit of " +
                        "${config.fileSizeLimitMb} MB.",
                )
            }
        }

        private suspend fun checkWritePermission(locationId: String) {
            if (!storageLocationProvider.isWriteAllowed(locationId)) {
                throw McpToolException.PermissionDenied("Write not allowed")
            }
        }

        private suspend fun checkDeletePermission(locationId: String) {
            if (!storageLocationProvider.isDeleteAllowed(locationId)) {
                throw McpToolException.PermissionDenied("Delete not allowed")
            }
        }

        private fun countOccurrences(
            haystack: String,
            needle: String,
        ): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var startIndex = 0
            while (true) {
                val index = haystack.indexOf(needle, startIndex)
                if (index < 0) break
                count++
                startIndex = index + needle.length
            }
            return count
        }

        // ─── move / directory operations ────────────────────────────────────

        override suspend fun moveFile(
            locationId: String,
            sourcePath: String,
            destinationPath: String,
            overwrite: Boolean,
            allowCopyFallback: Boolean,
        ): FileMoveResult = throw unsupportedForBuiltin("move files within")

        override suspend fun createDirectory(
            locationId: String,
            path: String,
        ): Boolean = throw unsupportedForBuiltin("create directories in")

        override suspend fun deleteDirectory(
            locationId: String,
            path: String,
        ): Int = throw unsupportedForBuiltin("delete directories in")

        /**
         * MediaStore has no directory entities — a directory exists only as a prefix shared by
         * indexed files — so relocating and removing them are not operations this backend can
         * offer. A user who needs them adds the folder as a storage location instead.
         */
        private fun unsupportedForBuiltin(operation: String) =
            McpToolException.InvalidParams(
                "Cannot $operation a built-in location. Add the folder as a storage location " +
                    "in the app settings and use that location id instead.",
            )

        override suspend fun statPath(
            locationId: String,
            path: String,
        ): PathKind? =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                if (path.isEmpty()) return@withContext PathKind.DIRECTORY

                val relativePath = buildRelativePathForListing(builtin, "")
                val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
                // A directory is a prefix of some indexed file's relative path; a file is an
                // exact relative-path plus display-name match.
                val directoryPrefix = "$path/"
                var kind: PathKind? = null

                for (collection in builtin.collections) {
                    if (kind == PathKind.FILE) break
                    val includeNonOwned = hasNonOwnedReadAccess(collection)
                    context.contentResolver
                        .query(
                            collection.uri,
                            projection,
                            buildListSelection(includeNonOwned),
                            buildListSelectionArgs(relativePath, includeNonOwned),
                            null,
                        )?.use { cursor ->
                            val relIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            while (cursor.moveToNext()) {
                                val entryPath = entryRelativePath(cursor, relIdx, nameIdx, relativePath)
                                if (entryPath == path) {
                                    kind = PathKind.FILE
                                    break
                                }
                                if (entryPath.startsWith(directoryPrefix)) {
                                    kind = PathKind.DIRECTORY
                                }
                            }
                        }
                }
                kind
            }

        // ─── diskUsage ──────────────────────────────────────────────────────

        override suspend fun diskUsage(
            locationId: String,
            path: String,
            maxDepth: Int,
        ): DiskUsageResult =
            withContext(Dispatchers.IO) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)

                val relativePath = buildRelativePathForListing(builtin, path)
                val projection =
                    arrayOf(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                    )
                // Totals accumulate per directory. ContentResolver.query does not honour SQL
                // GROUP BY — the sortOrder injection trick is rejected from Android 11 on and
                // minSdk here is 33 — so the aggregation is client-side, one query per
                // collection rather than one query overall.
                val bytesByDir = mutableMapOf<String, Long>()
                val countByDir = mutableMapOf<String, Int>()
                var complete = true

                for (collection in builtin.collections) {
                    val includeNonOwned = hasNonOwnedReadAccess(collection)
                    // Without non-owned read access the query sees only files this app wrote,
                    // so the totals are a floor, not the figure. The caller must be told.
                    if (!includeNonOwned) complete = false
                    context.contentResolver
                        .query(
                            collection.uri,
                            projection,
                            buildListSelection(includeNonOwned),
                            buildListSelectionArgs(relativePath, includeNonOwned),
                            null,
                        )?.use { cursor ->
                            val relIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val locationRelativePath = buildRelativePathForListing(builtin, "")
                            while (cursor.moveToNext()) {
                                val entryPath = entryRelativePath(cursor, relIdx, nameIdx, locationRelativePath)
                                val size = cursor.getLong(sizeIdx)
                                // Credit the file to its own directory and to every ancestor,
                                // so a shallow breakdown still reports complete totals.
                                var dir = entryPath.substringBeforeLast('/', "")
                                while (true) {
                                    bytesByDir[dir] = (bytesByDir[dir] ?: 0L) + size
                                    countByDir[dir] = (countByDir[dir] ?: 0) + 1
                                    if (dir.isEmpty() || dir == path) break
                                    dir = dir.substringBeforeLast('/', "")
                                }
                            }
                        }
                }

                DiskUsageResult(
                    root = buildUsageNode(path, bytesByDir, countByDir, maxDepth, 0),
                    truncated = false,
                    complete = complete,
                )
            }

        /** Assembles the node for [dir] and, while within [maxDepth], its immediate children. */
        private fun buildUsageNode(
            dir: String,
            bytesByDir: Map<String, Long>,
            countByDir: Map<String, Int>,
            maxDepth: Int,
            depth: Int,
        ): DiskUsageNode {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val children =
                if (depth >= maxDepth) {
                    emptyList()
                } else {
                    bytesByDir.keys
                        .filter { it.startsWith(prefix) && it != dir && !it.removePrefix(prefix).contains('/') }
                        .sorted()
                        .map { buildUsageNode(it, bytesByDir, countByDir, maxDepth, depth + 1) }
                }
            return DiskUsageNode(
                path = dir,
                totalBytes = bytesByDir[dir] ?: 0L,
                fileCount = countByDir[dir] ?: 0,
                children = children,
            )
        }

        /** The location-relative path of a cursor row, derived from its MediaStore columns. */
        private fun entryRelativePath(
            cursor: android.database.Cursor,
            relPathIdx: Int,
            nameIdx: Int,
            locationRelativePath: String,
        ): String {
            val rowRelative = cursor.getString(relPathIdx).orEmpty()
            val name = cursor.getString(nameIdx).orEmpty()
            val withinLocation = rowRelative.removePrefix(locationRelativePath).trim('/')
            return if (withinLocation.isEmpty()) name else "$withinLocation/$name"
        }

        companion object {
            private const val TAG = "MCP:MediaStoreFileOps"
            private const val BYTES_PER_MB = 1024L * 1024L
            private const val MILLIS_PER_SECOND = 1000L
        }
    }
