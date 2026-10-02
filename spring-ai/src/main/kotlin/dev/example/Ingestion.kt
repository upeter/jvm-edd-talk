package dev.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * How session descriptions are cut into embeddable documents.
 *
 * Each strategy lives in its own pgvector table so they can be compared side by side (see `RAGEval`).
 * The app reads whichever table `spring.ai.vectorstore.pgvector.table-name` points at, and ingests
 * that table with the matching strategy when it is empty.
 *
 * Every document of a session carries the same metadata (title, room, times, …), so
 * [SessionSearchRepository] can collapse several hits of one session back into a single result.
 */
enum class ChunkingStrategy(val tableName: String) {
    /** A: one vector per session — title plus the full description. */
    WHOLE("talks"),

    /** B: ~[SessionDocuments.TOKEN_CHUNK_SIZE]-token chunks of the description, each prefixed with the title. */
    TOKEN_CHUNKS("talks_chunk_token"),

    /** C: line/paragraph-based chunks, short ones merged up to a minimum size, each prefixed with the title. */
    PARAGRAPH_CHUNKS("talks_chunk_paragraph"),

    /** D: several vectors per session — the whole description, the title alone, and B's token chunks. */
    MULTI_VECTOR("talks_multi_vector");

    companion object {
        fun forTable(tableName: String): ChunkingStrategy? = entries.firstOrNull { it.tableName == tableName }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionRecord(
    val title: String,
    val description: String = "",
    val startsAt: String,
    val endsAt: String,
    val room: String = "",
    val category: List<String> = emptyList(),
    val speakers: List<String> = emptyList(),
)

object SessionDocuments {
    const val TOKEN_CHUNK_SIZE = 120
    const val PARAGRAPH_MIN_CHARS = 400
    const val PARAGRAPH_MAX_CHARS = 900

    const val CHUNK_KIND = "chunkKind"
    const val CHUNK_INDEX = "chunkIndex"

    private val zone = ZoneId.systemDefault()

    private val tokenSplitter = TokenTextSplitter.builder()
        .withChunkSize(TOKEN_CHUNK_SIZE)
        .withMinChunkSizeChars(200)     // only back up to a sentence end once the chunk has 200+ chars
        .withMinChunkLengthToEmbed(20)  // drop trailing scraps
        .withMaxNumChunks(100)
        .withKeepSeparator(true)
        .build()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SessionsFile(val sessions: List<SessionRecord>)

    fun load(resource: Resource = ClassPathResource("data/dataset-devoxx26.json")): List<SessionRecord> =
        resource.inputStream.use { jacksonObjectMapper().readValue<SessionsFile>(it).sessions }

    fun documentsFor(strategy: ChunkingStrategy, sessions: List<SessionRecord>): List<Document> {
        val dayIndexByDate = sessions
            .map { Instant.parse(it.startsAt).atZone(zone).toLocalDate() }
            .distinct().sorted()
            .mapIndexed { idx, date -> date to (idx + 1) }.toMap()

        return sessions.flatMap { session ->
            val metadata = metadataOf(session, dayIndexByDate)
            textsFor(strategy, session).mapIndexed { index, (kind, text) ->
                Document(text, metadata + mapOf(CHUNK_KIND to kind, CHUNK_INDEX to index))
            }
        }
    }

    /** The (kind, text) pairs to embed for one session. */
    fun textsFor(strategy: ChunkingStrategy, session: SessionRecord): List<Pair<String, String>> {
        val whole = "whole" to "title:${session.title}, description:${session.description}"
        val title = "title" to "title:${session.title}"
        fun chunks(parts: List<String>) = parts.map { "chunk" to "title:${session.title}\n$it" }

        return when (strategy) {
            ChunkingStrategy.WHOLE -> listOf(whole)
            ChunkingStrategy.TOKEN_CHUNKS -> chunks(tokenChunks(session.description))
            ChunkingStrategy.PARAGRAPH_CHUNKS -> chunks(paragraphChunks(session.description))
            ChunkingStrategy.MULTI_VECTOR -> listOf(whole, title) + chunks(tokenChunks(session.description))
        }
    }

    fun tokenChunks(text: String): List<String> =
        tokenSplitter.split(Document(text)).mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }

    /**
     * Splits on line breaks, then greedily merges lines until a chunk reaches [minChars]. Descriptions
     * mix prose paragraphs with one-line bullets ("Create golden datasets"), so splitting alone would
     * produce fragments too small to embed meaningfully. A short leftover tail joins the previous chunk.
     */
    fun paragraphChunks(
        text: String,
        minChars: Int = PARAGRAPH_MIN_CHARS,
        maxChars: Int = PARAGRAPH_MAX_CHARS,
    ): List<String> = text.split(Regex("\\s*\\n\\s*"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .fold(emptyList<String>() to "") { (chunks, current), line ->
            val (closed, open) =
                if (current.isNotEmpty() && current.length + line.length > maxChars) chunks + current to ""
                else chunks to current
            val merged = if (open.isEmpty()) line else "$open\n$line"
            if (merged.length >= minChars) closed + merged to "" else closed to merged
        }
        .let { (chunks, tail) ->
            when {
                tail.isEmpty() -> chunks
                chunks.isNotEmpty() && tail.length < minChars / 2 -> chunks.dropLast(1) + "${chunks.last()}\n$tail"
                else -> chunks + tail
            }
        }

    private fun metadataOf(session: SessionRecord, dayIndexByDate: Map<java.time.LocalDate, Int>): Map<String, Any> {
        val startZoned = Instant.parse(session.startsAt).atZone(zone)
        val endZoned = Instant.parse(session.endsAt).atZone(zone)
        val conferenceDay = startZoned.toLocalDate()
        val durationMinutes = Duration.between(startZoned, endZoned).toMinutes()
        return mapOf(
            "id" to UUID.randomUUID().toString().replace("-", ""),
            "title" to session.title,
            "room" to session.room,
            "category" to session.category.toString(),
            "speakers" to session.speakers.toString(),
            // Extra derived metadata in local date-time (no UTC suffix)
            "startsAtLocalDateTime" to DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(startZoned.toLocalDateTime()),
            "endsAtLocalDateTime" to DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(endZoned.toLocalDateTime()),
            "startsAt" to startZoned.toInstant().toEpochMilli(),
            "endsAt" to endZoned.toInstant().toEpochMilli(),
            "conferenceDay" to conferenceDay.toString(),
            "dayIndex" to (dayIndexByDate[conferenceDay] ?: 1),
            "phaseOfDay" to phaseOfDay(startZoned.hour, durationMinutes),
            "durationMinutes" to durationMinutes.toInt(),
        )
    }

    private fun phaseOfDay(hour: Int, durationMinutes: Long): String = when {
        durationMinutes >= 8 * 60 -> "ALL_DAY"
        hour in 5..11 -> "MORNING"
        hour in 12..17 -> "AFTERNOON"
        else -> "EVENING"
    }
}

/**
 * Builds one pgvector store per table and fills it on demand. These stores are created by hand, not
 * as beans: a second `VectorStore` bean would make the auto-configured one back off.
 */
@Component
class SessionIngestion(
    private val jdbcTemplate: JdbcTemplate,
    private val embeddingModel: EmbeddingModel,
) {
    fun vectorStoreFor(strategy: ChunkingStrategy): PgVectorStore = vectorStoreFor(strategy.tableName)

    fun vectorStoreFor(tableName: String, initializeSchema: Boolean = false): PgVectorStore =
        PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .schemaName("public")
            .vectorTableName(tableName)
            .dimensions(embeddingModel.dimensions())
            .distanceType(COSINE_DISTANCE)
            .indexType(HNSW)
            .initializeSchema(initializeSchema)
            .build()
            .also { it.afterPropertiesSet() }

    /** Row count of [tableName], or null if the table does not exist yet. */
    fun documentCount(tableName: String): Int? =
        runCatching { jdbcTemplate.queryForObject<Int>("SELECT count(*) FROM public.$tableName") }.getOrNull()

    /** Embeds all sessions into [tableName] with [strategy], unless the table already holds documents. */
    fun ingestIfEmpty(strategy: ChunkingStrategy, tableName: String = strategy.tableName) {
        val count = documentCount(tableName)
        if (count != null && count > 0) {
            logger.info("$count $strategy documents already present in public.$tableName")
            return
        }
        val startTime = System.currentTimeMillis()
        logger.info("Start ingesting sessions into public.$tableName with $strategy ...")
        // Only create the table when it is missing: the seeded `talks` table has an undimensioned
        // vector column, on which Spring AI's HNSW index creation would fail.
        val store = vectorStoreFor(tableName, initializeSchema = count == null)
        val documents = SessionDocuments.documentsFor(strategy, SessionDocuments.load())
        store.add(documents)
        logger.info("Ingested ${documents.size} documents into public.$tableName in ${System.currentTimeMillis() - startTime} ms")
    }
}
