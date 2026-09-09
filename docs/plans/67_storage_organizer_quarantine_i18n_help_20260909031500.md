<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Storage Organizer: move, quarantine, disk usage, Spanish localization, inline setting help

Supersedes `66_storage_organizer_quarantine_i18n_help_20260909024801.md`, whose review findings
section lists what was wrong with it and why.

## Context

Driving use case: auditing and organizing a large WhatsApp media archive on a secondary device
where the on-disk copy is the only copy. WhatsApp media lives under
`Android/media/com.whatsapp/`, reachable through SAF (unlike `Android/data`) and not indexed by
MediaStore, so the move and quarantine tools target SAF locations.

Three decisions constrain everything below.

**Moving a file out of its path counts as deletion.** `move_file`, `quarantine_files` and
`restore_quarantine_batch` require BOTH the write and delete permissions of the storage
location. The previous plan gated them on write alone, which widened the documented meaning of
the write permission without saying so. What makes quarantine safe is that it is reversible,
not the permission it runs under.

**Quarantine never falls back to copy.** On a provider without `FLAG_SUPPORTS_MOVE`, a
copy-then-delete needs free space equal to the archive — the opposite of what quarantine is
for on a space-constrained device. `quarantine_files` refuses rather than silently degrading.
`move_file` exposes the fallback behind an explicit opt-in.

**Nothing enumerates a directory or reads a manifest through a single capped call.**
`MAX_LIST_ENTRIES` and `MAX_READ_LINES` are both 200 (`FileOperationProvider.kt:238,241`).
Every enumeration in this plan pages to exhaustion, and manifests are read as bytes.

### Ordering rationale

Localization comes LAST (US6). US1 prepares the resource file; US2–US5 each add English
strings — including the six new tool-catalog labels and all help text — and US6 translates the
completed set in one pass and only then promotes the translation lint checks to errors.
Translating first and promoting the lint check early, as plan 66 did, would fail the build on
every subsequent string.

---

## User Story 1 — Make the string resources translatable and verifiable

**Why**: A second locale cannot be added while user-visible text is compiled into Kotlin, and
the check plan 66 relied on (`HardcodedText`) inspects layout XML — this project has none, so
it reports clean regardless. This story creates a check that actually fails.

**Acceptance criteria**:
- [ ] Keys that are identifiers rather than prose are marked `translatable="false"`
- [ ] A Gradle task fails when a user-visible literal appears in a composable
- [ ] No user-visible literal remains under `app/src/main/kotlin/.../ui/`

### Task 1.1 — Mark non-translatable keys

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — add `translatable="false"` to every entry
   whose value is an identifier or a proper noun rather than prose. At minimum:
   `notification_channel_mcp_server_id`, `about_author_name`, `about_author_email`, and any
   entry whose value is a URL, a package name or a channel id.

```xml
    <string name="notification_channel_mcp_server_id" translatable="false">mcp_server_channel</string>
```

**Constraint**: translating a notification-channel id creates a second channel per locale, and
the app then posts to a channel the user never configured.

**Definition of Done**:
- [ ] Every remaining translatable entry is prose shown to a user

### Task 1.2 — Add a literal-detection Gradle task

**Actions**:

1. `app/build.gradle.kts` (modify) — register a verification task. Android Lint has no
   equivalent detector for Compose, so the check is explicit:

```kotlin
/**
 * Fails when a user-visible string literal appears in a composable.
 *
 * Android Lint's HardcodedText detector only inspects layout XML, of which this project has
 * none — every screen is Compose — so it cannot serve as the localization gate. This task
 * scans the UI source tree for literals passed to the composables that render text.
 */
abstract class VerifyNoHardcodedUiTextTask : DefaultTask() {
    @get:InputDirectory
    abstract val uiSourceDir: DirectoryProperty

    @TaskAction
    fun verify() {
        // Text("…"), label = "…", contentDescription = "…", placeholder = "…" with a literal
        // whose first character is a letter: identifiers, format keys and symbols are ignored.
        val literal = Regex("""(?:Text\(\s*|(?:label|contentDescription|placeholder|title|supportingText)\s*=\s*)"[A-Za-z][^"]{2,}"""")
        val offenders =
            uiSourceDir.get().asFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().withIndex()
                        .filter { (_, line) -> literal.containsMatchIn(line) }
                        .map { (index, line) -> "${file.relativeTo(uiSourceDir.get().asFile)}:${index + 1}: ${line.trim()}" }
                }
                .toList()
        if (offenders.isNotEmpty()) {
            error(
                "User-visible string literals must live in strings.xml so they can be " +
                    "translated. Offending lines:\n" + offenders.joinToString("\n"),
            )
        }
    }
}

val verifyNoHardcodedUiText =
    tasks.register<VerifyNoHardcodedUiTextTask>("verifyNoHardcodedUiText") {
        uiSourceDir.set(layout.projectDirectory.dir("src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui"))
    }

tasks.named("check") { dependsOn(verifyNoHardcodedUiText) }
```

