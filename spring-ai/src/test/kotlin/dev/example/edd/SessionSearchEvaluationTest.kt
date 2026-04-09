package dev.example.edd

import dev.dokimos.core.Assertions
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.Evaluator
import dev.dokimos.core.Example
import dev.dokimos.core.JudgeLM
import dev.dokimos.junit.DatasetSource
import dev.dokimos.kotlin.dsl.evaluators.EvaluatorsDsl
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools.Companion.TOOL_CONFERENCE_SESSION_SEARCH
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SessionSearchEvaluationTest @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val toolCallbackRecorder: ToolCallRecorder,
) {

    private lateinit var evaluators: List<Evaluator>

    @BeforeEach
    fun setup() {
        val judge: JudgeLM = SpringAiSupport.asJudge(builder)
        evaluators = SessionSearchEvaluators.standard(judge)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DatasetSource("classpath:datasets/session-search-rag.json")
    fun `should retrieve relevant session search results`(example: Example) {
        val conversationId = UUID.randomUUID().toString()
        toolCallbackRecorder.clear()

        val response = controller.chat(ChatMessage(example.input(), conversationId)).orEmpty()

        val retrievedContext = toolCallbackRecorder.findToolCall(TOOL_CONFERENCE_SESSION_SEARCH)?.output ?: ""
        val toolCalls = toolCallbackRecorder.getCalls().map {
            mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
        }

        val testCase = EvalTestCase.builder()
            .input(example.input())
            .expectedOutput(example.expectedOutput())
            .actualOutput(response)
            .actualOutput("retrievedContext", retrievedContext)
            .actualOutput("toolCalls", toolCalls)
            .build()

        Assertions.assertEval(testCase, evaluators)
    }
}

object SessionSearchEvaluators {
    fun standard(judge: JudgeLM): List<Evaluator> {
        val dsl = EvaluatorsDsl()
        dsl.llmJudge(judge) {
            name = "Answer Accuracy"
            criteria = "Does the answer mention at least one specific session title that is relevant to the user's query? Compare against the expected output."
            params(EvalTestCaseParam.INPUT, EvalTestCaseParam.EXPECTED_OUTPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
            threshold = 0.8
        }
        dsl.faithfulness(judge) {
            name = "Faithfulness"
            threshold = 0.8
            contextKey = "retrievedContext"
            includeReason = true
        }
        dsl.contextualRelevance(judge) {
            retrievalContextKey = "retrievedContext"
            includeReason = true
        }
        dsl.toolCallEvaluator {
            expectedToolName = TOOL_CONFERENCE_SESSION_SEARCH
        }
        return dsl.build()
    }
}
