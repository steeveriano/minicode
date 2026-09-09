<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Move, non-destructive quarantine, and disk usage

Narrowed from `67_storage_organizer_quarantine_i18n_help_20260909031500.md` to the three
storage stories. Localization and inline help are deliberately out of scope: combining them
with this work produced two rounds of critical review findings, and they carry no risk to user
data while these three do. Plan 67's review findings section records what this plan corrects.

## Context

Driving use case: auditing and organizing a large WhatsApp media archive on a secondary device
where the on-disk copy is the only copy. WhatsApp media lives under
`Android/media/com.whatsapp/`, reachable through SAF (unlike `Android/data`) and not indexed by
MediaStore, so these tools target SAF locations and report built-in MediaStore locations as
unsupported where they cannot serve the operation.

Decisions that constrain everything below:

**Moving a file out of its path counts as deletion.** `move_file`, `quarantine_files` and
`restore_quarantine_batch` require BOTH write and delete permission on the location. What makes
quarantine safe is reversibility, not the permission it runs under.

**Quarantine never falls back to copy.** On a provider without `FLAG_SUPPORTS_MOVE`, copy-then-
delete needs free space equal to the archive. `quarantine_files` fails instead; `move_file`
exposes the fallback behind an explicit opt-in.

**Nothing enumerates a directory or reads a manifest through a single capped call.**
`MAX_LIST_ENTRIES` and `MAX_READ_LINES` are both 200 (`FileOperationProvider.kt:238,241`).

**No destructive operation is implicit.** A destination that is a directory is never deleted to
make room, at any `overwrite` setting.

---

## User Story 1 — File relocation primitives

**Why**: The tool surface can create and delete a file but cannot relocate one, and
`deleteFile` refuses directories by design (`FileOperationProviderImpl.kt:431-436`). Quarantine
needs both a move and a directory delete.

**Acceptance criteria**:
- [ ] `move_file` relocates a file within one SAF location, preserving content and honouring a
      changed file name
- [ ] Rejected unless the location is authorized AND allows write AND allows delete
- [ ] A destination that is a directory is never deleted, at any `overwrite` setting
- [ ] The copy fallback runs only on explicit opt-in and the result says which mechanism ran
- [ ] Built-in MediaStore locations return a clear unsupported error
- [ ] Every path argument is validated before use

### Task 1.1 — Provider API

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add above the
   `FileOperationProvider` interface:

```kotlin
/**
 * How a move was carried out.
 *
 * A caller that cannot afford a second copy of the file — quarantining an archive on a device
 * that is short on space — refuses [COPY_DELETE] up front rather than discovering the cost
 * afterwards.
 */
enum class MoveMechanism {
    /** DocumentsContract.moveDocument: metadata-only, no second copy, no free space needed. */
    MOVE_DOCUMENT,

    /** DocumentsContract.renameDocument: same parent, name change only. */
    RENAME_DOCUMENT,

    /** moveDocument followed by renameDocument: different parent AND a different name. */
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
```

2. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add to the
   interface:

```kotlin
    /**
     * Moves a file within a single storage location.
     *
     * Requires write AND delete permission: the file leaves the path it occupied, which is a
     * deletion from that path's point of view.
     *
     * @param locationId The authorized storage location identifier.
     * @param sourcePath Relative path of the existing file.
     * @param destinationPath Relative destination path; parent directories are created.
     * @param overwrite When false, an existing destination file fails the operation. An
     *   existing destination *directory* fails regardless — see [FileMoveResult].
     * @param allowCopyFallback When false and the provider supports neither move nor rename,
     *   the operation fails instead of duplicating the file's bytes.
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
     * Creates [path] as a directory, including missing parents, and returns whether it was
     * newly created (false when it already existed).
     *
     * Requires write permission. Quarantine needs to claim a batch directory before moving any
     * file into it, and an already-existing directory is how a batch-id collision is detected.
     */
    suspend fun createDirectory(locationId: String, path: String): Boolean

    /**
     * Reports whether [path] exists within [locationId], and whether it is a directory.
     *
     * Requires read authorization only. Returns null when nothing exists at [path].
     */
    suspend fun statPath(locationId: String, path: String): PathKind?

    /**
     * Deletes a directory and everything below it.
     *
     * [deleteFile] refuses directories by design, so purging a quarantine batch needs this.
     * Requires delete permission. Throws rather than deleting partially when the subtree
     * exceeds [MAX_USAGE_NODES] directories: a partial delete that reports success is the
     * failure mode this whole plan exists to avoid.
     *
     * @return the number of files deleted.
     */
    suspend fun deleteDirectory(locationId: String, path: String): Int
```

   and, in the existing companion object beside `MAX_LIST_ENTRIES` / `MAX_READ_LINES`:

```kotlin
        /** Upper bound on directories visited by a single recursive traversal. */
        const val MAX_USAGE_NODES = 20_000
```

   plus the small result type:

```kotlin
/** What lives at a path. */
enum class PathKind { FILE, DIRECTORY }
```

3. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperations.kt` (modify) — add
   `moveFile`, `createDirectory`, `statPath` and `deleteDirectory` to the interface, so
   `FileOperationProviderImpl` can dispatch built-in ids to it.

4. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperationsImpl.kt` (modify) —
   implement all four:

```kotlin
    override suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult = throw unsupportedForBuiltin("move")

    override suspend fun createDirectory(locationId: String, path: String): Boolean =
        throw unsupportedForBuiltin("create a directory in")

    override suspend fun deleteDirectory(locationId: String, path: String): Int =
        throw unsupportedForBuiltin("delete a directory in")

    private fun unsupportedForBuiltin(operation: String) =
        McpToolException.InvalidParams(
            "Cannot $operation a built-in location. Add the folder as a storage location in " +
                "the app settings and use that location id instead.",
        )
```

   `statPath` IS implementable for built-in locations and is implemented, since `diskUsage`
   (US2) and callers checking for a path both benefit: query the collections for an exact
   `RELATIVE_PATH`/`DISPLAY_NAME` match and return `FILE`, or `DIRECTORY` when the path is a
   prefix of any indexed entry, else null.

**Constraint**: the interface and its implementation MUST be modified in the same task. Adding a
member to one without the other does not compile.

**Definition of Done**:
- [ ] `MediaStoreFileOperations` and `MediaStoreFileOperationsImpl` declare identical members
- [ ] No interface member is left abstract in the implementation

### Task 1.2 — `moveFile` implementation

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) — add:

