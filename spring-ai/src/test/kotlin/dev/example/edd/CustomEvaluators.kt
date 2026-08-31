package dev.example.edd

import dev.dokimos.core.BaseEvaluator
import dev.dokimos.core.EvalResult
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.agents.ToolCall
import dev.dokimos.kotlin.core.EvalResult
import dev.dokimos.kotlin.dsl.DokimosDsl
import dev.dokimos.kotlin.dsl.evaluators.EvaluatorsDsl
import dev.example.ConferenceSession
import java.time.Instant

class ResponseLengthEvaluator(
    private val minLength: Int,
    private val maxLength: Int,
    private val evaluatorName: String = "Length Check"
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val output = testCase.actualOutput()
        val length = output.length

        val withinBounds = length in minLength..maxLength
        val score = if (withinBounds) 1.0 else 0.0
        val success = score >= threshold()
        val reason = "Output length $length (expected $minLength-$maxLength)"

        return EvalResult(
            name(),
            score,
            threshold(),
            success,
            reason,
            mutableMapOf()
        )
    }
}

/**
 * Asserts that one specific named tool was called, tolerant of whatever other tools the agent also
 * called. Optionally also checks that the tool's result contained an expected value.
 *
 * This fills a gap the built-in agent evaluators leave open:
 *  - `toolCorrectness` set-matches the *entire* call list, so it penalizes any extra legitimate call;
 *  - `toolTrajectory` matches a full golden name+argument sequence, which an LLM's nondeterministic
 *    arguments rarely reproduce exactly.
 *
 * Prefer a built-in when the exact set or order of calls can be pinned down; reach for this when all
 * you need is "tool X was used somewhere along the way".
 *
 * Reads the recorded calls from the "toolCalls" output key, as produced by [asToolCalls].
 */
class ToolPresenceEvaluator(
    evaluatorName: String,
    private val expectedToolName: String,
    /** If set, looks up an expected substring in [EvalTestCase.expectedOutputs] and checks the tool result contains it. */
    private val toolOutputKey: String? = null,
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val calls = (testCase.actualOutputs()[PARAM_TOOL_CALLS] as? List<*>).orEmpty()
            .mapNotNull { it.asView() }

        val match = calls.firstOrNull { it.name == expectedToolName }
        val expectedOutput = toolOutputKey?.let { testCase.expectedOutputs()[it]?.toString() }
        val outputOk = expectedOutput?.let { match?.result?.contains(it) == true }

        val score = when {
            match == null -> 0.0
            outputOk == false -> 0.0
            else -> 1.0
        }
        val reason = buildString {
            append(if (match != null) "Tool '$expectedToolName' was called" else "Tool '$expectedToolName' was NOT called")
            append(" (calls: ${calls.joinToString { it.name }.ifEmpty { "none" }})")
            if (expectedOutput != null) append("; expected output '$expectedOutput' present=$outputOk")
        }
        return EvalResult(
            name(), score, threshold(), score >= threshold(), reason,
            mapOf("expectedToolName" to expectedToolName, "calledTools" to calls.map { it.name }),
        )
    }

    private data class View(val name: String, val result: String?)

    private fun Any?.asView(): View? = when (this) {
        is ToolCall -> View(name(), result())
        is Map<*, *> -> (this["name"] ?: this["toolName"])?.toString()
            ?.let { View(it, (this["result"] ?: this["toolOutput"])?.toString()) }
        else -> null
    }

    companion object {
        const val PARAM_TOOL_CALLS = "toolCalls"
    }
}

@DokimosDsl
class ToolPresenceEvaluatorDsl {
    var name: String = "Tool Presence"
    var expectedToolName: String? = null
    var toolOutputKey: String? = null

    fun build(): ToolPresenceEvaluator = ToolPresenceEvaluator(
        evaluatorName = name,
        expectedToolName = requireNotNull(expectedToolName) { "expectedToolName must be provided" },
        toolOutputKey = toolOutputKey
    )
}

