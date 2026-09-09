<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Storage Organizer: disk usage, non-destructive quarantine, Spanish localization, inline setting help

## Context

Driving use case: auditing and organizing a large WhatsApp media archive on a secondary
device where the on-disk copy is the only copy. Two constraints follow from that and shape
every decision below:

1. **No irreversible operation may be the first action.** The existing `delete_file` is the
   only file-mutating exit in the tool surface. Reclaiming space must go through a reversible
   staging step instead.
2. **Auditing must not require walking the tree one directory per round trip.** `list_files`
   is flat and paginated; summarising a media archive through it costs hundreds of MCP calls.

WhatsApp media lives under `Android/media/com.whatsapp/`, which is reachable through SAF
(unlike `Android/data`) and is not indexed by MediaStore. The quarantine and move tools
therefore target SAF locations; built-in MediaStore locations return a clear unsupported
error rather than a partial implementation (see US3 T1).

Localization and inline help are in the same plan because both new screens and new error
strings land here — extracting strings after the fact would mean touching the same files
twice.

---

## User Story 1 — Externalize the remaining hardcoded UI strings

**Why**: `values/strings.xml` already holds 292 entries and most composables use
`stringResource`, but ~150 literals remain inline. A second locale cannot be added while any
user-visible text is compiled into Kotlin, and every screen added later in this plan would
add more of them.

**Acceptance criteria**:
- [ ] No user-visible literal string remains in `app/src/main/kotlin/.../ui/`
- [ ] `./gradlew :app:lintFossDebug` reports no `HardcodedText` warnings
- [ ] No behavior change: every extracted string renders identically

### Task 1.1 — Extract literals from settings screens

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — add one `<string>` per extracted literal.
   Naming follows the existing convention in the file: `<screen>_<element>_<role>`, e.g.
   `storage_settings_add_location_button`.
2. `app/src/main/kotlin/.../ui/screens/settings/*.kt` (modify) — replace each literal with
   `stringResource(R.string.<key>)`.

**Constraint**: strings that are not user-visible — `@Suppress` rule names, log tags, MCP
tool identifiers, DataStore keys, MIME types — MUST NOT be extracted. Extracting an MCP tool
name would rename the tool per locale and break every client.

**Definition of Done**:
- [ ] Every settings screen compiles with no inline user-visible literal
- [ ] Existing screen tests pass unchanged

### Task 1.2 — Extract literals from top-level screens and components

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — add remaining keys.
2. `app/src/main/kotlin/.../ui/screens/{MainScreen,ServerScreen,ServerTabScreen,LogsScreen,AboutScreen,ApprovalScreen}.kt` (modify) — replace literals.
3. `app/src/main/kotlin/.../ui/components/*.kt` (modify) — replace literals.

**Definition of Done**:
- [ ] `lintFossDebug` clean of `HardcodedText`

---

## User Story 2 — Spanish localization

**Why**: The operator of this deployment works in Spanish. Android selects the locale
automatically from system settings, so a second resource folder is the whole feature.

**Acceptance criteria**:
- [ ] `values-es/strings.xml` defines every key present in `values/strings.xml`
- [ ] A device set to Spanish renders the app in Spanish; any other locale falls back to English
- [ ] No layout truncation: Spanish strings average ~20% longer than English

### Task 2.1 — Add the Spanish resource set

**Actions**:

1. `app/src/main/res/values-es/strings.xml` (create) — translate every key from
   `values/strings.xml`.

**Constraints**:
- Technical terms that appear in the MCP/Android ecosystem stay untranslated: `token`,
  `bearer`, `MCP`, `OAuth`, `ngrok`, `Cloudflare`, tool names, `HTTP`/`HTTPS`.
- Format specifiers (`%1$s`, `%d`) MUST be preserved in count and order.
- `translatable="false"` entries MUST NOT appear in `values-es`.

**Definition of Done**:
- [ ] Key sets of `values/strings.xml` and `values-es/strings.xml` are identical
- [ ] `lintFossDebug` reports no `MissingTranslation` or `ExtraTranslation`

