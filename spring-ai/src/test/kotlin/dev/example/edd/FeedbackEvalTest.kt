package dev.example.edd

import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.springai.SpringAiSupport
import dev.example.langfuse.LangfuseFeedbackClient
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LANGFUSE_PUBLIC_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LANGFUSE_SECRET_KEY", matches = ".+")
class FeedbackEvalTest @Autowired constructor(
    private val feedbackClient: LangfuseFeedbackClient,
    val builder: ChatClient.Builder,
) {

    val judge: JudgeLM = SpringAiSupport.asJudge(builder)


    @Test
    fun `should run dokimos experiment on retrieved feedback`() {
        val entries = feedbackClient.fetchFeedback(limit = 10)
        entries.shouldNotBeEmpty()

        val exp = experiment {
            name = "Negative Feedback Eval"
            dataset {
                name = "feedback-samples"
                entries.filter { it.rating == 0.0 }.forEach { entry ->
                    example {
                        input = entry.request
                        expected = entry.answer
                        metadata("sessionId", entry.sessionId)
                    }
                }
            }

            task { example ->
                mapOf(
                    "output" to (example.expectedOutput()),
                    "sessionId" to (example.metadata().getValue("sessionId"))
                )
            }

            evaluators {
                llmJudge(judge) {
                    name = "Answer Quality"
                    criteria = "Is the answer helpful, accurate, and professionally worded?"
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
                    threshold = 0.8
                }

            }
        }

        exp.run().print()
    }
}