```kotlin
    override suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult =
        withContext(Dispatchers.IO) {
            if (BuiltinStorageLocation.isBuiltinId(locationId)) {
                return@withContext mediaStoreFileOperations.moveFile(
                    locationId, sourcePath, destinationPath, overwrite, allowCopyFallback,
                )
            }
            BuiltinStorageLocation.validatePath(sourcePath)
            BuiltinStorageLocation.validatePath(destinationPath)
            checkAuthorization(locationId)
            checkWritePermission(locationId)
            checkDeletePermission(locationId)

            val source = resolveRegularFileOrThrow(locationId, sourcePath)
            val sizeBytes = source.length()
            val sourceParent =
                source.parentFile
                    ?: throw McpToolException.ActionFailed("Cannot resolve the parent of $sourcePath")

            val destinationParentPath = destinationPath.substringBeforeLast('/', "")
            val destinationName = destinationPath.substringAfterLast('/')
            if (destinationName.isEmpty()) {
                throw McpToolException.InvalidParams("Destination path must name a file")
            }
            val destinationParent = ensureDirectory(locationId, destinationParentPath)

            destinationParent.findFile(destinationName)?.let { existing ->
                // A directory is never removed to make room. DocumentFile.delete() on a
                // directory removes the whole subtree, so honouring `overwrite` here would turn
                // one mistaken argument into unbounded data loss.
                if (existing.isDirectory) {
                    throw McpToolException.InvalidParams(
                        "Destination is a directory: $destinationPath",
                    )
                }
                if (!overwrite) {
                    throw McpToolException.InvalidParams(
                        "Destination already exists: $destinationPath",
                    )
                }
                if (!existing.delete()) {
                    throw McpToolException.ActionFailed(
                        "Could not replace the existing destination: $destinationPath",
                    )
                }
            }

            val samePar = sourceParent.uri == destinationParent.uri
            val sameName = source.name == destinationName
            val canMove = source.hasFlag(DocumentsContract.Document.FLAG_SUPPORTS_MOVE)
            val canRename = source.hasFlag(DocumentsContract.Document.FLAG_SUPPORTS_RENAME)

            // moveDocument preserves the display name, so a move that also changes the name
            // needs a rename afterwards. Getting this wrong silently lands the file under its
            // old name while the caller records the new one.
            val moved: Uri =
                when {
                    samePar && sameName -> source.uri
                    samePar && canRename -> renameOrThrow(source.uri, destinationName)
                    !samePar && canMove && sameName ->
                        moveOrThrow(source.uri, sourceParent.uri, destinationParent.uri)
                    !samePar && canMove && canRename ->
                        renameOrThrow(
                            moveOrThrow(source.uri, sourceParent.uri, destinationParent.uri),
                            destinationName,
                        )
                    !allowCopyFallback ->
                        throw McpToolException.InvalidParams(
                            "The storage provider supports neither move nor rename for this " +
                                "file. Copying instead would need $sizeBytes bytes of free " +
                                "space; pass allow_copy_fallback to accept that cost.",
                        )
                    else -> copyThenDeleteSource(source, destinationParent, destinationName)
                }

            val mechanism =
                when {
                    samePar && sameName -> MoveMechanism.RENAME_DOCUMENT
                    samePar -> MoveMechanism.RENAME_DOCUMENT
                    canMove && sameName -> MoveMechanism.MOVE_DOCUMENT
                    canMove -> MoveMechanism.MOVE_THEN_RENAME
                    else -> MoveMechanism.COPY_DELETE
                }

            // The provider may have assigned a different display name; report where the file
            // actually is, not where it was asked to go.
            val actualName =
                DocumentFile.fromSingleUri(context, moved)?.name ?: destinationName
            val actualPath =
                if (destinationParentPath.isEmpty()) actualName else "$destinationParentPath/$actualName"

            FileMoveResult(actualPath, sizeBytes, mechanism)
        }
```

2. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) — add the
   private helpers the block above uses:

```kotlin
        /** Reads [DocumentsContract.Document.COLUMN_FLAGS] for a document; DocumentFile hides it. */
        private fun DocumentFile.hasFlag(flag: Int): Boolean =
            context.contentResolver
                .query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) and flag != 0 else false }
                ?: false

        private fun moveOrThrow(sourceUri: Uri, fromParent: Uri, toParent: Uri): Uri =
            DocumentsContract.moveDocument(context.contentResolver, sourceUri, fromParent, toParent)
                ?: throw McpToolException.ActionFailed("Move rejected by the storage provider")

        private fun renameOrThrow(sourceUri: Uri, newName: String): Uri =
            DocumentsContract.renameDocument(context.contentResolver, sourceUri, newName)
                ?: throw McpToolException.ActionFailed("Rename rejected by the storage provider")

        /**
         * Streams [source] into a new document under [destinationParent] and deletes the source
         * only once the copy is complete and its length verified.
         *
         * The order matters: on a device holding the only copy of a file, an interruption must
         * leave the original readable. A partial destination is removed before rethrowing.
         */
        private suspend fun copyThenDeleteSource(
            source: DocumentFile,
            destinationParent: DocumentFile,
            destinationName: String,
        ): Uri {
            checkFileSize(source)
            val expected = source.length()
            val mimeType = source.type ?: MimeTypeUtils.guessMimeType(destinationName)
            val destination =
                destinationParent.createFile(mimeType, destinationName)
                    ?: throw McpToolException.ActionFailed(
                        "Failed to create destination '$destinationName'",
                    )
            try {
                context.contentResolver.openInputStream(source.uri).use { input ->
                    context.contentResolver.openOutputStream(destination.uri).use { output ->
                        if (input == null || output == null) {
                            throw McpToolException.ActionFailed("Could not open the file streams")
                        }
                        input.copyTo(output)
                    }
                }
                if (destination.length() != expected) {
                    throw McpToolException.ActionFailed(
                        "Copy is incomplete: expected $expected bytes, wrote ${destination.length()}",
                    )
                }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
            if (!source.delete()) {
                destination.delete()
                throw McpToolException.ActionFailed(
                    "Copied the file but could not remove the source; nothing was changed",
                )
            }
            return destination.uri
        }

        /**
         * Resolves [path] to a directory, creating it and any missing parents.
         *
         * [ensureParentDirectoriesAndCreateFile] creates a *file* at the leaf, so it cannot
         * serve a caller that needs the directory itself.
         */
        private suspend fun ensureDirectory(
            locationId: String,
            path: String,
        ): DocumentFile {
            val treeUri =
                storageLocationProvider.getTreeUriForLocation(locationId)
                    ?: throw McpToolException.ActionFailed(
                        "Could not retrieve tree URI for location '$locationId'",
                    )
            var current =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw McpToolException.ActionFailed(
                        "Could not create DocumentFile from tree URI for location '$locationId'",
                    )
            for (segment in path.split("/").filter { it.isNotEmpty() }) {
                val existing = current.findFile(segment)
                current =
                    when {
                        existing != null && existing.isDirectory -> existing
                        existing != null ->
                            throw McpToolException.ActionFailed(
                                "Path component '$segment' is a file, not a directory",
                            )
                        else ->
                            current.createDirectory(segment)
                                ?: throw McpToolException.ActionFailed(
                                    "Failed to create directory '$segment'",
                                )
                    }
            }
            return current
        }
```