**Definition of Done**:
- [ ] The task fails on the tree as it stands today
- [ ] It is wired into `check`, so `make lint` runs it

### Task 1.3 — Extract literals from the settings screens

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — add one `<string>` per extracted literal,
   named `<screen>_<element>_<role>` following the convention already in the file.
2. `app/src/main/kotlin/.../ui/screens/settings/*.kt` (modify) — replace each literal with
   `stringResource(R.string.<key>)`.

**Constraint**: `@Suppress` rule names, log tags, MCP tool identifiers, DataStore keys and MIME
types MUST NOT be extracted. Extracting an MCP tool name would rename the tool per locale and
break every client.

### Task 1.4 — Extract literals from the remaining screens and components

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — add the remaining keys.
2. `app/src/main/kotlin/.../ui/screens/{MainScreen,ServerScreen,ServerTabScreen,LogsScreen,AboutScreen,ApprovalScreen}.kt` (modify) — replace literals.
3. `app/src/main/kotlin/.../ui/components/*.kt` (modify) — replace literals.

**Definition of Done**:
- [ ] `verifyNoHardcodedUiText` passes

---

## User Story 2 — `move_file` MCP tool

**Why**: The tool surface can create and delete a file but cannot relocate one. Quarantine
(US4) is a move.

**Acceptance criteria**:
- [ ] `move_file` relocates a file within one SAF location, preserving content
- [ ] Missing destination directories are created
- [ ] Rejected unless the location allows BOTH write and delete
- [ ] The copy fallback runs only when the caller opts in, and reports which mechanism ran
- [ ] Built-in MediaStore locations return a clear unsupported error
- [ ] Every path argument is validated before use

### Task 2.1 — Provider API

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add the
   result type and the operation:

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

    /** Streamed copy followed by deletion of the source: needs free space equal to the file. */
    COPY_DELETE,
}

/**
 * Result of a file move.
 *
 * @property destinationPath The relative path the file now occupies.
 * @property sizeBytes Size of the moved file.
 * @property mechanism How the move was performed.
 */
data class FileMoveResult(
    val destinationPath: String,
    val sizeBytes: Long,
    val mechanism: MoveMechanism,
)
```

```kotlin
    /**
     * Moves a file within a single storage location.
     *
     * Requires both write and delete permission on the location: the file leaves the path it
     * occupied, which is a deletion from that path's point of view.
     *
     * @param locationId The authorized storage location identifier.
     * @param sourcePath Relative path of the existing file.
     * @param destinationPath Relative destination path; parent directories are created.
     * @param overwrite When false, an existing destination fails the operation.
     * @param allowCopyFallback When false and the provider supports neither move nor rename,
     *   the operation fails instead of duplicating the file's bytes.
     * @return [FileMoveResult] describing the destination and the mechanism used.
     */
    suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult

    /**
     * Deletes a directory and everything below it.
     *
     * [deleteFile] refuses directories by design; purging a quarantine batch needs this.
     * Traversal is bounded by [MAX_USAGE_NODES] to keep a pathological tree from blocking the
     * request indefinitely.
     *
     * @return the number of files deleted.
     */
    suspend fun deleteDirectory(locationId: String, path: String): Int
```

2. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperations.kt` (modify) — add
   `moveFile` and `deleteDirectory` to the interface so `FileOperationProviderImpl` can
   dispatch built-in ids to it.
3. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperationsImpl.kt` (modify) —
   implement both by throwing `McpToolException.InvalidParams` with the message
   `"Not supported for built-in locations. Add the folder as a storage location instead."`

**Constraint**: the interface and the implementation must be modified together. Adding a member
to one without the other does not compile.

### Task 2.2 — Provider implementation

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) —
   implement `moveFile`, following the existing dispatch and validation shape:

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
            requireWriteAllowed(locationId)
            requireDeleteAllowed(locationId)

            val source =
                resolveExistingFile(locationId, sourcePath)
                    .takeIf { !it.isDirectory }
                    ?: throw McpToolException.InvalidParams(
                        "Cannot move a directory. Only single files can be moved.",
                    )
            val sizeBytes = source.length()

            val destinationParentPath = destinationPath.substringBeforeLast('/', "")
            val destinationName = destinationPath.substringAfterLast('/')
            val destinationParent = resolveOrCreateDirectory(locationId, destinationParentPath)

            destinationParent.findFile(destinationName)?.let { existing ->
                if (!overwrite) {
                    throw McpToolException.InvalidParams(
                        "Destination already exists: $destinationPath",
                    )
                }
                existing.delete()
            }

            val sourceParent = source.parentFile
            val mechanism =
                when {
                    sourceParent != null &&
                        sourceParent.uri != destinationParent.uri &&
                        source.supportsFlag(DocumentsContract.Document.FLAG_SUPPORTS_MOVE) -> {
                        DocumentsContract.moveDocument(
                            context.contentResolver, source.uri, sourceParent.uri, destinationParent.uri,
                        ) ?: throw McpToolException.InternalError("Move rejected by the storage provider")
                        MoveMechanism.MOVE_DOCUMENT
                    }
                    sourceParent != null &&
                        sourceParent.uri == destinationParent.uri &&
                        source.supportsFlag(DocumentsContract.Document.FLAG_SUPPORTS_RENAME) -> {
                        DocumentsContract.renameDocument(
                            context.contentResolver, source.uri, destinationName,
                        ) ?: throw McpToolException.InternalError("Rename rejected by the storage provider")
                        MoveMechanism.RENAME_DOCUMENT
                    }
                    !allowCopyFallback ->
                        throw McpToolException.InvalidParams(
                            "The storage provider supports neither move nor rename for this file. " +
                                "Copying instead would need $sizeBytes bytes of free space; pass " +
                                "allow_copy_fallback to accept that cost.",
                        )
                    else -> {
                        copyThenDeleteSource(source, destinationParent, destinationName, sizeBytes)
                        MoveMechanism.COPY_DELETE
                    }
                }

            FileMoveResult(destinationPath, sizeBytes, mechanism)
        }
```

   `copyThenDeleteSource` streams the source into a freshly created destination document,
   verifies the written length equals `sizeBytes`, and only then deletes the source. On any
   failure it deletes the partial destination and leaves the source untouched, so an
   interruption never loses the only copy.

   `source.supportsFlag` is a private extension reading `DocumentsContract.Document.COLUMN_FLAGS`
   for the document, since `DocumentFile` does not expose the flags directly.

   The file size limit is NOT applied to `MOVE_DOCUMENT` or `RENAME_DOCUMENT`: no bytes are
   read, so the limit — which exists to bound how much a single request transfers — does not
   apply. It IS applied on the `COPY_DELETE` path.

2. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) —
   implement `deleteDirectory` as an iterative post-order traversal bounded by
   `MAX_USAGE_NODES`, deleting files before their parents and returning the file count.

3. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add
   alongside the existing caps:

```kotlin
        /** Upper bound on directories visited by a single recursive traversal. */
        const val MAX_USAGE_NODES = 20_000
```

**Constraint**: every operation in this file currently runs SAF I/O directly in a `suspend`
function without a dispatcher. New operations MUST wrap in `withContext(Dispatchers.IO)` per
ARCHITECTURE.md; the divergence from the surrounding code is deliberate and the correct
direction.

### Task 2.3 — Register the tool

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — add a `MoveFileHandler`
   following the shape of `DeleteFileHandler`, with `TOOL_NAME = "move_file"` and parameters
   `location_id` (required string), `source_path` (required string), `destination_path`
   (required string), `overwrite` (optional boolean, default false), `allow_copy_fallback`
   (optional boolean, default false). Register it inside `registerFileTools` behind
   `perms.isToolEnabled(MoveFileHandler.TOOL_NAME)`, matching the surrounding entries.

   The response is server-generated text and uses the plain result helper.

2. `app/src/main/kotlin/.../ui/screens/settings/McpToolsSettingsScreen.kt` (modify) — add a
   `ToolEntry` for `move_file` to the "File Operations" group, or the tool cannot be toggled
   in the UI. Its label and description are new user-visible strings and go in
   `values/strings.xml` per US1's constraint.

**Definition of Done**:
- [ ] Invalid parameters surface as `CallToolResult(isError = true)` via
      `McpToolException.InvalidParams` — this codebase does not emit JSON-RPC `-32602`
      (PROJECT.md line 189)

### Task 2.4 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderMoveTest.kt`

**Setup**: MockK `DocumentFile` trees mirroring the existing provider tests; mocked
`StorageLocationProvider` with configurable `isWriteAllowed`/`isDeleteAllowed`; mocked
`ContentResolver` so `DocumentsContract` flag queries are controllable per document.