### Task 2.2 — Guard translation completeness in CI

**Actions**:

1. `app/build.gradle.kts` (modify) — promote `MissingTranslation` and `ExtraTranslation` to
   errors in the `lint { }` block so a future string added to `values/` without a Spanish
   counterpart fails the build rather than silently falling back to English.

**Definition of Done**:
- [ ] Removing one `values-es` entry fails `lintFossDebug`

---

## User Story 3 — `move_file` MCP tool

**Why**: The tool surface can create and delete a file but cannot relocate one. Quarantine
(US4) is a move, and a copy+delete emulation would double the space requirement and lose the
original on a mid-operation failure — unacceptable on a device that is short on space and
holds the only copy.

**Acceptance criteria**:
- [ ] `android_move_file` relocates a file within one SAF location, preserving content
- [ ] Missing intermediate directories in the destination are created
- [ ] The operation is rejected when the location does not allow write
- [ ] Built-in MediaStore locations return a clear unsupported error

### Task 3.1 — Extend the file operation provider

**Actions**:

1. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add to the
   interface:

```kotlin
    /**
     * Moves a file within a single storage location.
     *
     * @param locationId The authorized storage location identifier.
     * @param sourcePath Relative path of the existing file.
     * @param destinationPath Relative destination path; parent directories are created.
     * @param overwrite When false, an existing destination fails the operation.
     * @return [FileMoveResult] describing the destination actually written.
     */
    suspend fun moveFile(
        locationId: String,
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
    ): FileMoveResult
```

   and the result type:

```kotlin
/**
 * Result of a file move.
 *
 * @property destinationPath The relative path the file now occupies.
 * @property sizeBytes Size of the moved file.
 */
data class FileMoveResult(
    val destinationPath: String,
    val sizeBytes: Long,
)
```

2. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) —
   implement `moveFile`. Follow the dispatch pattern already used by the other operations:
   `BuiltinStorageLocation.isBuiltinId(locationId)` returns the unsupported error; otherwise
   resolve source and destination parents as `DocumentFile`s.

   Prefer `DocumentsContract.moveDocument` when source and destination parents differ and the
   provider advertises `FLAG_SUPPORTS_MOVE`; fall back to `renameDocument` when only the name
   changes; fall back to a streamed copy followed by deletion of the source only when neither
   flag is available. The copy fallback MUST delete the source only after the destination is
   fully written and its size verified, so an interruption leaves the original intact.

3. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperations.kt` (modify) — add
   `moveFile` returning the unsupported error, matching how the interface treats operations
   the MediaStore backend cannot serve.

**Definition of Done**:
- [ ] Write permission is checked before any mutation
- [ ] File size limit is enforced on the copy fallback path
- [ ] No partial state is observable after a failure

### Task 3.2 — Register the MCP tool

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — register `move_file` following
   the surrounding `registrar.addTool` pattern. Parameters: `location_id` (string, required),
   `source_path` (string, required), `destination_path` (string, required), `overwrite`
   (boolean, optional, default `false`).

   The response is server-generated text, so it uses the plain result helper, not the
   untrusted variant.

**Definition of Done**:
- [ ] Tool appears in `list_tools`
- [ ] Invalid parameters return MCP error `-32602`

### Task 3.3 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderMoveTest.kt`

**Setup**: mock `DocumentFile` trees via MockK, mirroring the existing provider tests; mock
`StorageLocationProvider` to return a SAF location with configurable `allowWrite`.

| Test | Verifies |
|------|----------|
| `moveFile relocates within the same location` | Destination holds the content, source is gone |
| `moveFile creates missing destination directories` | Parent chain is created before the move |
| `moveFile fails when write is not allowed` | Rejected on a read-only location, source untouched |
| `moveFile fails on a builtin location` | MediaStore backend returns the unsupported error |
| `moveFile refuses an existing destination when overwrite is false` | Source untouched |
| `moveFile overwrites when overwrite is true` | Destination replaced |
| `moveFile keeps the source when the copy fallback fails` | Simulated write failure leaves the original readable |
| `moveFile rejects a source above the size limit` | Size guard applies to the copy path |