**Constraint**: every other operation in this file runs SAF I/O directly in a `suspend` function
with no dispatcher. New operations wrap in `withContext(Dispatchers.IO)` per ARCHITECTURE.md
line 137; the divergence is deliberate and is the correct direction.

**Constraint**: the file size limit is NOT applied to a metadata-only move — no bytes are read,
and the limit bounds how much a single request transfers. It IS applied inside
`copyThenDeleteSource`.

**Definition of Done**:
- [ ] `checkAuthorization`, `checkWritePermission` and `checkDeletePermission` all run before
      any mutation, matching the order used by `writeFile` (line 196) and `deleteFile` (line 427)
- [ ] No code path deletes a directory
- [ ] A failure at any point leaves the source file readable at its original path

### Task 1.3 — `createDirectory`, `statPath` and `deleteDirectory`

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify):

```kotlin
    override suspend fun createDirectory(locationId: String, path: String): Boolean =
        withContext(Dispatchers.IO) {
            if (BuiltinStorageLocation.isBuiltinId(locationId)) {
                return@withContext mediaStoreFileOperations.createDirectory(locationId, path)
            }
            BuiltinStorageLocation.validatePath(path)
            checkAuthorization(locationId)
            checkWritePermission(locationId)
            val existed = resolveDocumentFile(locationId, path)?.isDirectory == true
            ensureDirectory(locationId, path)
            !existed
        }

    override suspend fun statPath(locationId: String, path: String): PathKind? =
        withContext(Dispatchers.IO) {
            if (BuiltinStorageLocation.isBuiltinId(locationId)) {
                return@withContext mediaStoreFileOperations.statPath(locationId, path)
            }
            BuiltinStorageLocation.validatePath(path)
            checkAuthorization(locationId)
            resolveDocumentFile(locationId, path)?.let {
                if (it.isDirectory) PathKind.DIRECTORY else PathKind.FILE
            }
        }

    override suspend fun deleteDirectory(locationId: String, path: String): Int =
        withContext(Dispatchers.IO) {
            if (BuiltinStorageLocation.isBuiltinId(locationId)) {
                return@withContext mediaStoreFileOperations.deleteDirectory(locationId, path)
            }
            BuiltinStorageLocation.validatePath(path)
            checkAuthorization(locationId)
            checkDeletePermission(locationId)

            val root =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed("Directory not found: $path")
            if (!root.isDirectory) {
                throw McpToolException.InvalidParams("Not a directory: $path")
            }

            // Collect the whole subtree first. Deleting as we walk would, on hitting the node
            // budget, leave a half-deleted tree and a success-looking count.
            val directories = mutableListOf<DocumentFile>()
            val files = mutableListOf<DocumentFile>()
            val stack = ArrayDeque<DocumentFile>().apply { addLast(root) }
            while (stack.isNotEmpty()) {
                ensureActive()
                val dir = stack.removeLast()
                directories += dir
                if (directories.size > FileOperationProvider.MAX_USAGE_NODES) {
                    throw McpToolException.ActionFailed(
                        "Directory tree exceeds ${FileOperationProvider.MAX_USAGE_NODES} " +
                            "directories; nothing was deleted. Delete a smaller subtree.",
                    )
                }
                for (child in dir.listFiles()) {
                    if (child.isDirectory) stack.addLast(child) else files += child
                }
            }

            var deleted = 0
            for (file in files) {
                if (file.delete()) deleted++
            }
            // Children before parents.
            for (dir in directories.asReversed()) {
                dir.delete()
            }
            deleted
        }
```

**Definition of Done**:
- [ ] The node budget is reached before anything is deleted, never during
- [ ] `createDirectory` is idempotent: a second call returns false and changes nothing

### Task 1.4 — Register `move_file`

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — add a `MoveFileHandler` beside
   `DeleteFileHandler`, following that handler's shape exactly (private class, `TOOL_NAME`
   companion constant, `register(registrar, toolNamePrefix)` method, `McpToolUtils` argument
   helpers, plain text result):

```kotlin
private class MoveFileHandler(
    private val fileOperationProvider: FileOperationProvider,
) {
    fun register(registrar: LoggedToolRegistration, toolNamePrefix: String) {
        registrar.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Move a file to a different path within the same storage location. Requires " +
                    "the location to allow both write and delete, because the file leaves the " +
                    "path it occupied. Fails if the destination is a directory. When the " +
                    "storage provider cannot move or rename, the call fails unless " +
                    "allow_copy_fallback is set, since copying needs free space equal to the file.",
            // input schema: location_id, source_path, destination_path required;
            // overwrite and allow_copy_fallback optional booleans defaulting to false
        ) { request ->
            val params = request.arguments
            val result =
                fileOperationProvider.moveFile(
                    locationId = McpToolUtils.requireString(params, "location_id"),
                    sourcePath = McpToolUtils.requireString(params, "source_path"),
                    destinationPath = McpToolUtils.requireString(params, "destination_path"),
                    overwrite = McpToolUtils.optionalBoolean(params, "overwrite") ?: false,
                    allowCopyFallback =
                        McpToolUtils.optionalBoolean(params, "allow_copy_fallback") ?: false,
                )
            McpToolUtils.textResult(
                "Moved to ${result.destinationPath} (${result.sizeBytes} bytes, " +
                    "${result.mechanism.name.lowercase()})",
            )
        }
    }

    companion object {
        const val TOOL_NAME = "move_file"
    }
}
```

2. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — register it inside
   `registerFileTools`, matching the surrounding entries:

```kotlin
    if (perms.isToolEnabled(MoveFileHandler.TOOL_NAME)) {
        MoveFileHandler(fileOperationProvider).register(registrar, toolNamePrefix)
    }
```

**Constraint**: the response is server-generated text — the only device-derived element is the
destination path the caller itself supplied — so it uses the plain result helper. The exact
helper names and the `addTool` signature MUST be taken from the surrounding handlers in the file
rather than from this block, which shows structure rather than exact API.

**Definition of Done**:
- [ ] `move_file` appears in `list_tools` when enabled and is absent when disabled

### Task 1.5 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderMoveTest.kt` (create)

**Setup**: mirror `FileOperationProviderTest.kt:73` — `mockkStatic(DocumentFile::class)` and, new
here, `mockkStatic(DocumentsContract::class)`, because `unitTests.isReturnDefaultValues = true`
(`app/build.gradle.kts:300`) makes the real static methods return null. Mock `ContentResolver`
so the `COLUMN_FLAGS` cursor is controllable per document. Mock `StorageLocationProvider` with
independent `isLocationAuthorized`, `isWriteAllowed` and `isDeleteAllowed`.

