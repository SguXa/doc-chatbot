package com.aos.chatbot.routes

import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.db.repositories.ChunkRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.db.repositories.ImageRepository
import com.aos.chatbot.services.BackfillStatus
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.QueueService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private const val OLLAMA_PROBE_TIMEOUT_MS: Long = 5_000L
private const val OLLAMA_PROBE_CACHE_TTL_MS: Long = 5_000L
private const val OLLAMA_PROBE_FAILURE_CACHE_TTL_MS: Long = 500L
private const val EMBEDDING_DIMENSION: Int = 1024

@Serializable
private data class OllamaTag(val name: String)

@Serializable
private data class OllamaTagsResponse(val models: List<OllamaTag> = emptyList())

private data class OllamaProbeResult(
    val up: Boolean,
    val models: List<String>,
    val timestampMs: Long
)

/**
 * Wires `/api/health`, `/api/health/ready`, and `/api/stats`.
 *
 * Ollama reachability is probed via `GET {ollamaUrl}/api/tags`. The probe has
 * a 5 s timeout and its result is cached for 5 s in a per-route
 * [AtomicReference] so that container health probes hitting the endpoint once
 * per second do not serialize behind in-flight LLM calls on the shared HTTP
 * client.
 *
 * Readiness requires three conditions simultaneously: Ollama up with both the
 * configured llm and embed models present, JMS queue connection open, and
 * backfill status [BackfillStatus.Completed]. When any dependency is down the
 * endpoint responds 503 with a body that still itemizes the state of every
 * dependency.
 */
fun Route.healthRoutes(
    database: Database,
    databasePath: String,
    ollamaClient: HttpClient,
    ollamaConfig: OllamaConfig,
    queueService: QueueService,
    backfillJob: EmbeddingBackfillJob
) {
    val log = LoggerFactory.getLogger("com.aos.chatbot.routes.HealthRoutes")
    val probeCache = AtomicReference<OllamaProbeResult?>(null)

    suspend fun probeOllama(): OllamaProbeResult {
        val cached = probeCache.get()
        val now = System.currentTimeMillis()
        if (cached != null) {
            // Cache successes for the full TTL; failures expire much sooner so
            // readiness recovers quickly once Ollama comes back, instead of
            // returning 503 for another ~5 s.
            val ttl = if (cached.up) OLLAMA_PROBE_CACHE_TTL_MS else OLLAMA_PROBE_FAILURE_CACHE_TTL_MS
            if (now - cached.timestampMs < ttl) return cached
        }
        val result = try {
            val tags: OllamaTagsResponse = withTimeout(OLLAMA_PROBE_TIMEOUT_MS) {
                val resp = ollamaClient.get("${ollamaConfig.url.trimEnd('/')}/api/tags")
                if (resp.status.value !in 200..299) {
                    throw IllegalStateException("Ollama /api/tags returned ${resp.status.value}")
                }
                resp.body()
            }
            val names = tags.models.map { it.name }
            // Ollama /api/tags returns fully-qualified `name:tag` entries and
            // normalizes unqualified pulls to `name:latest`. Match both the
            // raw configured name and its `:latest`-canonicalized form so a
            // config value of `bge-m3` still recognizes `bge-m3:latest`.
            val canonical = names.map { canonicalModelName(it) }.toSet()
            val hasLlm = canonicalModelName(ollamaConfig.llmModel) in canonical
            val hasEmbed = canonicalModelName(ollamaConfig.embedModel) in canonical
            OllamaProbeResult(up = hasLlm && hasEmbed, models = names, timestampMs = now)
        } catch (e: TimeoutCancellationException) {
            log.debug("Ollama probe timed out after {} ms", OLLAMA_PROBE_TIMEOUT_MS)
            OllamaProbeResult(up = false, models = emptyList(), timestampMs = now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.debug("Ollama probe failed: {}", e.message)
            OllamaProbeResult(up = false, models = emptyList(), timestampMs = now)
        }
        probeCache.set(result)
        return result
    }

    route("/api/health") {
        get {
            call.respond(mapOf("status" to "healthy"))
        }

        get("/ready") {
            val ollama = probeOllama()
            val queueUp = queueService.isConnected()
            val backfillLabel = when {
                backfillJob.isRunning() -> "running"
                backfillJob.status() is BackfillStatus.Completed -> "ready"
                backfillJob.status() is BackfillStatus.Running -> "running"
                backfillJob.status() is BackfillStatus.Failed -> "failed"
                else -> "idle"
            }

            var databaseUp = true
            val counts = try {
                withContext(Dispatchers.IO) {
                    database.connect().use { conn ->
                        DocumentRepository(conn).count() to ChunkRepository(conn).count()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("Failed to read counts for /api/health/ready: {}", e.message)
                databaseUp = false
                0L to 0L
            }
            val (docCount, chunkCount) = counts

            val ready = ollama.up && databaseUp && queueUp && backfillLabel == "ready"
            val body: JsonObject = buildJsonObject {
                put("status", if (ready) "ready" else "not_ready")
                put("ollama", buildJsonObject {
                    put("status", if (ollama.up) "up" else "down")
                    put("models", buildJsonArray {
                        ollama.models.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                })
                put("database", buildJsonObject {
                    put("status", if (databaseUp) "up" else "down")
                    put("documents", docCount)
                    put("chunks", chunkCount)
                })
                put("queue", buildJsonObject {
                    put("status", if (queueUp) "up" else "down")
                })
                put("backfill", buildJsonObject {
                    put("status", backfillLabel)
                })
            }
            if (ready) {
                call.respond(HttpStatusCode.OK, body)
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, body)
            }
        }
    }

    route("/api/stats") {
        get {
            val counts = withContext(Dispatchers.IO) {
                database.connect().use { conn ->
                    Triple(
                        DocumentRepository(conn).count(),
                        ChunkRepository(conn).count(),
                        ImageRepository(conn).count()
                    )
                }
            }
            val dbSize = try {
                Files.size(Path.of(databasePath))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                0L
            }
            val uptimeMs = ManagementFactory.getRuntimeMXBean().uptime

            val body: JsonObject = buildJsonObject {
                put("documents", counts.first)
                put("chunks", counts.second)
                put("images", counts.third)
                put("embeddingDimension", EMBEDDING_DIMENSION)
                put("databaseSize", formatBytesAsMb(dbSize))
                put("uptime", formatUptime(uptimeMs))
            }
            call.respond(body)
        }
    }
}

private fun canonicalModelName(name: String): String =
    if (name.contains(':')) name else "$name:latest"

private fun formatBytesAsMb(bytes: Long): String {
    val mb = bytes / 1024 / 1024
    return "$mb MB"
}

private fun formatUptime(millis: Long): String {
    val totalSeconds = millis / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return "${days}d ${hours}h ${minutes}m"
}
