package com.aos.chatbot.routes

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.parsers.UnreadableDocumentException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.aos.chatbot.routes.dto.DocumentListResponse
import com.aos.chatbot.routes.dto.DuplicateDocumentResponse
import com.aos.chatbot.routes.dto.EmptyDocumentResponse
import com.aos.chatbot.routes.dto.InvalidUploadResponse
import com.aos.chatbot.routes.dto.OllamaUnavailableResponse
import com.aos.chatbot.routes.dto.ReindexAlreadyRunningResponse
import com.aos.chatbot.routes.dto.ReindexInProgressResponse
import com.aos.chatbot.routes.dto.ReindexStartedResponse
import com.aos.chatbot.routes.dto.UnreadableDocumentResponse
import com.aos.chatbot.services.BackfillStatus
import com.aos.chatbot.services.DocumentService
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.EmptyDocumentException
import com.aos.chatbot.services.InvalidUploadException
import com.aos.chatbot.services.OllamaUnavailableException
import com.aos.chatbot.services.UploadResult
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val MAX_UPLOAD_SIZE = 100L * 1024 * 1024 // 100 MB
private val logger = LoggerFactory.getLogger("AdminRoutes")

fun Route.adminRoutes(
    documentService: DocumentService,
    database: Database,
    backfillJob: EmbeddingBackfillJob,
    applicationScope: CoroutineScope,
    documentsPath: String = "",
    imagesPath: String = ""
) {
    route("/api/admin") {
        post("/documents") {
            if (backfillJob.isRunning()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ReindexInProgressResponse(
                        message = "Reindex is running; uploads are temporarily blocked. Please retry shortly."
                    )
                )
                return@post
            }
            var filename: String? = null
            var fileBytes: ByteArray? = null
            var oversize = false

            val multipart = try {
                call.receiveMultipart()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    InvalidUploadResponse(
                        reason = "invalid_content_type",
                        message = "Request must be multipart/form-data"
                    )
                )
                return@post
            }
            try {
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            filename = part.originalFileName
                            val stream = part.streamProvider()
                            val buffer = java.io.ByteArrayOutputStream()
                            val readBuf = ByteArray(8192)
                            var totalRead = 0L
                            while (true) {
                                val n = stream.read(readBuf)
                                if (n == -1) break
                                totalRead += n
                                if (totalRead > MAX_UPLOAD_SIZE) {
                                    oversize = true
                                    break
                                }
                                buffer.write(readBuf, 0, n)
                            }
                            stream.close()
                            if (!oversize) {
                                fileBytes = buffer.toByteArray()
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidUploadResponse(
                        reason = "malformed_multipart",
                        message = "Malformed multipart request body"
                    )
                )
                return@post
            }

            if (oversize) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    InvalidUploadResponse(
                        reason = "file_too_large",
                        message = "File exceeds maximum upload size of ${MAX_UPLOAD_SIZE / (1024 * 1024)} MB"
                    )
                )
                return@post
            }

            val actualFilename = filename ?: ""
            val actualBytes = fileBytes ?: ByteArray(0)

            try {
                when (val result = documentService.processDocument(actualFilename, actualBytes)) {
                    is UploadResult.Created -> {
                        call.respond(HttpStatusCode.Created, result.document)
                    }
                    is UploadResult.Duplicate -> {
                        call.respond(
                            HttpStatusCode.Conflict,
                            DuplicateDocumentResponse(
                                message = "Document already exists",
                                existing = result.document
                            )
                        )
                    }
                }
            } catch (e: InvalidUploadException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidUploadResponse(
                        reason = e.reason,
                        message = e.message ?: "Invalid upload"
                    )
                )
            } catch (e: UnreadableDocumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    UnreadableDocumentResponse(
                        reason = "${e.reason.code}_${e.fileType}",
                        message = "Unable to read ${e.fileType} document: ${e.reason.code}"
                    )
                )
            } catch (e: EmptyDocumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    EmptyDocumentResponse(
                        message = e.message ?: "Document contains no extractable content"
                    )
                )
            } catch (e: OllamaUnavailableException) {
                // Inline embedding (DocumentService) raises this when Ollama is
                // unreachable. Surface it as a dependency-specific 503 so ops
                // can distinguish a transient outage from an internal bug,
                // rather than falling through to the generic 500 handler.
                logger.warn("Upload failed because Ollama is unavailable: {}", e.message)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    OllamaUnavailableResponse()
                )
            }
        }

        get("/documents") {
            val documents = withContext(Dispatchers.IO) {
                database.connect().use { conn ->
                    DocumentRepository(conn).findAll()
                }
            }
            call.respond(HttpStatusCode.OK, DocumentListResponse(documents = documents, total = documents.size))
        }

        delete("/documents/{id}") {
            // Fail fast during reindex rather than blocking on the reindex/upload
            // mutex (which DocumentService.deleteDocument acquires). A reindex
            // can take minutes — hanging here would trip client timeouts.
            if (backfillJob.isRunning()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ReindexInProgressResponse(
                        message = "Reindex is running; deletes are temporarily blocked. Please retry shortly."
                    )
                )
                return@delete
            }
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid document ID"))

            // Delegates DB delete + in-memory search-index removal to the service.
            // Bypassing this (going straight to DocumentRepository) would leave
            // chunks of the deleted document in SearchService until restart.
            val doc = documentService.deleteDocument(id)

            if (doc == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Document not found"))
                return@delete
            }

            // Clean up source file
            if (documentsPath.isNotEmpty()) {
                val sourceFile = Path.of(documentsPath, "${doc.fileHash}.${doc.fileType}")
                runCatching { Files.deleteIfExists(sourceFile) }
                    .onFailure { logger.warn("Failed to delete source file {}: {}", sourceFile, it.message) }
            }
            // Clean up image directory
            if (imagesPath.isNotEmpty()) {
                val imageDir = Path.of(imagesPath, id.toString())
                if (Files.exists(imageDir)) {
                    runCatching {
                        Files.walk(imageDir).use { stream ->
                            stream.sorted(Comparator.reverseOrder())
                                .forEach { Files.deleteIfExists(it) }
                        }
                    }.onFailure { logger.warn("Failed to delete image directory {}: {}", imageDir, it.message) }
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }

        post("/reindex") {
            // Refuse while a reindex is in flight OR the startup backfill has not
            // yet reached a terminal state. Without the status check, a reindex
            // launched during startup races the backfill loop and can NULL
            // embeddings the backfill is about to write. Failed is terminal and
            // must be eligible — /api/chat's BackfillFailedResponse instructs
            // operators to recover via this route.
            val status = backfillJob.status()
            if (backfillJob.isRunning() ||
                (status !is BackfillStatus.Completed && status !is BackfillStatus.Failed)
            ) {
                call.respond(HttpStatusCode.Accepted, ReindexAlreadyRunningResponse())
                return@post
            }
            applicationScope.launch {
                try {
                    backfillJob.clearAndReindex()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logger.error("Reindex job failed: {}", e.message, e)
                }
            }
            call.respond(HttpStatusCode.Accepted, ReindexStartedResponse())
        }
    }
}