| Test | Verifies |
|------|----------|
| `moveFile uses moveDocument for a different parent and the same name` | Mechanism MOVE_DOCUMENT, no rename call |
| `moveFile moves then renames for a different parent and a different name` | Mechanism MOVE_THEN_RENAME — the file lands under the requested name |
| `moveFile uses renameDocument within the same parent` | Mechanism RENAME_DOCUMENT |
| `moveFile is a no-op move when parent and name are unchanged` | No provider call, success |
| `moveFile reports the provider-assigned name` | Destination path read back from the document |
| `moveFile refuses the copy fallback by default` | Free-space message, source intact |
| `moveFile copies when the fallback is opted into` | Mechanism COPY_DELETE, content preserved |
| `moveFile creates missing destination directories` | Parent chain created |
| `moveFile succeeds with write and delete allowed` | The permitted path works end to end |
| `moveFile fails when the location is not authorized` | `PermissionDenied`, nothing mutated |
| `moveFile fails when write is not allowed` | Source untouched |
| `moveFile fails when delete is not allowed` | Source untouched — the recorded decision |
| `moveFile fails when the destination is a directory and overwrite is false` | Refused |
| `moveFile fails when the destination is a directory and overwrite is true` | Still refused, directory intact — no subtree deletion |
| `moveFile fails when replacing the destination file fails` | Refused before touching the source |
| `moveFile overwrites an existing destination file when overwrite is true` | Replaced |
| `moveFile fails on a builtin location` | Unsupported message names the workaround |
| `moveFile rejects a traversal path in either argument` | `../` refused |
| `moveFile rejects a directory as source` | Explicit error |
| `moveFile ignores the size limit on a metadata-only move` | A file above the limit still moves |
| `moveFile enforces the size limit on the copy fallback` | Rejected |
| `moveFile keeps the source when the copy is short` | Length mismatch removes the partial destination, source readable |
| `moveFile keeps both when the source delete fails after a copy` | Destination removed, source readable, error raised |

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderDirectoryTest.kt` (create)

| Test | Verifies |
|------|----------|
| `createDirectory creates missing parents and reports true` | Chain created |
| `createDirectory on an existing directory reports false` | Idempotent |
| `createDirectory fails when write is not allowed` | Rejected |
| `statPath returns FILE for a file` | — |
| `statPath returns DIRECTORY for a directory` | — |
| `statPath returns null for a missing path` | No exception |
| `deleteDirectory removes nested content and reports the file count` | Children before parents |
| `deleteDirectory refuses a file` | Explicit error |
| `deleteDirectory on a missing path fails cleanly` | No partial state |
| `deleteDirectory requires delete permission` | Rejected |
| `deleteDirectory deletes nothing past the node budget` | Throws with the tree intact |

**File**: `app/src/test/kotlin/.../integration/FileToolsIntegrationTest.kt` (modify)

| Test | Verifies |
|------|----------|
| `move_file returns success and names the mechanism` | Full HTTP → SDK → provider dispatch |
| `move_file returns an error for an unknown location` | `isError = true` |

**Definition of Done**:
- [ ] Every acceptance criterion of US1 is covered by at least one test

---

## User Story 2 — `disk_usage` MCP tool

**Why**: `list_files` is flat and capped at 200 entries, so summarising a media archive costs
one call per directory page. A single recursive call turns a several-hundred-call audit into one.

**Acceptance criteria**:
- [ ] Returns per-directory aggregated size and file count to a caller-specified depth
- [ ] Enumeration is not subject to the 200-entry listing cap
- [ ] Traversal is bounded and the result says when it stopped early
- [ ] Built-in MediaStore locations report whether their totals are complete
- [ ] The response carries the untrusted-content warning

### Task 2.1 — Model and API

**Actions**:

1. `app/src/main/kotlin/.../data/model/DiskUsageNode.kt` (create):

```kotlin
/**
 * Aggregated usage for one directory in a storage location.
 *
 * @property path Path relative to the location root; empty for the root itself.
 * @property totalBytes Sum of every file below this directory, at any depth.
 * @property fileCount Number of files below this directory, at any depth.
 * @property children Sub-directories, present only while within the requested depth.
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
 * @property truncated Traversal stopped at the node budget, so totals under-report.
 * @property complete False when the backend cannot see every file — a MediaStore location the
 *   app does not fully own reports only the entries it may read, and a caller must not present
 *   those totals as the whole picture.
 */
data class DiskUsageResult(
    val root: DiskUsageNode,
    val truncated: Boolean,
    val complete: Boolean,
)
```

2. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add to the
   interface:

```kotlin
    /**
     * Aggregates file sizes below [path].
     *
     * Totals always cover the whole subtree; [maxDepth] limits only how deep the returned tree
     * is broken down. Traversal stops after [MAX_USAGE_NODES] directories and says so.
     */
    suspend fun diskUsage(locationId: String, path: String, maxDepth: Int): DiskUsageResult
```

3. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperations.kt` (modify) — add the
   same member so built-in ids can be dispatched.

**Definition of Done**:
- [ ] Interface and both implementations declare the member

### Task 2.2 — SAF traversal

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify):

```kotlin
    override suspend fun diskUsage(
        locationId: String,
        path: String,
        maxDepth: Int,
    ): DiskUsageResult =
        withContext(Dispatchers.IO) {
            if (BuiltinStorageLocation.isBuiltinId(locationId)) {
                return@withContext mediaStoreFileOperations.diskUsage(locationId, path, maxDepth)
            }
            if (path.isNotEmpty()) BuiltinStorageLocation.validatePath(path)
            checkAuthorization(locationId)

            val root =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed("Directory not found: $path")
            if (!root.isDirectory) {
                throw McpToolException.InvalidParams("Not a directory: $path")
            }

            var visited = 0
            var truncated = false

            // Explicit stack rather than recursion: a deep tree must not overflow. Enumerate
            // with DocumentFile.listFiles() directly — listFiles(locationId, …) caps at 200
            // entries and would silently under-count a large media directory.
            fun walk(dir: DocumentFile, relativePath: String, depth: Int): DiskUsageNode {
                ensureActive()
                visited++
                if (visited > FileOperationProvider.MAX_USAGE_NODES) {
                    truncated = true
                    return DiskUsageNode(relativePath, 0L, 0, emptyList())
                }
                var totalBytes = 0L
                var fileCount = 0
                val children = mutableListOf<DiskUsageNode>()
                for (child in dir.listFiles()) {
                    if (child.isDirectory) {
                        val childPath =
                            if (relativePath.isEmpty()) child.name.orEmpty()
                            else "$relativePath/${child.name.orEmpty()}"
                        val node = walk(child, childPath, depth + 1)
                        totalBytes += node.totalBytes
                        fileCount += node.fileCount
                        if (depth < maxDepth) children += node
                    } else {
                        totalBytes += child.length()
                        fileCount++
                    }
                }
                return DiskUsageNode(relativePath, totalBytes, fileCount, children)
            }

            DiskUsageResult(walk(root, path, 0), truncated, complete = true)
        }
```

