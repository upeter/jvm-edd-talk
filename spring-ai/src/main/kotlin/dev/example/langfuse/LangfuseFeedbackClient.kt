package dev.example.langfuse

import com.langfuse.client.LangfuseClient
import com.langfuse.client.resources.observationsv2.requests.GetObservationsV2Request
import dev.example.observability.LangfuseAttributes.METADATA_FEEDBACK_RATING
import dev.example.observability.LangfuseAttributes.METADATA_FEEDBACK_REASON
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class FeedbackEntry(
    val traceId: String,
    val observationId: String,
    val sessionId: String,
    val request: String,
    val answer: String,
    val reason: String?,
    val rating: Double,
    val timestamp: Instant
)

/** A row of the Observations API v2: field names as documented, values as raw JSON types. */
typealias ObservationRow = Map<String, Any?>

/** The observations of one trace. Langfuse v4 has no trace entity; the root observation stands in for it. */
data class TraceObservations(val traceId: String, val observations: List<ObservationRow>) {
    val root: ObservationRow? = observations.firstOrNull { it["parentObservationId"] == null }
    val projectId: String? = observations.firstNotNullOfOrNull { it["projectId"] as? String }
    val totalCost: Double = observations.sumOf { (it["totalCost"] as? Number)?.toDouble() ?: 0.0 }
    val htmlPath: String? = projectId?.let { "/project/$it/traces/$traceId" }
}

/**
 * Reads feedback and traces through the Langfuse Observations API v2 (`GET /api/public/v2/observations`),
 * which replaces the v1 observations and traces endpoints deprecated by Langfuse v4.
 */
@Component
class LangfuseFeedbackClient(
    private val langfuseClient: LangfuseClient,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** v2 requires a bounded time range and pages with a cursor; results are sorted by start time, newest first. */
    fun fetchObservations(
        limit: Int = 20,
        observationName: String,
        lookback: Duration = Duration.ofDays(90),
    ): List<ObservationRow> =
        runCatching {
            val to = OffsetDateTime.now(ZoneOffset.UTC)
            fetchAll(limit) { cursor ->
                GetObservationsV2Request.builder()
                    .name(observationName)
                    .fields(FEEDBACK_FIELDS)
                    .expandMetadata(LEGACY_ATTRIBUTES)
                    .fromStartTime(to.minus(lookback))
                    .toStartTime(to)
                    .limit(minOf(limit, MAX_PAGE_SIZE))
                    .apply { cursor?.let { cursor(it) } }
                    .build()
            }
        }.onFailure {
            logger.warn("Failed to fetch observations from Langfuse", it)
        }.getOrDefault(emptyList())


    fun fetchFeedback(limit: Int = 20): List<FeedbackEntry> =
        fetchObservations(limit, "feedback")
                .map { toFeedback(it) }

    /** All observations of [traceId]. [around] bounds the query, as v2 requires a time range. */
    fun fetchTrace(traceId: String, around: Instant): TraceObservations {
        val at = around.atOffset(ZoneOffset.UTC)
        val observations = fetchAll(Int.MAX_VALUE) { cursor ->
            GetObservationsV2Request.builder()
                .traceId(traceId)
                .fields(TRACE_FIELDS)
                .fromStartTime(at.minus(TRACE_WINDOW))
                .toStartTime(at.plus(TRACE_WINDOW))
                .limit(MAX_PAGE_SIZE)
                .apply { cursor?.let { cursor(it) } }
                .build()
        }
        return TraceObservations(traceId, observations)
    }

    private fun fetchAll(limit: Int, request: (cursor: String?) -> GetObservationsV2Request): List<ObservationRow> {
        val rows = mutableListOf<ObservationRow>()
        var cursor: String? = null
        do {
            val page = langfuseClient.observationsV2().getMany(request(cursor))
            rows += page.data
            cursor = page.meta.cursor.orElse(null)
        } while (cursor != null && rows.size < limit)
        return rows.take(limit)
    }


    private fun toFeedback(row: ObservationRow): FeedbackEntry {
        val metadata = row.metadata()
        // Feedback recorded before the v4 migration kept everything in unmapped span attributes: nested under
        // `attributes` when ingested by v3, flattened to `attributes.<key>` when ingested by v4.
        val legacy = (metadata["attributes"] as? Map<*, *>).orEmpty()
        fun value(current: Any?, legacyKey: String): String =
            (current as? String)?.takeIf { it.isNotEmpty() }
                ?: legacy[legacyKey] as? String
                ?: metadata["attributes.$legacyKey"] as? String
                ?: ""
        return FeedbackEntry(
            traceId = row["traceId"] as String,
            observationId = row["id"] as String,
            sessionId = value(row["sessionId"], "langfuse.session.id"),
            request = value(row["input"], "langfuse.user.request"),
            answer = value(row["output"], "langfuse.answer"),
            reason = value(metadata[METADATA_FEEDBACK_REASON], "langfuse.feedback.reason").takeIf { it.isNotBlank() },
            rating = value(metadata[METADATA_FEEDBACK_RATING], "langfuse.feedback").let { if (it == "UP") 1.0 else 0.0 },
            timestamp = Instant.parse(row["startTime"] as String)
        )
    }


    companion object {
        private const val MAX_PAGE_SIZE = 1000
        private const val FEEDBACK_FIELDS = "core,basic,io,metadata"
        private const val TRACE_FIELDS = "core,basic,io,model,usage"
        private const val LEGACY_ATTRIBUTES = "attributes"
        private val TRACE_WINDOW: Duration = Duration.ofDays(1)

        fun ObservationRow.metadata(): Map<*, *> = (this["metadata"] as? Map<*, *>).orEmpty()
    }
}
