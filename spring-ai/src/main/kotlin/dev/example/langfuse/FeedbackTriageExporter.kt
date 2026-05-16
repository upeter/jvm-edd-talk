package dev.example.langfuse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.langfuse.client.resources.commons.types.ObservationsView
import com.langfuse.client.resources.commons.types.TraceWithFullDetails
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

data class FeedbackTriageExport(
    val csvFile: Path,
    val tracesDirectory: Path,
    val exportedRows: Int,
)

data class FeedbackTriageRow(
    val traceId: String,
    val observationId: String,
    val sessionId: String,
    val timestamp: String,
    val rating: String,
    val request: String,
    val answer: String,
    val userFeedbackReason: String,
    val complaintSummary: String = "",
    val failureMode: String = "",
    val affectedCapability: String = "",
    val severity: String = "",
    val confidence: String = "",
    val rationale: String = "",
    val humanDecision: String = "",
    val humanNotes: String = "",
    val regressionCandidate: String = "",
    val traceFile: String,
    val langfuseUrl: String,
)

class FeedbackTriageExporter(
    private val feedbackClient: LangfuseFeedbackClient,
    private val triager: FeedbackTriager? = null,
) {
    private val mapper = jacksonObjectMapper().writerWithDefaultPrettyPrinter()

    fun exportNegativeFeedback(
        limit: Int = 20,
        outputDir: Path = Path.of("eval-data/feedback-triage"),
    ): FeedbackTriageExport {
        outputDir.createDirectories()
        val tracesDirectory = outputDir.resolve("traces").also { it.createDirectories() }
        val rows = feedbackClient.fetchFeedback(limit)
            .filter { it.rating == 0.0 }
            .map { entry -> exportEntry(entry, tracesDirectory) }

        val csvFile = outputDir.resolve("negative-feedback-triage.csv")
        csvFile.writeText(buildCsv(rows))

        return FeedbackTriageExport(
            csvFile = csvFile,
            tracesDirectory = tracesDirectory,
            exportedRows = rows.size,
        )
    }

    private fun exportEntry(entry: FeedbackEntry, tracesDirectory: Path): FeedbackTriageRow {
        val trace = runCatching { feedbackClient.fetchTrace(entry.traceId) }.getOrNull()
        val traceFileName = "trace-${entry.traceId.toSafeFileName()}.md"
        val traceFile = tracesDirectory.resolve(traceFileName)
        val traceMarkdown = renderTrace(entry, trace)
        traceFile.writeText(traceMarkdown)
        val classification = triager?.let {
            runCatching { it.classify(entry, traceMarkdown) }
                .getOrElse { error ->
                    FeedbackClassification(
                        complaintSummary = "Triage classification failed.",
                        failureMode = "Classification failure",
                        affectedCapability = "Feedback triage",
                        severity = "low",
                        confidence = 0.0,
                        rationale = error.message.orEmpty(),
                    )
                }
        } ?: FeedbackClassification()

        return FeedbackTriageRow(
            traceId = entry.traceId,
            observationId = entry.observationId,
            sessionId = entry.sessionId,
            timestamp = entry.timestamp.toString(),
            rating = "DOWN",
            request = entry.request,
            answer = entry.answer,
            userFeedbackReason = entry.reason.orEmpty(),
            complaintSummary = classification.complaintSummary,
            failureMode = classification.failureMode,
            affectedCapability = classification.affectedCapability,
            severity = classification.severity,
            confidence = classification.confidence.toString(),
            rationale = classification.rationale,
            traceFile = "traces/$traceFileName",
            langfuseUrl = trace?.htmlPath.orEmpty(),
        )
    }

    private fun renderTrace(entry: FeedbackEntry, trace: TraceWithFullDetails?): String = buildString {
        appendLine("# Trace ${entry.traceId}")
        appendLine()
        trace?.htmlPath?.takeIf { it.isNotBlank() }?.let {
            appendLine("Langfuse: $it")
            appendLine()
        }
        appendLine("## Feedback")
        appendLine()
        appendLine("Rating: DOWN")
        appendLine()
        appendLine("Reason:")
        appendLine(entry.reason.orEmpty().ifBlank { "(no reason provided)" })
        appendLine()
        appendLine("## User Request")
        appendLine()
        appendLine(entry.request.ifBlank { "(empty)" })
        appendLine()
        appendLine("## Assistant Answer")
        appendLine()
        appendLine(entry.answer.ifBlank { "(empty)" })
        appendLine()

        if (trace == null) {
            appendLine("## Trace Details")
            appendLine()
            appendLine("Trace details could not be retrieved from Langfuse.")
            return@buildString
        }

        appendLine("## Trace Summary")
        appendLine()
        appendLine("Session: ${trace.sessionId.orElse(entry.sessionId)}")
        appendLine("Started: ${trace.timestamp}")
        appendLine("Latency: ${trace.latency}")
        appendLine("Total cost: ${trace.totalCost}")
        appendLine()

        appendLine("## Trace Input")
        appendJsonBlock(trace.input.orElse(null))
        appendLine()
        appendLine("## Trace Output")
        appendJsonBlock(trace.output.orElse(null))
        appendLine()

        appendLine("## Observations")
        appendLine()
        trace.observations.sortedBy { it.startTime }.forEach { observation ->
            appendObservation(observation)
        }
    }

    private fun StringBuilder.appendObservation(observation: ObservationsView) {
        appendLine("### ${observation.name.orElse(observation.type)}")
        appendLine()
        appendLine("- id: ${observation.id}")
        appendLine("- type: ${observation.type}")
        appendLine("- start: ${observation.startTime}")
        observation.endTime.ifPresent { appendLine("- end: $it") }
        observation.parentObservationId.ifPresent { appendLine("- parent: $it") }
        observation.model.ifPresent { appendLine("- model: $it") }
        observation.statusMessage.ifPresent { appendLine("- status: $it") }
        appendLine()
        appendLine("Input:")
        appendJsonBlock(observation.input.orElse(null))
        appendLine()
        appendLine("Output:")
        appendJsonBlock(observation.output.orElse(null))
        appendLine()
    }

    private fun StringBuilder.appendJsonBlock(value: Any?) {
        appendLine("```json")
        appendLine(value?.toPrettyJson().orEmpty().ifBlank { "null" })
        appendLine("```")
    }

    private fun Any.toPrettyJson(): String = runCatching {
        mapper.writeValueAsString(this)
    }.getOrElse { toString() }

    private fun buildCsv(rows: List<FeedbackTriageRow>): String {
        val header = listOf(
            "traceId",
            "observationId",
            "sessionId",
            "timestamp",
            "rating",
            "request",
            "answer",
            "userFeedbackReason",
            "complaintSummary",
            "failureMode",
            "affectedCapability",
            "severity",
            "confidence",
            "rationale",
            "humanDecision",
            "humanNotes",
            "regressionCandidate",
            "traceFile",
            "langfuseUrl",
        )
        return buildString {
            appendLine(header.toCsvLine())
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.traceId,
                        row.observationId,
                        row.sessionId,
                        row.timestamp,
                        row.rating,
                        row.request,
                        row.answer,
                        row.userFeedbackReason,
                        row.complaintSummary,
                        row.failureMode,
                        row.affectedCapability,
                        row.severity,
                        row.confidence,
                        row.rationale,
                        row.humanDecision,
                        row.humanNotes,
                        row.regressionCandidate,
                        row.traceFile,
                        row.langfuseUrl,
                    ).toCsvLine()
                )
            }
        }
    }

    private fun List<String>.toCsvLine(): String = joinToString(",") { it.csvEscape() }

    private fun String.csvEscape(): String = "\"${replace("\"", "\"\"")}\""

    private fun String.toSafeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
}