**Constraint**: `walk` is written as a local function for readability but MUST be converted to
an explicit stack if a reviewer finds the recursion depth unbounded; `MAX_USAGE_NODES` bounds
the node count, not the depth. A path deeper than the JVM stack is not reachable through SAF on
Android, where the path length limit binds first.

**Constraint**: `DocumentFile.listFiles()` costs one binder round trip per directory, and
materialises every child of a directory into an array. The node budget is the only time bound;
the tool description MUST say a large archive takes minutes on the first call.

**Definition of Done**:
- [ ] Cancellation of the calling scope stops the walk
- [ ] Totals include files below `maxDepth` even though those nodes are not returned

### Task 2.3 — MediaStore aggregation

**Actions**:

1. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperationsImpl.kt` (modify) —
   implement `diskUsage` by querying each collection of the built-in location and aggregating
   client-side over the cursor, in the shape `processCursorRow` (lines 106–158) already uses for
   directory synthesis. Accumulate `size` into a `MutableMap<String, DiskUsageNode>` keyed by
   relative path, then fold child totals into their parents.

**Constraint**: `ContentResolver.query()` does not honour SQL `GROUP BY`; the `sortOrder`
injection trick is rejected from Android 11 onward and this project's `minSdk` is 33.
Aggregation is client-side, and a built-in location has multiple collections
(`for (collection in builtin.collections)`, line 61), so this is one query per collection.

**Constraint**: when `buildListSelection` (lines 160–168) filters on `OWNER_PACKAGE_NAME`
because the app lacks non-owned read access, totals cover only owned entries and
`DiskUsageResult.complete` MUST be false.

**Definition of Done**:
- [ ] Every collection of the location is queried
- [ ] `complete` is false exactly when the owner filter is applied

### Task 2.4 — Register `disk_usage`

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — add a `DiskUsageHandler`
   following the same shape as `MoveFileHandler`, with `TOOL_NAME = "disk_usage"`, parameters
   `location_id` (required string), `path` (optional string, default `""`) and `max_depth`
   (optional int, default 2, coerced into `0..MAX_USAGE_DEPTH` where `MAX_USAGE_DEPTH = 10`),
   and register it in `registerFileTools` behind its permission check.

   The rendered tree embeds directory names that originate from the device, so the response MUST
   use `McpToolUtils.untrustedTextResult()`. The rendering MUST state when `truncated` or
   `!complete`, so a caller never reads a partial total as the whole figure.

**Definition of Done**:
- [ ] The first line of the response is the untrusted-content warning
- [ ] A truncated or incomplete result says so in the text

### Task 2.5 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderDiskUsageTest.kt` (create)

**Setup**: `mockkStatic(DocumentFile::class)` as in `FileOperationProviderTest.kt:73`, with a
tree builder that produces a directory of N children so the 200-entry and node-budget boundaries
are reachable.

| Test | Verifies |
|------|----------|
| `diskUsage sums a flat directory` | Total and count match |
| `diskUsage sums nested directories into the root total` | Deep files counted at the root |
| `diskUsage breaks down only to maxDepth` | Deeper nodes absent, root total still complete |
| `diskUsage enumerates past 200 entries` | A 250-file directory is fully counted |
| `diskUsage on an empty directory returns zero` | No null or divide issues |
| `diskUsage reports truncation past the node budget` | `truncated = true` |
| `diskUsage clamps maxDepth above the maximum` | 99 behaves as 10 |
| `diskUsage honours cancellation` | Cancelled scope stops the walk |
| `diskUsage rejects a traversal path` | `../` refused |
| `diskUsage refuses a file path` | Explicit error |
| `diskUsage requires an authorized location` | `PermissionDenied` |

**File**: `app/src/test/kotlin/.../services/storage/MediaStoreDiskUsageTest.kt` (create)

| Test | Verifies |
|------|----------|
| `diskUsage aggregates a cursor client-side` | Totals grouped by relative path |
| `diskUsage queries every collection of the location` | Multi-collection built-in covered |
| `diskUsage reports incomplete for an owner-filtered location` | `complete = false` |

**File**: `app/src/test/kotlin/.../integration/FileToolsIntegrationTest.kt` (modify)

| Test | Verifies |
|------|----------|
| `disk_usage returns a tree and carries the untrusted-content warning` | First line is the warning |
| `disk_usage reports truncation in the response text` | Caller cannot mistake a partial total |

---

## User Story 3 — Non-destructive quarantine

**Why**: Reclaiming space on an archive that has no second copy must be reversible. Files are
staged into a quarantine directory in the same location — a metadata-only move, instant and
needing no free space — recorded in a manifest that maps each file back to its origin, and
removed only in a later, explicit step.

**Acceptance criteria**:
- [ ] Quarantining a batch moves files under `.quarantine/<batch_id>/` in the same location
- [ ] A manifest records the original path, quarantined name, size and time for every file
- [ ] Two batches created in the same second do not collide
- [ ] `quarantine_files` and `restore_quarantine_batch` require write AND delete;
      `purge_quarantine_batch` requires delete; `list_quarantine_batches` requires read
- [ ] Any batch can be restored, including batches above 200 files
- [ ] Listing a location that has never been quarantined returns an empty list, not an error
- [ ] Concurrent quarantine, restore and purge on one location cannot lose an entry
- [ ] Manifest-derived paths are validated before use
- [ ] A restore whose target path is occupied reports that entry and restores the rest

### Task 3.1 — Model

**Actions**:

1. `app/src/main/kotlin/.../data/model/QuarantineBatch.kt` (create):

```kotlin
import kotlinx.serialization.Serializable

/**
 * One quarantine operation: a set of files staged under a single batch directory.
 *
 * Serialized to `.quarantine/<batchId>/manifest.json` inside the storage location itself, not
 * to DataStore: a restore must remain possible after the app is uninstalled and reinstalled,
 * otherwise the staged archive is stranded under names that mean nothing.
 *
 * @property schemaVersion Manifest format version, so a future change is detectable.
 * @property batchId Directory name under `.quarantine/`.
 * @property createdAtEpochMs When the batch was created.
 * @property reason Caller-supplied explanation, echoed on listing and restore.
 * @property entries The files staged in this batch.
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

        /** Upper bound on a manifest read, well above MAX_BATCH_ENTRIES worth of JSON. */
        const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
    }
}

/**
 * One quarantined file.
 *
 * @property originalPath Path relative to the location root, before quarantine.
 * @property quarantinedName File name inside the batch directory; disambiguated when two
 *   sources share a name.
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
```

**Definition of Done**:
- [ ] Every field the US3 acceptance criteria mention exists on the model

### Task 3.2 — Provider

**Actions**:

1. `app/src/main/kotlin/.../services/storage/QuarantineProvider.kt` (create):

