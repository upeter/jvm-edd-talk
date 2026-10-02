package dev.example.observability

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpHttpSpanExporterBuilderCustomizer
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot 4 only creates its OTLP span exporter from `management.opentelemetry.tracing.export.otlp.*`
 * and ignores the standard `OTEL_EXPORTER_OTLP_*` env vars. This bridges them, so traces reach Langfuse
 * with the same env vars as before. Without `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` nothing is exported.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
class LangfuseTracingConfig {

    @Bean
    fun otlpTracingConnectionDetails(
        @Value("\${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}") endpoint: String,
    ) = OtlpTracingConnectionDetails { _: Transport -> endpoint }

    /**
     * `OTEL_EXPORTER_OTLP_HEADERS` uses the OTel spec format: `key1=value1,key2=value2`.
     * `x-langfuse-ingestion-version: 4` selects Langfuse's v4 (observations-first) ingestion path.
     */
    @Bean
    fun otlpHeadersCustomizer(
        @Value("\${OTEL_EXPORTER_OTLP_HEADERS:}") headers: String,
    ) = OtlpHttpSpanExporterBuilderCustomizer { builder ->
        val parsed = headers.split(',')
            .filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        (mapOf("x-langfuse-ingestion-version" to "4") + parsed).forEach(builder::addHeader)
    }

    /** Spring Boot adds every `SpanProcessor` bean to the tracer provider. */
    @Bean
    fun traceAttributePropagatingSpanProcessor() = TraceAttributePropagatingSpanProcessor()
}