**File**: `app/src/test/kotlin/.../integration/FileToolsIntegrationTest.kt` (modify)

| Test | Verifies |
|------|----------|
| `move_file returns success for a valid move` | Full HTTP → SDK → provider dispatch |
| `move_file returns an error for an unknown location` | `isError = true` |

---

## User Story 4 — Non-destructive quarantine

**Why**: Reclaiming space on an archive that has no second copy must be reversible. Files are
staged into a quarantine folder on the same volume — a rename, so it is instant and needs no
free space — recorded in a manifest that maps each file back to its origin, and only removed
in a later, explicit step.

**Acceptance criteria**:
- [ ] Quarantining a batch moves files under `.quarantine/<batch_id>/` in the same location
- [ ] A manifest records the original path, size and timestamp of every file in the batch
- [ ] Any batch can be restored to its original paths
- [ ] Purging is a separate operation gated on the location's delete permission
- [ ] Quarantine itself requires only write permission — nothing is destroyed

### Task 4.1 — Quarantine model and manifest

**Actions**:

1. `app/src/main/kotlin/.../data/model/QuarantineBatch.kt` (create):

```kotlin
/**
 * One quarantine operation: a set of files staged under a single batch directory.
 *
 * @property batchId Directory name under `.quarantine/`, formatted `yyyy-MM-dd-HHmmss`.
 * @property createdAtEpochMs When the batch was created.
 * @property reason Caller-supplied explanation, surfaced in the UI and on restore.
 * @property entries The files staged in this batch.
 */
data class QuarantineBatch(
    val batchId: String,
    val createdAtEpochMs: Long,
    val reason: String,
    val entries: List<QuarantineEntry>,
)

/**
 * One quarantined file.
 *
 * @property originalPath Path relative to the location root, before quarantine.
 * @property quarantinedName File name inside the batch directory.
 * @property sizeBytes Size at the time of quarantine.
 */
data class QuarantineEntry(
    val originalPath: String,
    val quarantinedName: String,
    val sizeBytes: Long,
)
```

**Constraint**: the manifest is written as JSON to `.quarantine/<batch_id>/manifest.json` via
Kotlinx Serialization, inside the storage location itself rather than in DataStore. The
manifest must survive the app being uninstalled and reinstalled; a restore is otherwise
impossible and the archive is stranded under opaque names.

**Constraint**: `quarantinedName` disambiguates collisions between files that share a name in
different source directories, by prefixing an index when the plain name is already taken
inside the batch.

### Task 4.2 — Quarantine provider

**Actions**:

1. `app/src/main/kotlin/.../services/storage/QuarantineProvider.kt` (create) — interface:

```kotlin
interface QuarantineProvider {
    /** Stages [paths] under a new batch, returning the batch as written. */
    suspend fun quarantine(locationId: String, paths: List<String>, reason: String): QuarantineBatch

    /** Reads every batch manifest present in [locationId]. */
    suspend fun listBatches(locationId: String): List<QuarantineBatch>

    /** Restores [batchId] to the original paths, returning the entries restored. */
    suspend fun restore(locationId: String, batchId: String): List<QuarantineEntry>

    /** Permanently deletes [batchId] and its manifest. */
    suspend fun purge(locationId: String, batchId: String): Int
}
```

2. `app/src/main/kotlin/.../services/storage/QuarantineProviderImpl.kt` (create) — implement
   on top of `FileOperationProvider.moveFile`, `listFiles`, `readFile`, `writeFile` and
   `deleteFile`. Inject via Hilt `@Binds` in the existing storage module.

   `quarantine` writes the manifest **after** the moves complete, and records only files that
   actually moved, so a manifest never claims a file it does not hold.

   `restore` moves each entry back and rewrites the manifest with the remaining entries;
   a batch whose entries are all restored has its directory and manifest removed.

   `purge` requires the location's delete permission; `quarantine` and `restore` require only
   write.

