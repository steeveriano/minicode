package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.QuarantineBatch
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val LOCATION = "com.android.externalstorage.documents/primary:WhatsApp"

class QuarantineProviderTest {
    private fun provider(fake: FakeFileOperationProvider) = QuarantineProviderImpl(fake)

    private fun fakeWith(vararg paths: String): FakeFileOperationProvider =
        FakeFileOperationProvider().apply {
            paths.forEachIndexed { index, path -> putFile(path, "content-$index") }
        }

    // ─── quarantine ─────────────────────────────────────────────────────────

    @Test
    fun `quarantine moves files and writes a manifest`() =
        runTest {
            val fake = fakeWith("Media/a.jpg", "Media/b.jpg")
            val batch = provider(fake).quarantine(LOCATION, listOf("Media/a.jpg", "Media/b.jpg"), "old media")

            assertEquals(2, batch.entries.size)
            assertEquals("old media", batch.reason)
            assertFalse(fake.exists("Media/a.jpg"), "the source must no longer be in place")
            batch.entries.forEach { entry ->
                assertTrue(
                    fake.exists("${QuarantineBatch.QUARANTINE_DIR}/${batch.batchId}/${entry.quarantinedName}"),
                )
            }
            assertTrue(
                fake.exists(
                    "${QuarantineBatch.QUARANTINE_DIR}/${batch.batchId}/${QuarantineBatch.MANIFEST_FILE_NAME}",
                ),
            )
        }

    @Test
    fun `quarantine succeeds when write and delete are both allowed`() =
        runTest {
            val fake =
                fakeWith("a.jpg").apply {
                    allowWrite = true
                    allowDelete = true
                }
            val batch = provider(fake).quarantine(LOCATION, listOf("a.jpg"), "why")
            assertEquals(1, batch.entries.size)
        }

    @Test
    fun `quarantine fails when delete is not allowed`() =
        runTest {
            // Moving a file out of its path is a deletion from that path, so write alone is not
            // enough. This is the permission decision the design rests on.
            val fake = fakeWith("a.jpg").apply { allowDelete = false }
            val batch = provider(fake).quarantine(LOCATION, listOf("a.jpg"), "why")

            assertTrue(batch.entries.isEmpty(), "nothing may be staged without delete permission")
            assertTrue(fake.exists("a.jpg"), "the source must be untouched")
        }

    @Test
    fun `quarantine fails when write is not allowed`() =
        runTest {
            val fake = fakeWith("a.jpg").apply { allowWrite = false }
            assertThrows<McpToolException.PermissionDenied> {
                provider(fake).quarantine(LOCATION, listOf("a.jpg"), "why")
            }
            assertTrue(fake.exists("a.jpg"))
        }

    @Test
    fun `quarantine records only the files that moved`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg").apply { failMoveFor = setOf("b.jpg") }
            val batch = provider(fake).quarantine(LOCATION, listOf("a.jpg", "b.jpg"), "why")