| Test | Verifies |
|------|----------|
| `moveFile uses moveDocument when the provider supports move` | Mechanism is MOVE_DOCUMENT, no stream opened |
| `moveFile uses renameDocument within the same parent` | Mechanism is RENAME_DOCUMENT |
| `moveFile refuses the copy fallback by default` | Fails with the free-space message, source intact |
| `moveFile copies when the fallback is opted into` | Mechanism is COPY_DELETE, content preserved |
| `moveFile creates missing destination directories` | Parent chain created before the move |
| `moveFile succeeds with write and delete allowed` | The permitted path works end to end |
| `moveFile fails when write is not allowed` | Source untouched |
| `moveFile fails when delete is not allowed` | Source untouched — the decision this plan records |
| `moveFile fails on a builtin location` | MediaStore backend reports unsupported |
| `moveFile refuses an existing destination when overwrite is false` | Source untouched |
| `moveFile overwrites when overwrite is true` | Destination replaced |
| `moveFile keeps the source when the copy fallback fails mid-write` | Original still readable, partial destination removed |
| `moveFile rejects a directory as source` | Explicit error |
| `moveFile rejects a traversal path` | `../` in either argument is refused |
| `moveFile ignores the size limit on a metadata-only move` | A file above the limit still moves |
| `moveFile enforces the size limit on the copy fallback` | Rejected |

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderDeleteDirectoryTest.kt`

| Test | Verifies |
|------|----------|
| `deleteDirectory removes nested content and reports the count` | Post-order deletion, count correct |
| `deleteDirectory on a missing path fails cleanly` | No partial state |
| `deleteDirectory requires delete permission` | Rejected |

---

## User Story 3 — `disk_usage` MCP tool

**Why**: `list_files` is flat and capped at 200 entries, so summarising a media archive costs
one call per directory page. A single recursive call turns a several-hundred-call audit into
one.

**Acceptance criteria**:
- [ ] Returns per-directory aggregated size and file count to a caller-specified depth
- [ ] Traversal is bounded and reports when it stopped early
- [ ] Enumeration pages past the 200-entry cap
- [ ] Built-in MediaStore locations report whether their totals are complete

### Task 3.1 — Model and API

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
 * @property truncated Traversal stopped at the node budget; totals under-report.
 * @property complete False when the backend cannot see every file in the location — a
 *   MediaStore location the app does not fully own reports only the entries it may read, and
 *   a caller must not present those totals as the whole picture.
 */
data class DiskUsageResult(
    val root: DiskUsageNode,
    val truncated: Boolean,
    val complete: Boolean,
)
```

2. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add:

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

### Task 3.2 — SAF traversal

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) —
   implement `diskUsage` with an explicit stack rather than recursion, so a deep tree cannot
   overflow, on `Dispatchers.IO`, calling `ensureActive()` per directory so a client
   disconnect stops the walk. Enumerate each directory with `DocumentFile.listFiles()`
   directly rather than through `listFiles`, which is capped at 200 entries.

**Constraint**: `DocumentFile.listFiles()` costs one binder round trip per directory. The node
budget is the time bound; there is no additional timeout, and the tool description must state
that a large archive takes minutes on first call.

### Task 3.3 — MediaStore aggregation

**Actions**:

1. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperationsImpl.kt` (modify) —
   implement `diskUsage` by querying each collection of the built-in location and aggregating
   **client-side** over the cursor, in the shape `processCursorRow` already uses for directory
   synthesis.

**Constraint**: `ContentResolver.query()` does not honour SQL `GROUP BY`; the `sortOrder`
injection trick is rejected from Android 11 onward and this project's `minSdk` is 33. A
built-in location has multiple collections, so this is one query per collection, not one query
total.

**Constraint**: when `buildListSelection` filters on `OWNER_PACKAGE_NAME` because the app lacks
non-owned read access, the totals cover only owned entries. `DiskUsageResult.complete` MUST be
false in that case.

### Task 3.4 — Register the tool

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — add a `DiskUsageHandler` with
   `TOOL_NAME = "disk_usage"` and parameters `location_id` (required string), `path` (optional
   string, default `""`), `max_depth` (optional int, default 2, clamped to 10). Register it in
   `registerFileTools` behind its permission check.

   The response embeds device-derived directory names and MUST use
   `McpToolUtils.untrustedTextResult()`.

2. `app/src/main/kotlin/.../ui/screens/settings/McpToolsSettingsScreen.kt` (modify) — add its
   `ToolEntry`.

### Task 3.5 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderDiskUsageTest.kt`

**Setup**: MockK `DocumentFile` tree fixture with a builder that produces a directory of N
children, so the 200-entry and node-budget boundaries are reachable.

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

**File**: `app/src/test/kotlin/.../services/storage/MediaStoreDiskUsageTest.kt`

| Test | Verifies |
|------|----------|
| `diskUsage aggregates a cursor client-side` | Totals grouped by relative path |
| `diskUsage queries every collection of the location` | Multi-collection built-in covered |
| `diskUsage reports incomplete for an owner-filtered location` | `complete = false` |

---

## User Story 4 — Non-destructive quarantine

**Why**: Reclaiming space on an archive that has no second copy must be reversible. Files are
staged into a quarantine directory in the same location — a metadata-only move, so it is
instant and needs no free space — recorded in a manifest that maps each file back to its
origin, and removed only in a later, explicit step.