/** Convenience builder for creating a [ToolPresenceEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.toolPresence(block: ToolPresenceEvaluatorDsl.() -> Unit) {
    evaluator(ToolPresenceEvaluatorDsl().apply(block).build())
}

class ContainsEvaluator(
    evaluatorName: String = "Contains",
    private val containsTextKey: String? = null,
    private val caseSensitive: Boolean = false,
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val output = testCase.actualOutput()
        val containsText = containsTextKey?.let{testCase.expectedOutputs()[it]?.toString() } ?: testCase.expectedOutput()
        val contains = if (caseSensitive) output.contains(containsText) else output.lowercase().contains(containsText.lowercase())

        val score = if (contains) 1.0 else 0.0
        val reason = "Output text $containsText expected in $output"

        return EvalResult(
            name = name(),
            score = score,
            threshold = threshold(),
            reason = reason,
        )
    }
}


@DokimosDsl
class ContainsEvaluatorDsl {
    var name: String = "ContainsEvaluator"
    var containsTextKey: String? = null
    var caseSensitive: Boolean = false

    fun build(): ContainsEvaluator {
        return ContainsEvaluator(
            evaluatorName = name,
            containsTextKey = containsTextKey,
            caseSensitive = caseSensitive
        )
    }
}

/** Convenience builder for creating a [ContainsEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.contains(block: ContainsEvaluatorDsl.() -> Unit) {
    evaluator(ContainsEvaluatorDsl().apply(block).build())
}

class StartedSessionOverlapEvaluator(
    evaluatorName: String = "Started Session Overlap",
    private val preferredSessionsKey: String = "preferredSessions",
    private val currentTimeKey: String = "currentTime"
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val outputs = testCase.actualOutputs()
        val currentTimeValue = outputs[currentTimeKey]?.toString()
        val currentTime = currentTimeValue?.let { Instant.parse(it) }
            ?: return overlapEvalResult(0.0, "Missing current time in '$currentTimeKey'.", emptyMap())
        val preferredSessions = ((outputs[preferredSessionsKey] as? Iterable<*>) ?: emptyList<Any>())
            .mapNotNull { it.asSessionForOverlap() }
        val startedSessions = preferredSessions.filter { Instant.parse(it.startsAt).isBefore(currentTime) }
        val score = if (startedSessions.isEmpty()) 1.0 else 0.0
        val reason = if (startedSessions.isEmpty()) {
            "No already-started sessions were added to preferences."
        } else {
            "Already-started sessions were added: ${startedSessions.joinToString { "${it.startsAt} - ${it.title}" }}"
        }
        val metadata: Map<String, Any> = mapOf(
            "currentTime" to currentTime.toString(),
            "preferredSessionsCount" to preferredSessions.size,
            "startedSessionsCount" to startedSessions.size,
            "startedSessions" to startedSessions.map { mapOf("startsAt" to it.startsAt, "title" to it.title) }
        )

        return EvalResult(
            name(),
            score,
            threshold(),
            score >= threshold(),
            reason,
            metadata
        )
    }

    private fun overlapEvalResult(score: Double, reason: String, metadata: Map<String, Any>): EvalResult = EvalResult(
        name(),
        score,
        threshold(),
        score >= threshold(),
        reason,
        metadata
    )

    private fun Any?.asSessionForOverlap(): SessionForOverlap? = when (this) {
        is ConferenceSession -> SessionForOverlap(title, startsAt)
        is Map<*, *> -> {
            val title = this["title"]?.toString()
            val startsAt = this["startsAt"]?.toString()
            if (title == null || startsAt == null) null else SessionForOverlap(title, startsAt)
        }
        else -> null
    }

    private data class SessionForOverlap(val title: String, val startsAt: String)
}

@DokimosDsl
class StartedSessionOverlapEvaluatorDsl {
    var name: String = "Started Session Overlap"
    var preferredSessionsKey: String = "preferredSessions"
    var currentTimeKey: String = "currentTime"

    fun build(): StartedSessionOverlapEvaluator = StartedSessionOverlapEvaluator(
        evaluatorName = name,
        preferredSessionsKey = preferredSessionsKey,
        currentTimeKey = currentTimeKey
    )
}

fun EvaluatorsDsl.startedSessionOverlap(block: StartedSessionOverlapEvaluatorDsl.() -> Unit = {}) {
    evaluator(StartedSessionOverlapEvaluatorDsl().apply(block).build())
}
