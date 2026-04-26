package com.aos.chatbot

import com.aos.chatbot.config.AppConfig
import com.aos.chatbot.config.AppMode
import com.aos.chatbot.config.DatabaseConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.parsers.ChunkingService
import com.aos.chatbot.parsers.ParserFactory
import com.aos.chatbot.parsers.aos.AosParser
import com.aos.chatbot.routes.adminRoutes
import com.aos.chatbot.routes.chatRoutes
import com.aos.chatbot.routes.healthRoutes
import com.aos.chatbot.services.ChatResponseBus
import com.aos.chatbot.services.ChatService
import com.aos.chatbot.services.DocumentService
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.EmbeddingService
import com.aos.chatbot.services.LlmService
import com.aos.chatbot.services.ModelWarmup
import com.aos.chatbot.services.QueueService
import com.aos.chatbot.services.SearchService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
            if (cause is CancellationException) throw cause
            if (cause is Error) throw cause
            this@module.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    val dbConfig = DatabaseConfig(appConfig)
    val migrationConn = dbConfig.initialize()
    migrationConn.close() // Migration connection is no longer needed; keeping it open blocks WAL checkpointing

    if (appConfig.mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
        val cleanedCount = cleanupOrphanTempFiles(appConfig.documentsPath, appConfig.imagesPath)
        val logger = LoggerFactory.getLogger("com.aos.chatbot.Application")
        logger.info(
            "Startup temp-file cleanup: {} orphaned temp files removed (sources: {}, images: {})",
            cleanedCount.total, cleanedCount.sources, cleanedCount.images
        )
    }

    // Phase 3 object graph — constructed in the order documented in the plan so
    // that downstream services always see their deps already wired up.
    val database = Database(appConfig.databasePath)
    val parserFactory = ParserFactory()
    val aosParser = AosParser()
    val chunkingService = ChunkingService()

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json {
                isLenient = false
                ignoreUnknownKeys = true
            })
        }
    }

    val embeddingService = EmbeddingService(httpClient, appConfig.ollama)
    val llmService = LlmService(httpClient, appConfig.ollama)
    val searchService = SearchService()
    val backfillJob = EmbeddingBackfillJob(
        database = database,
        embeddingService = embeddingService,
        searchService = searchService
    )
    val responseBus = ChatResponseBus()
    val queueService = QueueService(appConfig.artemis)

    // Artemis start-up is best-effort at boot: if the broker is unreachable, we
    // log and keep going. The chat route refuses 503 until the queue is up, and
    // `/api/health/ready` surfaces queue.status=down. Production deployments
    // should retry the container until Artemis is reachable.
    try {
        queueService.start()
    } catch (e: Exception) {
        log.warn("Artemis queue failed to start; chat disabled until broker is reachable: {}", e.message)
    }

    val chatService = ChatService(
        queueService = queueService,
        embeddingService = embeddingService,
        searchService = searchService,
        llmService = llmService,
        database = database,
        responseBus = responseBus
    )
    if (queueService.isConnected()) {
        launch { chatService.start(this@module) }
    }

    val documentService = DocumentService(
        database = database,
        parserFactory = parserFactory,
        aosParser = aosParser,
        chunkingService = chunkingService,
        embeddingService = embeddingService,
        searchService = searchService,
        backfillJob = backfillJob,
        documentsPath = appConfig.documentsPath,
        imagesPath = appConfig.imagesPath
    )

    // Warmup and backfill are both fire-and-forget. Warmup never gates
    // readiness; backfill gates the chat route via `/api/health/ready`.
    ModelWarmup(embeddingService, llmService).warmupAsync(this)

    launch {
        try {
            backfillJob.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Embedding backfill failed: {}", e.message, e)
        }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        runCatching { queueService.stop() }
        runCatching { httpClient.close() }
    }

    routing {
        healthRoutes(
            database = database,
            databasePath = appConfig.databasePath,
            ollamaClient = httpClient,
            ollamaConfig = appConfig.ollama,
            queueService = queueService,
            backfillJob = backfillJob
        )
        registerModeGatedRoutes(
            mode = appConfig.mode,
            adminRegistrar = {
                adminRoutes(
                    documentService,
                    database,
                    backfillJob,
                    this@module,
                    appConfig.documentsPath,
                    appConfig.imagesPath
                )
            },
            chatRegistrar = {
                chatRoutes(queueService, responseBus, backfillJob)
            }
        )
    }

    if (appConfig.mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
        log.warn("Admin routes are unprotected — auth is deferred to Phase 4. Restrict this deployment to internal networks.")
    }

    log.info("AOS Chatbot started in ${appConfig.mode} mode on ${appConfig.host}:${appConfig.port}")
}

/**
 * Registers routes whose availability depends on [mode]:
 *  - Admin routes in [AppMode.FULL] and [AppMode.ADMIN]
 *  - Chat routes in [AppMode.FULL] and [AppMode.CLIENT]
 *
 * Extracted so tests can exercise the same gating without booting the full
 * Phase 3 object graph.
 */
internal fun Route.registerModeGatedRoutes(
    mode: AppMode,
    adminRegistrar: (Route.() -> Unit)? = null,
    chatRegistrar: (Route.() -> Unit)? = null
) {
    if (mode in listOf(AppMode.FULL, AppMode.ADMIN)) adminRegistrar?.invoke(this)
    if (mode in listOf(AppMode.FULL, AppMode.CLIENT)) chatRegistrar?.invoke(this)
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
                    val deleted = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
                    if (deleted) sourceCount++
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
                            val deleted = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
                            if (deleted) imageCount++
                        }
                }
            }
        }
    }

    return CleanupResult(sourceCount, imageCount)
}
