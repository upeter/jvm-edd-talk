package dev.example.edd

import dev.dokimos.core.BaseEvaluator
import dev.dokimos.core.EvalResult
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.kotlin.core.EvalResult
import dev.dokimos.kotlin.dsl.DokimosDsl
import dev.dokimos.kotlin.dsl.evaluators.EvaluatorsDsl

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

class ToolCallEvaluator(
    evaluatorName: String = "ToolCallEvaluator",
    private val expectedToolName: String,
    /** If set, this key is used to look up an expected toolInput value from [EvalTestCase.expectedOutputs]. */
    private val toolInputKey: String? = null,
    /** If set, this key is used to look up an expected toolOutput value from [EvalTestCase.expectedOutputs]. */
    private val toolOutputKey: String? = null

) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)) {


    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val outputs = testCase.actualOutputs()

        // Expected shape from the task():
        // "toolCalls" -> List<Map<String, Any>> where map contains keys like "toolName", "toolInput", "toolOutput"
        val actualToolCalls: List<Map<String, Any?>> = (outputs[PARAM_TOOL_CALLS] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { it.entries.associate { (k, v) -> k?.toString().orEmpty() to v } }

        val expectedInput: String? = toolInputKey?.let { key ->
            testCase.expectedOutputs()[key]?.toString()
        }
        val expectedOutput: String? = toolOutputKey?.let { key ->
            testCase.expectedOutputs()[key]?.toString()
        }

        // Try to find the relevant tool call. If multiple match by name, prefer one that also matches input/output.
        val matchingByName = actualToolCalls.filter { it[PARAM_TOOL_NAME]?.toString() == expectedToolName }
        val matchingToolCall: Map<String, Any?>? = when {
            matchingByName.isEmpty() -> null
            expectedInput == null && expectedOutput == null -> matchingByName.first()
            else -> matchingByName.firstOrNull { candidate ->
                val inputOk = expectedInput?.let { candidate[PARAM_TOOL_INPUT]?.toString() == it } ?: true
                val outputOk = expectedOutput?.let { candidate[PARAM_TOOL_OUTPUT]?.toString() == it } ?: true
                inputOk && outputOk
            } ?: matchingByName.first()
        }

        val toolNameOk = matchingToolCall != null

        val toolInputOk: Boolean? = expectedInput?.let { expected ->
            matchingToolCall?.get(PARAM_TOOL_INPUT)?.toString() == expected
        }

        val toolOutputOk: Boolean? = expectedOutput?.let { expected ->
            matchingToolCall?.get(PARAM_TOOL_OUTPUT)?.toString() == expected
        }

        // Score: always include toolName check. Include input/output checks only when expectations were provided.
        val checks: List<Pair<String, Boolean>> = buildList {
            add(PARAM_TOOL_NAME to toolNameOk)
            if (toolInputOk != null) add(PARAM_TOOL_INPUT to toolInputOk)
            if (toolOutputOk != null) add(PARAM_TOOL_OUTPUT to toolOutputOk)
        }

        val passed = checks.count { it.second }
        val total = checks.size
        val score = if (total == 0) 0.0 else passed.toDouble() / total.toDouble()
        val success = score >= threshold()

        val actualToolName = matchingToolCall?.get(PARAM_TOOL_NAME)?.toString()
        val actualToolInput = matchingToolCall?.get(PARAM_TOOL_INPUT)?.toString()
        val actualToolOutput = matchingToolCall?.get(PARAM_TOOL_OUTPUT)?.toString()

        val failedChecks = checks.filterNot { it.second }.map { it.first }

        val reason = buildString {
            append("checks=").append(passed).append("/").append(total)
            if (failedChecks.isNotEmpty()) append(", failed=").append(failedChecks)
            append(", expectedToolName='").append(expectedToolName).append("'")
            append(", actualToolName='").append(actualToolName).append("'")
            if (expectedInput != null) {
                append(", expectedToolInput='").append(expectedInput).append("'")
                append(", actualToolInput='").append(actualToolInput).append("'")
            }
            if (expectedOutput != null) {
                append(", expectedToolOutput='").append(expectedOutput).append("'")
                append(", actualToolOutput='").append(actualToolOutput).append("'")
            }
        }

        val metadata: Map<String, Any?> = buildMap {
            put("expectedToolName", expectedToolName)
            put("actualToolName", actualToolName)
            expectedInput?.let { put("expectedToolInput", it) }
            put("actualToolInput", actualToolInput)
            expectedOutput?.let { put("expectedToolOutput", it) }
            put("actualToolOutput", actualToolOutput)
            put("passedChecks", passed)
            put("totalChecks", total)
            put("failedChecks", failedChecks)
            put("toolCallsCount", total)
        }

        return EvalResult(
            name(),
            score,
            threshold(),
            success,
            reason,
            metadata
        )
    }


    companion object {
        const val PARAM_TOOL_CALLS = "toolCalls"
        const val PARAM_TOOL_NAME = "toolName"
        const val PARAM_TOOL_INPUT = "toolInput"
        const val PARAM_TOOL_OUTPUT = "toolOutput"
    }
}

@DokimosDsl
class ToolCallEvaluatorDsl {

    var name: String = "ToolCallEvaluator"

    var expectedToolName: String? = null
    var toolInputKey: String? = null
    var toolOutputKey: String? = null

    fun build(): ToolCallEvaluator {
        val expectedName = requireNotNull(expectedToolName) {
            "expectedToolName must be provided"
        }

        return ToolCallEvaluator(
            evaluatorName = name,
            expectedToolName = expectedName,
            toolInputKey = toolInputKey,
            toolOutputKey = toolOutputKey
        )
    }
}

/** Convenience builder for creating a [ToolCallEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.toolCallEvaluator(block: ToolCallEvaluatorDsl.() -> Unit) {
    evaluator(ToolCallEvaluatorDsl().apply(block).build())
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