```kotlin
/**
 * Reversible staging for files a caller intends to remove.
 *
 * Every operation is scoped to one storage location and serialised against other operations on
 * that same location.
 */
interface QuarantineProvider {
    /** Stages [paths] under a new batch, returning the batch as written. */
    suspend fun quarantine(locationId: String, paths: List<String>, reason: String): QuarantineBatch

    /** Reads every batch manifest in [locationId]; empty when nothing was ever quarantined. */
    suspend fun listBatches(locationId: String): List<QuarantineBatch>

    /** Restores [batchId] to the original paths. */
    suspend fun restore(locationId: String, batchId: String): RestoreOutcome

    /** Permanently deletes [batchId] and its manifest, returning the file count removed. */
    suspend fun purge(locationId: String, batchId: String): Int
}

/**
 * @property restored Entries returned to their original paths.
 * @property skipped Entries left in quarantine, each with the reason it could not be restored.
 */
data class RestoreOutcome(
    val restored: List<QuarantineEntry>,
    val skipped: List<SkippedEntry>,
)

/** @property reason Why [entry] stayed in quarantine. */
data class SkippedEntry(
    val entry: QuarantineEntry,
    val reason: String,
)
```

2. `app/src/main/kotlin/.../services/storage/QuarantineProviderImpl.kt` (create) — constructor
   takes `FileOperationProvider` and `StorageLocationProvider`; the latter because the
   permission checks belong to this provider, not to the file layer, for `listBatches` (read
   only) versus the mutating operations.

```kotlin
@Singleton
class QuarantineProviderImpl
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
        private val storageLocationProvider: StorageLocationProvider,
    ) : QuarantineProvider {
        // One lock per location. quarantine, restore and purge all read-modify-write the same
        // manifest and enumerate the same directory; concurrent MCP requests are explicitly in
        // scope, and a lost update here means lost files.
        private val locationLocks = ConcurrentHashMap<String, Mutex>()

        private suspend fun <T> withLocationLock(locationId: String, block: suspend () -> T): T =
            locationLocks.computeIfAbsent(locationId) { Mutex() }.withLock { block() }

        override suspend fun quarantine(
            locationId: String,
            paths: List<String>,
            reason: String,
        ): QuarantineBatch =
            withLocationLock(locationId) {
                require(paths.isNotEmpty()) { "paths must not be empty" }
                paths.forEach { BuiltinStorageLocation.validatePath(it) }

                val batchId = claimBatchDirectory(locationId)
                val batchPath = "${QuarantineBatch.QUARANTINE_DIR}/$batchId"
                val usedNames = mutableSetOf<String>()
                val entries = mutableListOf<QuarantineEntry>()

                for (path in paths) {
                    val name = disambiguate(path.substringAfterLast('/'), usedNames)
                    val moved =
                        runCatching {
                            fileOperationProvider.moveFile(
                                locationId = locationId,
                                sourcePath = path,
                                destinationPath = "$batchPath/$name",
                                overwrite = false,
                                // A provider without move support would copy, needing free
                                // space equal to the archive — the opposite of the point.
                                allowCopyFallback = false,
                            )
                        }.getOrNull() ?: continue
                    // Record where the file actually landed, which may differ from the request.
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

                // Written after the moves and listing only what moved: a manifest must never
                // claim a file it does not hold.
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
```

   **Batch id** — `yyyyMMdd-HHmmss` plus a random suffix, with the directory claimed before any
   move. A timestamp alone collides for two calls in the same second, and the second call would
   overwrite the first manifest and orphan its files:

```kotlin
        @OptIn(ExperimentalStdlibApi::class)
        private suspend fun claimBatchDirectory(locationId: String): String {
            repeat(BATCH_ID_ATTEMPTS) {
                val candidate =
                    "${LocalDateTime.now().format(BATCH_ID_FORMAT)}-${Random.nextBytes(3).toHexString()}"
                val path = "${QuarantineBatch.QUARANTINE_DIR}/$candidate"
                if (fileOperationProvider.createDirectory(locationId, path)) return candidate
            }
            throw McpToolException.ActionFailed("Could not claim a unique quarantine batch id")
        }

        private fun disambiguate(name: String, used: MutableSet<String>): String {
            if (used.add(name)) return name
            var index = 1
            while (true) {
                val candidate = "${name.substringBeforeLast('.')}-$index" +
                    name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                if (used.add(candidate)) return candidate
                index++
            }
        }

        private companion object {
            val BATCH_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            const val BATCH_ID_ATTEMPTS = 8
        }
```

   **Enumeration** — every listing pages to exhaustion; `listFiles` caps at 200, so one call
   would enumerate part of a batch and `purge` would then report success having left files
   behind:

```kotlin
        private suspend fun listAll(locationId: String, path: String): List<FileInfo> {
            val all = mutableListOf<FileInfo>()
            var offset = 0
            while (true) {
                val page =
                    fileOperationProvider.listFiles(
                        locationId, path, offset, FileOperationProvider.MAX_LIST_ENTRIES,
                    )
                all += page.files
                if (!page.hasMore || page.files.isEmpty()) return all
                offset += page.files.size
            }
        }
```

   **`listBatches`** — a location that was never quarantined has no `.quarantine` directory, and
   `listFiles` throws for a missing path (`FileOperationProviderImpl.kt:54`). That is the most
   common first call to the tool and MUST return an empty list:

```kotlin
        override suspend fun listBatches(locationId: String): List<QuarantineBatch> =
            withLocationLock(locationId) {
                if (fileOperationProvider.statPath(locationId, QuarantineBatch.QUARANTINE_DIR) == null) {
                    return@withLocationLock emptyList()
                }
                listAll(locationId, QuarantineBatch.QUARANTINE_DIR)
                    .filter { it.isDirectory }
                    // A manifest that will not parse must not fail the whole listing: the other
                    // batches are still restorable and the caller needs to see them.
                    .mapNotNull { runCatching { readManifest(locationId, it.name) }.getOrNull() }
            }
```

   **Manifest I/O** — read as bytes, not lines: `readFile` truncates at 200 lines and yields
   invalid JSON for a large batch. Written as a single line so size never interacts with that
   cap:

```kotlin
        private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

        private suspend fun readManifest(locationId: String, batchId: String): QuarantineBatch {
            val bytes =
                fileOperationProvider.readFileBytes(
                    locationId,
                    manifestPath(batchId),
                    QuarantineBatch.MAX_MANIFEST_BYTES,
                )
            return json.decodeFromString(bytes.bytes.decodeToString())
        }

        private suspend fun writeManifest(locationId: String, batch: QuarantineBatch) {
            fileOperationProvider.writeFile(
                locationId, manifestPath(batch.batchId), json.encodeToString(batch),
            )
        }

        private fun manifestPath(batchId: String) =
            "${QuarantineBatch.QUARANTINE_DIR}/$batchId/${QuarantineBatch.MANIFEST_FILE_NAME}"
```

   **`restore`** — each entry is moved back independently, failures are collected rather than
   aborting, and the manifest is rewritten with what remains so a partial restore is resumable:

