package dev.example.edd

import dev.dokimos.core.agents.ToolCall
import dev.dokimos.kotlin.core.EvalTestCase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ToolPresenceEvaluatorTest {

    private fun toolCall(name: String, result: String = "ok"): ToolCall =
        ToolCall.builder().name(name).arguments(emptyMap<String, Any>()).result(result).build()

    @Test
    fun `passes when the expected tool was called among others`() {
        val evaluator = ToolPresenceEvaluator("Venue Tool", "general-venue-information-kotlinconf")
        val testCase = EvalTestCase(
            actualOutputs = mapOf(
                "toolCalls" to listOf(
                    toolCall("conference-session-search"),
                    toolCall("general-venue-information-kotlinconf")
                )
            )
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 1.0
        result.success() shouldBe true
    }

    @Test
    fun `fails when the expected tool was never called`() {
        val evaluator = ToolPresenceEvaluator("Venue Tool", "general-venue-information-kotlinconf")
        val testCase = EvalTestCase(
            actualOutputs = mapOf("toolCalls" to listOf(toolCall("conference-session-search")))
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 0.0
        result.success() shouldBe false
    }

    @Test
    fun `fails when the tool was called but its result lacks the expected text`() {
        val evaluator = ToolPresenceEvaluator(
            "Venue Tool",
            "general-venue-information-kotlinconf",
            toolOutputKey = "venue"
        )
        val testCase = EvalTestCase(
            input = "",
            actualOutputs = mapOf("toolCalls" to listOf(toolCall("general-venue-information-kotlinconf", "Berlin"))),
            expectedOutputs = mapOf("venue" to "Muenchen")
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 0.0
    }
}
