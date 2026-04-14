package com.aos.chatbot

import com.aos.chatbot.config.AppConfig
import com.aos.chatbot.config.AppMode
import com.aos.chatbot.config.DatabaseConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.parsers.ChunkingService
import com.aos.chatbot.parsers.ParserFactory
import com.aos.chatbot.parsers.aos.AosParser
import com.aos.chatbot.routes.adminRoutes
import com.aos.chatbot.routes.healthRoutes
import com.aos.chatbot.services.DocumentService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

fun Application.module() {
    val appConfig = AppConfig.from(environment)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<Throwable> { call, cause ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            if (cause is Error) throw cause
            this@module.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    val dbConfig = DatabaseConfig(appConfig)
    val connection = dbConfig.initialize()

    // Cleanup orphan temp files after migrations, before route registration
    if (appConfig.mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
        val cleanedCount = cleanupOrphanTempFiles(appConfig.documentsPath, appConfig.imagesPath)
        val logger = LoggerFactory.getLogger("com.aos.chatbot.Application")
        logger.info("Startup temp-file cleanup: {} orphaned temp files removed (sources: {}, images: {})",
            cleanedCount.total, cleanedCount.sources, cleanedCount.images)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        connection.close()
    }

    // Wire stateless dependencies
    val database = Database(appConfig.databasePath)
    val parserFactory = ParserFactory()
    val aosParser = AosParser()
    val chunkingService = ChunkingService()
    val documentService = DocumentService(
        database = database,
        parserFactory = parserFactory,
        aosParser = aosParser,
        chunkingService = chunkingService,
        documentsPath = appConfig.documentsPath,
        imagesPath = appConfig.imagesPath
    )

    routing {
        healthRoutes(connection)
        if (appConfig.mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
            adminRoutes(documentService, database)
        }
    }

    if (appConfig.mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
        log.warn("Phase 2 admin routes are unprotected — auth is deferred to Phase 4. Restrict this deployment to internal networks.")
    }

    log.info("AOS Chatbot started in ${appConfig.mode} mode on ${appConfig.host}:${appConfig.port}")
}

data class CleanupResult(val sources: Int, val images: Int) {
    val total: Int get() = sources + images
}

fun cleanupOrphanTempFiles(documentsPath: String, imagesPath: String): CleanupResult {
    val tempFilePattern = Regex(""".*\.tmp\..*""")
    var sourceCount = 0
    var imageCount = 0

    // Scan documentsPath root for *.tmp.* files (no recursion)
    val docsDir = Path.of(documentsPath)
    if (Files.exists(docsDir) && Files.isDirectory(docsDir)) {
        Files.list(docsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && tempFilePattern.matches(it.fileName.toString()) }
                .forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                    sourceCount++
                }
        }
    }

    // Scan imagesPath one level deep (per-document subdirs)
    val imgsDir = Path.of(imagesPath)
    if (Files.exists(imgsDir) && Files.isDirectory(imgsDir)) {
        Files.list(imgsDir).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { subdir ->
                Files.list(subdir).use { fileStream ->
                    fileStream.filter { Files.isRegularFile(it) && tempFilePattern.matches(it.fileName.toString()) }
                        .forEach { path ->
                            runCatching { Files.deleteIfExists(path) }
                            imageCount++
                        }
                }
            }
        }
    }

    return CleanupResult(sourceCount, imageCount)
}