**Acceptance criteria**:
- [ ] Quarantining a batch moves files under `.quarantine/<batch_id>/` in the same location
- [ ] A manifest records the original path, size and timestamp of every file in the batch
- [ ] Two batches created in the same second do not collide
- [ ] Any batch can be restored to its original paths, including batches above 200 files
- [ ] Concurrent quarantine, restore and purge on one location cannot lose an entry
- [ ] Purging is a separate operation gated on delete permission
- [ ] Manifest-derived paths are validated before use

### Task 4.1 — Model

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
    }
}

/**
 * One quarantined file.
 *
 * @property originalPath Path relative to the location root, before quarantine.
 * @property quarantinedName File name inside the batch directory; disambiguated when two
 *   sources share a name.
 * @property sizeBytes Size at the time of quarantine.
 */
@Serializable
data class QuarantineEntry(
    val originalPath: String,
    val quarantinedName: String,
    val sizeBytes: Long,
)
```

### Task 4.2 — Provider

**Actions**:

1. `app/src/main/kotlin/.../services/storage/QuarantineProvider.kt` (create):

```kotlin
interface QuarantineProvider {
    /** Stages [paths] under a new batch, returning the batch as written. */
    suspend fun quarantine(locationId: String, paths: List<String>, reason: String): QuarantineBatch

    /** Reads every batch manifest present in [locationId]. */
    suspend fun listBatches(locationId: String): List<QuarantineBatch>

    /** Restores [batchId], returning the entries that could not be restored. */
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
    val skipped: List<Pair<QuarantineEntry, String>>,
)
```

2. `app/src/main/kotlin/.../services/storage/QuarantineProviderImpl.kt` (create) — constructor
   takes `FileOperationProvider` and `StorageLocationProvider`; the latter is required because
   the permission checks are the provider's own responsibility, not the file layer's.

   **Batch id**: `yyyyMMdd-HHmmss` plus a six-character random suffix, and the batch directory
   is created before any file moves. A directory that already exists means a collision, and the
   id is regenerated. A timestamp alone collides for two calls in the same second, and the
   second call would then overwrite the first manifest and orphan its files.

```kotlin
    private fun newBatchId(): String =
        "${LocalDateTime.now().format(BATCH_ID_FORMAT)}-${Random.nextBytes(3).toHexString()}"
```

   **Concurrency**: a `Mutex` per `locationId`, held for the whole of `quarantine`, `restore`
   and `purge`. All three read-modify-write the same manifest and enumerate the same directory,
   and CLAUDE.md puts concurrent MCP requests explicitly in scope. A lost update here means
   lost files.

```kotlin
    private val locationLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withLocationLock(locationId: String, block: suspend () -> T): T =
        locationLocks.computeIfAbsent(locationId) { Mutex() }.withLock { block() }
```

   **Enumeration**: every directory listing pages until `hasMore` is false. `listFiles` caps at
   200, so a single call would silently enumerate part of a batch — purge would then delete 200
   files, report success, and orphan the rest.

```kotlin
    private suspend fun listAll(locationId: String, path: String): List<FileInfo> {
        val all = mutableListOf<FileInfo>()
        var offset = 0
        while (true) {
            val page = fileOperationProvider.listFiles(locationId, path, offset, MAX_LIST_ENTRIES)
            all += page.files
            if (!page.hasMore || page.files.isEmpty()) return all
            offset += page.files.size
        }
    }
```

   **Manifest I/O**: read with `readFileBytes`, not `readFile`, which truncates at 200 lines and
   yields invalid JSON for a large batch. Write with `writeFile`. Serialize with
   `Json { prettyPrint = false }` so the manifest stays a single line regardless of size.

   **Path validation**: `quarantine` validates every element of `paths`; `restore` validates
   every `originalPath` read back from the manifest before using it as a move target. The
   manifest lives in user-writable storage, so its contents are untrusted input: a crafted
   `../../..` would otherwise write outside the location root.

   **Permissions**: `quarantine` and `restore` require write and delete — they move files out of
   their paths. `purge` requires delete. `listBatches` requires read only.

   **Ordering within `quarantine`**: create the batch directory, move each file, then write the
   manifest listing only the files that actually moved. A manifest must never claim a file it
   does not hold. Each move passes `allowCopyFallback = false`; a provider without move support
   fails the batch rather than duplicating an archive on a device short on space.

   **`restore`**: move each entry back, collecting failures rather than aborting; rewrite the
   manifest with the entries that remain; when none remain, `deleteDirectory` the batch.

3. `app/src/main/kotlin/.../di/AppModule.kt` (modify) — add the binding beside the existing
   storage bindings:

```kotlin
    @Binds
    @Singleton
    abstract fun bindQuarantineProvider(impl: QuarantineProviderImpl): QuarantineProvider
