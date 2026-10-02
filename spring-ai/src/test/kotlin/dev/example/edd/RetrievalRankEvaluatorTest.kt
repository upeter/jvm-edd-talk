package dev.example.edd

import dev.dokimos.kotlin.core.EvalTestCase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RetrievalRankEvaluatorTest {

    private fun retrieved(vararg titles: String, expected: String = "B") = EvalTestCase(
        input = "query",
        actualOutputs = mapOf(RetrievalRankEvaluator.PARAM_RETRIEVED to titles.toList()),
        expectedOutputs = mapOf("output" to expected),
    )

    @Test
    fun `reciprocal rank is one over the position of the expected title`() {
        RetrievalRankEvaluator("MRR").evaluate(retrieved("A", "B", "C")).score() shouldBe 0.5
    }

    @Test
    fun `hit at k counts only the first k results`() {
        val testCase = retrieved("A", "B", "C")

        RetrievalRankEvaluator("Hit@1", k = 1).evaluate(testCase).score() shouldBe 0.0
        RetrievalRankEvaluator("Hit@2", k = 2).evaluate(testCase).score() shouldBe 1.0
    }

    @Test
    fun `scores zero and explains when the expected title is missing`() {
        val result = RetrievalRankEvaluator("MRR").evaluate(retrieved("A", "C"))

        result.score() shouldBe 0.0
        result.success() shouldBe false
        result.reason().contains("not retrieved") shouldBe true
    }
}
