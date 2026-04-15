package com.aos.chatbot.services

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.repositories.ChunkRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.db.repositories.ImageRepository
import com.aos.chatbot.models.Chunk
import com.aos.chatbot.models.Document
import com.aos.chatbot.models.ImageData
import com.aos.chatbot.models.ParsedContent
import com.aos.chatbot.models.TextBlock
import com.aos.chatbot.parsers.ChunkingService
import com.aos.chatbot.parsers.ImageExtractor
import com.aos.chatbot.parsers.ParserFactory
import com.aos.chatbot.parsers.aos.AosParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class DocumentService(
    private val database: Database,
    private val parserFactory: ParserFactory,
    private val aosParser: AosParser,
    private val chunkingService: ChunkingService,
    private val documentsPath: String,
    private val imagesPath: String
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)

    suspend fun processDocument(originalFilename: String, bytes: ByteArray): UploadResult =
        withContext(Dispatchers.IO) {
            // Validation step
            val trimmedFilename = Path.of(originalFilename.trim()).fileName?.toString()?.trim() ?: ""
            if (trimmedFilename.isEmpty()) {
                throw InvalidUploadException("missing_filename", "Filename must not be empty")
            }
            if (bytes.isEmpty()) {
                throw InvalidUploadException("empty_file", "File must not be empty")
            }
            val extension = trimmedFilename.substringAfterLast('.', "").lowercase()
            if (extension !in listOf("docx", "pdf")) {
                throw InvalidUploadException("unsupported_extension", "Unsupported file extension: $extension")
            }

            // Hash step
            val hash = sha256(bytes)

            // Dedup pre-check
            database.connect().use { conn ->
                val existing = DocumentRepository(conn).findByHash(hash)
                if (existing != null) {
                    return@withContext UploadResult.Duplicate(existing)
                }
            }

            // Source file write (temp + atomic move)
            val sourceFileName = "$hash.$extension"
            val docsDir = Path.of(documentsPath)
            Files.createDirectories(docsDir)
            val finalPath = docsDir.resolve(sourceFileName)
            val tempPath = Path.of("${finalPath}.tmp.${UUID.randomUUID()}")
            // Track whether this request owns the hash-named file. When we lose
            // a move race (another thread already placed an identical file), we
            // must not delete finalPath on later errors — it belongs to the winner.
            var ownsSourceFile = false
            try {
                Files.write(tempPath, bytes)
                try {
                    Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
                    ownsSourceFile = true
                } catch (moveEx: Exception) {
                    // On some filesystems ATOMIC_MOVE does not support replacing an existing
                    // target. Since the filename is the content hash, an existing target has
                    // identical content — just discard the temp file and continue. The DB
                    // unique constraint (line ~139) will still surface Duplicate correctly.
                    if (Files.exists(finalPath)) {
                        runCatching { Files.deleteIfExists(tempPath) }
                    } else {
                        throw moveEx
                    }
                }
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tempPath) }
                throw e
            }

            // Parse step
            val sourceFile = finalPath.toFile()
            val parsed: ParsedContent
            try {
                parsed = parserFactory.getParser(trimmedFilename).parse(sourceFile)
            } catch (e: Exception) {
                if (ownsSourceFile) runCatching { Files.deleteIfExists(finalPath) }
                throw e
            }

            // AOS post-processing
            val processed: ParsedContent
            try {
                processed = aosParser.process(parsed)
            } catch (e: Exception) {
                if (ownsSourceFile) runCatching { Files.deleteIfExists(finalPath) }
                throw e
            }

            // Chunking step
            val chunkedBlocks: List<TextBlock>
            try {
                chunkedBlocks = chunkingService.chunk(processed.textBlocks)
            } catch (e: Exception) {
                if (ownsSourceFile) runCatching { Files.deleteIfExists(finalPath) }
                throw e
            }

            // Empty-content check
            if (chunkedBlocks.isEmpty() && processed.images.isEmpty()) {
                if (ownsSourceFile) runCatching { Files.deleteIfExists(finalPath) }
                throw EmptyDocumentException()
            }

            // Image linkage validation
            try {
                validateImageLinkage(chunkedBlocks, processed.images)
            } catch (e: Exception) {
                if (ownsSourceFile) runCatching { Files.deleteIfExists(finalPath) }
                throw e
            }

            // Persist phase
            val document = Document(
                filename = trimmedFilename,
                fileType = extension,
                fileSize = bytes.size.toLong(),
                fileHash = hash
            )

            var documentId: Long? = null
            var conn: Connection? = null
            var committed = false
            try {
                conn = database.connect()
                conn.autoCommit = false

                val docRepo = DocumentRepository(conn)
                val inserted: Document
                try {
                    inserted = docRepo.insert(document)
                } catch (e: SQLException) {
                    if (e.message?.contains("UNIQUE constraint failed") == true) {
                        conn.rollback()
                        conn.close()
                        // Do NOT delete finalPath here — the winning thread's DB row
                        // references this same hash-named file with identical content.
                        // Race-condition: another thread inserted the same hash
                        database.connect().use { lookupConn ->
                            val existing = DocumentRepository(lookupConn).findByHash(hash)
                                ?: throw IllegalStateException("Race duplicate detected but row not found by hash")
                            return@withContext UploadResult.Duplicate(existing)
                        }
                    }
                    throw e
                }
                documentId = inserted.id

                // Save images BEFORE chunks
                val imageExtractor = ImageExtractor(imagesPath, ImageRepository(conn))
                imageExtractor.saveImages(documentId, processed.images)

                // Insert chunks
                val chunks = chunkedBlocks.map { block ->
                    Chunk(
                        documentId = documentId,
                        content = block.content,
                        contentType = block.type,
                        pageNumber = block.pageNumber,
                        sectionId = block.sectionId,
                        heading = block.heading,
                        imageRefs = block.imageRefs
                    )
                }
                ChunkRepository(conn).insertBatch(chunks)

                // Update counts and indexedAt
                docRepo.updateAfterIndexing(documentId, chunks.size, processed.images.size)

                conn.commit()
                committed = true

                // If a racing uploader that owned the file deleted it after
                // a transient error, restore the source from the original
                // bytes (identical content — same hash).
                if (!ownsSourceFile && !Files.exists(finalPath)) {
                    try {
                        Files.write(finalPath, bytes)
                    } catch (repairEx: Exception) {
                        logger.warn("Failed to restore source file {} after commit — document {} may reference a missing file", finalPath, documentId, repairEx)
                    }
                }

                // Re-read committed state
                val finalDoc = docRepo.findById(documentId)
                    ?: throw IllegalStateException("Document $documentId missing after successful commit")
                conn.close()
                conn = null

                UploadResult.Created(finalDoc)
            } catch (e: Exception) {
                // Rollback / compensation — only clean up if transaction was NOT committed
                if (!committed) {
                    try {
                        conn?.rollback()
                    } catch (_: Exception) {
                    }

                    // Delete source file only if this request placed it
                    if (ownsSourceFile) runCatching { Files.deleteIfExists(finalPath) }

                    // Delete image directory if it was created
                    if (documentId != null) {
                        val imageDir = Path.of(imagesPath, documentId.toString())
                        if (Files.exists(imageDir)) {
                            runCatching {
                                Files.walk(imageDir).use { stream ->
                                    stream.sorted(Comparator.reverseOrder())
                                        .forEach { Files.deleteIfExists(it) }
                                }
                            }
                        }
                    }
                }

                try {
                    conn?.close()
                } catch (_: Exception) {
                }

                throw e
            }
        }

    private fun validateImageLinkage(chunks: List<TextBlock>, images: List<ImageData>) {
        val imageFilenames = images.map { it.filename }.toSet()
        val referencedFilenames = chunks.flatMap { it.imageRefs }.toSet()

        // Every chunk ref must point to a real image
        val danglingRefs = referencedFilenames - imageFilenames
        if (danglingRefs.isNotEmpty()) {
            throw IllegalStateException("Image linkage validation failed: dangling refs $danglingRefs")
        }

        // Every image must be referenced by at least one chunk
        val unreferencedImages = imageFilenames - referencedFilenames
        if (unreferencedImages.isNotEmpty()) {
            throw IllegalStateException("Image linkage validation failed: unreferenced images $unreferencedImages")
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
