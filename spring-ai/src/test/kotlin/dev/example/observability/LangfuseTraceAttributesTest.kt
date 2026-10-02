package dev.example.observability

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Test

/** Runs the real Micrometer → OTEL bridge, as Spring Boot wires it, against an in-memory exporter. */
class LangfuseTraceAttributesTest {

    private val exported = mutableListOf<SpanData>()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(TraceAttributePropagatingSpanProcessor())
        .addSpanProcessor(SimpleSpanProcessor.create(object : SpanExporter {
            override fun export(spans: Collection<SpanData>) = CompletableResultCode.ofSuccess().also { exported += spans }
            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }))
        .build()
    private val registry = ObservationRegistry.create().apply {
        val tracer = OtelTracer(tracerProvider.get("test"), OtelCurrentTraceContext()) {}
        observationConfig().observationHandler(DefaultTracingObservationHandler(tracer))
    }

    private fun span(name: String) = exported.single { it.name == name }
    private fun SpanData.attr(key: String): String? = attributes.get(AttributeKey.stringKey(key))

    @Test
    fun `puts overall io on the root and propagates session and trace name to every child`() {
        // The HTTP server observation that Spring opens around each request is Langfuse's root observation.
        Observation.createNotStarted("http post /chat", registry).observe {
            registry.langfuseObservation("chat", sessionId = "session-1", input = "Where is the venue?") {
                Observation.createNotStarted("chat gpt-4.1", registry).observe {
                    Observation.createNotStarted("conference-session-search", registry).observe { }
                }
                "In Antwerp."
            }
        }

        exported shouldHaveSize 4
        val root = span("http post /chat")
        val chat = span("chat")
        val generation = span("chat gpt-4.1")
        val tool = span("conference-session-search")
        assertSoftly {
            generation.parentSpanId shouldBe chat.spanId
            chat.parentSpanId shouldBe root.spanId

            listOf(root, chat).forEach {
                it.attr(LangfuseAttributes.OBSERVATION_INPUT) shouldBe "Where is the venue?"
                it.attr(LangfuseAttributes.OBSERVATION_OUTPUT) shouldBe "In Antwerp."
            }
            listOf(root, chat, generation, tool).forEach {
                it.attr(LangfuseAttributes.SESSION_ID) shouldBe "session-1"
                it.attr(LangfuseAttributes.TRACE_NAME) shouldBe "chat"
            }
        }
    }

    @Test
    fun `records metadata as filterable observation metadata and stops propagating after the block`() {
        Observation.createNotStarted("http post /feedback", registry).observe {
            registry.langfuseObservation(
                name = "feedback",
                sessionId = "session-2",
                input = "request",
                metadata = mapOf(LangfuseAttributes.METADATA_FEEDBACK_RATING to "DOWN"),
            ) { "answer" }
            Observation.createNotStarted("after", registry).observe { }
        }

        assertSoftly {
            span("feedback").attr("langfuse.observation.metadata.feedback_rating") shouldBe "DOWN"
            span("feedback").attr(LangfuseAttributes.OBSERVATION_OUTPUT) shouldBe "answer"
            span("after").attr(LangfuseAttributes.SESSION_ID) shouldBe null
        }
    }
}