**Definition of Done**:
- [ ] A batch interrupted mid-way leaves a manifest consistent with what is on disk
- [ ] Restoring into an occupied original path fails that entry without aborting the batch

### Task 4.3 — Register the quarantine MCP tools

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/QuarantineTools.kt` (create) — register four tools
   following the `FileTools.kt` registration pattern:

| Tool | Params | Permission |
|---|---|---|
| `quarantine_files` | `location_id`, `paths` (array of string), `reason` (string) | write |
| `list_quarantine_batches` | `location_id` | read |
| `restore_quarantine_batch` | `location_id`, `batch_id` | write |
| `purge_quarantine_batch` | `location_id`, `batch_id` | delete |

   `list_quarantine_batches` returns device-derived content (file names originating from the
   device) and MUST use `McpToolUtils.untrustedTextResult()`. The other three return
   server-generated confirmations and use the plain helper.

2. `app/src/main/kotlin/.../mcp/McpServer.kt` (modify) — register `QuarantineTools` alongside
   the existing tool categories.

### Task 4.4 — Tests

**File**: `app/src/test/kotlin/.../services/storage/QuarantineProviderTest.kt`

**Setup**: fake `FileOperationProvider` backed by an in-memory path→bytes map, so move,
write and delete are observable without SAF mocking.

| Test | Verifies |
|------|----------|
| `quarantine moves files and writes a manifest` | Files under the batch dir, manifest lists them |
| `quarantine records only files that moved` | A failing move is absent from the manifest |
| `quarantine disambiguates colliding file names` | Two same-named sources both survive |
| `quarantine requires write permission` | Rejected on a read-only location |
| `listBatches reads every manifest` | Two batches both returned |
| `listBatches skips an unreadable manifest` | Corrupt JSON does not fail the listing |
| `restore returns files to their original paths` | Round trip is byte-identical |
| `restore skips an entry whose original path is occupied` | Remaining entries still restore |
| `restore removes an emptied batch` | Directory and manifest gone |
| `purge requires delete permission` | Rejected when delete is not allowed |
| `purge removes the batch and reports the count` | Directory gone, count matches |

**File**: `app/src/test/kotlin/.../integration/QuarantineToolsIntegrationTest.kt` (create)

| Test | Verifies |
|------|----------|
| `quarantine_files then restore_quarantine_batch round trips` | Full dispatch over HTTP |
| `list_quarantine_batches carries the untrusted-content warning` | First line is the warning |
| `purge_quarantine_batch errors when delete is not allowed` | `isError = true` |

---

## User Story 5 — `disk_usage` MCP tool

**Why**: `list_files` is flat and paginated, so summarising a directory tree costs one call
per directory. A single recursive call with aggregated sizes turns a several-hundred-call
audit into one.

**Acceptance criteria**:
- [ ] Returns per-directory aggregated size and file count to a caller-specified depth
- [ ] Bounded: traversal stops at a node budget and reports that it was truncated
- [ ] Works on SAF locations and on built-in MediaStore locations

### Task 5.1 — Recursive aggregation

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
```

2. `app/src/main/kotlin/.../services/storage/FileOperationProvider.kt` (modify) — add:

```kotlin
    /**
     * Aggregates file sizes below [path], descending at most [maxDepth] levels.
     *
     * Traversal always covers the whole subtree for the totals; [maxDepth] limits only how
     * deep the returned tree is broken down. Traversal stops after [MAX_USAGE_NODES]
     * directories, and the result reports whether it was truncated.
     */
    suspend fun diskUsage(locationId: String, path: String, maxDepth: Int): DiskUsageResult
```

   with `DiskUsageResult(val root: DiskUsageNode, val truncated: Boolean)` and a
   `MAX_USAGE_NODES` companion constant alongside the existing `MAX_LIST_ENTRIES`.

3. `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` (modify) —
   implement an iterative (explicit stack, not recursive) traversal so a pathological tree
   cannot overflow the stack. Run on the IO dispatcher and check for coroutine cancellation
   each directory, so a client disconnect stops the walk.

