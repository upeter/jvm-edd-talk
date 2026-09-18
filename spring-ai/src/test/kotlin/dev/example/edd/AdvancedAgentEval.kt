package dev.example.edd

import dev.dokimos.core.Dataset
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.conversation.AggregationStrategy
import dev.dokimos.core.conversation.ConversationTrajectory
import dev.dokimos.core.conversation.ConversationalApplication
import dev.dokimos.core.conversation.Message
import dev.dokimos.core.conversation.SimulatedUser
import dev.dokimos.core.conversation.TrajectoryEvaluationCriteria
import dev.dokimos.core.conversation.TrajectoryEvaluator
import dev.dokimos.kotlin.core.EvalTestCase
import dev.dokimos.kotlin.dsl.conversation.assistantMessage
import dev.dokimos.kotlin.dsl.conversation.goldenGenerator
import dev.dokimos.kotlin.dsl.conversation.llmUser
import dev.dokimos.kotlin.dsl.conversation.simulator
import dev.dokimos.kotlin.dsl.conversation.trajectoryEvaluator
import dev.dokimos.kotlin.dsl.experiment
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Path
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

        assert(trajectory.toolCalls().isNotEmpty()) {
            "Expected the trajectory to carry at least one tool call, but none were recorded"
        }

        val evaluator: TrajectoryEvaluator = trajectoryEvaluator(judge) {
            name = "Tool-Aware Schedule Trajectory"
            threshold = 0.7
            criteria(
                listOf(
                    TrajectoryEvaluationCriteria.goalCompletion(),
                    TrajectoryEvaluationCriteria.professionalTone(),
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

    /**
     * Regenerates the committed golden suite. Disabled by default: it calls the model and rewrites a
     * reviewed artifact. Remove @Disabled, run it once, review the diff, commit.
     */
    @Test
    //@Disabled("Run manually to regenerate src/test/resources/datasets/kotlinconf-goldens.json")
    fun `generate conversation goldens`() {
        val conversationId = UUID.randomUUID().toString()
        val chatApp = ConversationalApplication { trajectory: ConversationTrajectory ->
            Message.assistant(controller.chat(ChatMessage(trajectory.toText(), conversationId)).orEmpty())
        }

        val generator = goldenGenerator {
            application = chatApp
            name = "kotlinconf-goldens"
            description = "Scripted conference-assistant conversations, replayed as a regression suite"

            seed {
                scenario = "First-time attendee asks about the venue, then the ticket price"
                userTurns(
                    listOf(
                        "Where is KotlinConf 2026 held?",
                        "And what does a regular ticket cost?"
                    )
                )
                expectedOutcome = "The assistant gives the venue address and then the regular ticket price"
                // TaskCompletionEvaluator reads its tasks from metadata under "tasks", as a List<String>.
                // expectedOutcome alone is a String under a different key, so it cannot be used directly.
                metadata(
                    "tasks",
                    listOf(
                        "State the KotlinConf 2026 venue address",
                        "State the regular ticket price"
                    )
                )
            }

            seed {
                scenario = "Attendee searches for AI sessions and adds one to their schedule"
                userTurns(
                    listOf(
                        "Which sessions are about AI frameworks?",
                        "Add the first one to my preferred sessions"
                    )
                )
                expectedOutcome = "The assistant lists AI sessions and confirms one was added to the schedule"
                metadata(
                    "tasks",
                    listOf(
                        "List conference sessions about AI frameworks",
                        "Confirm that a session was added to the preferred schedule"
                    )
                )
            }
        }

        generator.write(Path.of("src/test/resources/datasets/kotlinconf-goldens-2.json"))
    }

    @Test
    fun `replay conversation goldens against their expected outcome`() {
        val goldens = Dataset.fromJson(Path.of("src/test/resources/datasets/kotlinconf-goldens.json"))

        experiment {
            name = "KotlinConf Conversation Goldens"
            dataset(goldens)
            task { example -> mapOf("output" to example.input()) }
            evaluators {
                taskCompletion(judge) {
                    name = "Goal Reached"
                    threshold = 0.7
                }
            }
        }.run().print().assert()
    }
}