```

### Task 4.3 — Register the tools

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/McpToolUtils.kt` (modify) — add a string-array parameter
   helper; the file currently has none, and four scalar helpers cannot express `paths`:

```kotlin
    /**
     * Reads a required array-of-strings argument.
     *
     * @throws McpToolException.InvalidParams when absent, not an array, containing a
     *   non-string element, empty, or longer than [maxSize].
     */
    fun JsonObject.requireStringArray(name: String, maxSize: Int): List<String>
```

2. `app/src/main/kotlin/.../mcp/tools/QuarantineTools.kt` (create) — four handlers plus a
   `registerQuarantineTools(registrar, storageLocationProvider, quarantineProvider, toolNamePrefix, perms)`
   free function mirroring `registerFileTools`:

| Tool | Params | Permission | Result helper |
|---|---|---|---|
| `quarantine_files` | `location_id`, `paths` (≤ `MAX_BATCH_ENTRIES`), `reason` | write + delete | untrusted |
| `list_quarantine_batches` | `location_id` | read | untrusted |
| `restore_quarantine_batch` | `location_id`, `batch_id` | write + delete | untrusted |
| `purge_quarantine_batch` | `location_id`, `batch_id` | delete | plain |

**Constraint**: the first three echo names and paths that originate from the device and from a
manifest writable by other apps. Per PROJECT.md's anti-prompt-injection rule — and CLAUDE.md's
"if uncertain, use the untrusted variant" — they use `McpToolUtils.untrustedTextResult()`.
`purge_quarantine_batch` returns only a count and uses the plain helper.

3. `app/src/main/kotlin/.../services/mcp/McpServerService.kt` (modify) — add an `@Inject`
   `quarantineProvider: QuarantineProvider` field and call `registerQuarantineTools(...)` inside
   `registerAllTools()` beside the `registerFileTools(...)` call at line 458. Tool categories
   are registered here, not in `McpServer.kt`.

4. `app/src/main/kotlin/.../ui/screens/settings/McpToolsSettingsScreen.kt` (modify) — add a
   "Quarantine" group with the four `ToolEntry` rows.

### Task 4.4 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FakeFileOperationProvider.kt` (create) —
shared test infrastructure, given in full because every quarantine test builds on it:

```kotlin
/**
 * In-memory [FileOperationProvider] for quarantine tests.
 *
 * Backs a location with a path→bytes map so moves, writes, listings and deletions are directly
 * observable, and honours [MAX_LIST_ENTRIES] so pagination bugs surface in tests rather than on
 * a device. [failMoveFor] and [supportsMove] make the failure paths reachable.
 */
class FakeFileOperationProvider(
    private val files: MutableMap<String, ByteArray> = mutableMapOf(),
    var supportsMove: Boolean = true,
    var failMoveFor: Set<String> = emptySet(),
) : FileOperationProvider {
    override suspend fun listFiles(locationId: String, path: String, offset: Int, limit: Int): FileListResult {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val entries =
            files.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .sorted()
        val capped = limit.coerceAtMost(FileOperationProvider.MAX_LIST_ENTRIES)
        val page = entries.drop(offset).take(capped)
        return FileListResult(
            files = page.map { name ->
                val full = "$prefix$name"
                FileInfo(
                    name = name,
                    path = full,
                    isDirectory = files.keys.none { it == full },
                    size = files[full]?.size?.toLong() ?: 0L,
                    lastModified = 0L,
                    mimeType = null,
                )
            },
            totalCount = entries.size,
            hasMore = offset + page.size < entries.size,
        )
    }

    override suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        allowCopyFallback: Boolean,
    ): FileMoveResult {
        if (sourcePath in failMoveFor) throw McpToolException.InternalError("move failed: $sourcePath")
        if (!supportsMove && !allowCopyFallback) {
            throw McpToolException.InvalidParams("provider supports neither move nor rename")
        }
        val bytes = files.remove(sourcePath) ?: throw McpToolException.InvalidParams("missing: $sourcePath")
        files[destinationPath] = bytes
        return FileMoveResult(
            destinationPath,
            bytes.size.toLong(),
            if (supportsMove) MoveMechanism.MOVE_DOCUMENT else MoveMechanism.COPY_DELETE,
        )
    }

    // Remaining members delegate to `files` in the same direct fashion.
}
```

**File**: `app/src/test/kotlin/.../services/storage/QuarantineProviderTest.kt`

**Setup**: `FakeFileOperationProvider` above; mocked `StorageLocationProvider` with independent
`isWriteAllowed` / `isDeleteAllowed`.

