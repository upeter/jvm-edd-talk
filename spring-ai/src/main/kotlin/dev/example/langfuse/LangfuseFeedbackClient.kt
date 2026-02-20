package dev.example.langfuse

import com.langfuse.client.LangfuseClient
import com.langfuse.client.resources.commons.types.ObservationsView
import com.langfuse.client.resources.observations.requests.GetObservationsRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.collections.emptyMap

data class FeedbackEntry(
    val traceId: String,
    val observationId: String,
    val sessionId: String,
    val request: String,
    val answer: String,
    val rating: Double,
    val timestamp: Instant
)

@Component
class LangfuseFeedbackClient(
    private val langfuseClient: LangfuseClient,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun fetchObservations(limit: Int = 20, observationName: String): List<ObservationsView> =
        runCatching {
            val request = GetObservationsRequest.builder()
                .name(observationName)
                .limit(limit)
                .build()
            langfuseClient.observations().getMany(request).data
        }.onFailure {
            logger.warn("Failed to fetch observations from Langfuse", it)
        }.getOrDefault(emptyList())


    fun fetchFeedback(limit: Int = 20): List<FeedbackEntry> =
        fetchObservations(limit, "feedback")
                .map { toFeedback(it) }


    private fun toFeedback(view: ObservationsView): FeedbackEntry {
        val attrs = view.metaDataAttribues()
        return FeedbackEntry(
            traceId = view.traceId.orElse(null),
            observationId = view.id,
            sessionId = attrs.getValue("langfuse.session.id"),
            request = attrs.getValue("langfuse.user.request"),
            answer = attrs.getValue("langfuse.answer"),
            rating = attrs.getValue("langfuse.feedback").let{if(it == "UP") 1.0 else 0.0},
            timestamp = view.startTime.toInstant()
        )
    }


    companion object {
        fun ObservationsView.metaDataAttribues(): Map<String, String> =
            (this.metadata.orElse(emptyMap<String, Map<String, String>>()) as? Map<Any, Map<String, String>>)?.get("attributes").orEmpty()
    }
}
