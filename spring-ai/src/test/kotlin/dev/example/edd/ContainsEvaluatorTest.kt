package dev.example.edd

import dev.dokimos.kotlin.core.EvalTestCase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContainsEvaluatorTest {

    @Test
    fun `passes when split fragments appear in the output in order`() {
        val evaluator = ContainsEvaluator(splitFragmentsBy = "|")
        val testCase = EvalTestCase(
            input = "",
            actualOutputs = mapOf(
                "output" to "You can park at Kinepolis Antwerp. For pricing, check parking rates online."
            ),
            expectedOutputs = mapOf("output" to "Kinepolis|parking rates"),
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 1.0
        result.success() shouldBe true
    }

    @Test
    fun `keeps whole string contains behavior when fragment splitting is disabled`() {
        val evaluator = ContainsEvaluator(splitFragmentsBy = null)
        val testCase = EvalTestCase(
            input = "",
            actualOutputs = mapOf("output" to "The venue is Kinepolis Antwerp."),
            expectedOutputs = mapOf("output" to "Kinepolis Antwerp"),
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 1.0
        result.success() shouldBe true
    }
}
