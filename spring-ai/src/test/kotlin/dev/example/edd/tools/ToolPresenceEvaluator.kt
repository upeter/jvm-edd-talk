package dev.example.edd

import dev.dokimos.core.BaseEvaluator
import dev.dokimos.core.EvalResult
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.agents.ToolCall
import dev.dokimos.kotlin.dsl.DokimosDsl
import dev.dokimos.kotlin.dsl.evaluators.EvaluatorsDsl

/**
 * Asserts that every expected tool was called, **tolerant of whatever other tools the agent also called**.
 *
 * This deliberately fills a gap left by the built-in agent evaluators:
 *  - `toolCorrectness` set-matches the *entire* call list, so it penalizes any extra (legitimate) call;
 *  - `toolTrajectory` matches a full golden name+argument sequence, which an LLM's nondeterministic
 *    arguments rarely reproduce exactly.
 *
 * Use a built-in when you can pin down the exact set/order of calls; reach for this when you only need
 * "tools X and Y were used somewhere along the way".
 *
 * The expected tools are declared on the dataset example (`expected("toolCalls", expectedToolCalls(...))`),
 * the recorded calls are read from the "toolCalls" output (Dokimos [ToolCall]s, as produced by [asToolCalls]).
 * The score is the fraction of expected tools that were called.
 */
class ToolPresenceEvaluator(
    evaluatorName: String,
    threshold: Double,
    private val toolCallsKey: String,
) : BaseEvaluator(evaluatorName, threshold, listOf(EvalTestCaseParam.ACTUAL_OUTPUT, EvalTestCaseParam.EXPECTED_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val expected = toolNames(testCase.expectedOutputs()[toolCallsKey]).distinct()
        require(expected.isNotEmpty()) {
            "No expected tools found: declare them on the example with expected(\"$toolCallsKey\", expectedToolCalls(...))"
        }
        val called = toolNames(testCase.actualOutputs()[toolCallsKey])

        val present = expected.filter { it in called }
        val missing = expected - present.toSet()
        val score = present.size.toDouble() / expected.size

        val reason = buildString {
            append("Present: $present, Missing: $missing")
            append(" (calls: ${called.joinToString().ifEmpty { "none" }})")
        }
        return EvalResult(
            name(), score, threshold(), score >= threshold(), reason,
            mapOf("presentTools" to present, "missingTools" to missing, "calledTools" to called),
        )
    }

    private fun toolNames(value: Any?): List<String> = (value as? List<*>).orEmpty().mapNotNull {
        when (it) {
            is ToolCall -> it.name()
            is Map<*, *> -> (it["name"] ?: it["toolName"])?.toString()
            is String -> it
            else -> null
        }
    }
}

@DokimosDsl
class ToolPresenceEvaluatorDsl {
    var name: String = "Tool Presence"
    var threshold: Double = 1.0
    var toolCallsKey: String = "toolCalls"

    fun build(): ToolPresenceEvaluator = ToolPresenceEvaluator(
        evaluatorName = name,
        threshold = threshold,
        toolCallsKey = toolCallsKey,
    )
}

/** Convenience builder for creating a [ToolPresenceEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.toolPresence(block: ToolPresenceEvaluatorDsl.() -> Unit = {}) {
    evaluator(ToolPresenceEvaluatorDsl().apply(block).build())
}
