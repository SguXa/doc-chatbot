package com.aos.chatbot

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupOrphanTempFilesTest {

    private lateinit var tempDir: Path
    private lateinit var documentsPath: Path
    private lateinit var imagesPath: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("cleanup-test")
        documentsPath = tempDir.resolve("documents")
        imagesPath = tempDir.resolve("images")
        Files.createDirectories(documentsPath)
        Files.createDirectories(imagesPath)
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    @Test
    fun `deletes temp files from documentsPath root`() {
        val tempFile = documentsPath.resolve("abc123.docx.tmp.550e8400-e29b-41d4-a716-446655440000")
        Files.writeString(tempFile, "temp")
        val finalFile = documentsPath.resolve("abc123.docx")
        Files.writeString(finalFile, "final")

        val result = cleanupOrphanTempFiles(documentsPath.toString(), imagesPath.toString())

        assertEquals(1, result.sources)
        assertEquals(0, result.images)
        assertEquals(1, result.total)
        assertFalse(Files.exists(tempFile))
        assertTrue(Files.exists(finalFile))
    }

    @Test
    fun `deletes temp files from imagesPath one level deep`() {
        val docSubdir = imagesPath.resolve("42")
        Files.createDirectories(docSubdir)
        val tempImg = docSubdir.resolve("img_001.png.tmp.550e8400-e29b-41d4-a716-446655440000")
        Files.writeString(tempImg, "temp")
        val finalImg = docSubdir.resolve("img_001.png")
        Files.writeString(finalImg, "final")

        val result = cleanupOrphanTempFiles(documentsPath.toString(), imagesPath.toString())

        assertEquals(0, result.sources)
        assertEquals(1, result.images)
        assertEquals(1, result.total)
        assertFalse(Files.exists(tempImg))
        assertTrue(Files.exists(finalImg))
    }

    @Test
    fun `does not delete files without tmp infix`() {
        Files.writeString(documentsPath.resolve("abc123.docx"), "final")
        Files.writeString(documentsPath.resolve("abc123.pdf"), "final")

        val result = cleanupOrphanTempFiles(documentsPath.toString(), imagesPath.toString())

        assertEquals(0, result.total)
    }

    @Test
    fun `handles missing directories gracefully`() {
        val result = cleanupOrphanTempFiles(
            tempDir.resolve("nonexistent-docs").toString(),
            tempDir.resolve("nonexistent-imgs").toString()
        )

        assertEquals(0, result.total)
    }

    @Test
    fun `deletes temp files from both sources and images`() {
        Files.writeString(documentsPath.resolve("file.docx.tmp.uuid1"), "temp")
        Files.writeString(documentsPath.resolve("file2.pdf.tmp.uuid2"), "temp")
        val docSubdir = imagesPath.resolve("1")
        Files.createDirectories(docSubdir)
        Files.writeString(docSubdir.resolve("img.png.tmp.uuid3"), "temp")

        val result = cleanupOrphanTempFiles(documentsPath.toString(), imagesPath.toString())

        assertEquals(2, result.sources)
        assertEquals(1, result.images)
        assertEquals(3, result.total)
    }

    @Test
    fun `does not recurse deeper than one level in imagesPath`() {
        val docSubdir = imagesPath.resolve("1")
        val nestedDir = docSubdir.resolve("nested")
        Files.createDirectories(nestedDir)
        Files.writeString(nestedDir.resolve("deep.tmp.uuid"), "temp")

        val result = cleanupOrphanTempFiles(documentsPath.toString(), imagesPath.toString())

        assertEquals(0, result.total)
        // Deep file should still exist
        assertTrue(Files.exists(nestedDir.resolve("deep.tmp.uuid")))
    }

    @Test
    fun `does not delete temp files at imagesPath root level`() {
        Files.writeString(imagesPath.resolve("root.tmp.uuid"), "temp")

        val result = cleanupOrphanTempFiles(documentsPath.toString(), imagesPath.toString())

        // Root-level files in imagesPath are not scanned (only per-document subdirs)
        assertEquals(0, result.images)
        assertTrue(Files.exists(imagesPath.resolve("root.tmp.uuid")))
    }
}
