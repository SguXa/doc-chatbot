package com.aos.chatbot.routes

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.parsers.UnreadableDocumentException
import java.nio.file.Files
import java.nio.file.Path
import com.aos.chatbot.routes.dto.DocumentListResponse
import com.aos.chatbot.routes.dto.DuplicateDocumentResponse
import com.aos.chatbot.routes.dto.EmptyDocumentResponse
import com.aos.chatbot.routes.dto.InvalidUploadResponse
import com.aos.chatbot.routes.dto.UnreadableDocumentResponse
import com.aos.chatbot.services.DocumentService
import com.aos.chatbot.services.EmptyDocumentException
import com.aos.chatbot.services.InvalidUploadException
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

fun Route.adminRoutes(documentService: DocumentService, database: Database, documentsPath: String = "", imagesPath: String = "") {
    route("/api/admin") {
        post("/documents") {
            var filename: String? = null
            var fileBytes: ByteArray? = null
            var oversize = false

            val multipart = call.receiveMultipart()
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
                        reason = e.reason.code,
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
            }
        }

        get("/documents") {
            database.connect().use { conn ->
                val documents = DocumentRepository(conn).findAll()
                call.respond(HttpStatusCode.OK, DocumentListResponse(documents = documents, total = documents.size))
            }
        }

        delete("/documents/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid document ID"))

            database.connect().use { conn ->
                val repo = DocumentRepository(conn)
                val doc = repo.findById(id)
                if (doc == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Document not found"))
                    return@use
                }
                repo.delete(id)

                // Clean up source file
                if (documentsPath.isNotEmpty()) {
                    val sourceFile = Path.of(documentsPath, "${doc.fileHash}.${doc.fileType}")
                    runCatching { Files.deleteIfExists(sourceFile) }
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
                        }
                    }
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