| Test | Verifies |
|------|----------|
| `quarantine moves files and writes a manifest` | Files under the batch dir, manifest lists them |
| `quarantine succeeds with write and delete allowed` | The permitted path works |
| `quarantine fails when delete is not allowed` | The recorded permission decision holds |
| `quarantine fails when write is not allowed` | Rejected |
| `quarantine records only files that moved` | A failing move is absent from the manifest |
| `quarantine disambiguates colliding file names` | Two same-named sources both survive |
| `quarantine refuses a provider without move support` | No copy fallback, batch fails, sources intact |
| `quarantine rejects a traversal path` | `../` in `paths` refused |
| `quarantine rejects a batch above the entry cap` | Bounded request |
| `two quarantine calls in the same second get distinct batches` | No manifest overwrite — the plan-66 data loss |
| `concurrent quarantine calls both complete` | Two parallel batches, no lost entry |
| `listBatches reads every manifest` | Two batches both returned |
| `listBatches pages past 200 batch directories` | 250 batches all listed |
| `listBatches skips an unreadable manifest` | Corrupt JSON does not fail the listing |
| `listBatches reads a manifest above 200 lines` | Large batch parses — readFileBytes, not readFile |
| `restore returns files to their original paths` | Round trip is byte-identical |
| `restore skips an entry whose original path is occupied` | Reported in `skipped`, others restored |
| `restore skips an entry whose manifest path escapes the root` | Traversal refused, batch survives |
| `restore rewrites the manifest with the remaining entries` | Partial restore is resumable |
| `restore removes an emptied batch` | Directory and manifest gone via deleteDirectory |
| `restore of a 250-file batch restores all of them` | Pagination |
| `purge requires delete permission` | Rejected |
| `purge removes the batch and reports the count` | Directory gone, count matches |
| `purge of a 250-file batch removes all of them` | Pagination — the silent-truncation failure |

**File**: `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` (modify) — shared
test infrastructure: add `quarantineProvider: QuarantineProvider` to `MockDependencies` and call
`registerQuarantineTools(...)` beside the existing `registerFileTools(...)` call, so integration
tests can reach the new tools.

**File**: `app/src/test/kotlin/.../integration/QuarantineToolsIntegrationTest.kt` (create)

| Test | Verifies |
|------|----------|
| `quarantine_files then restore_quarantine_batch round trips` | Full dispatch over HTTP |
| `list_quarantine_batches carries the untrusted-content warning` | First line is the warning |
| `restore_quarantine_batch carries the untrusted-content warning` | First line is the warning |
| `quarantine_files rejects an empty paths array` | `isError = true` |
| `purge_quarantine_batch errors when delete is not allowed` | `isError = true` |

### Task 4.5 — Repair the existing suite

**Actions**:

1. `app/src/test/kotlin/.../integration/McpProtocolIntegrationTest.kt` (modify) — update
   `EXPECTED_TOOL_COUNT` from 57 to 63 (line 138) and the test name at line 38.
2. `app/src/test/kotlin/.../integration/AuthIntegrationTest.kt` (modify) — update
   `EXPECTED_TOOL_COUNT` from 57 to 63 (line 109).
3. `app/src/test/kotlin/.../integration/ToolPermissionsIntegrationTest.kt` (modify) — add the
   six new names to `ALL_TOOL_NAMES` (line 266), so `all tools disabled returns empty tool list`
   still holds.

**Constraint**: this task MUST be completed in the same sequence as US2–US4, not deferred.
CLAUDE.md forbids leaving the suite broken.

---

## User Story 5 — Inline help for settings

**Why**: The settings surface exposes protocol-level concepts — bearer token, binding address,
tunnel provider, storage permissions — that a non-specialist operator cannot act on safely
without knowing what each changes. Misconfiguring the binding address or disabling
authentication exposes the device to anyone who can reach it.

**Acceptance criteria**:
- [ ] Every setting row can reveal a plain-language explanation
- [ ] Rows whose permission is blocked by Android's restricted-settings protection explain how
      to unblock it
- [ ] Help is reachable by TalkBack and meets the 48dp touch target

### Task 5.1 — Help affordance

**Actions**:

1. `app/src/main/kotlin/.../ui/components/SettingHelp.kt` (create) — a composable rendering an
   `Icons.Outlined.HelpOutline` icon button that toggles inline expandable text below the row it
   annotates, plus the state holder it delegates to:

```kotlin
/**
 * Expansion state for a [SettingHelp] row.
 *
 * Extracted from the composable so the behaviour is unit-testable with plain JUnit, matching
 * how the existing component tests (ServerStatusCardTest, ConnectionInfoCardTest) cover UI
 * logic. This project has no Compose test runtime: `compose-ui-test-junit4` is declared in the
 * version catalog but not wired into the app's dependencies, and there is no androidTest source
 * set.
 */
class SettingHelpState(initiallyExpanded: Boolean = false) {
    var isExpanded: Boolean = initiallyExpanded
        private set

    fun toggle() {
        isExpanded = !isExpanded
    }
}
```

   Inline expansion rather than a tooltip or dialog: tooltips are unreliable under TalkBack, and
   a dialog per setting interrupts the scan of a settings list.

   The icon's `contentDescription` names the setting it explains, so TalkBack announces "Help
   for bearer token" rather than a bare "Help". Expanded state uses `rememberSaveable` so it
   survives rotation.

