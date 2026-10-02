package dev.example.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/** Langfuse OTEL attribute names, see https://langfuse.com/integrations/native/opentelemetry#property-mapping */
object LangfuseAttributes {
    const val SESSION_ID = "langfuse.session.id"
    const val TRACE_NAME = "langfuse.trace.name"
    const val OBSERVATION_INPUT = "langfuse.observation.input"
    const val OBSERVATION_OUTPUT = "langfuse.observation.output"
    const val OBSERVATION_METADATA_PREFIX = "langfuse.observation.metadata."

    const val METADATA_FEEDBACK_RATING = "feedback_rating"
    const val METADATA_FEEDBACK_REASON = "feedback_reason"
}

/**
 * Langfuse v4 queries observations directly, so trace-wide attributes (session, trace name) must sit on every
 * span, not only the root. In particular, the cost-bearing `chat gpt-4.1` generations need the session ID.
 *
 * Each span copies them from its parent span when it starts. Unlike OTEL Baggage, this keeps the session ID
 * in-process: it is not propagated to outbound calls such as the OpenAI API. It also survives the Micrometer
 * bridge, which replaces the OTEL context for every observation it opens.
 */
class TraceAttributePropagatingSpanProcessor : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        val parent = Span.fromContext(parentContext) as? ReadableSpan ?: return
        PROPAGATED.forEach { key -> parent.getAttribute(key)?.let { span.setAttribute(key, it) } }
    }

    override fun isStartRequired() = true
    override fun onEnd(span: ReadableSpan) {}
    override fun isEndRequired() = false

    private companion object {
        val PROPAGATED = listOf(LangfuseAttributes.SESSION_ID, LangfuseAttributes.TRACE_NAME).map { AttributeKey.stringKey(it) }
    }
}

/**
 * Runs [block] as the observation [name] and records it in the shape Langfuse v4 expects: the overall
 * input/output on the root observation (the HTTP server observation of the current request), and the session
 * ID and trace name on the root and every observation started within [block].
 */
fun ObservationRegistry.langfuseObservation(
    name: String,
    sessionId: String,
    input: String,
    metadata: Map<String, String> = emptyMap(),
    block: () -> String?,
): String? {
    val traceAttributes = mapOf(LangfuseAttributes.SESSION_ID to sessionId, LangfuseAttributes.TRACE_NAME to name)
    val root = currentObservation
    val observation = Observation.createNotStarted(name, this)
    listOfNotNull(root, observation).forEach { obs ->
        traceAttributes.forEach { (key, value) -> obs.highCardinalityKeyValue(key, value) }
        obs.highCardinalityKeyValue(LangfuseAttributes.OBSERVATION_INPUT, input)
    }
    metadata.forEach { (key, value) ->
        observation.highCardinalityKeyValue(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + key, value)
    }
    return observation.observe<String?> {
        // Key values reach the span only when the observation stops, too late for children to copy them from.
        // So set the trace attributes on this observation's span right away (it is the current span here).
        traceAttributes.forEach { (key, value) -> Span.current().setAttribute(key, value) }
        // Output must be set before each observation stops, as that is when its key values are copied onto the span.
        block()?.also { output ->
            listOfNotNull(root, observation).forEach { it.highCardinalityKeyValue(LangfuseAttributes.OBSERVATION_OUTPUT, output) }
        }
    }
}
