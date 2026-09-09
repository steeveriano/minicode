@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@ExtendWith(MockKExtension::class)
@DisplayName("FileOperationProviderImpl")
class FileOperationProviderTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockStorageLocationProvider: StorageLocationProvider

    @MockK
    private lateinit var mockSettingsRepository: SettingsRepository

    @MockK
    private lateinit var mockMediaStoreFileOperations: MediaStoreFileOperations

    private lateinit var provider: FileOperationProviderImpl

    private val mockTreeUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(DocumentFile::class)
        mockkStatic(DocumentsContract::class)
        stubbedDirectories.clear()

        every { mockContext.contentResolver } returns mockContentResolver

        provider =
            FileOperationProviderImpl(
                mockContext,
                mockStorageLocationProvider,
                mockSettingsRepository,
                mockMediaStoreFileOperations,
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
        unmockkStatic(DocumentFile::class)
        unmockkStatic(DocumentsContract::class)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Sets up mocks so that [locationId] is treated as authorized.
     * [storageLocationProvider.isLocationAuthorized] returns true and
     * [storageLocationProvider.getTreeUriForLocation] returns the shared [mockTreeUri].
     */
    private fun setupAuthorizedLocation(locationId: String) {
        coEvery { mockStorageLocationProvider.isLocationAuthorized(locationId) } returns true
        coEvery { mockStorageLocationProvider.getTreeUriForLocation(locationId) } returns mockTreeUri
        coEvery { mockStorageLocationProvider.isWriteAllowed(locationId) } returns true
        coEvery { mockStorageLocationProvider.isDeleteAllowed(locationId) } returns true
    }

    /**
     * Sets up mocks so that [locationId] is treated as unauthorized.
     */
    private fun setupUnauthorizedLocation(locationId: String) {
        coEvery { mockStorageLocationProvider.isLocationAuthorized(locationId) } returns false
    }

    /**
     * Configures [DocumentFile.fromTreeUri] to return [rootDoc] for the shared [mockTreeUri].
     */
    private fun setupRootDocument(rootDoc: DocumentFile) {
        every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns rootDoc
    }

    /** One row of a stubbed directory enumeration. */
    private data class TestChild(
        val name: String,
        val isDirectory: Boolean = false,
        val size: Long = 0L,
        val lastModified: Long = 0L,
        val mimeType: String? = null,
        /** Only needed when the code under test resolves this child into a [DocumentFile]. */
        val document: DocumentFile? = null,
    )

    private class StubbedDirectory(
        val uri: Uri,
        val documentId: String,
        val children: MutableList<TestChild> = mutableListOf(),
    )

    private val stubbedDirectories = mutableMapOf<DocumentFile, StubbedDirectory>()
    private var nextDocumentId = 0

    /**
     * Makes [parent] enumerate to [children] the way the provider actually reads a directory: one
     * ContentResolver query over the children URI, with every attribute taken from that cursor.
     *
     * Stubbing `DocumentFile.listFiles()` or `findFile()` would no longer describe the provider's
     * behaviour — it stopped using them because each of their accessors is a separate query.
     *
     * Additive: calling it again for the same parent appends to that directory.
     */
    private fun stubChildren(
        parent: DocumentFile,
        children: List<TestChild> = emptyList(),
    ) {
        val directory =
            stubbedDirectories[parent]
                ?: registerDirectory(parent, mockk(relaxed = true), "doc-${nextDocumentId++}")

        for (child in children) {
            val childDocumentId = "${directory.documentId}/${child.name}"
            val childUri = mockk<Uri>(relaxed = true)
            every {
                DocumentsContract.buildDocumentUriUsingTree(directory.uri, childDocumentId)
            } returns childUri
            child.document?.let { document ->
                every { DocumentFile.fromTreeUri(mockContext, childUri) } returns document
                // The provider recurses on the URI the enumeration produced, so a child that is
                // itself stubbed later must already be bound to that same URI and document id.
                if (child.isDirectory && stubbedDirectories[document] == null) {
                    registerDirectory(document, childUri, childDocumentId)
                }
            }
            directory.children += child
        }
    }

    /** Binds [document] to [uri] and makes its children readable through one stubbed query. */
    private fun registerDirectory(
        document: DocumentFile,
        uri: Uri,
        documentId: String,
    ): StubbedDirectory {
        val childrenUri = mockk<Uri>(relaxed = true)
        every { document.uri } returns uri
        every { DocumentsContract.getDocumentId(uri) } returns documentId
        every { DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId) } returns childrenUri
        // `answers`, not `returns`: each query must get a cursor at position -1.
        every { mockContentResolver.query(childrenUri, any(), any(), any(), any()) } answers {
            childrenCursor(stubbedDirectories.getValue(document))
        }
        return StubbedDirectory(uri, documentId).also { stubbedDirectories[document] = it }
    }

    /** Registers [parent] as an empty directory. */
    private fun stubNoChildren(parent: DocumentFile) = stubChildren(parent)

    private fun childrenCursor(directory: StubbedDirectory): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var position = -1
        val rows = directory.children
        every { cursor.moveToNext() } answers {
            position++
            position < rows.size
        }
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) } returns 3
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } answers { "${directory.documentId}/${rows[position].name}" }
        every { cursor.getString(1) } answers { rows[position].name }
        every { cursor.getString(2) } answers {
            rows[position].let { if (it.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else it.mimeType }
        }
        every { cursor.getLong(3) } answers { rows[position].size }
        every { cursor.getLong(4) } answers { rows[position].lastModified }
        return cursor
    }

    // ─────────────────────────────────────────────────────────────────────
    // listFiles
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listFiles")
    inner class ListFiles {
        @Test
        fun `listFiles returns entries for authorized location`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true

                stubChildren(
                    mockDir,
                    listOf(
                        TestChild("subdir", isDirectory = true, lastModified = 1000L),
                        TestChild("test.txt", size = 500L, lastModified = 2000L, mimeType = "text/plain"),
                    ),
                )

                // Act
                val result = provider.listFiles("loc1", "", 0, 10)

                // Assert
                assertEquals(2, result.files.size)
                assertEquals(2, result.totalCount)
                assertFalse(result.hasMore)

                // Directories come first in sort order
                val dir = result.files[0]
                assertEquals("subdir", dir.name)
                assertEquals("loc1/subdir", dir.path)
                assertTrue(dir.isDirectory)
                assertEquals(0L, dir.size)

                val file = result.files[1]
                assertEquals("test.txt", file.name)
                assertEquals("loc1/test.txt", file.path)
                assertFalse(file.isDirectory)
                assertEquals(500L, file.size)
                assertEquals("text/plain", file.mimeType)
            }

        @Test
        fun `listFiles throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.listFiles("loc1", "", 0, 10)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `listFiles respects offset and limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true

                stubChildren(
                    mockDir,
                    ('a'..'e').map { letter ->
                        TestChild("file_$letter.txt", size = 100L, lastModified = 1000L, mimeType = "text/plain")
                    },
                )

                // Act — skip 2, take 2 from 5 total
                val result = provider.listFiles("loc1", "", 2, 2)

                // Assert
                assertEquals(2, result.files.size)
                assertEquals("file_c.txt", result.files[0].name)
                assertEquals("file_d.txt", result.files[1].name)
                assertEquals(5, result.totalCount)
                assertTrue(result.hasMore)
            }

        @Test
        fun `listFiles caps limit at MAX_LIST_ENTRIES`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true

                stubChildren(
                    mockDir,
                    (1..LIST_ENTRIES_ABOVE_MAX).map { i ->
                        TestChild(
                            "file_%03d.txt".format(i),
                            size = 100L,
                            lastModified = 1000L,
                            mimeType = "text/plain",
                        )
                    },
                )

                // Act — request limit far above MAX_LIST_ENTRIES
                val result = provider.listFiles("loc1", "", 0, REQUESTED_LIMIT_ABOVE_MAX)

                // Assert — capped at 200
                assertEquals(FileOperationProvider.MAX_LIST_ENTRIES, result.files.size)
                assertEquals(LIST_ENTRIES_ABOVE_MAX, result.totalCount)
                assertTrue(result.hasMore)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // readFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFile")
    inner class ReadFile {
        @Test
        fun `readFile returns lines with pagination info`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("data.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 100L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val content = "line1\nline2\nline3\nline4\nline5"
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — read lines starting at line 2, limit 2
                val result = provider.readFile("loc1", "data.txt", 2, 2)

                // Assert
                assertEquals("line2\nline3", result.content)
                assertEquals(5, result.totalLines)
                assertEquals(2, result.startLine)
                assertEquals(3, result.endLine)
                assertTrue(result.hasMore)
            }

        @Test
        fun `readFile caps limit at MAX_READ_LINES`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("big.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 10_000L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val lines = (1..LINES_ABOVE_MAX).map { "line$it" }
                val content = lines.joinToString("\n")
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — request limit far above MAX_READ_LINES
                val result = provider.readFile("loc1", "big.txt", 1, REQUESTED_LIMIT_ABOVE_MAX)

                // Assert — capped at 200 lines
                assertEquals(
                    FileOperationProvider.MAX_READ_LINES,
                    result.content.split("\n").size,
                )
                assertEquals(LINES_ABOVE_MAX, result.totalLines)
                assertTrue(result.hasMore)
            }

        @Test
        fun `readFile includes hasMore hint when more lines exist`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("file.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 50L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val content = "line1\nline2\nline3"
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — read 2 of 3 lines
                val result = provider.readFile("loc1", "file.txt", 1, 2)

                // Assert
                assertEquals("line1\nline2", result.content)
                assertEquals(3, result.totalLines)
                assertTrue(result.hasMore)
            }

        @Test
        fun `readFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.readFile("loc1", "file.txt", 1, 100)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `readFile throws IllegalArgumentException when offset is less than 1`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<IllegalArgumentException> {
                        provider.readFile("loc1", "file.txt", 0, 100)
                    }
                assertTrue(exception.message!!.contains("offset must be >= 1"))
            }

        @Test
        fun `readFile throws ActionFailed when file exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                stubChildren(mockRootDoc, listOf(TestChild("huge.bin", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns FILE_SIZE_EXCEEDING_LIMIT
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)

                // Act & Assert — 100 MB > 1 MB limit
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.readFile("loc1", "huge.bin", 1, 100)
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
            }

        @Test
        fun `readFile streams lines without loading entire file into memory`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("multiline.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 10_000L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val lines = (1..50).map { "line$it" }
                val content = lines.joinToString("\n")
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — read 10 lines starting at line 10
                val result = provider.readFile("loc1", "multiline.txt", 10, 10)

                // Assert — streaming produces correct offset/limit window
                assertEquals(50, result.totalLines)
                assertEquals(10, result.startLine)
                assertEquals(19, result.endLine)
                assertTrue(result.hasMore)
                val returnedLines = result.content.split("\n")
                assertEquals(10, returnedLines.size)
                assertEquals("line10", returnedLines[0])
                assertEquals("line19", returnedLines[9])
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // readFileBytes
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFileBytes")
    inner class ReadFileBytes {
        @Test
        fun `readFileBytes reads existing file without creating it`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("doc.pdf", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 4L
                every { mockFile.uri } returns mockFileUri
                every { mockFile.name } returns "doc.pdf"
                every { mockContentResolver.getType(mockFileUri) } returns "application/pdf"
                val bytes = byteArrayOf(1, 2, 3, 4)
                every { mockContentResolver.openInputStream(mockFileUri) } returns ByteArrayInputStream(bytes)

                // Act
                val result = provider.readFileBytes("loc1", "doc.pdf", TEN_MB)

                // Assert
                assertArrayEquals(bytes, result.bytes)
                assertEquals("application/pdf", result.mimeType)
                assertEquals("doc.pdf", result.fileName)
                assertEquals(4L, result.sizeBytes)
                verify(exactly = 0) { mockRootDoc.createFile(any(), any()) }
            }

        @Test
        fun `readFileBytes throws ActionFailed for missing file`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                stubNoChildren(mockRootDoc)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.readFileBytes("loc1", "missing.pdf", TEN_MB)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `readFileBytes throws ActionFailed when file exceeds maxBytes`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                stubChildren(mockRootDoc, listOf(TestChild("huge.bin", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns FILE_SIZE_EXCEEDING_LIMIT
                every { mockFile.uri } returns mockk()
                every { mockFile.name } returns "huge.bin"

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.readFileBytes("loc1", "huge.bin", TEN_MB)
                    }
                assertTrue(exception.message!!.contains("exceeds"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // writeFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeFile")
    inner class WriteFile {
        @Test
        fun `writeFile creates file with content`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(mockRootDoc)
                every { mockRootDoc.createFile("text/plain", "file.txt") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                provider.writeFile("loc1", "file.txt", "hello world")

                // Assert
                assertEquals("hello world", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `writeFile with empty content creates empty file`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(mockRootDoc)
                every { mockRootDoc.createFile("text/plain", "empty.txt") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                provider.writeFile("loc1", "empty.txt", "")

                // Assert
                assertEquals(0, outputStream.size())
            }

        @Test
        fun `writeFile throws ActionFailed when content exceeds size limit`() =
            runTest {
                // Arrange — 1 MB limit, content > 1 MB
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)
                val largeContent = "x".repeat(BYTES_PER_MB + 1)

                // Act & Assert — size check happens before authorization
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.writeFile("loc1", "file.txt", largeContent)
                    }
                assertTrue(exception.message!!.contains("exceeds the configured file size limit"))
            }

        @Test
        fun `writeFile creates parent directories`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)

                val subDirDoc = mockk<DocumentFile>()
                stubNoChildren(rootDoc)
                every { rootDoc.createDirectory("subdir") } returns subDirDoc

                val createdFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(subDirDoc)
                every { subDirDoc.createFile("text/plain", "file.txt") } returns createdFile
                every { createdFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                provider.writeFile("loc1", "subdir/file.txt", "content")

                // Assert
                verify { rootDoc.createDirectory("subdir") }
                assertEquals("content", outputStream.toString(Charsets.UTF_8.name()))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // appendFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("appendFile")
    inner class AppendFile {
        @Test
        fun `appendFile throws ActionFailed with hint when provider does not support append`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("file.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 100L
                every { mockFile.uri } returns mockFileUri

                every {
                    mockContentResolver.openOutputStream(mockFileUri, "wa")
                } throws UnsupportedOperationException("Append not supported")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.appendFile("loc1", "file.txt", "more data")
                    }
                assertTrue(exception.message!!.contains("does not support append mode"))
                assertTrue(exception.message!!.contains("write_file"))
            }

        @Test
        fun `appendFile throws ActionFailed when resulting size exceeds limit`() =
            runTest {
                // Arrange — file already at 1 MB limit, appending 1 more byte exceeds it
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockFile = mockk<DocumentFile>()
                stubChildren(mockRootDoc, listOf(TestChild("file.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns BYTES_PER_MB.toLong()

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.appendFile("loc1", "file.txt", "a")
                    }
                assertTrue(exception.message!!.contains("exceed"))
            }

        @Test
        fun `appendFile appends content to existing file`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubChildren(mockRootDoc, listOf(TestChild("file.txt", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 100L
                every { mockFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "wa") } returns outputStream

                // Act
                provider.appendFile("loc1", "file.txt", "appended data")

                // Assert
                assertEquals("appended data", outputStream.toString(Charsets.UTF_8.name()))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // replaceInFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replaceInFile")
    inner class ReplaceInFile {
        /**
         * Common setup for replace tests: resolves a file with the given [originalContent],
         * stubs the read + write mocks, and returns the [ByteArrayOutputStream] that
         * captures the written-back content.
         */
        private fun setupReplaceFile(originalContent: String): ByteArrayOutputStream {
            setupAuthorizedLocation("loc1")
            val mockRootDoc = mockk<DocumentFile>()
            setupRootDocument(mockRootDoc)

            val mockFile = mockk<DocumentFile>()
            val mockFileUri = mockk<Uri>()
            stubChildren(mockRootDoc, listOf(TestChild("file.txt", document = mockFile)))
            every { mockFile.isFile } returns true
            every { mockFile.isDirectory } returns false
            every { mockFile.length() } returns originalContent.length.toLong()
            every { mockFile.uri } returns mockFileUri
            coEvery { mockSettingsRepository.getServerConfig() } returns
                ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

            every { mockContentResolver.openInputStream(mockFileUri) } returns
                ByteArrayInputStream(originalContent.toByteArray(Charsets.UTF_8))

            // writeWithOptionalLock: openFileDescriptor returns null → OutputStream fallback
            every { mockContentResolver.openFileDescriptor(any(), any()) } returns null
            val outputStream = ByteArrayOutputStream()
            every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream
            return outputStream
        }

        @Test
        fun `replaceInFile replaces first occurrence`() =
            runTest {
                // Arrange
                val outputStream = setupReplaceFile("hello world hello")

                // Act
                val result =
                    provider.replaceInFile(
                        "loc1",
                        "file.txt",
                        "hello",
                        "hi",
                        false,
                    )

                // Assert
                assertEquals(1, result.replacementCount)
                assertEquals("hi world hello", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `replaceInFile replaces all occurrences when replaceAll is true`() =
            runTest {
                // Arrange
                val outputStream = setupReplaceFile("hello world hello")

                // Act
                val result =
                    provider.replaceInFile(
                        "loc1",
                        "file.txt",
                        "hello",
                        "hi",
                        true,
                    )

                // Assert
                assertEquals(2, result.replacementCount)
                assertEquals("hi world hi", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `replaceInFile returns zero count when old_string not found`() =
            runTest {
                // Arrange
                setupReplaceFile("hello world")

                // Act
                val result =
                    provider.replaceInFile(
                        "loc1",
                        "file.txt",
                        "xyz",
                        "abc",
                        false,
                    )

                // Assert
                assertEquals(0, result.replacementCount)
                // No write should have occurred
                verify(exactly = 0) { mockContentResolver.openOutputStream(any(), any()) }
                verify(exactly = 0) { mockContentResolver.openFileDescriptor(any(), any()) }
            }

        @Test
        fun `replaceInFile throws ActionFailed when file exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                stubChildren(mockRootDoc, listOf(TestChild("big.bin", document = mockFile)))
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns FILE_SIZE_EXCEEDING_LIMIT
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.replaceInFile("loc1", "big.bin", "a", "b", false)
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // downloadFromUrl
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("downloadFromUrl")
    inner class DownloadFromUrl {
        @Test
        fun `downloadFromUrl throws ActionFailed when HTTP not allowed`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = false)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "http://example.com/file.txt")
                    }
                assertTrue(exception.message!!.contains("HTTP downloads are not allowed"))
            }

        @Test
        fun `downloadFromUrl throws ActionFailed for invalid URL`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = true)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "not_a_valid_url")
                    }
                assertTrue(exception.message!!.contains("Invalid URL"))
            }

        @Test
        fun `downloadFromUrl throws ActionFailed when Content-Length exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1, allowHttpDownloads = true)

                val mockConnection = mockk<HttpURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns (2L * BYTES_PER_MB)

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        spyProvider.downloadFromUrl(
                            "loc1",
                            "file.txt",
                            "https://example.com/large.bin",
                        )
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
            }

        @Test
        fun `downloadFromUrl downloads file and saves to location`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = true)

                val mockConnection = mockk<HttpURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns 5L
                every { mockConnection.inputStream } returns
                    ByteArrayInputStream("hello".toByteArray())

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(rootDoc)
                every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
                every { mockFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                val size =
                    spyProvider.downloadFromUrl(
                        "loc1",
                        "file.txt",
                        "https://example.com/file.txt",
                    )

                // Assert
                assertEquals(5L, size)
                assertEquals("hello", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `downloadFromUrl throws ActionFailed when streamed size exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1, allowHttpDownloads = true)

                val mockConnection = mockk<HttpURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns -1L
                every { mockConnection.inputStream } returns
                    ByteArrayInputStream(ByteArray(BYTES_PER_MB + 1))

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(rootDoc)
                every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
                every { mockFile.uri } returns mockFileUri
                every { mockFile.delete() } returns true

                val outputStream = ByteArrayOutputStream()
                every {
                    mockContentResolver.openOutputStream(mockFileUri, "w")
                } returns outputStream

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        spyProvider.downloadFromUrl(
                            "loc1",
                            "file.txt",
                            "https://example.com/big.bin",
                        )
                    }
                assertTrue(
                    exception.message!!.contains("exceeds the configured file size limit"),
                )
            }

        @Test
        fun `downloadFromUrl uses permissive SSL when unverified certs allowed`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(
                        fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB,
                        allowHttpDownloads = true,
                        allowUnverifiedHttpsCerts = true,
                    )

                val mockConnection = mockk<HttpsURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns 5L
                every { mockConnection.inputStream } returns
                    ByteArrayInputStream("hello".toByteArray())

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(rootDoc)
                every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
                every { mockFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                spyProvider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")

                // Assert — permissive SSL was configured
                verify { mockConnection.sslSocketFactory = any() }
                verify { mockConnection.hostnameVerifier = any() }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // deleteFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteFile")
    inner class DeleteFile {
        @Test
        fun `deleteFile deletes the file`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                stubChildren(mockRootDoc, listOf(TestChild("file.txt", document = mockFile)))
                every { mockFile.isDirectory } returns false
                every { mockFile.delete() } returns true

                // Act
                provider.deleteFile("loc1", "file.txt")

                // Assert
                verify { mockFile.delete() }
            }

        @Test
        fun `deleteFile throws ActionFailed when path is a directory`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockDir = mockk<DocumentFile>()
                stubChildren(mockRootDoc, listOf(TestChild("subdir", document = mockDir)))
                every { mockDir.isDirectory } returns true

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.deleteFile("loc1", "subdir")
                    }
                assertTrue(exception.message!!.contains("Cannot delete a directory"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // AuthorizationDeniedForWriteDelete
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AuthorizationDeniedForWriteDelete")
    inner class AuthorizationDeniedForWriteDelete {
        @Test
        fun `writeFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange — getServerConfig() runs before checkAuthorization()
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.writeFile("loc1", "file.txt", "content")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `appendFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.appendFile("loc1", "file.txt", "content")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `replaceInFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.replaceInFile("loc1", "file.txt", "old", "new", false)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `downloadFromUrl throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `deleteFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.deleteFile("loc1", "file.txt")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WritePermissionDenied
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WritePermissionDenied")
    inner class WritePermissionDenied {
        @Test
        fun `writeFile throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.writeFile("loc1", "file.txt", "small content")
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `appendFile throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.appendFile("loc1", "file.txt", "more data")
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `replaceInFile throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.replaceInFile("loc1", "file.txt", "old", "new", false)
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `downloadFromUrl throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")
                    }
                assertEquals("Write not allowed", exception.message)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DeletePermissionDenied
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DeletePermissionDenied")
    inner class DeletePermissionDenied {
        @Test
        fun `deleteFile throws PermissionDenied when delete not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isDeleteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.deleteFile("loc1", "file.txt")
                    }
                assertEquals("Delete not allowed", exception.message)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // createFileUri
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createFileUri")
    inner class CreateFileUri {
        @Test
        fun `createFileUri returns valid URI for authorized location with write permission`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(mockRootDoc)
                every { mockRootDoc.createFile("image/jpeg", "photo.jpg") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                // Act
                val result = provider.createFileUri("loc1", "photo.jpg", "image/jpeg")

                // Assert
                assertEquals(mockFileUri, result)
            }

        @Test
        fun `createFileUri throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.createFileUri("loc1", "photo.jpg", "image/jpeg")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `createFileUri throws PermissionDenied for read-only location`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.getTreeUriForLocation("loc1") } returns mockTreeUri
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.createFileUri("loc1", "photo.jpg", "image/jpeg")
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `createFileUri creates parent directories when they don't exist`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)

                val subDirDoc = mockk<DocumentFile>()
                stubNoChildren(rootDoc)
                every { rootDoc.createDirectory("photos") } returns subDirDoc

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                stubNoChildren(subDirDoc)
                every { subDirDoc.createFile("image/jpeg", "photo.jpg") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                // Act
                val result = provider.createFileUri("loc1", "photos/photo.jpg", "image/jpeg")

                // Assert
                assertEquals(mockFileUri, result)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MediaStore Routing
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MediaStore Routing")
    inner class MediaStoreRouting {
        @Test
        fun `listFiles routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.listFiles("builtin:downloads", "", 0, 200)
                } returns FileListResult(files = emptyList(), totalCount = 0, hasMore = false)

                val result = provider.listFiles("builtin:downloads", "", 0, 200)

                assertEquals(0, result.totalCount)
                coVerify { mockMediaStoreFileOperations.listFiles("builtin:downloads", "", 0, 200) }
            }

        @Test
        fun `listFiles routes to SAF for non-builtin ID`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true
                stubNoChildren(mockDir)

                provider.listFiles("loc1", "", 0, 10)

                coVerify(exactly = 0) { mockMediaStoreFileOperations.listFiles(any(), any(), any(), any()) }
            }

        @Test
        fun `readFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.readFile("builtin:downloads", "test.txt", 1, 200)
                } returns FileReadResult(content = "hello", totalLines = 1, hasMore = false, startLine = 1, endLine = 1)

                val result = provider.readFile("builtin:downloads", "test.txt", 1, 200)

                assertEquals("hello", result.content)
                coVerify { mockMediaStoreFileOperations.readFile("builtin:downloads", "test.txt", 1, 200) }
            }

        @Test
        fun `writeFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.writeFile("builtin:downloads", "test.txt", "content")
                } returns Unit

                provider.writeFile("builtin:downloads", "test.txt", "content")

                coVerify { mockMediaStoreFileOperations.writeFile("builtin:downloads", "test.txt", "content") }
            }

        @Test
        fun `appendFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.appendFile("builtin:downloads", "test.txt", "more")
                } returns Unit

                provider.appendFile("builtin:downloads", "test.txt", "more")

                coVerify { mockMediaStoreFileOperations.appendFile("builtin:downloads", "test.txt", "more") }
            }

        @Test
        fun `replaceInFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.replaceInFile("builtin:downloads", "test.txt", "old", "new", false)
                } returns FileReplaceResult(1)

                val result = provider.replaceInFile("builtin:downloads", "test.txt", "old", "new", false)

                assertEquals(1, result.replacementCount)
                coVerify {
                    mockMediaStoreFileOperations.replaceInFile("builtin:downloads", "test.txt", "old", "new", false)
                }
            }

        @Test
        fun `downloadFromUrl routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.downloadFromUrl(
                        "builtin:downloads",
                        "file.txt",
                        "https://example.com/f",
                    )
                } returns 1234L

                val result =
                    provider.downloadFromUrl(
                        "builtin:downloads",
                        "file.txt",
                        "https://example.com/f",
                    )

                assertEquals(1234L, result)
                coVerify {
                    mockMediaStoreFileOperations.downloadFromUrl(
                        "builtin:downloads",
                        "file.txt",
                        "https://example.com/f",
                    )
                }
            }

        @Test
        fun `deleteFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.deleteFile("builtin:downloads", "test.txt")
                } returns Unit

                provider.deleteFile("builtin:downloads", "test.txt")

                coVerify { mockMediaStoreFileOperations.deleteFile("builtin:downloads", "test.txt") }
            }

        @Test
        fun `createFileUri routes to MediaStore for builtin ID`() =
            runTest {
                val mockUri = mockk<Uri>()
                coEvery {
                    mockMediaStoreFileOperations.createFileUri("builtin:downloads", "test.jpg", "image/jpeg")
                } returns mockUri

                val result = provider.createFileUri("builtin:downloads", "test.jpg", "image/jpeg")

                assertEquals(mockUri, result)
                coVerify {
                    mockMediaStoreFileOperations.createFileUri("builtin:downloads", "test.jpg", "image/jpeg")
                }
            }
    }

    @Nested
    @DisplayName("path traversal validation (non-builtin locations)")
    inner class PathTraversalValidation {
        // The guard runs before authorization and resolution, so a bad path is rejected
        // for every entry point regardless of mock setup. "loc1" is a non-builtin location.

        @Test
        fun `listFiles rejects parent traversal segment`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        provider.listFiles("loc1", "../secrets", 0, 10)
                    }
                assertTrue(exception.message!!.contains(".."))
            }

        @Test
        fun `readFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.readFile("loc1", "sub/../../etc/hosts", 1, 10)
                }
            }

        @Test
        fun `readFileBytes rejects absolute path`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        provider.readFileBytes("loc1", "/etc/hosts", TEN_MB)
                    }
                assertTrue(exception.message!!.contains("absolute"))
            }

        @Test
        fun `writeFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.writeFile("loc1", "../escape.txt", "data")
                }
            }

        @Test
        fun `appendFile rejects current-directory segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.appendFile("loc1", "./relative.txt", "data")
                }
            }

        @Test
        fun `replaceInFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.replaceInFile("loc1", "../file.txt", "a", "b", false)
                }
            }

        @Test
        fun `downloadFromUrl rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.downloadFromUrl("loc1", "../evil.bin", "https://example.com/f")
                }
            }

        @Test
        fun `deleteFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.deleteFile("loc1", "../../etc/passwd")
                }
            }

        @Test
        fun `createFileUri rejects control-character segment`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        provider.createFileUri("loc1", "bad\u0000name.txt", "text/plain")
                    }
                assertTrue(exception.message!!.contains("control"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // diskUsage
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("diskUsage")
    inner class DiskUsage {
        @Test
        fun `diskUsage aggregates the whole subtree`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                every { rootDoc.isDirectory } returns true

                val subDoc = mockk<DocumentFile>()
                stubChildren(
                    rootDoc,
                    listOf(
                        TestChild("a.bin", size = 100L),
                        TestChild("b.bin", size = 200L),
                        TestChild("sub", isDirectory = true, document = subDoc),
                    ),
                )
                stubChildren(subDoc, listOf(TestChild("c.bin", size = 700L)))

                val result = provider.diskUsage("loc1", "", 1)

                assertEquals(1000L, result.root.totalBytes)
                assertEquals(3, result.root.fileCount)
                assertFalse(result.truncated)
                assertTrue(result.complete)
                assertEquals(1, result.root.children.size)
                assertEquals("sub", result.root.children[0].path)
                assertEquals(700L, result.root.children[0].totalBytes)
            }

        @Test
        fun `diskUsage totals stay complete below the requested depth`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                every { rootDoc.isDirectory } returns true

                val subDoc = mockk<DocumentFile>()
                val deepDoc = mockk<DocumentFile>()
                stubChildren(rootDoc, listOf(TestChild("sub", isDirectory = true, document = subDoc)))
                stubChildren(subDoc, listOf(TestChild("deep", isDirectory = true, document = deepDoc)))
                stubChildren(deepDoc, listOf(TestChild("buried.bin", size = 4096L)))

                // maxDepth 0 reports no breakdown at all, but the total still covers `deep`.
                val result = provider.diskUsage("loc1", "", 0)

                assertEquals(4096L, result.root.totalBytes)
                assertEquals(1, result.root.fileCount)
                assertTrue(result.root.children.isEmpty())
            }

        @Test
        fun `diskUsage reads each directory exactly once`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                every { rootDoc.isDirectory } returns true

                val subDoc = mockk<DocumentFile>()
                stubChildren(
                    rootDoc,
                    (1..LIST_ENTRIES_ABOVE_MAX).map { TestChild("f$it.bin", size = 1L) } +
                        TestChild("sub", isDirectory = true, document = subDoc),
                )
                stubChildren(subDoc, (1..LIST_ENTRIES_ABOVE_MAX).map { TestChild("g$it.bin", size = 1L) })

                provider.diskUsage("loc1", "", 1)

                // The regression this guards: reading a directory through DocumentFile costs a
                // query per child per attribute, which is what made disk_usage time out on a real
                // media folder. Two directories must cost two queries, whatever their size.
                verify(exactly = 2) { mockContentResolver.query(any(), any(), any(), any(), any()) }
            }

        @Test
        fun `diskUsage throws InvalidParams when the path is not a directory`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                every { rootDoc.isDirectory } returns true
                val fileDoc = mockk<DocumentFile> { every { isDirectory } returns false }
                stubChildren(rootDoc, listOf(TestChild("notadir.txt", document = fileDoc)))

                assertThrows<McpToolException.InvalidParams> {
                    provider.diskUsage("loc1", "notadir.txt", 1)
                }
            }

        @Test
        fun `diskUsage throws PermissionDenied for unauthorized location`() =
            runTest {
                setupUnauthorizedLocation("loc1")

                assertThrows<McpToolException.PermissionDenied> {
                    provider.diskUsage("loc1", "", 1)
                }
            }

        @Test
        fun `diskUsage routes to MediaStore for builtin ID`() =
            runTest {
                val expected =
                    com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageResult(
                        root =
                            com.danielealbano.androidremotecontrolmcp.data.model.DiskUsageNode(
                                path = "",
                                totalBytes = 42L,
                                fileCount = 1,
                                children = emptyList(),
                            ),
                        truncated = false,
                        complete = true,
                    )
                coEvery { mockMediaStoreFileOperations.diskUsage("builtin:pictures", "", 1) } returns expected

                val result = provider.diskUsage("builtin:pictures", "", 1)

                assertEquals(42L, result.root.totalBytes)
                coVerify(exactly = 1) { mockMediaStoreFileOperations.diskUsage("builtin:pictures", "", 1) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Directory enumeration cost
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("directory enumeration cost")
    inner class DirectoryEnumerationCost {
        @Test
        fun `listFiles reads the directory with a single query`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true
                stubChildren(mockDir, (1..LIST_ENTRIES_ABOVE_MAX).map { TestChild("f%03d.bin".format(it)) })

                provider.listFiles("loc1", "", 0, 10)

                // Sorting by name used to query the provider on every comparison.
                verify(exactly = 1) { mockContentResolver.query(any(), any(), any(), any(), any()) }
            }

        @Test
        fun `resolving a nested path costs one query per segment`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val subDoc = mockk<DocumentFile>()
                val leafDoc = mockk<DocumentFile> { every { isDirectory } returns true }
                stubChildren(
                    rootDoc,
                    (1..LIST_ENTRIES_ABOVE_MAX).map { TestChild("noise$it.bin") } +
                        TestChild("sub", isDirectory = true, document = subDoc),
                )
                stubChildren(subDoc, listOf(TestChild("leaf", isDirectory = true, document = leafDoc)))
                stubChildren(leafDoc, listOf(TestChild("found.txt", size = 7L)))

                val result = provider.listFiles("loc1", "sub/leaf", 0, 10)

                assertEquals(1, result.files.size)
                assertEquals("found.txt", result.files[0].name)
                assertEquals(7L, result.files[0].size)
                // Two segments to walk plus the listing itself — not one per child scanned.
                verify(exactly = 3) { mockContentResolver.query(any(), any(), any(), any(), any()) }
            }
    }

    companion object {
        private const val BYTES_PER_MB = 1024 * 1024
        private const val DEFAULT_FILE_SIZE_LIMIT_MB = 50
        private const val FILE_SIZE_EXCEEDING_LIMIT = 100_000_000L
        private const val TEN_MB = 10L * 1024 * 1024
        private const val LIST_ENTRIES_ABOVE_MAX = 210
        private const val LINES_ABOVE_MAX = 210
        private const val REQUESTED_LIMIT_ABOVE_MAX = 300
    }
}