```kotlin
        override suspend fun restore(locationId: String, batchId: String): RestoreOutcome =
            withLocationLock(locationId) {
                BuiltinStorageLocation.validatePath(batchId)
                val batch = readManifest(locationId, batchId)
                val restored = mutableListOf<QuarantineEntry>()
                val skipped = mutableListOf<SkippedEntry>()

                for (entry in batch.entries) {
                    // The manifest lives in user-writable storage, so its contents are
                    // untrusted input: a crafted originalPath would otherwise write outside
                    // the location root. moveFile validates too; failing here keeps the batch
                    // intact and tells the caller which entry is wrong.
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
                                sourcePath =
                                    "${QuarantineBatch.QUARANTINE_DIR}/$batchId/${entry.quarantinedName}",
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
                    fileOperationProvider.deleteDirectory(
                        locationId, "${QuarantineBatch.QUARANTINE_DIR}/$batchId",
                    )
                } else {
                    writeManifest(locationId, batch.copy(entries = skipped.map { it.entry }))
                }
                RestoreOutcome(restored, skipped)
            }

        override suspend fun purge(locationId: String, batchId: String): Int =
            withLocationLock(locationId) {
                BuiltinStorageLocation.validatePath(batchId)
                fileOperationProvider.deleteDirectory(
                    locationId, "${QuarantineBatch.QUARANTINE_DIR}/$batchId",
                )
            }
```

   **Permissions** — `quarantine` and `restore` move files, so `moveFile`'s own
   authorization/write/delete checks apply and are the single source of truth. `purge` relies on
   `deleteDirectory`'s delete check. `listBatches` relies on `listFiles`/`statPath`
   authorization. No permission logic is duplicated in this class.

3. `app/src/main/kotlin/.../di/AppModule.kt` (modify) — add to `ServiceModule` (the abstract
   class at line 178 that holds the `@Binds`; `AppModule` itself is an `object` and cannot hold
   them), beside `bindFileOperationProvider` at line 216:

```kotlin
    @Binds
    @Singleton
    abstract fun bindQuarantineProvider(impl: QuarantineProviderImpl): QuarantineProvider
```

**Constraint on scope** — the per-location lock serialises this provider's own operations only.
`move_file`, `delete_file` and `write_file` reach `FileOperationProvider` directly and take no
lock, so a client calling them on a staged file mid-batch can still leave the manifest
disagreeing with disk. That is accepted for this plan: `restore` already tolerates it by
`statPath`-checking each entry and reporting skips rather than failing, and `listBatches`
tolerates an unparseable manifest. Moving the lock into `FileOperationProvider` keyed by
location is the correct long-term fix and is out of scope here.

**Definition of Done**:
- [ ] No permission check is duplicated between this provider and `FileOperationProvider`
- [ ] Every path that reaches a file operation has been validated first
- [ ] `listBatches` never throws for a location that was never quarantined

### Task 3.3 — Argument helper

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/McpToolUtils.kt` (modify) — add a string-array helper
   beside `requireString` (line 299), taking `params` first like every other helper in this
   `internal object`:

```kotlin
    /**
     * Reads a required array-of-strings argument.
     *
     * @throws McpToolException.InvalidParams when absent, not an array, empty, longer than
     *   [maxSize], or containing a non-string element.
     */
    fun requireStringArray(
        params: JsonObject?,
        name: String,
        maxSize: Int,
    ): List<String> {
        val array =
            (params?.get(name) as? JsonArray)
                ?: throw McpToolException.InvalidParams("Missing or invalid array parameter: $name")
        if (array.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter '$name' must not be empty")
        }
        if (array.size > maxSize) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' accepts at most $maxSize entries, got ${array.size}",
            )
        }
        return array.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw McpToolException.InvalidParams("Parameter '$name' must contain only strings")
        }
    }
```

2. `app/src/main/kotlin/.../mcp/tools/UtilityTools.kt` (modify) — replace the hand-rolled
   `node_ids` array parse at lines 722-738 with a call to the new helper, so there is one
   implementation rather than two.

**Definition of Done**:
- [ ] `UtilityTools` no longer parses a string array itself
- [ ] Existing `node_ids` tests still pass unchanged

### Task 3.4 — Register the quarantine tools

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/QuarantineTools.kt` (create) — four handlers following
   `FileTools.kt`'s handler shape, plus the registration function mirroring `registerFileTools`:

```kotlin
fun registerQuarantineTools(
    registrar: LoggedToolRegistration,
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
```

| Tool | Params | Result helper |
|---|---|---|
| `quarantine_files` | `location_id`, `paths` (≤ `MAX_BATCH_ENTRIES`), `reason` | untrusted |
| `list_quarantine_batches` | `location_id` | untrusted |
| `restore_quarantine_batch` | `location_id`, `batch_id` | untrusted |
| `purge_quarantine_batch` | `location_id`, `batch_id` | plain |

**Constraint**: the first three echo names and paths that originate from the device and from a
manifest writable by other applications. Per PROJECT.md's anti-prompt-injection rule and
CLAUDE.md's "if uncertain, use the untrusted variant", they use
`McpToolUtils.untrustedTextResult()`. `purge_quarantine_batch` returns only a count.

**Constraint**: `quarantine_files` MUST report entries that did not move — `paths.size` versus
`batch.entries.size` — so a caller never reads a partial batch as complete.

2. `app/src/main/kotlin/.../services/mcp/McpServerService.kt` (modify) — add the injected field
   beside the other providers and the registration call beside `registerFileTools` at line 458:

```kotlin
    @Inject
    lateinit var quarantineProvider: QuarantineProvider
```

```kotlin
        registerQuarantineTools(registrar, quarantineProvider, toolNamePrefix, perms)
```

**Constraint**: tool categories register here, in `registerAllTools()`, not in `McpServer.kt`.

3. `app/src/main/kotlin/.../ui/screens/settings/McpToolsSettingsScreen.kt` (modify) — add a
   `ToolCategory` for the four quarantine tools and a `ToolEntry` for `move_file` and
   `disk_usage` in the existing "File Operations" group. Without these the tools cannot be
   toggled in the UI. `ToolEntry` is `(toolName, displayName, params)` — there is no description
   field.

**Definition of Done**:
- [ ] All six new tools are toggleable in the settings UI
- [ ] Disabling a tool removes it from `list_tools`

### Task 3.5 — Test infrastructure

**Actions**:

1. `app/src/test/kotlin/.../services/storage/FakeFileOperationProvider.kt` (create) — shared
   test infrastructure, implementing EVERY member of `FileOperationProvider` over an in-memory
   map. `FileInfo.path` MUST be `"$locationId/$relativePath"`, matching
   `FileOperationProviderImpl.kt:80` and `MediaStoreFileOperationsImpl.kt:130`; a
   location-relative path here would make every test green while the real code double-prefixes.
   `listFiles` MUST honour `MAX_LIST_ENTRIES` so pagination bugs surface in tests.
   `moveFile` MUST honour `overwrite`, or the `restore` skip path is untestable.
   `supportsMove` and `failMoveFor` make the failure paths reachable.

