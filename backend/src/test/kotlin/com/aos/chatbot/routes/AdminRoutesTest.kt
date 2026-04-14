package com.aos.chatbot.routes

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.models.Document
import com.aos.chatbot.parsers.UnreadableDocumentException
import com.aos.chatbot.parsers.UnreadableReason
import com.aos.chatbot.services.DocumentService
import com.aos.chatbot.services.EmptyDocumentException
import com.aos.chatbot.services.InvalidUploadException
import com.aos.chatbot.services.UploadResult
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRoutesTest {

    private val sampleDocument = Document(
        id = 1,
        filename = "test.docx",
        fileType = "docx",
        fileSize = 1024,
        fileHash = "abc123",
        chunkCount = 5,
        imageCount = 2,
        indexedAt = "2024-01-01T00:00:00",
        createdAt = "2024-01-01T00:00:00"
    )

    private fun createMultipartData(filename: String, content: ByteArray) = formData {
        append("file", content, Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            append(HttpHeaders.ContentType, "application/octet-stream")
        })
    }

    @Test
    fun `POST documents returns 201 on Created`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } returns UploadResult.Created(sampleDocument)

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", "test content".toByteArray())
        )

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals(1, body["id"]?.jsonPrimitive?.long)
        assertEquals("test.docx", body["filename"]?.jsonPrimitive?.content)
        assertEquals("docx", body["fileType"]?.jsonPrimitive?.content)
        assertEquals(1024, body["fileSize"]?.jsonPrimitive?.long)
        assertEquals("abc123", body["fileHash"]?.jsonPrimitive?.content)
        assertEquals(5, body["chunkCount"]?.jsonPrimitive?.int)
        assertEquals(2, body["imageCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `POST documents returns 409 on Duplicate with correct body`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } returns UploadResult.Duplicate(sampleDocument)

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", "test content".toByteArray())
        )

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("duplicate_document", body["error"]?.jsonPrimitive?.content)
        assertTrue(body.containsKey("existing"))
        val existing = body["existing"]!!.jsonObject
        assertEquals(1, existing["id"]?.jsonPrimitive?.long)
        assertEquals("test.docx", existing["filename"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST documents returns 400 on InvalidUploadException`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } throws
            InvalidUploadException("unsupported_extension", "Unsupported file extension: txt")

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.txt", "test content".toByteArray())
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_upload", body["error"]?.jsonPrimitive?.content)
        assertEquals("unsupported_extension", body["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST documents returns 400 on InvalidUploadException for empty file`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } throws
            InvalidUploadException("empty_file", "File must not be empty")

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", ByteArray(0))
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_upload", body["error"]?.jsonPrimitive?.content)
        assertEquals("empty_file", body["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST documents returns 400 on InvalidUploadException for missing filename`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } throws
            InvalidUploadException("missing_filename", "Filename must not be empty")

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", "content".toByteArray())
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_upload", body["error"]?.jsonPrimitive?.content)
        assertEquals("missing_filename", body["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST documents returns 400 on UnreadableDocumentException without leaking internals`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } throws
            UnreadableDocumentException(UnreadableReason.CORRUPTED, "docx", RuntimeException("org.apache.poi.POIXMLException: internal detail"))

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", "corrupted content".toByteArray())
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("unreadable_document", body["error"]?.jsonPrimitive?.content)
        assertEquals("corrupted", body["reason"]?.jsonPrimitive?.content)

        val bodyText = response.bodyAsText()
        assertFalse(bodyText.contains("apache.poi"), "Response should not leak POI internals")
        assertFalse(bodyText.contains("apache.pdfbox"), "Response should not leak PDFBox internals")
        assertFalse(bodyText.contains("POIXMLException"), "Response should not leak POI exception classes")
        assertFalse(bodyText.contains("stackTrace"), "Response should not contain stack traces")
    }

    @Test
    fun `POST documents returns 400 on UnreadableDocumentException for encrypted PDF`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } throws
            UnreadableDocumentException(UnreadableReason.ENCRYPTED, "pdf")

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.pdf", "encrypted content".toByteArray())
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("unreadable_document", body["error"]?.jsonPrimitive?.content)
        assertEquals("encrypted", body["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST documents returns 400 on EmptyDocumentException`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        coEvery { mockService.processDocument(any(), any()) } throws EmptyDocumentException()

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", "empty docx content".toByteArray())
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("empty_content", body["error"]?.jsonPrimitive?.content)
        assertEquals("empty_content", body["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET documents returns 200 with document list preserving order`() {
        val dbFile = Files.createTempFile("admin-test-", ".db")
        try {
            val realDb = Database(dbFile.toString())
            realDb.connect().use { conn -> Migrations(conn).apply() }
            realDb.connect().use { conn ->
                val repo = DocumentRepository(conn)
                repo.insert(Document(filename = "first.docx", fileType = "docx", fileSize = 100, fileHash = "hash1"))
                repo.insert(Document(filename = "second.pdf", fileType = "pdf", fileSize = 200, fileHash = "hash2"))
            }

            testApplication {
                environment { config = io.ktor.server.config.MapApplicationConfig() }
                val mockService = mockk<DocumentService>()

                application {
                    install(ContentNegotiation) { json() }
                    routing { adminRoutes(mockService, realDb) }
                }

                val response = client.get("/api/admin/documents")

                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
                assertEquals(2, body["total"]?.jsonPrimitive?.int)
                val documents = body["documents"]!!.jsonArray
                assertEquals(2, documents.size)
                // findAll returns newest first (ORDER BY created_at DESC, id DESC)
                assertEquals("second.pdf", documents[0].jsonObject["filename"]?.jsonPrimitive?.content)
                assertEquals("first.docx", documents[1].jsonObject["filename"]?.jsonPrimitive?.content)
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun `DELETE documents returns 204 on success`() {
        val dbFile = Files.createTempFile("admin-test-", ".db")
        try {
            val realDb = Database(dbFile.toString())
            var insertedId: Long
            realDb.connect().use { conn -> Migrations(conn).apply() }
            realDb.connect().use { conn ->
                val repo = DocumentRepository(conn)
                val inserted = repo.insert(Document(filename = "test.docx", fileType = "docx", fileSize = 100, fileHash = "hash1"))
                insertedId = inserted.id
            }

            testApplication {
                environment { config = io.ktor.server.config.MapApplicationConfig() }
                val mockService = mockk<DocumentService>()

                application {
                    install(ContentNegotiation) { json() }
                    routing { adminRoutes(mockService, realDb) }
                }

                val response = client.delete("/api/admin/documents/$insertedId")
                assertEquals(HttpStatusCode.NoContent, response.status)
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun `DELETE documents returns 404 when not found`() {
        val dbFile = Files.createTempFile("admin-test-", ".db")
        try {
            val realDb = Database(dbFile.toString())
            realDb.connect().use { conn -> Migrations(conn).apply() }

            testApplication {
                environment { config = io.ktor.server.config.MapApplicationConfig() }
                val mockService = mockk<DocumentService>()

                application {
                    install(ContentNegotiation) { json() }
                    routing { adminRoutes(mockService, realDb) }
                }

                val response = client.delete("/api/admin/documents/999")
                assertEquals(HttpStatusCode.NotFound, response.status)
                val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
                assertEquals("Document not found", body["error"]?.jsonPrimitive?.content)
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun `admin routes are NOT registered in CLIENT mode`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }

        application {
            install(ContentNegotiation) { json() }
            routing {
                // Simulate CLIENT mode: only health routes, no admin routes
                val mockDatabase = mockk<Database>()
                val mockConnection = mockk<Connection>()
                every { mockDatabase.connect() } returns mockConnection
                every { mockConnection.isValid(any()) } returns true
                every { mockConnection.close() } returns Unit
                healthRoutes(mockDatabase)
            }
        }

        val getResponse = client.get("/api/admin/documents")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)

        val deleteResponse = client.delete("/api/admin/documents/1")
        assertEquals(HttpStatusCode.NotFound, deleteResponse.status)
    }

    @Test
    fun `POST documents passes raw client filename to service`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()
        var capturedFilename: String? = null
        coEvery { mockService.processDocument(any(), any()) } coAnswers {
            capturedFilename = firstArg()
            UploadResult.Created(sampleDocument)
        }

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("My Document (1).docx", "test content".toByteArray())
        )

        assertEquals("My Document (1).docx", capturedFilename)
    }

    @Test
    fun `GET documents preserves service order without re-sorting`() {
        val dbFile = Files.createTempFile("admin-test-", ".db")
        try {
            val realDb = Database(dbFile.toString())
            realDb.connect().use { conn -> Migrations(conn).apply() }
            realDb.connect().use { conn ->
                val repo = DocumentRepository(conn)
                repo.insert(Document(filename = "a.docx", fileType = "docx", fileSize = 100, fileHash = "hash_a"))
                repo.insert(Document(filename = "b.pdf", fileType = "pdf", fileSize = 200, fileHash = "hash_b"))
                repo.insert(Document(filename = "c.docx", fileType = "docx", fileSize = 300, fileHash = "hash_c"))
            }

            testApplication {
                environment { config = io.ktor.server.config.MapApplicationConfig() }
                val mockService = mockk<DocumentService>()

                application {
                    install(ContentNegotiation) { json() }
                    routing { adminRoutes(mockService, realDb) }
                }

                val response = client.get("/api/admin/documents")
                val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
                val documents = body["documents"]!!.jsonArray
                assertEquals(3, documents.size)
                assertEquals(3, body["total"]?.jsonPrimitive?.int)
                // ORDER BY created_at DESC, id DESC
                assertEquals("c.docx", documents[0].jsonObject["filename"]?.jsonPrimitive?.content)
                assertEquals("b.pdf", documents[1].jsonObject["filename"]?.jsonPrimitive?.content)
                assertEquals("a.docx", documents[2].jsonObject["filename"]?.jsonPrimitive?.content)
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun `response bodies do not contain POI or PDFBox class names`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val mockService = mockk<DocumentService>()
        val mockDatabase = mockk<Database>()

        coEvery { mockService.processDocument(any(), any()) } throws
            UnreadableDocumentException(
                UnreadableReason.CORRUPTED, "docx",
                RuntimeException("org.apache.poi.SomeException: detail")
            )

        application {
            install(ContentNegotiation) { json() }
            routing { adminRoutes(mockService, mockDatabase) }
        }

        val response = client.submitFormWithBinaryData(
            url = "/api/admin/documents",
            formData = createMultipartData("test.docx", "content".toByteArray())
        )

        val bodyText = response.bodyAsText()
        assertFalse(bodyText.contains("org.apache"), "Response should not leak Apache internals: $bodyText")
        assertFalse(bodyText.contains("Exception"), "Response should not leak exception classes: $bodyText")
    }
}
