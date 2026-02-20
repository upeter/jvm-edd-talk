package dev.example

import dev.dokimos.core.BaseEvaluator
import dev.dokimos.core.EvalResult
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.evaluators.FaithfulnessEvaluator
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
    private val expectedToolInput: String? = null,
    private val expectedToolOutput: String? = null,

    ) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)) {


    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val outputs = testCase.actualOutputs()

        // Expected shape from the task():
        // "toolCalls" -> List<Map<String, Any>> where map contains keys like "toolName", "toolInput", "toolOutput"
        val actualToolCalls = (outputs[PARAM_TOOL_CALLS] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { it.entries.associate { (k, v) -> k?.toString().orEmpty() to v } }

        val matchingToolCall = actualToolCalls.firstOrNull { it[PARAM_TOOL_NAME]?.toString() == expectedToolName }

        val toolNameOk = matchingToolCall != null

        val toolInputOk: Boolean? = expectedToolInput?.let { expected ->
            // If user cares about input, require tool call to exist and match
            matchingToolCall?.get(PARAM_TOOL_INPUT)?.toString() == expected
        }

        val toolOutputOk: Boolean? = expectedToolOutput?.let { expected ->
            // If user cares about output, require tool call to exist and match
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

        val reason = buildString {
            append("checks=").append(passed).append("/").append(total)
            append(", expectedToolName='").append(expectedToolName).append("'")
            append(", actualToolName='").append(actualToolName).append("'")
            if (expectedToolInput != null) {
                append(", expectedToolInput='").append(expectedToolInput).append("'")
                append(", actualToolInput='").append(actualToolInput).append("'")
            }
            if (expectedToolOutput != null) {
                append(", expectedToolOutput='").append(expectedToolOutput).append("'")
                append(", actualToolOutput='").append(actualToolOutput).append("'")
            }
        }

        val metadata: MutableMap<String, Any> = mutableMapOf(
            "expectedToolName" to expectedToolName,
            "actualToolName" to (actualToolName ?: ""),
            "expectedToolInput" to (expectedToolInput ?: ""),
            "actualToolInput" to (actualToolInput ?: ""),
            "expectedToolOutput" to (expectedToolOutput ?: ""),
            "actualToolOutput" to (actualToolOutput ?: ""),
            "passedChecks" to passed,
            "totalChecks" to total,
        )

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

    /** The tool name we expect to have been called. Required. */
    var expectedToolName: String? = null

    /** If set, toolInput must match exactly. */
    var expectedToolInput: String? = null

    /** If set, toolOutput must match exactly. */
    var expectedToolOutput: String? = null

    fun build(): ToolCallEvaluator {
        val expectedName = requireNotNull(expectedToolName) {
            "expectedToolName must be provided"
        }

        return ToolCallEvaluator(
            evaluatorName = name,
            expectedToolName = expectedName,
            expectedToolInput = expectedToolInput,
            expectedToolOutput = expectedToolOutput,
        )
    }
}

/** Convenience builder for creating a [ToolCallEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.toolCallEvaluator(block: ToolCallEvaluatorDsl.() -> Unit) {
    val evaluator = ToolCallEvaluatorDsl().apply(block).build()

    // Preferred path: use the public API if present.
    runCatching {
        this.evaluator(evaluator)
    }.onFailure {
        // Fallback: add to the private mutable collection `evaluators` via reflection.
        // This is useful when the DSL stores evaluators internally and only later iterates them.
        runCatching {
            val field = this::class.java.getDeclaredField("evaluators").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = field.get(this) as? MutableCollection<Any>
                ?: error("EvaluatorsDsl.evaluators is not a MutableCollection")
            list.add(evaluator)
        }.getOrThrow()
    }
}