**Constraint**: this class is given in outline here rather than in full because it is a
mechanical adapter over a `MutableMap<String, ByteArray>`; the binding requirements above are
the parts that are not derivable. Every member of the interface — `listFiles`, `readFile`,
`readFileBytes`, `writeFile`, `appendFile`, `replaceInFile`, `downloadFromUrl`, `deleteFile`,
`createFileUri`, `moveFile`, `createDirectory`, `statPath`, `deleteDirectory`, `diskUsage` —
MUST be implemented; an unimplemented member does not compile.

2. `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` (modify) — add
   `quarantineProvider: QuarantineProvider` to the `MockDependencies` data class (line 593),
   construct it in `createMockDependencies()`, and call `registerQuarantineTools(...)` beside
   the existing `registerFileTools(...)` call near line 335.

**Definition of Done**:
- [ ] `FakeFileOperationProvider` compiles against the full interface
- [ ] Existing integration tests still pass with the extended `MockDependencies`

### Task 3.6 — Tests

**File**: `app/src/test/kotlin/.../services/storage/QuarantineProviderTest.kt` (create)

**Setup**: `FakeFileOperationProvider`; mocked `StorageLocationProvider` with independent
`isLocationAuthorized`, `isWriteAllowed`, `isDeleteAllowed`.

| Test | Verifies |
|------|----------|
| `quarantine moves files and writes a manifest` | Files under the batch dir, manifest lists them |
| `quarantine succeeds with write and delete allowed` | The permitted path works |
| `quarantine fails when delete is not allowed` | The recorded permission decision holds |
| `quarantine fails when write is not allowed` | Rejected |
| `quarantine records only files that moved` | A failing move is absent from the manifest |
| `quarantine records the name the file actually got` | Provider-assigned name reaches the manifest |
| `quarantine disambiguates colliding file names` | Two same-named sources both survive and both restore |
| `quarantine refuses a provider without move support` | No copy fallback, sources intact |
| `quarantine rejects a traversal path` | `../` in `paths` refused |
| `quarantine rejects an empty path list` | Explicit error |
| `two quarantine calls in the same second get distinct batches` | No manifest overwrite |
| `concurrent quarantine calls both complete` | Two parallel batches, no lost entry |
| `listBatches returns empty for a location never quarantined` | No exception on the first call |
| `listBatches reads every manifest` | Two batches both returned |
| `listBatches pages past 200 batch directories` | 250 batches all listed |
| `listBatches skips an unreadable manifest` | Corrupt JSON does not fail the listing |
| `listBatches reads a manifest above 200 lines` | Large batch parses — readFileBytes, not readFile |
| `restore returns files to their original paths` | Round trip is byte-identical |
| `restore requires write and delete` | Both denial cases rejected |
| `restore skips an entry whose original path is occupied` | Reported in `skipped`, others restored |
| `restore skips an entry whose manifest path escapes the root` | Traversal refused, batch survives |
| `restore rewrites the manifest with the skipped entries` | Partial restore is resumable |
| `restore removes a fully restored batch` | Directory and manifest gone |
| `restore of a 250-file batch restores all of them` | Pagination |
| `purge requires delete permission` | Rejected |
| `purge removes the batch and reports the count` | Directory gone, count matches |
| `purge of a 250-file batch removes all of them` | Pagination |

**File**: `app/src/test/kotlin/.../integration/QuarantineToolsIntegrationTest.kt` (create)

| Test | Verifies |
|------|----------|
| `quarantine_files then restore_quarantine_batch round trips` | Full dispatch over HTTP |
| `quarantine_files reports entries that did not move` | Partial batch is visible to the caller |
| `list_quarantine_batches carries the untrusted-content warning` | First line is the warning |
| `restore_quarantine_batch carries the untrusted-content warning` | First line is the warning |
| `quarantine_files rejects an empty paths array` | `isError = true` |
| `quarantine_files rejects an array above the entry cap` | `isError = true` |
| `purge_quarantine_batch errors when delete is not allowed` | `isError = true` |

**Definition of Done**:
- [ ] Every US3 acceptance criterion has at least one covering test

---

## User Story 4 — Keep the tool inventory truthful

**Why**: Three test files assert a tool count of 57, and four documents publish it. Six new
tools break the first and stale the second. PROJECT.md is the declared source of truth, so a
stale inventory misleads every later plan that reads it.

**Acceptance criteria**:
- [ ] The suite passes with 63 tools
- [ ] No document still claims 57 tools or 14 categories

### Task 4.1 — Repair the suite

**Actions**:

1. `app/src/test/kotlin/.../integration/McpProtocolIntegrationTest.kt` (modify) —
   `EXPECTED_TOOL_COUNT` 57 → 63 (line 138) and the test name at line 38.
2. `app/src/test/kotlin/.../integration/AuthIntegrationTest.kt` (modify) —
   `EXPECTED_TOOL_COUNT` 57 → 63 (line 109).
3. `app/src/test/kotlin/.../integration/ToolPermissionsIntegrationTest.kt` (modify) — add the
   six names to `ALL_TOOL_NAMES` (line 266), so `all tools disabled returns empty tool list`
   still holds.

**Constraint**: `e2e-tests/.../E2ECalculatorTest.kt:90` asserts `>= 27` and needs no change.

**Definition of Done**:
- [ ] `./gradlew :app:test` green

### Task 4.2 — Update the documentation

**Actions**:

1. `docs/PROJECT.md` (modify) — tool count and category count at line 202 (57 → 63, 14 → 15);
   the "all 57 tools" reference at line 812; add `move_file` and `disk_usage` to the §8 File
   Tools table; add a Quarantine tools section with the four tools; under "Storage Location
   Permissions", document that `move_file`, `quarantine_files` and `restore_quarantine_batch`
   require write AND delete, and why.
2. `docs/MCP_TOOLS.md` (modify) — the count at line 37, the File Operations row at line 48, and
   full entries for all six tools.
3. `README.md` (modify) — "57 MCP Tools across 14 Categories" at line 62.
4. `docs/ARCHITECTURE.md` (modify) — add `QuarantineProvider` to the "Storage & App Services"
   subgraph of the component diagram.

**Constraint**: the Mermaid diagram MUST be validated with `mmdc` before committing, per
CLAUDE.md. If `mmdc` is not on PATH, load nvm first (`. "$NVM_DIR/nvm.sh"`).

**Definition of Done**:
- [ ] `grep -rn "57 tools\|57 MCP\|14 Categories\|14 categories" docs README.md` returns nothing

---

## Quality gates (run once, after every user story is implemented)

- [ ] `make lint` clean
- [ ] `./gradlew :app:test` green
- [ ] `./gradlew build` succeeds without errors or warnings
- [ ] `code-reviewer` subagent in plan compliance mode reports no findings
