package dev.example.edd

import dev.dokimos.core.JudgeLM
import dev.dokimos.core.conversation.AggregationStrategy
import dev.dokimos.core.conversation.ConversationalApplication
import dev.dokimos.core.conversation.SimulatedUser
import dev.dokimos.core.conversation.TrajectoryEvaluationCriteria
import dev.dokimos.core.conversation.TrajectoryEvaluator
import dev.dokimos.kotlin.core.EvalTestCase
import dev.dokimos.kotlin.dsl.conversation.assistantMessage
import dev.dokimos.kotlin.dsl.conversation.llmUser
import dev.dokimos.kotlin.dsl.conversation.simulator
import dev.dokimos.kotlin.dsl.conversation.trajectoryEvaluator
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * Evals built on Dokimos 0.27 features. Kept separate from [ChatEval], whose method order and
 * spacing are the talk's reveal sequence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class AdvancedAgentEval @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val tools: ConferenceTools,
    val toolCallbackRecorder: ToolCallRecorder,
) {

    val judge: JudgeLM = springAiJudge(builder)

    @Test
    fun `multiturn trajectory carries the tool calls the agent made`() {
        val user: SimulatedUser = llmUser(judge) {
            persona = "Kotlin backend developer planning a conference schedule"
            behaviorGuidelines = """
                - Asks about backend and AI sessions, then asks to add them to the schedule.
                - Keeps going until a few sessions are on the schedule.
            """
        }
        val conversationId = UUID.randomUUID().toString()

        // Each turn: clear the recorder, answer, then attach the calls made for THIS turn.
        val chatApp = ConversationalApplication { trajectory ->
            toolCallbackRecorder.clear()
            val response = controller.chat(ChatMessage(trajectory.toText(), conversationId)).orEmpty()
            assistantMessage(response, toolCallbackRecorder.getCalls().asToolCalls())
        }

        val trajectory = simulator {
            simulatedUser = user
            application = chatApp
            maxTurns = 4
            scenario = "User builds a conference schedule with the assistant"
            initialMessage = "Hi, I'm looking for AI sessions"
        }.simulate()

        println("=== Conversation ===")
        println(trajectory.toText())

        val evaluator: TrajectoryEvaluator = trajectoryEvaluator(judge) {
            name = "Tool-Aware Schedule Trajectory"
            threshold = 0.7
            criteria(
                listOf(
                    TrajectoryEvaluationCriteria.goalCompletion(),
                    TrajectoryEvaluationCriteria.helpfulness()
                )
            )
            aggregationStrategy = AggregationStrategy.WEIGHTED_MEAN
        }

        val result = evaluator.evaluate(EvalTestCase(actualOutputs = mapOf("trajectory" to trajectory)))

        println("Overall Score: ${"%.2f".format(result.score())}")
        println("Reason: ${result.reason()}")
        assert(result.success()) { "Trajectory scored ${result.score()}: ${result.reason()}" }
    }
}