### Task 5.2 — Help content

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — one `help_<setting>` entry per setting
   across the settings screens.
2. `app/src/main/kotlin/.../ui/screens/settings/*.kt` (modify) — attach `SettingHelp` to each
   setting row.

**Constraint**: help text says what the setting does and what changes if it is switched, not how
the implementation works. Settings with a security consequence — binding address, disabling
authentication, storage write and delete permissions, tunnel exposure — MUST say what becomes
reachable and by whom.

### Task 5.3 — Restricted-settings guidance

**Actions**:

1. `app/src/main/kotlin/.../ui/screens/settings/PermissionsSettingsScreen.kt` (modify) — when a
   permission the app depends on is off, show the unblocking steps inline: **Settings → Apps →
   this app → ⋮ → Allow restricted settings**, then return and enable it.
2. `app/src/main/res/values/strings.xml` (modify) — the step text.

**Why this belongs here**: Android 13+ greys out Accessibility and Notification access for
sideloaded installs and shows only a generic security dialog with no actionable step. The README
documents the workaround (line 155) but the app itself does not, so a user who never reads the
README is stuck at a dead end.

**Constraint**: the app cannot query whether restricted settings are blocked — there is no
public API — so the guidance is shown whenever the permission is off, phrased as "if the toggle
is greyed out" rather than asserting the state.

### Task 5.4 — Tests

**File**: `app/src/test/kotlin/.../ui/components/SettingHelpStateTest.kt`

**Setup**: plain JUnit 5 over `SettingHelpState`, matching the existing component tests.

| Test | Verifies |
|------|----------|
| `state starts collapsed` | Default is false |
| `toggle expands` | True after one call |
| `toggle twice collapses` | Back to false |

---

## User Story 6 — Spanish localization

**Why**: The operator of this deployment works in Spanish. Android picks the locale from system
settings, so a second resource folder is the whole feature. It runs last so that it covers the
strings added by US2–US5 in one pass.

**Acceptance criteria**:
- [ ] `values-es/strings.xml` defines every translatable key in `values/strings.xml`
- [ ] A device set to Spanish renders the app in Spanish; other locales fall back to English
- [ ] A future untranslated string fails the build

### Task 6.1 — Spanish resource set

**Actions**:

1. `app/src/main/res/values-es/strings.xml` (create) — translate every translatable key.

**Constraints**:
- Ecosystem terms stay untranslated: `token`, `bearer`, `MCP`, `OAuth`, `ngrok`, `Cloudflare`,
  tool names, `HTTP`/`HTTPS`.
- Format specifiers (`%1$s`, `%d`) preserved in count and order.
- Entries marked `translatable="false"` in US1 MUST NOT appear here.

### Task 6.2 — Promote the translation checks

**Actions**:

1. `app/build.gradle.kts` (modify) — in the `lint { }` block, promote `MissingTranslation` and
   `ExtraTranslation` to errors.

**Constraint**: this MUST be the last task of the plan. Promoting it earlier fails the build on
every English string added by US2–US5 before its Spanish counterpart exists.

### Task 6.3 — Manual QA

**Manual Test** — locale coverage cannot be asserted from a JVM unit test in this project (no
instrumentation source set, and `Configuration`-based resource loading needs a device):

1. Set the device language to Spanish, launch the app, and walk every settings screen.
2. Confirm no English text remains and no label is clipped or ellipsised.
3. Set the device language to English and confirm the app follows.

---

## Documentation

### Task D.1 — Update the source of truth

**Actions**:

1. `docs/PROJECT.md` (modify) — tool count 57 → 63 (line 202); add the six tools to the §8 File
   Tools table plus a new Quarantine section; document under "Storage Location Permissions"
   that move and quarantine require write AND delete, and why.
2. `docs/MCP_TOOLS.md` (modify) — tool count (line 37), the File Operations row (line 48), and
   full entries for the six new tools.

**Constraint**: PROJECT.md is the declared source of truth. Leaving its inventory stale makes
every later plan that reads it wrong.

---

## Quality gates (run once, after every user story is implemented)

- [ ] `make lint` clean, including `verifyNoHardcodedUiText`
- [ ] `./gradlew :app:test` green
- [ ] `./gradlew build` succeeds without errors or warnings
- [ ] `code-reviewer` subagent in plan compliance mode reports no findings
