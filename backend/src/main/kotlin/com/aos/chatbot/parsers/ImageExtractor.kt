package com.aos.chatbot.parsers

import com.aos.chatbot.db.repositories.ImageRepository
import com.aos.chatbot.models.ExtractedImage
import com.aos.chatbot.models.ImageData
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Saves parsed images to disk and writes corresponding DB rows.
 *
 * Operation-scoped (NOT singleton). Instances must not outlive the injected [ImageRepository]
 * or its underlying connection.
 *
 * Uses an atomic temp+move pattern: each image is first written to a temporary file
 * (`{final}.tmp.{UUID}`), then atomically moved to its final path. The DB row is inserted
 * only after the file is safely on disk.
 */
class ImageExtractor(
    private val imagesBasePath: String,
    private val imageRepository: ImageRepository
) {
    private val logger = LoggerFactory.getLogger(ImageExtractor::class.java)

    /**
     * Saves all [images] to disk under `{imagesBasePath}/{documentId}/` and inserts
     * corresponding DB rows. Returns the list of persisted [ExtractedImage] records.
     *
     * Per-image order: file write (temp -> atomic move) FIRST, DB row insert SECOND.
     * IO errors throw immediately so the caller (DocumentService) can trigger rollback.
     */
    fun saveImages(documentId: Long, images: List<ImageData>): List<ExtractedImage> {
        if (images.isEmpty()) return emptyList()

        val docDir = Path.of(imagesBasePath, documentId.toString())
        Files.createDirectories(docDir)

        val results = mutableListOf<ExtractedImage>()
        for (image in images) {
            val finalPath = docDir.resolve(image.filename)
            val tempPath = Path.of("${finalPath}.tmp.${UUID.randomUUID()}")

            // Write to temp file
            try {
                Files.write(tempPath, image.data)
            } catch (e: java.io.IOException) {
                runCatching { Files.deleteIfExists(tempPath) }
                logger.error("Failed to write temp file for image '{}' of document {}", image.filename, documentId)
                throw e
            }

            // Atomic move temp -> final
            // Explicit pre-check: ATOMIC_MOVE on Linux silently replaces existing files,
            // so we enforce the no-overwrite contract manually.
            try {
                if (Files.exists(finalPath)) {
                    throw FileAlreadyExistsException(finalPath.toString())
                }
                Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileAlreadyExistsException) {
                logger.error(
                    "Image file already exists at '{}' for document {} — contract violation",
                    finalPath, documentId
                )
                runCatching { Files.deleteIfExists(tempPath) }
                throw e
            } catch (e: AtomicMoveNotSupportedException) {
                runCatching { Files.deleteIfExists(tempPath) }
                throw IllegalStateException(
                    "Atomic move not supported on this filesystem. " +
                        "Ensure imagesPath and temp files reside on the same filesystem.",
                    e
                )
            }

            // DB insert (only after file is safely on disk)
            val dbImage = imageRepository.insert(
                ExtractedImage(
                    documentId = documentId,
                    filename = image.filename,
                    path = finalPath.toString(),
                    pageNumber = image.pageNumber,
                    caption = image.caption
                )
            )
            results.add(dbImage)
        }

        return results
    }
}