            // A manifest that claimed b.jpg would make a later restore look for a file the batch
            // does not hold.
            assertEquals(listOf("a.jpg"), batch.entries.map { it.originalPath })
            assertTrue(fake.exists("b.jpg"), "a file that did not move stays in place")
        }

    @Test
    fun `quarantine disambiguates colliding file names`() =
        runTest {
            val fake = fakeWith("Images/photo.jpg", "Video/photo.jpg")
            val batch =
                provider(fake).quarantine(LOCATION, listOf("Images/photo.jpg", "Video/photo.jpg"), "why")

            assertEquals(2, batch.entries.size)
            val names = batch.entries.map { it.quarantinedName }
            assertEquals(names.size, names.toSet().size, "each staged file needs a distinct name")
            names.forEach { assertTrue(fake.exists("${QuarantineBatch.QUARANTINE_DIR}/${batch.batchId}/$it")) }
        }

    @Test
    fun `quarantine refuses a provider that cannot move`() =
        runTest {
            // Copying would need free space equal to the archive, which defeats the purpose on a
            // device that is short on it.
            val fake = fakeWith("a.jpg").apply { supportsMove = false }
            val batch = provider(fake).quarantine(LOCATION, listOf("a.jpg"), "why")

            assertTrue(batch.entries.isEmpty())
            assertTrue(fake.exists("a.jpg"))
        }

    @Test
    fun `quarantine rejects a traversal path`() =
        runTest {
            val fake = fakeWith("a.jpg")
            assertThrows<McpToolException> {
                provider(fake).quarantine(LOCATION, listOf("../../etc/passwd"), "why")
            }
        }

    @Test
    fun `quarantine rejects an empty path list`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                provider(FakeFileOperationProvider()).quarantine(LOCATION, emptyList(), "why")
            }
        }

    @Test
    fun `quarantine rejects a batch above the entry cap`() =
        runTest {
            val paths = (0..QuarantineBatch.MAX_BATCH_ENTRIES).map { "f$it.jpg" }
            assertThrows<McpToolException.InvalidParams> {
                provider(FakeFileOperationProvider()).quarantine(LOCATION, paths, "why")
            }
        }

    @Test
    fun `two batches created back to back do not collide`() =
        runTest {
            // A timestamp-only batch id collides within the same second, and the second call
            // would then overwrite the first manifest and orphan its files.
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            val first = subject.quarantine(LOCATION, listOf("a.jpg"), "first")
            val second = subject.quarantine(LOCATION, listOf("b.jpg"), "second")

            assertNotEquals(first.batchId, second.batchId)
            assertEquals(2, subject.listBatches(LOCATION).size)
            assertEquals(
                listOf("a.jpg"),
                subject
                    .listBatches(LOCATION)
                    .first { it.batchId == first.batchId }
                    .entries
                    .map { it.originalPath },
            )
        }

    @Test
    fun `concurrent quarantine calls both complete`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            val batches =
                listOf(
                    async { subject.quarantine(LOCATION, listOf("a.jpg"), "first") },
                    async { subject.quarantine(LOCATION, listOf("b.jpg"), "second") },
                ).awaitAll()

            assertNotEquals(batches[0].batchId, batches[1].batchId)
            assertEquals(1, batches[0].entries.size)
            assertEquals(1, batches[1].entries.size)
        }

    // ─── listBatches ────────────────────────────────────────────────────────

    @Test
    fun `listBatches returns empty for a location never quarantined`() =
        runTest {
            // The most common first call: there is no .quarantine directory yet, and listFiles
            // throws for a missing path.
            assertTrue(provider(FakeFileOperationProvider()).listBatches(LOCATION).isEmpty())
        }

    @Test
    fun `listBatches reads every manifest`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            subject.quarantine(LOCATION, listOf("a.jpg"), "first")
            subject.quarantine(LOCATION, listOf("b.jpg"), "second")

            assertEquals(setOf("first", "second"), subject.listBatches(LOCATION).map { it.reason }.toSet())
        }

    @Test
    fun `listBatches skips an unreadable manifest`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            val broken = subject.quarantine(LOCATION, listOf("a.jpg"), "broken")
            subject.quarantine(LOCATION, listOf("b.jpg"), "intact")
            fake.putFile(
                "${QuarantineBatch.QUARANTINE_DIR}/${broken.batchId}/${QuarantineBatch.MANIFEST_FILE_NAME}",
                "{ not json",
            )

            // The other batches are still restorable and the caller has to be able to see them.
            assertEquals(listOf("intact"), subject.listBatches(LOCATION).map { it.reason })
        }

    @Test
    fun `listBatches reads a manifest larger than the read-line cap`() =
        runTest {
            // readFile truncates at 200 lines; readFileBytes is what makes a large batch legible.
            val paths = (1..250).map { "Media/f$it.jpg" }
            val fake = FakeFileOperationProvider().apply { paths.forEach { putFile(it, "x") } }
            val subject = provider(fake)
            subject.quarantine(LOCATION, paths, "large")

            assertEquals(
                250,
                subject
                    .listBatches(LOCATION)
                    .single()
                    .entries.size,
            )
        }

    @Test
    fun `listBatches pages past the listing cap`() =
        runTest {
            val fake = fakeWith(*(1..250).map { "f$it.jpg" }.toTypedArray())
            val subject = provider(fake)
            (1..250).forEach { subject.quarantine(LOCATION, listOf("f$it.jpg"), "batch $it") }

            assertEquals(250, subject.listBatches(LOCATION).size)
        }

    // ─── restore ────────────────────────────────────────────────────────────

    @Test
    fun `restore returns files to their original paths`() =
        runTest {
            val fake = fakeWith("Media/a.jpg")
            val original = fake.files["Media/a.jpg"]!!.decodeToString()
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("Media/a.jpg"), "why")

            val outcome = subject.restore(LOCATION, batch.batchId)

            assertEquals(1, outcome.restored.size)
            assertTrue(outcome.skipped.isEmpty())
            assertEquals(original, fake.files["Media/a.jpg"]?.decodeToString())
        }

    @Test
    fun `restore removes a fully restored batch`() =
        runTest {
            val fake = fakeWith("a.jpg")
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("a.jpg"), "why")
            subject.restore(LOCATION, batch.batchId)

            assertTrue(subject.listBatches(LOCATION).isEmpty())
        }

    @Test
    fun `restore skips an entry whose original path is occupied`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("a.jpg", "b.jpg"), "why")
            fake.putFile("a.jpg", "something else took the path")

            val outcome = subject.restore(LOCATION, batch.batchId)

            assertEquals(listOf("b.jpg"), outcome.restored.map { it.originalPath })
            assertEquals(listOf("a.jpg"), outcome.skipped.map { it.entry.originalPath })
            assertEquals("original path is occupied", outcome.skipped.single().reason)
        }

    @Test
    fun `restore rewrites the manifest with the skipped entries`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("a.jpg", "b.jpg"), "why")
            fake.putFile("a.jpg", "occupied")
            subject.restore(LOCATION, batch.batchId)

            // A partial restore has to stay resumable, so the batch keeps exactly what is left.
            val remaining = subject.listBatches(LOCATION).single()
            assertEquals(listOf("a.jpg"), remaining.entries.map { it.originalPath })
        }

    @Test
    fun `restore requires write and delete`() =
        runTest {
            val fake = fakeWith("a.jpg")
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("a.jpg"), "why")

            fake.allowDelete = false
            val outcome = subject.restore(LOCATION, batch.batchId)
            assertTrue(outcome.restored.isEmpty())
            assertEquals(1, outcome.skipped.size)
        }

    @Test
    fun `restore of a batch larger than the listing cap restores every file`() =
        runTest {
            val paths = (1..250).map { "Media/f$it.jpg" }
            val fake = FakeFileOperationProvider().apply { paths.forEach { putFile(it, "x") } }
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, paths, "large")

            val outcome = subject.restore(LOCATION, batch.batchId)

            assertEquals(250, outcome.restored.size)
            paths.forEach { assertTrue(fake.exists(it), "$it must be back in place") }
        }

    // ─── purge ──────────────────────────────────────────────────────────────

    @Test
    fun `purge removes the batch and reports the count`() =
        runTest {
            val fake = fakeWith("a.jpg", "b.jpg")
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("a.jpg", "b.jpg"), "why")

            // Two files plus the manifest.
            assertEquals(3, subject.purge(LOCATION, batch.batchId))
            assertTrue(subject.listBatches(LOCATION).isEmpty())
        }

    @Test
    fun `purge requires delete permission`() =
        runTest {
            val fake = fakeWith("a.jpg")
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, listOf("a.jpg"), "why")

            fake.allowDelete = false
            assertThrows<McpToolException.PermissionDenied> { subject.purge(LOCATION, batch.batchId) }
            assertEquals(
                1,
                subject
                    .listBatches(LOCATION)
                    .single()
                    .entries.size,
            )
        }

    @Test
    fun `purge of a batch larger than the listing cap removes every file`() =
        runTest {
            // A purge built on a single capped listing would delete 200 files, report success and
            // strand the rest with no record of them.
            val paths = (1..250).map { "Media/f$it.jpg" }
            val fake = FakeFileOperationProvider().apply { paths.forEach { putFile(it, "x") } }
            val subject = provider(fake)
            val batch = subject.quarantine(LOCATION, paths, "large")

            assertEquals(251, subject.purge(LOCATION, batch.batchId))
            assertTrue(fake.files.keys.none { it.startsWith(QuarantineBatch.QUARANTINE_DIR) })
        }

    @Test
    fun `purge rejects a traversal batch id`() =
        runTest {
            assertThrows<McpToolException> {
                provider(FakeFileOperationProvider()).purge(LOCATION, "../../..")
            }
        }
}
