package dev.example.langfuse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dokimos.core.JudgeLM

data class FeedbackClassification(
    val complaintSummary: String = "",
    val failureMode: String = "",
    val affectedCapability: String = "",
    val severity: String = "",
    val confidence: Double = 0.0,
    val rationale: String = "",
)

fun interface FeedbackTriager {
    fun classify(entry: FeedbackEntry, traceMarkdown: String): FeedbackClassification
}

class LlmFeedbackTriager(
    private val judge: JudgeLM,
) : FeedbackTriager {
    private val mapper = jacksonObjectMapper()

    override fun classify(entry: FeedbackEntry, traceMarkdown: String): FeedbackClassification {
        val response = judge.generate(prompt(entry, traceMarkdown.take(MAX_TRACE_CHARS)))
        return mapper.readValue(extractJson(response))
    }

    private fun prompt(entry: FeedbackEntry, traceMarkdown: String): String = """
        You are triaging negative user feedback for a conference assistant.

        Given:
        - the user request
        - the assistant answer
        - the optional user feedback reason
        - a trace excerpt

        Summarize the user complaint in one sentence.
        Infer the likely failure mode in generic product terms.
        Identify the affected assistant capability.
        Do not use a predefined taxonomy.
        Do not overfit to implementation details.
        If the complaint is unclear, say so.

        Return only valid JSON with this exact shape:
        {
          "complaintSummary": "one sentence",
          "failureMode": "generic product failure mode",
          "affectedCapability": "short capability name",
          "severity": "low|medium|high",
          "confidence": 0.0,
          "rationale": "short explanation"
        }

        User request:
        ${entry.request}

        Assistant answer:
        ${entry.answer}

        User feedback reason:
        ${entry.reason.orEmpty().ifBlank { "(none provided)" }}

        Trace excerpt:
        $traceMarkdown
    """.trimIndent()

    private fun extractJson(response: String): String {
        val fenced = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(response)
            ?.groupValues
            ?.get(1)
        if (fenced != null) return fenced

        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object found in triage response: $response" }
        return response.substring(start, end + 1)
    }

    companion object {
        private const val MAX_TRACE_CHARS = 12_000
    }
}
