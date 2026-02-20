package dev.example

import dev.dokimos.core.BaseEvaluator
import dev.dokimos.core.EvalResult
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.evaluators.FaithfulnessEvaluator
import dev.dokimos.kotlin.dsl.DokimosDsl
import dev.dokimos.kotlin.dsl.EvaluatorsDsl

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
    private val toolNameKey: String,
    private val expectedToolName: String,
    private val toolInputKey: String? = null,
    private val toolOutputKey: String? = null,
    private val expectedToolInput: String? = null,
    private val expectedToolOutput: String? = null,

    ) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)) {

    init {
        if(expectedToolInput != null) {
            require(toolInputKey != null) { "If expectedToolInput is provided, toolInputKey must also be provided" }
        }
        if(expectedToolOutput != null) {
            require(toolOutputKey != null) {  "If expectedToolOutput is provided, toolOutputKey must also be provided" }
        }

    }

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val outputs = testCase.actualOutputs()

        val actualToolName = outputs[toolNameKey]?.toString()
        val toolNameOk = actualToolName == expectedToolName

        val actualToolInput = toolInputKey?.let { outputs[it]?.toString() }
        val toolInputOk = toolInputKey?.let {
            if (expectedToolInput != null) {
                actualToolInput == expectedToolInput
            } else {
                !actualToolInput.isNullOrBlank()
            }
        }

        val actualToolOutput = toolOutputKey?.let { outputs[it]?.toString() }
        val toolOutputOk = toolOutputKey?.let {
            if (expectedToolOutput != null) {
                actualToolOutput == expectedToolOutput
            } else {
                !actualToolOutput.isNullOrBlank()
            }
        }

        // Always evaluate toolName. Only evaluate input/output if keys were provided.
        val checks: List<Pair<String, Boolean>> = buildList {
            add("toolName" to toolNameOk)
            if (toolInputOk != null) add("toolInput" to toolInputOk)
            if (toolOutputOk != null) add("toolOutput" to toolOutputOk)
        }

        val passed = checks.count { it.second }
        val total = checks.size
        val score = if (total == 0) 0.0 else passed.toDouble() / total.toDouble()
        val success = score >= threshold()

        val reason = buildString {
            append("expectedToolName='").append(expectedToolName).append("', actualToolName='").append(actualToolName)
                .append("'")

            if (toolInputKey != null) {
                append(", toolInputKey='").append(toolInputKey).append("'")
                if (expectedToolInput != null) {
                    append(", expectedToolInput='").append(expectedToolInput).append("'")
                }
                append(", toolInputOk=").append(toolInputOk == true)
            }

            if (toolOutputKey != null) {
                append(", toolOutputKey='").append(toolOutputKey).append("'")
                if (expectedToolOutput != null) {
                    append(", expectedToolOutput='").append(expectedToolOutput).append("'")
                }
                append(", toolOutputOk=").append(toolOutputOk == true)
            }

            append(", checks=").append(passed).append("/").append(total)
        }

        val metadata: MutableMap<String, Any> = mutableMapOf(
            "expectedToolName" to expectedToolName,
            "actualToolName" to (actualToolName ?: ""),
            "toolInputKey" to (toolInputKey ?: ""),
            "toolOutputKey" to (toolOutputKey ?: ""),
            "expectedToolInput" to (expectedToolInput ?: ""),
            "expectedToolOutput" to (expectedToolOutput ?: ""),
            "actualToolInput" to (actualToolInput ?: ""),
            "actualToolOutput" to (actualToolOutput ?: ""),
            "toolInputOk" to (toolInputOk == true),
            "toolOutputOk" to (toolOutputOk == true),
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
        const val PARAM_TOOL_NAME = "toolName"
    }
}

@DokimosDsl
class ToolCallEvaluatorDsl {

    var name: String = "ToolCallEvaluator"
    var toolNameKey: String = ToolCallEvaluator.PARAM_TOOL_NAME
    var expectedToolName: String? = null
    var toolInputKey: String? = null
    var toolOutputKey: String? = null
    var expectedToolInput: String? = null
    var expectedToolOutput: String? = null

    fun build(): ToolCallEvaluator {
        val expectedName = requireNotNull(expectedToolName) {
            "expectedToolName must be provided"
        }

        return ToolCallEvaluator(
            evaluatorName = name,
            toolNameKey = toolNameKey,
            expectedToolName = expectedName,
            toolInputKey = toolInputKey,
            toolOutputKey = toolOutputKey,
            expectedToolInput = expectedToolInput,
            expectedToolOutput = expectedToolOutput,
        )
    }
}

/** Convenience builder for creating a [ToolCallEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.toolCallEvaluator(block: ToolCallEvaluatorDsl.() -> Unit) {
    this.evaluator(ToolCallEvaluatorDsl().apply(block).build())
}