4. `app/src/main/kotlin/.../services/storage/MediaStoreFileOperationsImpl.kt` (modify) —
   implement via a single MediaStore query over the collection, grouping by relative path,
   rather than a directory walk.

### Task 5.2 — Register the MCP tool

**Actions**:

1. `app/src/main/kotlin/.../mcp/tools/FileTools.kt` (modify) — register `disk_usage`.
   Parameters: `location_id` (string, required), `path` (string, optional, default `""`),
   `max_depth` (int, optional, default 2, max 10).

   The response embeds device-derived directory names and MUST use
   `McpToolUtils.untrustedTextResult()`.

### Task 5.3 — Tests

**File**: `app/src/test/kotlin/.../services/storage/FileOperationProviderDiskUsageTest.kt`

**Setup**: same in-memory tree fixture as the quarantine tests.

| Test | Verifies |
|------|----------|
| `diskUsage sums a flat directory` | Total and count match |
| `diskUsage sums nested directories into the root total` | Deep files counted at the root |
| `diskUsage breaks down only to maxDepth` | Deeper nodes absent, totals still complete |
| `diskUsage on an empty directory returns zero` | No division or null issues |
| `diskUsage reports truncation past the node budget` | `truncated = true` |
| `diskUsage honours cancellation` | Cancelled scope stops the walk |
| `diskUsage on a builtin location aggregates via MediaStore` | Grouped by relative path |

---

## User Story 6 — Inline help for settings

**Why**: The settings surface exposes protocol-level concepts — bearer token, binding
address, tunnel provider, storage permissions — that a non-specialist operator cannot act on
safely without knowing what each one changes. Misconfiguring the binding address or
disabling authentication exposes the device.

**Acceptance criteria**:
- [ ] Every setting row can reveal a plain-language explanation of what it does
- [ ] Help text exists in both English and Spanish
- [ ] Help is reachable by TalkBack and meets the 48dp touch target

### Task 6.1 — Reusable help affordance

**Actions**:

1. `app/src/main/kotlin/.../ui/components/SettingHelp.kt` (create) — a composable that renders
   an `Icons.Outlined.HelpOutline` icon button which toggles an inline expandable text below
   the row it annotates.

   Inline expansion rather than a tooltip or dialog: tooltips are unreliable with TalkBack and
   a dialog per setting interrupts the scan of a settings list.

   The icon carries a `contentDescription` naming the setting it explains, so TalkBack
   announces "Help for bearer token" rather than a bare "Help".

**Definition of Done**:
- [ ] 48dp touch target
- [ ] Expanded state survives configuration change (`rememberSaveable`)

### Task 6.2 — Help content

**Actions**:

1. `app/src/main/res/values/strings.xml` (modify) — add one `<string name="help_<setting>">`
   per setting across the settings screens.
2. `app/src/main/res/values-es/strings.xml` (modify) — Spanish counterparts.
3. `app/src/main/kotlin/.../ui/screens/settings/*.kt` (modify) — attach `SettingHelp` to each
   setting row.

**Constraint**: help text states what the setting does and what changes if it is switched —
not how the implementation works. Settings with a security consequence (binding address,
disabling authentication, storage write/delete permissions, tunnel exposure) MUST say what
becomes reachable and by whom.

### Task 6.3 — Tests

**File**: `app/src/test/kotlin/.../ui/components/SettingHelpTest.kt`

**Setup**: Compose UI test with `createComposeRule`, as used by the existing component tests.

| Test | Verifies |
|------|----------|
| `help is collapsed by default` | Text not in the tree initially |
| `tapping the icon reveals the help text` | Text present after click |
| `tapping again collapses it` | Text removed |
| `the icon exposes a content description naming the setting` | TalkBack label present |

---

## Quality gates (run once, after every user story is implemented)

- [ ] `make lint` clean
- [ ] `./gradlew :app:test` green
- [ ] `./gradlew build` succeeds without warnings
- [ ] `code-reviewer` subagent in plan compliance mode reports no findings
